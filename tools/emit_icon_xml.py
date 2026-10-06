"""把羽毛造型转换成 SVG 路径，并重写 tools/icon.xml。

造型在**羽轴局部坐标系**里描述：
  - `t`：0 = 羽片根部，1 = 羽尖；`t < 0` 那一段是露在羽片外面的裸羽柄
  - `w`：垂直于羽轴；`+` 为右下侧，`-` 为左上侧

羽毛之所以像羽毛（而不像叶子、豌豆荚、圣诞树），靠的是下面五件事：

1. **根部露出一截裸羽柄。** 最强的辨识特征：羽片之外还有一截光杆。
   上一版羽轴整条埋在羽片里，轮廓是个封闭梭形，读起来就是叶子。
2. **羽轴是弯的。** 沿 `t^2` 微微侧弯，凸侧饱满、凹侧近尖端内收。
   对称直轴 + 两头尖 = 叶子，加多少装饰都救不回来。
3. **羽片根部是斜切的。** 左下侧根部半宽 5.0、左上侧只有 1.6，
   羽片从根部就偏在羽轴一侧，这是羽毛"掌状偏斜"的剪影特征。
4. **羽枝缝必须斜切、细口、深进。** 这是最费劲的一条：
   用"宽度包络 × 深度系数"的做法，缝只能垂直于羽轴 —— 渲染出来是一排
   等距方齿，像拉链/梳子。所以这里改成**在轮廓里显式插入折线**：
   走到缝口 `t_s`，向内、朝根部斜切到谷底，再折回缝口另一端 `t_s+TH`。
   缝口只有 0.012~0.018 宽（≈2 单位），谷底却吃掉该处 30%~50% 的宽度，
   于是每片羽枝都朝羽尖伸出、彼此被细缝分开。
5. **羽轴用负空间挖，不能叠白线。** 白压白在视觉上等于没画。
   正确做法是在同一条路径内用 `fillType=evenOdd` 挖一条细槽 ——
   注意 Android 拼写是驼峰 `evenOdd`，SVG 是小写 `evenodd`，写错了
   子路径会被填成实心，羽轴反而糊掉。缝和槽之间留 1.3 单位余量，
   缝不会把羽轴切断。
"""
import os
import sys
import math

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svg_preview import parse_path, flatten

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(HERE), "tools", "icon.xml")

# ---------------------------------------------------------------- 画布与环
CX, CY = 128.0, 120.0          # 外环圆心（略高于画布中心，视觉重心更稳）
RING_R, RING_W = 78.0, 14.0
INNER = RING_R - RING_W / 2    # 环内沿 71
CLEARANCE = 4.5                # 羽毛与环内沿之间的最小间隙
R_MAX = INNER - CLEARANCE      # 羽毛允许触达的最大半径

# ---------------------------------------------------------------- 羽毛几何
L = 134.0                      # 羽片长度（沿羽轴）
TILT = 30.0                    # 与竖直方向的夹角，尖端偏右
BEND = 15.0                    # 羽尖侧弯量：横向偏移 = BEND * t^2.6（近尖加速，成钩）
BEND_EXP = 2.6

QL = 22.0                      # 裸羽柄长度
Q_TIP, Q_BASE = 3.0, 2.4       # 羽柄末端 / 接羽片处的半宽

# 两侧包络线：
#   - 根部不是收成尖，而是"张开"到最宽处的六七成——羽片根部是**截断**的。
#     从窄根渐变收放的梭形，无论怎么加缝都还是叶子。
#   - 最宽处在中偏根（t≈0.3），向羽尖长距离平缓收窄。
W0_POS, W_POS, T_POS, E_POS = 12.0, 26.5, 0.30, 1.60   # 右下侧：鼓出
W0_NEG, W_NEG, T_NEG, E_NEG = 5.5, 18.5, 0.34, 1.45    # 左上侧：根部更斜、略平

