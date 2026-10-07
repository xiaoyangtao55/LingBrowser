"""把主页的描边羽毛（HomePage.kt 里的内联 SVG）重排进启动图标，写出 tools/icon.xml。

主页 logo 的构成：**纯色圆底（primaryContainer）+ 一枚描边羽毛
（onPrimaryContainer 描边）**。启动图标现在完全对齐这套造型：
纯色圆底（取主页的 primaryContainer 色）+ 同一枚描边羽毛，**去掉旧版的外环**。

主页 logo 里的颜色来自 Material You 动态取色（`--pc` / `--on-pc`），
而启动图标的位图/矢量资源是静态的、无法在运行时读壁纸色。所以这里用
**浅色主题下的主页配色**作为固定值；真正的"跟随取色"由 Android 13+
的主题化图标（monochrome 单色层）承担 —— 系统按壁纸给单色层重新着色。

流程：
  - 从 HomePage.kt 读内联 SVG 的 6 条 stroke 路径（24x24 视口，stroke-width 1.6）
  - 等比缩放到 256x256，羽毛包围盒中心对齐画布中心，描边宽随缩放等比放大
  - 写成 stroke 路径 + 纯色圆底
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

# ---------------------------------------------------------------- 颜色
# 取自 HomePage.kt 的 HomeColors 默认值（浅色主题），与主页 logo 完全一致
BG_COLOR = "#A8F2CB"          # primaryContainer —— 主页圆底色
FG_COLOR = "#00210F"          # onPrimaryContainer —— 主页羽毛描边色

# ---------------------------------------------------------------- 画布
VB = 256.0                    # 视口边长
CENTER = VB / 2               # 画布中心 (128,128)
HOME_STROKE = 1.6             # 主页羽毛的 stroke-width（24 视口单位）

# 羽毛（含描边）离画布中心的目标距离。画布半径 128，自适应图标那边还会
# 再按安全区缩一次（见 gen_launcher_icon.py 的 FG），这里给的是"主稿"尺寸：
# 在 API<26 的整圆 PNG 上，羽毛约占半径的 7 成。
FEATHER_REACH = 90.0


def extract_feather_paths():
    """从 HomePage.kt 抽出主页羽毛的所有 <path d="...">。"""
    src = open(KOTLIN, encoding="utf-8").read()
    m = re.search(r"<svg\b.*?</svg>", src, re.S)
    if not m:
        raise SystemExit("HomePage.kt 里找不到 <svg>")
    return re.findall(r'<path\s+d="([^"]+)"', m.group(0))


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

    allpts = [p for d in paths for p in flatten(d)]
    xs = [p[0] for p in allpts]
    ys = [p[1] for p in allpts]
    b0x = (min(xs) + max(xs)) / 2
    b0y = (min(ys) + max(ys)) / 2
    far0 = max(math.hypot(x - b0x, y - b0y) for x, y in allpts)

    # 缩放：让「羽毛最远触达 + 半描边」正好等于 FEATHER_REACH
    scale = FEATHER_REACH / (far0 + HOME_STROKE / 2)
    stroke = HOME_STROKE * scale

    # 羽毛包围盒中心对齐画布中心（没有外环了，不必再迁就环心）
    tx = CENTER - b0x * scale
    ty = CENTER - b0y * scale

    feather = "\n\n".join(
        f'  <path fill="none" stroke="{FG_COLOR}" stroke-width="{stroke:.2f}" '
        f'stroke-linecap="round" stroke-linejoin="round"\n'
        f'        d="{transform(d, scale, tx, ty)}"/>'
        for d in paths
    )

    xml = f'''<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256">
  <!--
    与主页 logo 完全同款：纯色圆底（primaryContainer）+ 描边羽毛
    （onPrimaryContainer）。由 tools/emit_icon_xml.py 从 HomePage.kt 生成，请勿手改。
    颜色取主页浅色主题的默认配色；"跟随取色"由 Android 13+ 主题化图标承担。
  -->
  <circle cx="128" cy="128" r="128" fill="{BG_COLOR}"/>

  <!--
    羽毛：与主页 logo 同一枚描边羽毛（6 条 stroke 路径，等比放大 {scale:.4f} 倍）。
    fill=none + round cap/join，无外环、无高光点。
  -->
{feather}
</svg>
'''

    with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(xml)

    reach = far0 * scale + stroke / 2
    print(f"主页羽毛 {len(paths)} 条 stroke 路径")
    print(f"纯色圆底 {BG_COLOR}（主页 primaryContainer），无外环")
    print(f"羽毛描边色 {FG_COLOR}（主页 onPrimaryContainer）")
    print(f"缩放系数 = {scale:.4f}，stroke-width = {stroke:.2f}")
    print(f"羽毛包围盒 {max(xs)-min(xs):.1f} x {max(ys)-min(ys):.1f}（24 视口）")
    print(f"羽毛描边外沿离画布中心 {reach:.1f}  (画布半径 {CENTER:.0f}，占比 {reach/CENTER*100:.0f}%)")
    print(f"\n已写出 {os.path.relpath(OUT)}")


if __name__ == "__main__":
    main()
