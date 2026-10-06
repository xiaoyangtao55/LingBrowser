"""为「翎」浏览器生成 API < 26 所需的传统 launcher 图标。

API 23~25（Android 6~7）不支持自适应图标，只能给位图。
这里绘制与 tools/icon.xml 完全一致的造型：
  渐变圆底 + 白色浏览器外环 + 羽毛 + 高光点

传统图标没有遮罩裁切，所以整圆可以铺满画布（与自适应图标的背景层一致），
但内容仍需留出内边距，否则贴边会显得拥挤。
"""
import os
import sys
import math

from PIL import Image, ImageDraw, ImageChops

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svg_preview import parse_svg, parse_path, flatten, XML

OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res",
)

# tools/icon.xml 的渐变色标
GRAD = [(0.0, (0x37, 0xE0, 0xC8)), (1.0, (0x1E, 0x88, 0xE5))]

VIEW = 256          # 原 SVG viewBox
SIZES = [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
]
SS = 8              # 超采样倍数


def grad_color(t):
    """对角线性渐变的颜色采样。"""
    t = max(0.0, min(1.0, t))
    for k in range(len(GRAD) - 1):
        a, b = GRAD[k], GRAD[k + 1]
        if a[0] <= t <= b[0]:
            span = (b[0] - a[0]) or 1.0
            u = (t - a[0]) / span
            return tuple(int(a[1][j] + (b[1][j] - a[1][j]) * u) for j in range(3))
    return GRAD[-1][1]


def make_gradient(size):
    """生成对角渐变底图。"""
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            px[x, y] = grad_color((x / size + y / size) / 2.0)
    return img


def draw_icon(size):
    px = size * SS
    scale = px / VIEW
    s = lambda v: v * scale

    # ---- 渐变圆底（圆形遮罩）----
    grad = make_gradient(px)
    mask = Image.new("L", (px, px), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, px - 1, px - 1], fill=255)
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    img.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(img)

    view, _, items = parse_svg(XML)
    circles = [i for i in items if i["type"] == "circle"]
    paths = [i for i in items if i["type"] == "path"]

    ring = next(c for c in circles if c["stroke"])
    dot = next(c for c in circles if c["fill"].startswith("#") and not c["stroke"])
    feather = paths[0]

    # ---- 白色浏览器外环 ----
    w = s(ring["sw"])
    d.ellipse(
        [s(ring["cx"] - ring["r"]), s(ring["cy"] - ring["r"]),
         s(ring["cx"] + ring["r"]), s(ring["cy"] + ring["r"])],
        outline=(255, 255, 255, int(255 * ring["opacity"])),
        width=max(1, int(round(w))),
    )

    # ---- 羽毛（贝塞尔采样成多边形）----
    # 必须按 SVG 的 evenodd 规则异或各子路径：羽轴是"负空间"挖出来的细槽，
    # 直接逐个子路径填白会把槽也填成白色 —— 白压白等于没画，
    # 位图 fallback 上的羽轴就此消失（矢量层用 fillType="evenOdd"，不受影响）。
    mask = Image.new("1", (px, px), 0)
    for pts, closed in flatten(parse_path(feather["d"]), steps=40):
        poly = [(s(x), s(y)) for x, y in pts]
        if len(poly) < 3:
            continue
        layer = Image.new("1", (px, px), 0)
        ImageDraw.Draw(layer).polygon(poly, fill=1)
        mask = ImageChops.logical_xor(mask, layer)
    alpha = mask.convert("L").point(
        lambda v: int(255 * feather["opacity"]) if v else 0
    )
    img.paste(Image.new("RGB", (px, px), (255, 255, 255)), (0, 0), alpha)

    # ---- 高光点 ----
    d.ellipse(
        [s(dot["cx"] - dot["r"]), s(dot["cy"] - dot["r"]),
         s(dot["cx"] + dot["r"]), s(dot["cy"] + dot["r"])],
        fill=(0xE8, 0xFB, 0xFF, int(255 * dot["opacity"])),
    )

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
