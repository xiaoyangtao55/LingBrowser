"""计算把 icon.xml 移植成 Android 自适应图标所需的缩放参数（推导说明用）。

自适应图标规则：
  - 画布 108dp x 108dp
  - 只有中心 72dp 直径的圆形区域对所有遮罩形状都可见
  - 背景层会被裁切，可以铺满 108dp
  - 前景层必须收在 72dp 内，否则会被裁掉边角

图标与主页 logo 同款：**纯色圆底 + 一枚描边羽毛（无外环）**。所以前景的缩放
基准是**羽毛的最远触达**，而不是旧版的白色外环。

实际生效的系数由 `tools/gen_launcher_icon.py` 在生成时算出并打印；这个脚本把
同一份推导摊开讲清楚。为避免"改完 icon.xml 这里没跟着改"的漂移，
羽毛触达是从 icon.xml **实测**的，不是写死的。
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svg_preview import parse_svg, parse_path, flatten, XML

DP = 108.0            # 自适应图标画布
SAFE_DP = 72.0        # 各遮罩形状的公共安全区直径
VB = 256.0            # 原 SVG 的 viewBox
MARGIN = 0.08         # 前景留出的安全余量

CENTER = VB / 2
K = DP / VB
safe_radius_svg = SAFE_DP / 2 / DP * VB

view, _, items = parse_svg(XML)
feather = [i for i in items if i["type"] == "path"]
sw = max(p["sw"] for p in feather)

pts = []
for p in feather:
    for sub, _ in flatten(parse_path(p["d"]), steps=48, matrix=p.get("matrix")):
        pts.extend(sub)
feather_reach = max(math.hypot(x - CENTER, y - CENTER) for x, y in pts) + sw / 2

fg_scale = safe_radius_svg * (1 - MARGIN) / feather_reach

print("=== 安全区换算 ===")
print(f"画布 {DP:.0f}dp，安全区直径 {SAFE_DP:.0f}dp")
print(f"安全区半径 = {SAFE_DP / 2:.0f}dp；换算到 256 视口 = {safe_radius_svg:.2f}")
print()
print("=== 前景层缩放（羽毛贴安全区、留 8% 余量）===")
print(f"羽毛 {len(feather)} 条描边路径，stroke-width {sw}")
print(f"羽毛（含描边）最远触达 = {feather_reach:.1f}（256 视口）")
print(f"前景缩放系数 = safe_radius_svg * 0.92 / feather_reach = {fg_scale:.4f}")
print()
print(f"缩放后羽毛最远触达 = {feather_reach * fg_scale:.1f} 视口 "
      f"= {feather_reach * fg_scale * K:.2f}dp")
print(f"安全区半径 = {SAFE_DP / 2:.2f}dp")
ok = feather_reach * fg_scale * K <= SAFE_DP / 2 + 0.01
print("结论:", "前景完全落在安全区内" if ok else "仍然超出安全区！")
print()
print("=== 背景层 ===")
print("纯色铺满 108dp 画布；外侧会被遮罩裁切，正合预期")
