#!/usr/bin/env python3
"""从设计稿生成启动器图标的两层资源（自适应图标）。

由来（Gate 2）：启动器图标换成了新的设计稿 —— 一张**满幅**的圆角方形插画
（两块玻璃片 + 轨道 + 星空），不是 Gate 0 那张"浅灰卡片 + 彩色线条图案"。
新稿没有可抠的背景，整张卡片就是图案本身，所以生成方式也随之改变：

  · 前景：把设计稿四周的黑边裁掉、补成正方形，然后**铺满** 108dp 画布。
    Gate 0 的做法是让图案只占画布中央 72%（怕被遮罩切掉）；新稿是一张满幅插画，
    缩到 72% 反而会在图标里露出底色，所以直接满幅 —— 遮罩切掉的是四个角，
    而构图（两块玻璃片、轨道、月亮）都在中央，实测圆形/圆角方形/圆角矩形三种遮罩
    都不会切到内容（见 android/README-ANDROID.md §5.1）。
  · 单色层：Gate 0 是取图案的 alpha 当剪影；新稿的 alpha 整张都是不透明的，
    取 alpha 会得到一块实心方块，所以这里**画**一个同构的剪影
    （两块倾斜的圆角玻璃 + 一圈轨道 + 两个光点），交给系统着色。

用法（只需要 Pillow，不需要 Android SDK）：

    python3 android/art/make_launcher_icon.py

输入：  android/art/ic_launcher_source.png
输出：  android/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
            ic_launcher_foreground.png    彩色前景，满幅，背景透明
            ic_launcher_monochrome.png    白色剪影（Android 13+ 主题图标用）

它顺手把测量结果打印出来，方便核对 res/values/colors.xml 里的 ic_launcher_background
（取卡片上边缘的深蓝；满幅前景几乎盖满画布，这个底色只在极端遮罩/视差下露出一点）。
"""

from __future__ import annotations

import os
import sys

from PIL import Image, ImageDraw, ImageStat

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main", "res"))
SRC = os.path.join(HERE, "ic_launcher_source.png")

# 各密度对应的 108dp 画布像素数（1x/1.5x/2x/3x/4x）。
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# 判定"设计稿的黑边"的亮度阈值：设计稿四角是纯黑（留白），卡片本体最暗处也远高于它。
BLACK_CUTOFF = 30

# 单色剪影在多大画布上绘制（再缩放到各密度；画大一点边缘更干净）。
SILHOUETTE_CANVAS = 1024


def card_box(img: Image.Image) -> tuple[int, int, int, int]:
    """卡片（去掉四周黑边）的包围盒，并补成以它为中心的正方形。"""
    width, height = img.size
    mask = img.convert("L").point(lambda v: 255 if v > BLACK_CUTOFF else 0)
    box = mask.getbbox()
    if box is None:
        raise SystemExit("整张图都是黑的：源图不对，或 BLACK_CUTOFF 设错了")
    x0, y0, x1, y1 = box
    side = max(x1 - x0, y1 - y0)
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    left, top = cx - side // 2, cy - side // 2
    # 正方形可能超出画布，夹回来（超出的部分本来就是黑边）。
    left = max(0, min(left, width - side))
    top = max(0, min(top, height - side))
    print(f"  内容包围盒 {box}（黑边 左{x0} 上{y0} 右{width - x1} 下{height - y1}）")
    return (left, top, left + side, top + side)


def edge_colour(card: Image.Image) -> tuple[int, int, int]:
    """卡片顶部夜空的主色 —— 这就是 ic_launcher_background 应该取的值。

    取 2%…6% 那几行，而不是最上面几行：设计稿卡片自己的上边缘有一圈很暗的抗锯齿
    描边（最上面 4 行均值只有 #182333），把它算进去会把底色压成近黑。
    """
    h = card.height
    strip = card.crop((0, max(1, round(h * 0.02)), card.width, max(2, round(h * 0.06))))
    return tuple(int(v) for v in ImageStat.Stat(strip).mean)


def _pane(box, radius, angle):
    """一块倾斜的圆角玻璃（画在整张剪影画布上，绕画布中心旋转）。"""
    layer = Image.new("L", (SILHOUETTE_CANVAS, SILHOUETTE_CANVAS), 0)
    ImageDraw.Draw(layer).rounded_rectangle(box, radius=radius, fill=255)
    return layer.rotate(
        angle, resample=Image.BICUBIC, center=(SILHOUETTE_CANVAS // 2, SILHOUETTE_CANVAS // 2)
    )


def silhouette() -> Image.Image:
    """同构剪影：两块倾斜的玻璃 + 一圈轨道 + 两个光点。

    刻意**实心**：主题图标是系统拿这层的 alpha 当蒙版、自己上色，
    描边式的细线在深色/浅色主题下都可能细到看不见。
    """
    size = SILHOUETTE_CANVAS
    out = Image.new("L", (size, size), 0)

    # 左玻璃（后、略窄），右玻璃（前、略高），倾角与设计稿一致（约 ±7°）。
    for box, radius, angle in (
        ((300, 180, 470, 830), 44, -7),
        ((560, 250, 730, 880), 44, 7),
    ):
        layer = _pane(box, radius, angle)
        out = Image.composite(layer, out, layer)

    # 轨道：一圈椭圆环，转 -11° 与两块玻璃的走向呼应。
    ring = Image.new("L", (size, size), 0)
    ImageDraw.Draw(ring).ellipse((120, 430, 904, 640), outline=255, width=22)
    ring = ring.rotate(-11, resample=Image.BICUBIC, center=(size // 2, size // 2))
    out = Image.composite(ring, out, ring)

    # 两个光点（轨道上那两颗）。
    dots = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(dots)
    draw.ellipse((190, 500, 258, 568), fill=255)
    draw.ellipse((766, 560, 828, 622), fill=255)
    return Image.composite(dots, out, dots)


def main() -> int:
    if not os.path.exists(SRC):
        raise SystemExit(f"找不到源图：{SRC}")

    src = Image.open(SRC).convert("RGB")
    print(f"源图 {SRC}")
    print(f"  尺寸 {src.size}")
    card = src.crop(card_box(src))
    print(f"  卡片 {card.size}")

    bg = edge_colour(card)
    print(f"  上边缘平均色 = {bg} → #%02X%02X%02X" % bg)

    mono = silhouette()
    for name, px in DENSITIES.items():
        outdir = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(outdir, exist_ok=True)

        # 前景：满幅铺满画布（不留边距、不透明），遮罩由启动器决定。
        card.resize((px, px), Image.LANCZOS).convert("RGBA").save(
            os.path.join(outdir, "ic_launcher_foreground.png"), optimize=True
        )

        # 单色层：白色 + 剪影 alpha（RGB 必须是白，系统只取 alpha 当蒙版）。
        white = Image.new("RGBA", (px, px), (255, 255, 255, 0))
        white.putalpha(mono.resize((px, px), Image.LANCZOS))
        white.save(os.path.join(outdir, "ic_launcher_monochrome.png"), optimize=True)

        print(f"  mipmap-{name}: {px}×{px}")

    print()
    print("别忘了 res/values/colors.xml 里的 ic_launcher_background 要与上面的边缘色一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
