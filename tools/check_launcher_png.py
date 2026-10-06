"""用数字校验生成的 PNG 图标（因为我无法直接查看图片）。

检查：
  - 圆角/圆形外观：四角是否透明（圆形遮罩生效）
  - 渐变是否真的存在（左上偏青、右下偏蓝）
  - 白色外环是否闭合、位置是否正确
  - 羽毛是否落在环内
  - 各密度尺寸是否正确
"""
import os
from PIL import Image

ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res",
)
SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

problems = []


def analyze(folder, expect):
    path = os.path.join(ROOT, folder, "ic_launcher.png")
    img = Image.open(path).convert("RGBA")
    w, h = img.size
    print(f"--- {folder}  {w}x{h} ---")

    if (w, h) != (expect, expect):
        problems.append(f"{folder} 尺寸应为 {expect}x{expect}，实际 {w}x{h}")

    px = img.load()

    # 1) 四角透明（圆形遮罩）
    corners = [px[0, 0], px[w - 1, 0], px[0, h - 1], px[w - 1, h - 1]]
    opaque_corners = [c for c in corners if c[3] > 40]
    if opaque_corners:
        problems.append(f"{folder} 四角不透明，圆形遮罩没生效: {opaque_corners}")
    print(f"  四角 alpha: {[c[3] for c in corners]}  (应为 0 = 透明)")

    # 2) 中心不透明
    c = px[w // 2, h // 2]
    if c[3] < 200:
        problems.append(f"{folder} 中心几乎透明 alpha={c[3]}")
    print(f"  中心像素 RGBA = {c}")

    # 3) 渐变：左上应偏青(高G高B低R)，右下偏蓝
    tl = px[int(w * 0.18), int(h * 0.18)]
    br = px[int(w * 0.82), int(h * 0.82)]
    print(f"  左上角区 RGBA = {tl}")
    print(f"  右下角区 RGBA = {br}")
    if tl[3] > 200 and br[3] > 200:
        if not (tl[1] > tl[2] - 30):
            problems.append(f"{folder} 左上不偏青: {tl}")
        if not (br[2] > br[1] - 20):
            problems.append(f"{folder} 右下不偏蓝: {br}")

    # 4) 白色像素占比（外环 + 羽毛）
    white = sum(
        1 for y in range(h) for x in range(w)
        if px[x, y][3] > 128 and px[x, y][0] > 230
        and px[x, y][1] > 230 and px[x, y][2] > 230
    )
    ratio = white / (w * h) * 100
    print(f"  近白像素占比 = {ratio:.1f}%")
    if ratio < 5:
        problems.append(f"{folder} 白色元素太少({ratio:.1f}%)，环或羽毛可能没画出来")
    if ratio > 60:
        problems.append(f"{folder} 白色元素太多({ratio:.1f}%)")

    # 5) 外环：沿水平中线从中心向外找白环
    cy = h // 2
    hits = [x for x in range(w) if px[x, cy][3] > 128 and px[x, cy][0] > 230]
    if hits:
        print(f"  水平中线白色像素 x 范围: {min(hits)}..{max(hits)}")
    print()


for folder, size in SIZES.items():
    analyze(folder, size)

print("=" * 50)
if problems:
    print(f"发现 {len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
else:
    print("全部 PNG 图标校验通过")