# 羽枝缝：(缝口位置 t_s, 深度比例, 朝根部的斜切长度, 缝口宽度)
SLITS_POS = [
    (0.26, 0.30, 0.075, 0.020),
    (0.38, 0.42, 0.085, 0.019),
    (0.50, 0.52, 0.082, 0.017),
    (0.62, 0.60, 0.072, 0.016),
    (0.74, 0.66, 0.060, 0.014),
    (0.85, 0.70, 0.045, 0.012),
]
SLITS_NEG = [
    (0.32, 0.34, 0.075, 0.017),
    (0.44, 0.44, 0.078, 0.017),
    (0.56, 0.52, 0.075, 0.016),
    (0.68, 0.58, 0.065, 0.015),
    (0.79, 0.64, 0.052, 0.013),
]

# 羽轴负空间槽：(起点 t, 终点 t, 最宽处半宽, 两端收尖长度)
SHAFT = (0.04, 0.91, 2.4, 0.13)
SHAFT_MARGIN = 1.3             # 缝谷底与羽轴槽之间必须留的余量

_th = math.radians(TILT)
U = (math.sin(_th), -math.cos(_th))     # 羽轴方向：根 -> 尖
NRM = (-U[1], U[0])                     # +n = 右下侧

N_ENV = 44                              # 每侧包络线采样段数


def axis(t):
    """羽轴上的点。t<0（羽柄段）不参与弯曲，保证羽柄是直的。"""
    b = BEND * max(t, 0.0) ** BEND_EXP
    return (U[0] * L * t + NRM[0] * b, U[1] * L * t + NRM[1] * b)


def off(t, w):
    """羽轴上 t 处、法向偏移 w 的点。"""
    x, y = axis(t)
    return (x + NRM[0] * w, y + NRM[1] * w)


def envelope(t, w0, wmax, tmax, exp):
    """该侧羽片包络线的半宽（不含羽枝缝）。"""
    if t <= 0.0 or t >= 1.0:
        return 0.0
    if t < tmax:
        s = t / tmax
        return w0 + (wmax - w0) * math.sin(s * math.pi / 2) ** 0.7
    s = (t - tmax) / (1.0 - tmax)
    return wmax * (1.0 - s ** exp)


def quill_half(t):
    """羽柄半宽：t=0 处接羽片，t=-QL/L 处是羽柄末端。"""
    s = -t * L / QL                      # 0（根部）-> 1（末端）
    return Q_BASE + (Q_TIP - Q_BASE) * s


def shaft_half(t):
    """羽轴槽在 t 处的半宽（两端收成尖）。"""
    t0, t1, s0, taper = SHAFT
    if not (t0 < t < t1):
        return 0.0
    return s0 * min(1.0, (t - t0) / taper, (t1 - t) / taper)


def edge_points(sign, env_args, slits):
    """一侧的羽片边缘点列（从根部走到羽尖），羽枝缝作为折线插入。

    缝的形状：缝口在 `t_s`，轮廓先向内、朝根部斜切到谷底 `t_s - lean`，
    再折回缝口另一端的边缘点 `t_s + TH`。谷底深度以"不切穿羽轴槽"为下限。
    """
    keys = {round(i / N_ENV, 6) for i in range(N_ENV + 1)}
    for ts, _, _, th in slits:
        keys.add(round(ts, 6))
        keys.add(round(ts + th, 6))

    curves = []          # 校验用：每条缝的两面墙
    pts = []
    for t in sorted(k for k in keys if 0.0 <= k <= 1.0):
        if any(ts < t < ts + th for ts, _, _, th in slits):
            continue      # 缝口内部的普通采样点跳过
        w = envelope(t, *env_args)
        pts.append((t, sign * w))
        for ts, depth, lean, th in slits:
            if abs(ts - t) > 1e-9:
                continue
            ta = ts - lean
            wa = max(envelope(ta, *env_args) * (1.0 - depth),
                     shaft_half(ta) + SHAFT_MARGIN)
            pts.append((ta, sign * wa))
            curves.append((ts, w, ta, wa, ts + th,
                           envelope(ts + th, *env_args)))
    return pts, curves


def check_simple(curves, env_args, sign):
    """校验缝的两面墙没有捅出包络线（否则轮廓自交，evenodd 会出鬼影）。"""
    bad = 0
    for (t1, w1, ta, wa, t2, w2) in curves:
        for seg in ((t1, w1, ta, wa), (ta, wa, t2, w2)):
            for i in range(1, 24):
                u = i / 24
                t = seg[0] + (seg[2] - seg[0]) * u
                w = seg[1] + (seg[3] - seg[1]) * u
                if abs(w) > envelope(t, *env_args) + 0.02:
                    bad += 1
    return bad


