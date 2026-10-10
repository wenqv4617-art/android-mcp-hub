#!/usr/bin/env python3
"""用**编译产物**检查：AndroidManifest 里声明的每个组件类，在 dex 里真的存在。

为什么需要这个脚本
------------------
Gate 0.1 的 APK 点图标闪退、界面完全不出现。原因是 `HubApplication.kt` 里写的是

    package com.androidmcphub.app.ui        # 文件却在 app/ 目录下

于是编译出来的是 `com.androidmcphub.app.ui.HubApplication`，而 AndroidManifest 要的是
`com.androidmcphub.app.HubApplication`。系统在建应用进程时找不到这个类，直接抛
ClassNotFoundException —— 连 Activity 都没机会创建，所以是"闪一下就回桌面"。

Kotlin **不**要求目录与包名一致，编译器不会为此报错；`./gradlew assembleDebug` 一路绿灯；
132 个 JVM 单测也全绿（它们调用的类都是直接 import 的，不经过 Manifest 的字符串解析）。
这个错误只会在"系统按 Manifest 里的字符串去 dex 里找类"那一刻暴露 —— 也就是真机首次启动。

所以本脚本刻意**不做源码文本匹配**，只读真实产物：

  * 组件类名  ← APK 里编译过的二进制 AndroidManifest.xml（AXML），自己解析，不依赖 aapt2
  * 已定义的类 ← APK 里所有 classes*.dex 的 class_defs 表（不是"引用到的类型"，是"定义了的类"）

两者必须对得上；对不上就非零退出。用法：

    python3 android/tools/verify_manifest_classes.py --apk <path-to.apk>
    python3 android/tools/verify_manifest_classes.py        # 自动找 app/build 下最新的 debug APK

它同时也被 Gradle 任务 `:app:verifyManifestClasses` 调用（见 app/build.gradle.kts），
挂在 `check` 上，所以 `./gradlew check` 会跑。
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import struct
import sys
import zipfile

# AXML chunk 类型
CHUNK_STRING_POOL = 0x0001
CHUNK_START_ELEMENT = 0x0102

# AXML 里"装着组件"的元素名 —— 这些元素上的 android:name 必须是一个真实存在的类
COMPONENT_TAGS = {
    "application",
    "activity",
    "activity-alias",
    "service",
    "receiver",
    "provider",
    "instrumentation",
}

# android:name 的资源 id。用它比"属性名叫 name"更稳（不会和别的 name 撞车）。
ATTR_ANDROID_NAME = 0x01010003


# ---------------------------------------------------------------------------
#  AXML（编译后的二进制 AndroidManifest.xml）
# ---------------------------------------------------------------------------

def _axml_string_pool(buf: bytes, off: int):
    """解析 string pool chunk，返回 (字符串列表, chunk 总长)。"""
    ctype, _hsize, total = struct.unpack_from("<HHI", buf, off)
    assert ctype == CHUNK_STRING_POOL, f"chunk @{off} 不是 string pool：{ctype:#x}"
    string_count, _style_count, flags, strings_start, _styles_start = struct.unpack_from(
        "<IIIII", buf, off + 8
    )
    offsets = struct.unpack_from(f"<{string_count}I", buf, off + 28)
    utf8 = bool(flags & 0x100)
    base = off + strings_start

    out = []
    for o in offsets:
        p = base + o
        if utf8:
            # u16 长度（字符数）+ u8 长度（字节数）+ 字节内容 + NUL
            n = buf[p]
            p += 1
            if n & 0x80:
                n = ((n & 0x7F) << 8) | buf[p]
                p += 1
            m = buf[p]
            p += 1
            if m & 0x80:
                m = ((m & 0x7F) << 8) | buf[p]
                p += 1
            out.append(buf[p : p + m].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", buf, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", buf, p)[0]
                p += 2
            out.append(buf[p : p + n * 2].decode("utf-16-le", "replace"))
    return out, total


def parse_manifest_components(apk_path: str):
    """从 APK 里读二进制 Manifest，返回 [(元素名, 类名), ...] 与 manifest 的 package。"""
    with zipfile.ZipFile(apk_path) as z:
        raw = z.read("AndroidManifest.xml")

    chunks = []
    off = 8  # 跳过 RES_XML 头（type u16 + headerSize u16 + size u32）
    strings: list[str] = []
    while off < len(raw):
        ctype, _hsize, total = struct.unpack_from("<HHI", raw, off)
        if ctype == CHUNK_STRING_POOL:
            strings, total = _axml_string_pool(raw, off)
        elif ctype == CHUNK_START_ELEMENT:
            # resXMLTree_node(16) 后面是 attrExt：ns/name/attributeStart/attributeSize/count/...
            _line, _comment, _ns, name_idx = struct.unpack_from("<IIII", raw, off + 8)
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", raw, off + 24)
            attrs = []
            a = off + 16 + attr_start
            for _ in range(attr_count):
                a_ns, a_name, _raw_val = struct.unpack_from("<III", raw, a)
                a_dtype = raw[a + 15]
                a_data = struct.unpack_from("<I", raw, a + 16)[0]
                attrs.append((a_ns, a_name, a_dtype, a_data, attr_size))
                a += attr_size
            chunks.append((strings[name_idx] if name_idx < len(strings) else "?", attrs))
        off += total

    # resource map：属性名索引 → 资源 id。有它就能确认"这个属性就是 android:name"。
    res_map: dict[int, int] = {}
    off = 8
    while off < len(raw):
        ctype, _hsize, total = struct.unpack_from("<HHI", raw, off)
        if ctype == 0x0180:  # RES_XML_RESOURCE_MAP
            n = (total - 8) // 4
            for i, rid in enumerate(struct.unpack_from(f"<{n}I", raw, off + 8)):
                res_map[i] = rid
        off += total

    package = ""
    components: list[tuple[str, str]] = []
    for tag, attrs in chunks:
        if tag == "manifest":
            for _ns, nm, dtype, data, _sz in attrs:
                key = res_map.get(nm)
                if key is None and nm < len(strings):
                    # 有些工具不写 resource map，退化成按属性名匹配
                    key = 0x01010003 if strings[nm] == "name" else None
                if key == ATTR_ANDROID_NAME and dtype == 0x03 and data < len(strings):
                    package = strings[data]
        if tag not in COMPONENT_TAGS:
            continue
        for _ns, nm, dtype, data, _sz in attrs:
            key = res_map.get(nm)
            if key is None and nm < len(strings):
                key = 0x01010003 if strings[nm] == "name" else None
            if key != ATTR_ANDROID_NAME or dtype != 0x03 or data >= len(strings):
                continue
            cls = strings[data]
            if cls.startswith("."):
                cls = package + cls
            elif "." not in cls:
                cls = f"{package}.{cls}"
            components.append((tag, cls))
    return package, components


# ---------------------------------------------------------------------------
#  dex：只取 class_defs（**定义了**的类），不是"引用到的类型"
# ---------------------------------------------------------------------------

def _uleb128(buf: bytes, p: int):
    result = 0
    shift = 0
    while True:
        b = buf[p]
        p += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, p
        shift += 7


def _dex_string(buf: bytes, string_ids_off: int, idx: int) -> str:
    off = struct.unpack_from("<I", buf, string_ids_off + idx * 4)[0]
    _n, p = _uleb128(buf, off)
    # MUTF-8：结尾 NUL，且 U+0000 编码成 0xC0 0x80。类描述符里不会出现这两者。
    end = buf.index(b"\x00", p)
    return buf[p:end].decode("utf-8", "replace")


def dex_defined_classes(dex: bytes) -> set[str]:
    if dex[:4] != b"dex\n":
        return set()
    string_ids_size, string_ids_off = struct.unpack_from("<II", dex, 56)
    _type_ids_size, type_ids_off = struct.unpack_from("<II", dex, 64)
    class_defs_size, class_defs_off = struct.unpack_from("<II", dex, 96)

    out: set[str] = set()
    for i in range(class_defs_size):
        class_idx = struct.unpack_from("<I", dex, class_defs_off + i * 32)[0]
        descriptor_idx = struct.unpack_from("<I", dex, type_ids_off + class_idx * 4)[0]
        if descriptor_idx >= string_ids_size:
            continue
        desc = _dex_string(dex, string_ids_off, descriptor_idx)
        if desc.startswith("L") and desc.endswith(";"):
            out.add(desc[1:-1].replace("/", "."))
    return out


# ---------------------------------------------------------------------------
#  找 APK
# ---------------------------------------------------------------------------

def discover_apk() -> str | None:
    here = os.path.dirname(os.path.abspath(__file__))
    pattern = os.path.join(here, "..", "app", "build", "outputs", "apk", "**", "*.apk")
    found = glob.glob(pattern, recursive=True)
    if not found:
        return None
    return max(found, key=os.path.getmtime)


# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description="校验 Manifest 组件类名在 dex 里真实存在")
    ap.add_argument("--apk", help="要检查的 APK；省略则自动找 app/build 下最新的那个")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    apk = args.apk or discover_apk()
    if not apk or not os.path.exists(apk):
        print("✗ 找不到 APK。请先 `./gradlew :app:assembleDebug`，或用 --apk 指定。")
        return 2
    print(f"检查对象：{os.path.relpath(apk, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))}")
    print(f"  APK 大小 {os.path.getsize(apk)} 字节")

    package, components = parse_manifest_components(apk)
    print(f"  manifest package = {package}")
    print(f"  Manifest 里声明的组件类共 {len(components)} 个")

    with zipfile.ZipFile(apk) as z:
        dex_names = sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
        defined: set[str] = set()
        for n in dex_names:
            defined |= dex_defined_classes(z.read(n))
    print(f"  dex 共 {len(dex_names)} 个：{', '.join(dex_names)}")
    print(f"  dex 里**定义**的类共 {len(defined)} 个")
    print()

    missing: list[tuple[str, str]] = []
    for tag, cls in components:
        ok = cls in defined
        mark = "✓" if ok else "✗"
        print(f"  {mark} <{tag:15s}> {cls}")
        if not ok:
            missing.append((tag, cls))

    print()
    if missing:
        print(f"✗ {len(missing)} 个组件类在 dex 里不存在 —— 这些组件一旦被系统实例化就会")
        print("  抛 ClassNotFoundException（应用启动时立刻闪退、界面完全不出现）：")
        for tag, cls in missing:
            print(f"    <{tag}> {cls}")
            # 帮忙指出"是不是包名写错了"：找后缀相同的类
            simple = cls.rsplit(".", 1)[-1]
            near = sorted(c for c in defined if c.rsplit(".", 1)[-1] == simple)
            for n in near:
                print(f"        实际存在的同名单类：{n}   ← 源文件的 package 声明可能写错了")
        return 1

    print("✅ 全部对得上：Manifest 里每个组件类都能在 dex 里找到。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
