"""为「翎」浏览器生成 API < 26 所需的传统 launcher 图标。

API 23~25（Android 6~7）不支持自适应图标，只能给位图。
这里绘制与 tools/icon.xml 完全一致的造型：
  纯色圆底（主页 primaryContainer）+ 描边羽毛（与主页 logo 同款），无外环。

传统图标没有遮罩裁切，所以整圆可以铺满画布（与自适应图标的背景层一致），
但内容仍需留出内边距，否则贴边会显得拥挤。
"""
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svg_preview import parse_svg, parse_path, flatten, XML

OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res",
)

VIEW = 256          # 原 SVG viewBox
SIZES = [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
]
SS = 8              # 超采样倍数


def hex_rgb(h):
    """#RRGGBB -> (r, g, b)。"""
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def draw_icon(size):
    px = size * SS
    scale = px / VIEW
    s = lambda v: v * scale

    view, _, items = parse_svg(XML)
    circles = [i for i in items if i["type"] == "circle"]
    paths = [i for i in items if i["type"] == "path"]

    # ---- 纯色圆底（主页 primaryContainer，圆形遮罩）----
    bg = next(c for c in circles if c["fill"] and not c["stroke"])
    feather = paths

    mask = Image.new("L", (px, px), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, px - 1, px - 1], fill=255)
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    img.paste(Image.new("RGB", (px, px), hex_rgb(bg["fill"])), (0, 0), mask)
    d = ImageDraw.Draw(img)

    # ---- 羽毛（描边路径，与主页 logo 一致）----
    # 每条 path 采样成点列后用圆角线描出来，等价于 stroke-linecap/join="round"。
    # 描边色取自每条的 stroke（主页的 onPrimaryContainer）。
    for p in feather:
        rgb = hex_rgb(p["stroke"])
        sw_px = max(1, int(round(s(p["sw"]))))
        for poly, _ in flatten(parse_path(p["d"]), steps=48):
            poly = [(s(x), s(y)) for x, y in poly]
            if len(poly) < 2:
                continue
            for a, b in zip(poly, poly[1:]):
                d.line([a, b], fill=rgb + (255,), width=sw_px)
            r = sw_px / 2
            for x, y in poly:                        # 圆角端点 / 拐角
                d.ellipse([x - r, y - r, x + r, y + r], fill=rgb + (255,))

    return img.resize((size, size), Image.LANCZOS)


def main():
    for folder, size in SIZES:
        target_dir = os.path.join(OUT, folder)
        os.makedirs(target_dir, exist_ok=True)
        icon = draw_icon(size)
        path = os.path.join(target_dir, "ic_launcher.png")
        icon.save(path, "PNG", optimize=True)
        print(f"  wrote {os.path.relpath(path, os.path.dirname(OUT))} ({size}x{size})")


if __name__ == "__main__":
    main()
