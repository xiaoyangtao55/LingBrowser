"""分析并预览 tools/icon.xml，同时回答"能不能直接当 Android 启动图标"。"""
import os
import sys
import math

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svg_preview import parse_svg, render, XML, SS

view, grad, items = parse_svg(XML)
vx, vy, vw, vh = view

print(f"viewBox = {vw:.0f} x {vh:.0f}")
print(f"渐变色标: {grad}")
print()
print("元素清单:")
for it in items:
    if it["type"] == "circle":
        kind = f"圆 r={it['r']:.0f} @({it['cx']:.0f},{it['cy']:.0f})"
        paint = f"stroke={it['stroke']} w={it['sw']:.0f}" if it["stroke"] else f"fill={it['fill']}"
    else:
        kind = "路径"
        paint = f"fill={it['fill']}"
    print(f"  {kind:<28} {paint}  opacity={it['opacity']}")

# ---- Android 自适应图标安全区 ----
# 自适应图标画布 108dp，其中中心 72dp 是各形状共同的"安全区"，
# 外侧各 18dp 会被不同 launcher 裁掉。
SAFE_R = 72 / 108 / 2 * vw      # 安全区半径（换算到本 viewBox）
FULL_R = vw / 2

print()
print("=== Android 自适应图标安全区检查 ===")
print(f"安全区半径（必然可见）: {SAFE_R:.1f}  (占画布 {SAFE_R/FULL_R*100:.0f}%)")
print(f"整圆半径            : {FULL_R:.1f}")

worst = 0.0
for it in items:
    if it["type"] == "circle":
        for r_off, label in ((it["r"], "外沿"), (it["r"] + it["sw"] / 2, "描边外沿")):
            pass
        reach = it["r"] + (it["sw"] / 2 if it["stroke"] else 0)
        cx, cy = it["cx"], it["cy"]
        # 相对画布中心的最远距离
        d = math.hypot(cx - vw / 2, cy - vh / 2) + reach
        worst = max(worst, d)
        flag = "超出安全区" if d > SAFE_R + 0.5 else "在安全区内"
        print(f"  r={it['r']:<5.0f} 最远触达 {d:6.1f}  -> {flag}")
    else:
        pts = []
        if "C" in it["d"] or "c" in it["d"]:
            from svg_preview import parse_path, flatten
            for p, _ in flatten(parse_path(it["d"])):
                pts.extend(p)
        if pts:
            far = max(math.hypot(x - vw / 2, y - vh / 2) for x, y in pts)
            worst = max(worst, far)
            flag = "超出安全区" if far > SAFE_R + 0.5 else "在安全区内"
            print(f"  路径   最远触达 {far:6.1f}  -> {flag}")

print()
print(f"整体最远触达 {worst:.1f} / 安全区 {SAFE_R:.1f}")
if worst > SAFE_R + 0.5:
    print("  => 会被 launcher 裁切，需要整体缩小到 ~{:.0f}%".format(SAFE_R / worst * 100))
else:
    print("  => 不会被裁切")

# ---- 渲染预览 ----
SIZE = 64
img, S = render(items, grad, view, SIZE, ss=2)
print()
print(f"=== ASCII 预览 ({SIZE}x{SIZE}) ===")
for y in range(0, S, 2):
    row = ""
    for x in range(S, 0, -2):
        c = img[y][x - 1]
        if len(c) > 3 and c[3] < 40:
            row += " "
            continue
        r, g, b = c[0], c[1], c[2]
        lum = (0.299 * r + 0.587 * g + 0.114 * b)
        if lum > 215:
            row += "@"
        elif lum > 150:
            row += "o"
        elif lum > 90:
            row += "."
        else:
            row += " "
    print("  " + row)
