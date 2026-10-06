"""计算把 icon.xml 移植成 Android 自适应图标所需的缩放参数。

自适应图标规则：
  - 画布 108dp x 108dp
  - 只有中心 72dp 直径的圆形区域对所有遮罩形状都可见
  - 背景层会被裁切，可以铺满 108dp
  - 前景层必须收在 72dp 内，否则会被裁掉边角

因此本图标应拆成两层：
  background -> 渐变圆（铺满，允许被裁）
  foreground -> 白环 + 羽毛（缩放到安全区）
"""
import math

DP = 108.0            # 自适应图标画布
SAFE_DP = 72.0        # 各遮罩形状的公共安全区直径
VB = 256.0            # 原 SVG 的 viewBox

print("=== 安全区换算 ===")
print(f"画布 {DP:.0f}dp，安全区直径 {SAFE_DP:.0f}dp")
print(f"安全区半径 = {SAFE_DP/2:.0f}dp，占画布半宽的 {SAFE_DP/DP*100:.1f}%")
print()

# ---- 原图元素 ----
ring = {"cx": 128, "cy": 120, "r": 78, "sw": 14}
feather_reach = 68.0          # 由 check_icon_svg.py 测出
center = VB / 2

print("=== 原图几何 ===")
print(f"白环: 圆心({ring['cx']},{ring['cy']}) r={ring['r']} sw={ring['sw']}")
ring_far = math.hypot(ring["cx"] - center, ring["cy"] - center) + ring["r"] + ring["sw"] / 2
print(f"  白环最远触达（相对画布中心）= {ring_far:.1f}")
print(f"  羽毛最远触达 = {feather_reach:.1f}")
print()

# ---- 目标：把"白环外沿"缩到安全区半径 ----
# 前景里最大的元素是白环，让它刚好贴住安全区边界
safe_radius_svg = SAFE_DP / 2 / DP * VB      # 安全区半径换算到原 viewBox 单位
fg_scale = safe_radius_svg / ring_far

print("=== 前景层缩放（白环贴安全区边界）===")
print(f"安全区半径（原 viewBox 单位）= {safe_radius_svg:.2f}")
print(f"前景缩放系数 = {fg_scale:.4f}")
print()
print("缩放后的前景元素（相对画布中心，单位 = 原 viewBox）:")
for name, reach in (("白环外沿", ring_far), ("羽毛", feather_reach)):
    print(f"  {name:<10} {reach*fg_scale:6.1f}  (安全边界 {safe_radius_svg:.1f})")
print()

# ---- 输出：以 108 viewBox 为目标的最终坐标 ----
k = DP / VB          # 原 viewBox -> 108dp 画布的换算
print("=== 最终坐标（viewBox 0 0 108 108）===")
print(f"换算 k = 108/256 = {k:.4f}")
print()
print("background（铺满，渐变圆）:")
print(f"  圆心 = ({center*k:.1f}, {center*k:.1f})")
print(f"  半径 = 54（铺满画布；会被遮罩裁切，正合预期）")
print()
print("foreground（收进安全区）:")
rcx = center + (ring["cx"] - center) * fg_scale
rcy = center + (ring["cy"] - center) * fg_scale
rr = ring["r"] * fg_scale
rsw = ring["sw"] * fg_scale
print(f"  白环圆心 = ({rcx*k:.2f}, {rcy*k:.2f})")
print(f"  白环半径 = {rr*k:.2f}")
print(f"  白环线宽 = {rsw*k:.2f}")
print()
print("  羽毛路径按同一系数缩放：")
print(f"    scale = {fg_scale:.4f}，围绕画布中心 ({center:.0f},{center:.0f}) 变换")
print()

# ---- 校验 ----
print("=== 校验 ===")
print(f"前景最远触达 = {ring_far*fg_scale*k:.2f}dp")
print(f"安全区半径   = {SAFE_DP/2:.2f}dp")
ok = ring_far * fg_scale * k <= SAFE_DP / 2 + 0.01
print("结论:", "前景完全落在安全区内" if ok else "仍然超出安全区！")