# 羽片轮廓：根部 -> 左上侧 -> 羽尖 -> 右下侧 -> 根部（一个闭合折线）
neg_pts, neg_curves = edge_points(-1, (W0_NEG, W_NEG, T_NEG, E_NEG), SLITS_NEG)
pos_pts, pos_curves = edge_points(+1, (W0_POS, W_POS, T_POS, E_POS), SLITS_POS)

outline = []
outline.append(off(-QL / L, -Q_TIP))                    # 羽柄末端
for i in range(1, N_ENV + 1):                           # 羽柄左上侧
    t = (-QL / L) * (1 - i / N_ENV)
    outline.append(off(t, -quill_half(t)))
outline.append(off(0.0, -W0_NEG))                       # 台阶：羽柄 -> 羽片
outline += [off(t, w) for t, w in neg_pts]              # 左上侧：根 -> 尖
outline += [off(t, w) for t, w in reversed(pos_pts)]    # 右下侧：尖 -> 根
outline.append(off(0.0, Q_BASE))                        # 台阶：羽片 -> 羽柄
for i in range(1, N_ENV + 1):                           # 羽柄右下侧 -> 末端
    t = (-QL / L) * (i / N_ENV)
    outline.append(off(t, quill_half(t)))

# 羽轴槽：一条两端收尖的细缝，作为 evenodd 的第二个子路径挖空
M = 40
t0, t1, _, _ = SHAFT
slot = [off(t0 + (t1 - t0) * i / M, shaft_half(t0 + (t1 - t0) * i / M))
        for i in range(M + 1)]
slot += [off(t0 + (t1 - t0) * i / M, -shaft_half(t0 + (t1 - t0) * i / M))
         for i in range(M, -1, -1)]

# ---------------------------------------------------------------- 摆放与缩放
allpts = outline + slot
xs = [p[0] for p in allpts]
ys = [p[1] for p in allpts]
bcx, bcy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
far0 = max(math.hypot(x - bcx, y - bcy) for x, y in allpts)
scale = R_MAX / far0

placed = [(CX + (x - bcx) * scale, CY + (y - bcy) * scale) for x, y in allpts]
outline = placed[:len(outline)]
slot = placed[len(outline):]


def pts_to_path(pts, close=True):
    d = f"M{pts[0][0]:.1f} {pts[0][1]:.1f}"
    for x, y in pts[1:]:
        d += f" L{x:.1f} {y:.1f}"
    return d + (" Z" if close else "")


vane_d = pts_to_path(outline)
shaft_d = pts_to_path(slot)

# ---------------------------------------------------------------- 高光点
flat = [p for poly, _ in flatten(parse_path(vane_d), steps=4) for p in poly]
far = max(math.hypot(x - CX, y - CY) for x, y in flat)

best = None
for ang in range(150, 260, 3):
    for r in (48, 52, 56, 60):
        hx = CX + r * math.cos(math.radians(ang))
        hy = CY + r * math.sin(math.radians(ang))
        if math.hypot(hx - CX, hy - CY) > INNER - 12:
            continue
        dmin = min(math.hypot(x - hx, y - hy) for x, y in flat)
        if best is None or dmin > best[0]:
            best = (dmin, hx, hy)
dmin, hx, hy = best

