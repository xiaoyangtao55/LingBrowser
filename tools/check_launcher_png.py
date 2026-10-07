"""用数字校验生成的 PNG 图标（因为我无法直接查看图片）。

图标现在与主页 logo 同款：**纯色圆底（primaryContainer）+ 描边羽毛
（onPrimaryContainer），无外环、无渐变**。检查：

  - 圆形外观：四角是否透明（圆形遮罩生效）
  - 底色是否为预期的纯色、且在环内区域均匀
  - 羽毛描边色是否真的画上去了（足够多的深色像素）
  - 没有残留的白色外环
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

# 主页 logo 的取色（HomeColors 默认值，浅色主题）
BG = (0xA8, 0xF2, 0xCB)      # primaryContainer
FG = (0x00, 0x21, 0x0F)      # onPrimaryContainer

problems = []


def near(c, target, tol=24):
    return all(abs(c[i] - target[i]) <= tol for i in range(3))


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
    opaque = [c for c in corners if c[3] > 40]
    if opaque:
        problems.append(f"{folder} 四角不透明，圆形遮罩没生效: {opaque}")
    print(f"  四角 alpha: {[c[3] for c in corners]}  (应为 0 = 透明)")

    # 2) 中心不透明
    c = px[w // 2, h // 2]
    if c[3] < 200:
        problems.append(f"{folder} 中心几乎透明 alpha={c[3]}")
    print(f"  中心像素 RGBA = {c}")

    # 3) 底色是纯色且在圆内均匀：统计不透明区域里接近 BG 的占比
    opaque = bg_cnt = 0
    for y in range(h):
        for x in range(w):
            p = px[x, y]
            if p[3] > 200:
                opaque += 1
                if near(p, BG):
                    bg_cnt += 1
    bg_ratio = bg_cnt / max(1, opaque) * 100
    print(f"  底色 {BG} 占不透明区域 = {bg_ratio:.1f}%")
    if opaque < w * h * 0.6:
        problems.append(f"{folder} 不透明区域只有 {opaque / (w * h) * 100:.0f}%，圆形遮罩异常")
    if bg_ratio < 40:
        problems.append(f"{folder} 底色不是预期纯色 {BG}（仅占 {bg_ratio:.1f}%）")

    # 4) 羽毛描边色确实画上去了
    fg_cnt = sum(
        1 for y in range(h) for x in range(w)
        if px[x, y][3] > 128 and near(px[x, y], FG)
    )
    fg_ratio = fg_cnt / (w * h) * 100
    print(f"  羽毛描边色占比 = {fg_ratio:.1f}%")
    if fg_ratio < 3:
        problems.append(f"{folder} 羽毛描边色太少({fg_ratio:.1f}%)，羽毛可能没画出来")
    if fg_ratio > 60:
        problems.append(f"{folder} 羽毛描边色太多({fg_ratio:.1f}%)")

    # 5) 不应再有白色外环
    white = sum(
        1 for y in range(h) for x in range(w)
        if px[x, y][3] > 128 and px[x, y][0] > 230
        and px[x, y][1] > 230 and px[x, y][2] > 230
    )
    w_ratio = white / (w * h) * 100
    print(f"  近白像素占比 = {w_ratio:.1f}%  (外环已去掉，应接近 0)")
    if w_ratio > 2:
        problems.append(f"{folder} 仍有近白像素 {w_ratio:.1f}%，白色外环可能没去掉")
    print()


for folder, size in SIZES.items():
    analyze(folder, size)

print("=" * 50)
if problems:
    print(f"发现 {len(problems)} 个问题：")
    for p in problems:
        print("  ! " + p)
    raise SystemExit(1)
print("全部 PNG 图标校验通过")
