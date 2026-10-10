#!/usr/bin/env python3
"""从原始启动器图标生成 Android 自适应图标的三层资源。

为什么要这个脚本：图标是设计稿（一张带浅灰卡片底的 PNG），而 Android 的自适应图标要的是
「背景色 + 前景（含透明通道）+ 单色剪影」三层。把设计稿直接丢进去会被各家启动器的遮罩切掉
边缘，所以需要一次性的转换。转换是**可复现**的，所以脚本留在仓库里，而不是靠记忆重做。

用法（只需要 Pillow，不需要装 Android SDK）：

    python3 android/art/make_adaptive_icon.py

输入：  android/art/ic_launcher_source.png
输出：  android/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
            ic_launcher_foreground.png    彩色前景，背景透明
            ic_launcher_monochrome.png    纯黑剪影（Android 13+ 主题图标用，交给系统着色）

它顺手把测量结果打印出来，方便核对：
  * 卡片内部的中位色（就是 ic_launcher_background 该取的值）
  * 图案的包围盒
  * 每一档的输出尺寸

设计约束（改脚本前先读这几条）：

1. **前景只占画布中央 SAFE_RATIO**。自适应图标是 108dp 画布，其中保证可见的是中央约 66dp 的圆。
   本图案是横向的（长宽比约 1.285），所以按「最长边」缩放。
2. **单色层必须是剪影**。系统会对 monochrome 层整体着色，直接复用彩色前景会变成一坨。
   这里取前景的 alpha 通道填黑 —— 形状完全一致，颜色交给系统。
3. **抠图靠彩度，不靠"是不是白色"**。卡片的浅灰（RGB 差 < 10）要变透明，而图案里的浅薄荷绿
   （RGB 差约 30）必须留下。用 RGB 最大值减最小值做软过渡，比阈值二值化干净得多。
"""

from __future__ import annotations

import os
import statistics as st
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "res"))
SRC = os.path.join(HERE, "ic_launcher_source.png")

# 图案最长边占 108dp 画布的比例。0.72 是实测出来的：
# 图案包围盒的角点落在半径约 49dp 处，仍稳稳在 54dp 的可见圆内（详见 COMPATIBILITY-ANDROID-13-16.md §9.1）。
SAFE_RATIO = 0.72
# 彩度软过渡：sat <= LOW 全透明，sat >= HIGH 全不透明。
SAT_LOW, SAT_HIGH = 10.0, 22.0
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def card_colour(img: Image.Image) -> tuple[int, int, int]:
    """卡片内部的中位色 —— 这就是 ic_launcher_background 应该取的值。"""
    w, h = img.size
    px = img.load()
    samples = []
    for y in range(0, h, 7):
        for x in range(0, w, 7):
            r, g, b, a = px[x, y]
            if a >= 200 and max(r, g, b) - min(r, g, b) < 8:
                samples.append((r, g, b))
    if not samples:
        raise SystemExit("没找到卡片底色样本：源图可能不是「浅底 + 彩色图案」")
    return tuple(int(st.median([c[i] for c in samples])) for i in range(3))


def extract_art(img: Image.Image) -> Image.Image:
    """把近白卡片变成透明，只留下彩色图案。"""
    w, h = img.size
    px = img.load()
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    op = out.load()
    kept = 0
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            sat = max(r, g, b) - min(r, g, b)
            if sat <= SAT_LOW:
                continue
            t = min(1.0, (sat - SAT_LOW) / (SAT_HIGH - SAT_LOW))
            alpha = int(round(255 * t * (a / 255.0)))
            if alpha <= 3:
                continue
            op[x, y] = (r, g, b, alpha)
            kept += 1
    bbox = out.getchannel("A").getbbox()
    if bbox is None:
        raise SystemExit("抠图后一个像素都没留下：SAT_LOW 可能设得太高了")
    print(f"  保留像素 {kept}（{100 * kept / (w * h):.2f}%），图案包围盒 {bbox}")
    return out.crop(bbox)


def main() -> int:
    if not os.path.exists(SRC):
        raise SystemExit(f"找不到源图：{SRC}")

    src = Image.open(SRC).convert("RGBA")
    print(f"源图 {SRC}")
    print(f"  尺寸 {src.size}")

    bg = card_colour(src)
    print(f"  卡片底色中位数 = {bg} → #%02X%02X%02X" % bg)

    art = extract_art(src)
    aw, ah = art.size
    print(f"  长宽比 {aw / ah:.3f}")

    for name, k in DENSITIES.items():
        size = int(round(108 * k))
        scale = (SAFE_RATIO * size) / max(aw, ah)
        nw, nh = max(1, int(round(aw * scale))), max(1, int(round(ah * scale)))
        resized = art.resize((nw, nh), Image.LANCZOS)
        pos = ((size - nw) // 2, (size - nh) // 2)

        outdir = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(outdir, exist_ok=True)

        fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        fg.paste(resized, pos, resized)
        fg.save(os.path.join(outdir, "ic_launcher_foreground.png"), optimize=True)

        mono = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        silhouette = Image.new("RGBA", (nw, nh), (0, 0, 0, 0))
        silhouette.putalpha(resized.getchannel("A"))
        mono.paste(silhouette, pos, silhouette)
        mono.save(os.path.join(outdir, "ic_launcher_monochrome.png"), optimize=True)

        print(f"  mipmap-{name}: 画布 {size}×{size}，图案 {nw}×{nh}")

    print()
    print("别忘了 res/values/colors.xml 里的 ic_launcher_background 要与上面的底色一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
