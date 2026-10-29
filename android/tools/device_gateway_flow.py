#!/usr/bin/env python3
"""在真实 Android 系统上点完「服务 → 云端网关」的完整闭环。

装的是 :app:assembleDebug 打出来的 APK，点的是真实界面，配对的是公网 Worker：
  打开 App → 服务 → 云端网关 → 配对 → 添加 MCP 服务器 → 服务端发现工具
  → 打开详情 → 授权只读工具 → 真实调用测试 → 拿到上游结果 → 删服务 → 解除配对

只用 adb + uiautomator，不依赖额外 instrumentation。配对码只用于 `adb input text`，
不打印也不写文件；失败时把界面树里的可见文本打到日志里，便于在 CI 上定位。

环境变量：
  GATEWAY_ANDROID_PAIRING_CODE  必填，线上配对口令
  GATEWAY_BASE_URL              默认线上地址（应与 App 里预填的一致）
  GATEWAY_SERVICE_URL           默认 https://docs.mcp.cloudflare.com/mcp
  GATEWAY_SERVICE_NAME          默认 ci-device
  DEVICE_SHOT_DIR               截图目录，默认 build/device-shots
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass

PAIRING_CODE = os.environ.get("GATEWAY_ANDROID_PAIRING_CODE", "").strip()
GATEWAY_URL = os.environ.get("GATEWAY_BASE_URL", "https://android-mcp-hub.causticrry.workers.dev").rstrip("/")
SERVICE_URL = os.environ.get("GATEWAY_SERVICE_URL", "https://docs.mcp.cloudflare.com/mcp").strip()
SERVICE_NAME = os.environ.get("GATEWAY_SERVICE_NAME", "ci-device").strip()
SHOT_DIR = os.environ.get("DEVICE_SHOT_DIR", "build/device-shots")
PACKAGE = "com.androidmcphub.app"

BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


class FlowError(RuntimeError):
    pass


def log(message: str) -> None:
    print(f"[device-flow] {message}", flush=True)


def adb(*args: str, timeout: int = 120, check: bool = True) -> subprocess.CompletedProcess:
    try:
        result = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        raise FlowError(f"adb {' '.join(args[:3])} 超时") from error
    if check and result.returncode != 0:
        stderr = result.stderr.decode("utf-8", "replace")[:400]
        raise FlowError(f"adb {' '.join(args[:3])} 失败：{stderr}")
    return result


def shot(name: str) -> None:
    try:
        os.makedirs(SHOT_DIR, exist_ok=True)
        with open(os.path.join(SHOT_DIR, f"{name}.png"), "wb") as handle:
            handle.write(adb("exec-out", "screencap", "-p").stdout)
    except Exception as error:  # 截图失败不能影响验收本身
        log(f"截图 {name} 失败：{error}")


def ui_root() -> ET.Element:
    last = ""
    for _ in range(5):
        dump = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        raw = subprocess.run(
            ["adb", "exec-out", "cat", "/sdcard/ui.xml"],
            capture_output=True,
            timeout=60,
        ).stdout.decode("utf-8", "replace")
        if raw.lstrip().startswith("<?xml"):
            try:
                return ET.fromstring(raw)
            except ET.ParseError as error:
                last = str(error)
        else:
            last = (dump.stdout + dump.stderr).decode("utf-8", "replace")[:200]
        time.sleep(1)
    raise FlowError(f"读取不到界面树：{last}")


@dataclass
class Node:
    text: str
    desc: str
    cls: str
    clickable: bool
    enabled: bool
    bounds: tuple[int, int, int, int]
    index: int

    @property
    def label(self) -> str:
        return self.text or self.desc

    @property
    def center(self) -> tuple[int, int]:
        left, top, right, bottom = self.bounds
        return (left + right) // 2, (top + bottom) // 2


def nodes(root: ET.Element) -> list[Node]:
    result: list[Node] = []
    for index, element in enumerate(root.iter("node")):
        text = (element.get("text") or "").strip()
        desc = (element.get("content-desc") or "").strip()
        match = BOUNDS_RE.match(element.get("bounds") or "")
        if not match:
            continue
        left, top, right, bottom = (int(value) for value in match.groups())
        if right - left <= 0 or bottom - top <= 0:
            continue
        result.append(
            Node(
                text=text,
                desc=desc,
                cls=element.get("class") or "",
                clickable=element.get("clickable") == "true",
                enabled=element.get("enabled") != "false",
                bounds=(left, top, right, bottom),
                index=index,
            )
        )
    return result


def find(items: list[Node], needle: str, exact: bool = True) -> Node | None:
    """文本匹配。同名的页面标题与底栏按钮会同时出现，优先返回可点击的那个。"""
    matches: list[Node] = []
    for item in items:
        for value in (item.text, item.desc):
            if not value:
                continue
            if (value == needle) if exact else (needle in value):
                matches.append(item)
                break
    for item in matches:
        if item.clickable:
            return item
    return matches[0] if matches else None


def find_prefix(items: list[Node], prefix: str) -> Node | None:
    matches = [item for item in items if item.label.startswith(prefix)]
    for item in matches:
        if item.clickable:
            return item
    return matches[0] if matches else None


def describe(items: list[Node]) -> str:
    labels = [item.label for item in items if item.label]
    return " | ".join(dict.fromkeys(labels))[:1500]


def tap(node: Node) -> None:
    x, y = node.center
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.9)


def tap_text(needle: str, exact: bool = True, timeout: int = 30, scroll: bool = False) -> Node:
    node = wait_for_text(needle, exact=exact, timeout=timeout, scroll=scroll)
    tap(node)
    return node


def screen_size() -> tuple[int, int]:
    raw = adb("shell", "wm", "size").stdout.decode("utf-8", "replace")
    match = re.search(r"(\d+)x(\d+)", raw)
    if not match:
        return 1080, 2400
    return int(match.group(1)), int(match.group(2))


def swipe_up() -> None:
    width, height = screen_size()
    x = width // 2
    adb(
        "shell", "input", "swipe",
        str(x), str(int(height * 0.72)),
        str(x), str(int(height * 0.32)),
        "300",
    )
    time.sleep(0.6)


def scroll_to_top(times: int = 5) -> None:
    width, height = screen_size()
    x = width // 2
    for _ in range(times):
        adb(
            "shell", "input", "swipe",
            str(x), str(int(height * 0.30)),
            str(x), str(int(height * 0.85)),
            "200",
        )
        time.sleep(0.4)


def wait_for(check, description: str, timeout: int = 60, scroll: bool = False):
    deadline = time.time() + timeout
    attempt = 0
    while time.time() < deadline:
        try:
            found = check(nodes(ui_root()))
        except FlowError:
            found = None
        if found is not None:
            return found
        attempt += 1
        if scroll and attempt % 2 == 0:
            swipe_up()
        time.sleep(1.2)
    shot("timeout")
    try:
        log(f"超时时的界面：{describe(nodes(ui_root()))}")
    except FlowError:
        pass
    raise FlowError(f"等待超时：{description}")


def wait_for_text(needle: str, exact: bool = True, timeout: int = 60, scroll: bool = False):
    return wait_for(lambda items: find(items, needle, exact), f"出现「{needle}」", timeout, scroll)


def wait_for_prefix(prefix: str, timeout: int = 60, scroll: bool = False):
    return wait_for(lambda items: find_prefix(items, prefix), f"出现「{prefix}」开头的文本", timeout, scroll)


def keyboard_shown() -> bool:
    raw = adb("shell", "dumpsys", "input_method", check=False).stdout.decode("utf-8", "replace")
    return "mInputShown=true" in raw


def hide_keyboard() -> None:
    if keyboard_shown():
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.8)


def type_text(value: str) -> None:
    # adb 把参数拼成一行交给设备端 sh，所以先转义给 sh 看，再把空格换成 input 认识的 %s。
    escaped = value.replace("\\", "\\\\").replace('"', '\\"').replace("'", "\\'")
    escaped = escaped.replace(" ", "%s")
    adb("shell", "input", "text", escaped)


def edit_fields(items: list[Node]) -> list[Node]:
    fields = [item for item in items if "EditText" in item.cls]
    return sorted(fields, key=lambda item: (item.bounds[1], item.bounds[0]))


def fill_field(items: list[Node], index: int, value: str) -> None:
    fields = edit_fields(items)
    if len(fields) <= index:
        raise FlowError(f"界面上只有 {len(fields)} 个输入框，找不到第 {index + 1} 个")
    tap(fields[index])
    type_text(value)


def clear_field() -> None:
    adb("shell", "input", "keyevent", "123")  # MOVE_END
    adb("shell", "input", "keyevent", *(["67"] * 60))  # DEL


def launch() -> None:
    # 不执行 pm clear：它会把工作流里预先授予的通知权限一起清掉，启动时又弹一次权限框。
    adb("shell", "am", "force-stop", PACKAGE, check=False)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
    deadline = time.time() + 120
    while time.time() < deadline:
        items = nodes(ui_root())
        if find(items, "首页"):
            wait_for_text("服务", timeout=60)
            return
        dialog = find(items, "Allow") or find(items, "允许")
        if dialog is not None:
            log("关闭系统权限对话框")
            tap(dialog)
        time.sleep(2)
    shot("launch-timeout")
    raise FlowError("App 没有启动到主界面")


def pair() -> None:
    log("打开「服务」→「云端网关」")
    tap_text("服务", exact=True)
    wait_for_text("本机直连")
    tap(wait_for_prefix("云端网关"))
    wait_for_text("连接云端网关", timeout=30)
    fields = edit_fields(nodes(ui_root()))
    if len(fields) < 2:
        raise FlowError("配对表单里没有网关地址与配对口令两个输入框")
    if GATEWAY_URL.split("://", 1)[-1] not in fields[0].text:
        log(f"网关地址预填为「{fields[0].text}」，手动改成 {GATEWAY_URL}")
        tap(fields[0])
        clear_field()
        type_text(GATEWAY_URL)
    tap(fields[1])
    type_text(PAIRING_CODE)
    hide_keyboard()
    log("提交配对")
    tap_text("连接并配对")
    wait_for_text("解除配对", timeout=120, scroll=True)
    shot("paired")
    log("配对成功：云端服务列表已出现")


def add_service() -> None:
    log(f"添加服务 {SERVICE_NAME} → {SERVICE_URL}")
    tap_text("添加 MCP 服务器", timeout=30)
    # 先等编辑页顶部的标题，再回到列表顶部：如果先去等底部的「保存并连接测试」，
    # LazyColumn 会滚到底，顶部的输入框就不可见了（uiautomator 只报可见节点）。
    wait_for_text("添加云端 MCP 服务器", timeout=60)
    scroll_to_top()
    wait_for(lambda items: edit_fields(items) if len(edit_fields(items)) >= 2 else None, "编辑表单的输入框", timeout=60)
    fill_field(nodes(ui_root()), 0, SERVICE_NAME)
    fill_field(nodes(ui_root()), 1, SERVICE_URL)
    hide_keyboard()
    tap_text("保存并连接测试", scroll=True, timeout=60)
    # 保存后返回列表；服务端连接上游并发现工具需要几秒。
    wait_for(lambda items: find(items, "个工具 · 已授权", exact=False), "服务端发现工具", timeout=180, scroll=True)
    wait_for_text("查看工具与授权", timeout=60, scroll=True)
    shot("discovered")
    log("发现完成：服务列表显示工具数")


def wait_call_outcome(timeout: int = 120) -> str:
    """等一次真实调用的结论。

    「调用成功」标签在结果正文上方；正文很长时，持续上滑会把标签滚出可视区
    （第三次设备验收就卡在这里，尽管上游结果已经显示出来了）。所以同时认
    上游真实结果（文档类 MCP 返回 <result> 开头）作为成功信号，并且只在
    长时间没看到任何结果时才滚动一次。
    """
    deadline = time.time() + timeout
    attempt = 0
    while time.time() < deadline:
        items = nodes(ui_root())
        label = find(items, "调用成功") or find(items, "调用失败")
        if label is not None:
            return label.text
        upstream = next((item for item in items if item.text.startswith("<result>")), None)
        if upstream is not None:
            return "调用成功"
        attempt += 1
        if attempt % 6 == 0:
            swipe_up()
        time.sleep(1.5)
    return ""


def grant_and_call() -> None:
    log("打开服务详情并授权第一个工具")
    tap_text("查看工具与授权", scroll=True)
    wait_for_text("授权为只读", timeout=60, scroll=True)
    shot("tools")
    tap_text("授权为只读", scroll=True)
    wait_for_text("已授权 · 只读", timeout=60, scroll=True)
    tap_text("真实调用测试", scroll=True)
    wait_for_text("参数 JSON", timeout=30, scroll=True)
    log("空参数试调用（部分工具不需要参数）")
    tap_text("通过网关调用", scroll=True)
    first = wait_call_outcome()
    if first == "调用成功":
        shot("called")
        log("真实调用成功（空参数）")
        return

    log(f"空参数调用未成功（{first or '没有任何结果'}），填入查询参数后重试")
    items = nodes(ui_root())
    fields = edit_fields(items)
    if not fields:
        raise FlowError("调用测试里没有参数 JSON 输入框")
    tap(fields[-1])
    clear_field()
    type_text('{"query":"Durable Objects"}')
    hide_keyboard()
    tap_text("通过网关调用", scroll=True)
    if wait_call_outcome() != "调用成功":
        shot("call-failed")
        raise FlowError("重试后的真实调用仍未成功")
    shot("called")
    log("真实调用成功（带查询参数）")


def cleanup() -> None:
    """删掉 CI 服务并解除配对。失败只告警：验收结论已经拿到了。"""
    log("清理：删除服务、解除配对")
    try:
        adb("shell", "input", "keyevent", "4")  # 详情 → 列表
        time.sleep(1.5)
        if find(nodes(ui_root()), "删除服务", exact=False) is None:
            entry = find(nodes(ui_root()), "查看工具与授权", exact=False)
            if entry is not None:
                tap(entry)
                wait_for_text("删除服务", timeout=30, scroll=True)
        if find(nodes(ui_root()), "删除服务", exact=False) is not None:
            tap_text("删除服务", timeout=30, scroll=True)
            tap_text("确认删除（不可撤销）", timeout=30, scroll=True)
            wait_for(lambda items: find(items, "添加 MCP 服务器"), "删除后回到列表", timeout=60, scroll=True)
            log("服务已删除")
        else:
            log("没有可清理的服务")
        if find(nodes(ui_root()), "解除配对", exact=False) is not None:
            tap_text("解除配对", timeout=30, scroll=True)
            wait_for_text("连接云端网关", timeout=60)
            log("清理完成：配对已解除")
        else:
            log("当前未配对，无需解除")
    except Exception as error:
        log(f"清理失败（不影响验收结论，但请手工处理）：{error}")


def main() -> int:
    if len(PAIRING_CODE) < 8:
        print("::error::GATEWAY_ANDROID_PAIRING_CODE 未设置", flush=True)
        return 2
    if not SERVICE_URL.startswith("https://"):
        print("::error::GATEWAY_SERVICE_URL 必须是 https", flush=True)
        return 2

    try:
        launch()
        pair()
        add_service()
        grant_and_call()
    except FlowError as error:
        shot("failed")
        log(f"验收失败：{error}")
        return 1
    finally:
        try:
            cleanup()
        except Exception as error:  # cleanup 自己已经兜底，这里只防最后一道
            log(f"清理异常：{error}")

    log("DEVICE GATEWAY ACCEPTANCE: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
