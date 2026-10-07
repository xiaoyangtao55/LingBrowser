"""把 HomePage.kt 里的主页 logo（内联 SVG）渲染成 PNG，用来肉眼核对造型。

为什么单独写一个：`tools/svg_preview.py` 只画**填充**图形（circle / path 的
fill），而主页 logo 是 `fill="none"` 的**描边**线画，它渲染不出来 ——
结果就是"启动图标能看见、主页 logo 看不见"，改造型只能靠猜。

做法：从 Kotlin 源码里抽出 `<svg>…</svg>`，把 M/L/C/Q/Z 采样成点列，
再用 Pillow 以圆角端点/拐角描出来（等价于 stroke-linecap/linejoin="round"）。

    python tools/preview_home_mark.py        # 当前造型 -> build/home-mark.png
    python tools/preview_home_mark.py old    # 改版前的造型，用于对照

输出同时包含 200px 原图、40px 实际显示尺寸、以及两者并排的对照图。
"""
import os
import re
import sys

from PIL import Image, ImageDraw

BOX = 24.0          # viewBox 边长
STROKE = 1.6        # stroke-width
# 主页 logo 的取色（HomeColors 默认值，浅色主题）：--pc 圆底 / --on-pc 羽毛描边
PC_RGB = (0xA8, 0xF2, 0xCB)
ON_PC_RGB = (0x00, 0x21, 0x0F)
HERE = os.path.dirname(os.path.abspath(__file__))
KOTLIN = os.path.join(
    os.path.dirname(HERE),
    "app", "src", "main", "java", "com", "ling", "browser", "web", "HomePage.kt",
)
OUT_DIR = os.path.join(os.path.dirname(HERE), "build")

# 改版前的 logo（相对指令已换算成绝对指令），用于同条件对照
OLD_SVG = '''<svg viewBox="0 0 24 24" fill="none" stroke-width="1.6">
  <path d="M20.2 3.8 C20.2 10.4 14.8 15.8 8.2 15.8 L4.6 15.8"/>
  <path d="M20.2 3.8 C11.6 3.8 4.6 10.8 4.6 19.4 L4.6 20.2"/>
  <path d="M20.2 3.8 L6.6 17.4"/>
  <path d="M9.4 12.2 L14.6 12.2"/>
  <path d="M13.2 8.4 L17.4 8.4"/>
</svg>'''


def extract_svg():
    src = open(KOTLIN, encoding="utf-8").read()
    m = re.search(r"<svg\b.*?</svg>", src, re.S)
    if not m:
        raise SystemExit("HomePage.kt 里找不到 <svg>")
    return m.group(0)


def flatten(d):
    """解析 M/L/C/Q/Z（绝对坐标），采样成用户坐标点列。"""
    toks = re.findall(r"[MLCQZmlcqz]|-?\d*\.?\d+", d)
    subs, cur, start, cmd, i = [], [], None, None, 0

    def cubic(p0, c1, c2, p1):
        for k in range(1, 25):
            t = k / 24
            mt = 1 - t
            cur.append((
                mt ** 3 * p0[0] + 3 * mt ** 2 * t * c1[0] + 3 * mt * t ** 2 * c2[0]
                + t ** 3 * p1[0],
                mt ** 3 * p0[1] + 3 * mt ** 2 * t * c1[1] + 3 * mt * t ** 2 * c2[1]
                + t ** 3 * p1[1],
            ))

    def num():
        nonlocal i
        v = float(toks[i])
        i += 1
        return v

    while i < len(toks):
        t = toks[i]
        if re.match(r"[A-Za-z]", t):
            cmd = t
            i += 1
            if cmd in "Zz":
                if cur:
                    subs.append((list(cur), True))
                cur = [start] if start else []
            continue
        if cmd == "M":
            p = (num(), num())
            cur, start = [p], p
        elif cmd == "L":
            cur.append((num(), num()))
        elif cmd == "C":
            c1 = (num(), num())
            c2 = (num(), num())
            p = (num(), num())
            cubic(cur[-1] if cur else c1, c1, c2, p)
        elif cmd == "Q":
            c = (num(), num())
            p = (num(), num())
            p0 = cur[-1] if cur else c
            for k in range(1, 25):
                tt = k / 24
                mt = 1 - tt
                cur.append((mt ** 2 * p0[0] + 2 * mt * tt * c[0] + tt ** 2 * p[0],
                            mt ** 2 * p0[1] + 2 * mt * tt * c[1] + tt ** 2 * p[1]))
        else:
            i += 1
    if cur:
        subs.append((cur, False))
    return subs


def draw(svg_text, path, size=200, ss=6):
    w = size * ss
    scale = w / BOX
    img = Image.new("RGB", (w, w), (18, 20, 26))
    d = ImageDraw.Draw(img)
    # 主页 logo 坐在 --pc 圆底上、羽毛用 --on-pc 描边（见 HomePage.kt 的 CSS）。
    # 这两个值取 HomeColors 的默认配色（浅色主题），与启动图标用的是同一对，
    # 所以这张预览可以直接和启动图标对照。之前这里用品牌绿近似，颜色对不上。
    d.ellipse([0, 0, w - 1, w - 1], fill=PC_RGB)
    sw = STROKE * scale

    for m in re.finditer(r'<path\s+d="([^"]+)"', svg_text):
        for poly, closed in flatten(m.group(1)):
            poly = [(x * scale, y * scale) for x, y in poly]
            if closed and len(poly) > 2:
                poly = poly + [poly[0]]
            for a, b in zip(poly, poly[1:]):
                d.line([a, b], fill=ON_PC_RGB, width=int(round(sw)))
            r = sw / 2
            for x, y in poly:                      # 圆角端点 / 拐角
                d.ellipse([x - r, y - r, x + r, y + r], fill=ON_PC_RGB)

    img = img.resize((size, size), Image.LANCZOS)
    img.save(path)
    return img


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "now"
    svg = OLD_SVG if mode == "old" else extract_svg()
    os.makedirs(OUT_DIR, exist_ok=True)

    name = "home-mark-old" if mode == "old" else "home-mark"
    big = draw(svg, os.path.join(OUT_DIR, f"{name}.png"))
    small = big.resize((40, 40), Image.LANCZOS)
    sheet = Image.new("RGB", (big.width + 200, big.height), (18, 20, 26))
    sheet.paste(big, (0, 0))
    sheet.paste(small.resize((160, 160), Image.LANCZOS), (big.width + 20, 20))
    sheet.save(os.path.join(OUT_DIR, f"{name}-sizes.png"))
    print(f"wrote build/{name}.png / build/{name}-sizes.png")


if __name__ == "__main__":
    main()