# ---------------------------------------------------------------- 写文件
xml = f'''<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#37E0C8"/>
      <stop offset="1" stop-color="#1E88E5"/>
    </linearGradient>
  </defs>

  <!-- 圆形背景 -->
  <circle cx="128" cy="128" r="128" fill="url(#g)"/>

  <!-- 浏览器外环 -->
  <circle cx="128" cy="120" r="{RING_R:.0f}" fill="none" stroke="#ffffff" stroke-width="{RING_W:.0f}" opacity="0.95"/>

  <!--
    羽毛：裸羽柄 + 弯曲羽轴 + 朝根部斜切的细口深进羽枝缝。

    造型约束（由 tools/emit_icon_xml.py 生成并自校验）：
      - 根部露出一截裸羽柄（长 {QL/L*100:.0f}% 羽片长），这是最强的"这是羽毛"信号。
      - 羽轴按 t^{BEND_EXP:g} 侧弯 {BEND:.0f} 单位（近尖加速，成钩）：凸侧饱满、凹侧近尖内收。
        直轴的对称梭形 = 叶子，加多少装饰都救不回来。
      - 羽片根部斜切（右下 {W0_POS:.1f} vs 左上 {W0_NEG:.1f}），羽片整体偏在羽轴一侧。
      - 羽枝缝 {len(SLITS_POS)} + {len(SLITS_NEG)} 道，缝口仅 {min(th for _,_,_,th in SLITS_POS+SLITS_NEG):.3f}~{max(th for _,_,_,th in SLITS_POS+SLITS_NEG):.3f} 宽，
        却向根部斜切进 {min(d for _,d,_,_ in SLITS_POS+SLITS_NEG)*100:.0f}%~{max(d for _,d,_,_ in SLITS_POS+SLITS_NEG)*100:.0f}% 的宽度：
        细口 + 深进 + 斜切，才读成"一片片羽枝"。宽而浅的等距圆齿会读成豌豆荚，密齿会读成圣诞树。
      - 羽轴是同一路径内 evenodd 挖出的负空间窄槽（不是叠白线：白压白等于没画），
        缝谷底一律留出槽宽 + {SHAFT_MARGIN} 余量，缝不会把羽轴切断。
      - 羽片离环心最远 {far:.1f}，环内沿 {INNER:.0f}，留出 {INNER-far:.1f} 间隙，
        两个白块在低分辨率下不会粘连。
  -->
  <path fill="#ffffff" fill-rule="evenodd" opacity="0.96"
        d="{vane_d}
           {shaft_d}"/>

  <!-- 高光点：羽毛左上方的留白处 -->
  <circle cx="{hx:.0f}" cy="{hy:.0f}" r="9" fill="#E8FBFF" opacity="0.9"/>
</svg>
'''

with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
    fh.write(xml)

# ---------------------------------------------------------------- 实测数据
max_w = max(envelope(t, W0_POS, W_POS, T_POS, E_POS)
            + envelope(t, W0_NEG, W_NEG, T_NEG, E_NEG)
            for t in [i / 400 for i in range(1, 400)])
fxs = [x for x, _ in flat]
fys = [y for _, y in flat]
bad = check_simple(pos_curves, (W0_POS, W_POS, T_POS, E_POS), +1) \
    + check_simple(neg_curves, (W0_NEG, W_NEG, T_NEG, E_NEG), -1)
print(f"羽片长 {L:.0f} + 羽柄 {QL:.0f}，最宽 {max_w:.1f}"
      f"  -> 自身长宽比 {(L + QL) / max_w:.2f}")
print(f"包围盒 {max(fxs)-min(fxs):.0f} x {max(fys)-min(fys):.0f}"
      f"（长宽比 {(max(fys)-min(fys))/(max(fxs)-min(fxs)):.2f}）")
print(f"羽枝缝 {len(SLITS_POS)} + {len(SLITS_NEG)} 道"
      f"，最深 {max(d for _, d, _, _ in SLITS_POS + SLITS_NEG)*100:.0f}%"
      f"，缝口 {min(th for _, _, _, th in SLITS_POS + SLITS_NEG)*100:.1f}%~"
      f"{max(th for _, _, _, th in SLITS_POS + SLITS_NEG)*100:.1f}%")
print(f"羽轴槽半宽 {SHAFT[2]:.1f}（谷底保留 {SHAFT_MARGIN:.1f} 余量）")
print(f"轮廓自交检查：{bad} 个采样点捅出包络线"
      + ("  -> OK" if bad == 0 else "  -> 需要减小深度/斜切长度"))
print(f"离环心最远 {far:.1f}  (环内沿 {INNER:.1f}，间隙 {INNER-far:.1f})")
print(f"高光点 ({hx:.0f},{hy:.0f}) 离羽毛 {dmin:.1f}")
print(f"整体缩放 {scale:.4f}，轮廓 {len(outline)} 点 + 羽轴 {len(slot)} 点")
print(f"\n已写出 {os.path.relpath(OUT)}")
