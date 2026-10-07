"""把主页的描边羽毛（HomePage.kt 里的内联 SVG）重排进启动图标，写出 tools/icon.xml。

为什么重写这套生成逻辑：启动图标原来用一颗**填充**羽毛（emit_icon_xml 参数化
生成的羽片 + evenodd 挖羽轴），而主页 logo 是**描边**羽毛（fill=none + 5 条
stroke 路径）。两者造型不同 —— 用户要求启动图标与主页一致，于是让主页里的
描边羽毛成为唯一事实来源：

  - 从这里读 HomePage.kt 的 <svg> 路径（24x24 视口，stroke-width 1.6）
  - 等比缩放到 256x256，居中放在外环圆心 (128,120)
  - 写成 stroke 路径（白色描边 + round cap/join，与主页一致）
  - 去掉原来的高光点（主页没有），保留渐变底 + 白色外环

关键取舍：
  - 羽毛的 stroke-width 随缩放等比放大（1.6 -> 9），保证在 256 画布上
    与主页 24 画布上的视觉粗细一致。
  - 缩放系数由"羽毛尖端 + 半描边 <= 环内沿 - 余量"解出，避免羽毛描边
    压到外环。
"""
import os
import re
import sys
import math

HERE = os.path.dirname(os.path.abspath(__file__))
KOTLIN = os.path.join(
    os.path.dirname(HERE),
    "app", "src", "main", "java", "com", "ling", "browser", "web", "HomePage.kt",
)
OUT = os.path.join(HERE, "icon.xml")

# ---------------------------------------------------------------- 画布与环
CX, CY = 128.0, 120.0          # 外环圆心（与旧版一致）
RING_R, RING_W = 78.0, 14.0
INNER = RING_R - RING_W / 2    # 环内沿 71
CLEARANCE = 5.0                # 羽毛描边外沿与环内沿之间的最小间隙
R_MAX = INNER - CLEARANCE      # 羽毛描边外沿允许触达的最大半径

HOME_STROKE = 1.6              # 主页羽毛的 stroke-width（24 视口单位）
STROKE = 9.0                   # 256 画布上的描边宽（= HOME_STROKE * 缩放）


def extract_feather_paths():
    """从 HomePage.kt 抽出主页羽毛的所有 <path d="...">。"""
    src = open(KOTLIN, encoding="utf-8").read()
    m = re.search(r"<svg\b.*?</svg>", src, re.S)
    if not m:
        raise SystemExit("HomePage.kt 里找不到 <svg>")
    svg = m.group(0)
    return re.findall(r'<path\s+d="([^"]+)"', svg)


def flatten(d, steps=48):
    """把 M/L/C 绝对路径采样成点列，用于算包围盒与最远触达。"""
    toks = re.findall(r"[MLCZ]|-?\d*\.?\d+", d)
    pts = []
    cur = None
    i = 0
    cmd = None

    def cubic(p0, c1, c2, p1):
        for k in range(1, steps + 1):
            t = k / steps
            mt = 1 - t
            pts.append((
                mt**3 * p0[0] + 3 * mt**2 * t * c1[0] + 3 * mt * t**2 * c2[0] + t**3 * p1[0],
                mt**3 * p0[1] + 3 * mt**2 * t * c1[1] + 3 * mt * t**2 * c2[1] + t**3 * p1[1],
            ))

    while i < len(toks):
        t = toks[i]
        if re.match(r"[A-Za-z]", t):
            cmd = t
            i += 1
            continue
        if cmd == "M":
            cur = (float(toks[i]), float(toks[i + 1])); i += 2
            pts.append(cur)
        elif cmd == "L":
            cur = (float(toks[i]), float(toks[i + 1])); i += 2
            pts.append(cur)
        elif cmd == "C":
            c1 = (float(toks[i]), float(toks[i + 1]))
            c2 = (float(toks[i + 2]), float(toks[i + 3]))
            p1 = (float(toks[i + 4]), float(toks[i + 5])); i += 6
            cubic(cur, c1, c2, p1)
            cur = p1
        else:
            i += 1
    return pts


def transform(d, scale, tx, ty):
    """把 24 视口的路径等比缩放 + 平移到 256 画布，输出绝对坐标 pathData。"""
    toks = re.findall(r"[MLC]|-?\d*\.?\d+", d)
    out = []
    i = 0
    cmd = None

    def tf(x, y):
        return (tx + x * scale, ty + y * scale)

    def fmt(v):
        s = f"{v:.2f}".rstrip("0").rstrip(".")
        return s if s else "0"

    while i < len(toks):
        t = toks[i]
        if re.match(r"[A-Za-z]", t):
            cmd = t
            out.append(t)
            i += 1
            continue
        if cmd in ("M", "L"):
            x, y = float(toks[i]), float(toks[i + 1]); i += 2
            nx, ny = tf(x, y)
            out.append(f"{fmt(nx)} {fmt(ny)}")
        elif cmd == "C":
            vals = [float(toks[i + k]) for k in range(6)]; i += 6
            for k in (0, 2, 4):
                nx, ny = tf(vals[k], vals[k + 1])
                out.append(f"{fmt(nx)} {fmt(ny)}")
        else:
            i += 1
    return " ".join(out)


def main():
    paths = extract_feather_paths()
    if not paths:
        raise SystemExit("主页羽毛没有 path")

    # 羽毛包围盒中心 + 最远触达（相对中心）
    allpts = [p for d in paths for p in flatten(d)]
    xs = [p[0] for p in allpts]
    ys = [p[1] for p in allpts]
    b0x = (min(xs) + max(xs)) / 2
    b0y = (min(ys) + max(ys)) / 2
    far0 = max(math.hypot(x - b0x, y - b0y) for x, y in allpts)

    # 缩放：羽毛最远触达 + 半描边 <= R_MAX
    scale = (R_MAX - STROKE / 2) / far0

    # 平移到环心
    tx = CX - b0x * scale
    ty = CY - b0y * scale

    feather = "\n\n".join(
        f'  <path fill="none" stroke="#ffffff" stroke-width="{STROKE}" '
        f'stroke-linecap="round" stroke-linejoin="round" opacity="0.96"\n'
        f'        d="{transform(d, scale, tx, ty)}"/>'
        for d in paths
    )

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
  <circle cx="{CX:.0f}" cy="{CY:.0f}" r="{RING_R:.0f}" fill="none" stroke="#ffffff" stroke-width="{RING_W:.0f}" opacity="0.95"/>

  <!--
    羽毛：与主页 logo 同一枚描边羽毛（由 HomePage.kt 的内联 SVG 等比放大）。
    fill=none + 白色描边 + round cap/join，去掉旧版的高光点与 evenodd 羽轴。
    缩放 {scale:.4f}，stroke-width {STROKE}，描边外沿离环内沿留 {CLEARANCE} 余量。
  -->
{feather}
</svg>
'''

    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(xml)

    # 实测数据
    far = far0 * scale + STROKE / 2
    print(f"主页羽毛 {len(paths)} 条 stroke 路径")
    print(f"羽毛包围盒 {max(xs)-min(xs):.1f} x {max(ys)-min(ys):.1f}（24 视口）")
    print(f"缩放系数 = {scale:.4f}，stroke-width = {STROKE}")
    print(f"羽毛描边外沿离环心最远 {far:.1f}  (环内沿 {INNER:.0f}，间隙 {INNER-far:.1f})")
    print(f"\n已写出 {os.path.relpath(OUT)}")


if __name__ == "__main__":
    main()
