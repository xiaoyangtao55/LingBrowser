"""把生成的 PNG 图标渲染成 ASCII，用于人工核对构图（我无法直接看图）。"""
import os
import sys
from PIL import Image

ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res",
)

folder = sys.argv[1] if len(sys.argv) > 1 else "mipmap-xxxhdpi"
cols = int(sys.argv[2]) if len(sys.argv) > 2 else 76

img = Image.open(os.path.join(ROOT, folder, "ic_launcher.png")).convert("RGBA")
W = cols
H = int(cols * img.size[1] / img.size[0] * 0.5)   # 终端字符高宽比约 2:1
img = img.resize((W, H * 2), Image.LANCZOS)

print(f"{folder}/ic_launcher.png  ->  {W}x{H} 字符预览")
print()

for y in range(H):
    row = ""
    for x in range(W):
        # 每格取 2x2 平均
        acc = [0, 0, 0, 0]
        for dy in range(2):
            for dx in range(1):
                r, g, b, a = img.getpixel((x, y * 2 + dy))
                acc[0] += r; acc[1] += g; acc[2] += b; acc[3] += a
        r, g, b, a = (v / 2 for v in acc)
        if a < 40:
            row += " "
            continue
        lum = 0.299 * r + 0.587 * g + 0.114 * b
        if lum > 235:
            row += "@"      # 纯白（环/羽毛）
        elif lum > 190:
            row += "#"      # 接近白
        elif lum > 150:
            row += "="      # 亮青
        elif lum > 110:
            row += "-"      # 中青
        else:
            row += "."      # 深蓝
    print("  " + row)
