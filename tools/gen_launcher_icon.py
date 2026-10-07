"""把 tools/icon.xml 转成 Android 自适应图标的三层 vector drawable。

造型与主页 logo 对齐（见 tools/emit_icon_xml.py）：
  - background: 纯色圆底（主页 primaryContainer）铺满 108dp 画布，
    会被遮罩裁切 —— 正合预期
  - foreground: 一枚描边羽毛（无外环），缩到中心 72dp 安全区内，任何遮罩都不裁
  - monochrome: Android 13+ 主题图标，只保留羽毛剪影（单色），由系统重新着色
"""
import os
import re
import sys
import math

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svg_preview import parse_svg, XML, parse_path, flatten, apply_matrix

RES = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res",
)

DP = 108.0
VB = 256.0
CENTER = VB / 2
K = DP / VB

# 前景缩放：让羽毛描边外沿落在安全区边界内并留 8% 余量。
# 系数在 main() 里按 icon.xml 里羽毛**实测的最远触达**算出来 ——
# 写死常数时，羽毛一改就会静默超出安全区。
# （旧版是按白色外环的最远触达算的；外环去掉后改按羽毛算。）
SAFE_R_SVG = (DP / 2 * (72.0 / 108.0)) / K
FG = 1.0


def sx(x):
    """原 SVG 坐标 -> 108 画布（前景层，含安全区缩放）。"""
    return CENTER * K + (x - CENTER) * FG * K


def sy(y):
    return CENTER * K + (y - CENTER) * FG * K


def sr(v):
    return v * FG * K


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s else "0"


def path_to_androiddata(d, matrix=None, transform="fg"):
    """把 SVG path 转成 Android pathData（绝对坐标）。

    先把控制点全部转成绝对坐标，再依次应用 matrix 与前景缩放，
    最后输出绝对指令 —— 避免相对指令在多层变换下算错。
    """
    subs = parse_path(d)

    def map_pt(x, y):
        if matrix:
            x, y = apply_matrix(matrix, x, y)
        if transform == "fg":
            return sx(x), sy(y)
        return x * K, y * K

    out = []
    for sp in subs:
        # parse_path 在 Z 之后会留下一个单点子路径，转出来是没有任何作用的
        # 孤立 "M" 指令，直接丢掉。注意阈值是 <2 而不是 <3：
        # 描边羽毛里有「羽枝缝」这种两点线段（M x y L x y），
        # 2 点是一条合法线段，绝不能丢 —— 丢了羽枝缝就没了。
        if len(sp["pts"]) < 2:
            continue
        chunks = []
        prev_abs = None
        for item in sp["pts"]:
            if isinstance(item, tuple) and len(item) == 7 and item[0] == "C":
                _, x1, y1, x2, y2, x3, y3 = item
                p1 = map_pt(x1, y1)
                p2 = map_pt(x2, y2)
                p3 = map_pt(x3, y3)
                chunks.append(
                    f"C{fmt(p1[0])},{fmt(p1[1])} "
                    f"{fmt(p2[0])},{fmt(p2[1])} "
                    f"{fmt(p3[0])},{fmt(p3[1])}"
                )
                prev_abs = (x3, y3)
            else:
                x, y = item
                px, py = map_pt(x, y)
                if prev_abs is None:
                    chunks.append(f"M{fmt(px)},{fmt(py)}")
                else:
                    chunks.append(f"L{fmt(px)},{fmt(py)}")
                prev_abs = (x, y)
        if sp["closed"]:
            chunks.append("Z")
        out.append(" ".join(chunks))
    return " ".join(out)


def main():
    global FG

    view, grad, items = parse_svg(XML)

    circles = [i for i in items if i["type"] == "circle"]
    paths = [i for i in items if i["type"] == "path"]

    feather = paths                                            # 描边羽毛（全部 stroke 路径）

    # 纯色圆底（主页 primaryContainer）：取 icon.xml 里那个无描边的圆的填充色。
    # 旧版是渐变底 + 白色外环；现在与主页 logo 对齐 —— 纯色圆底 + 描边羽毛。
    bg_color = next(c["fill"] for c in circles if c["fill"] and not c["stroke"])
    fg_stroke = feather[0]["stroke"].upper()

    # 前景缩放系数：让「羽毛描边外沿」落在 72dp 安全区内并留 8% 余量。
    # 外环已去掉，没有 ring_far 可依；改按羽毛自身实测的最远触达来算。
    fg_pts = []
    for p in feather:
        for sub, _ in flatten(parse_path(p["d"]), steps=48, matrix=p.get("matrix")):
            fg_pts.extend(sub)
    feather_sw = max(p["sw"] for p in feather)
    fg_far = (max(math.hypot(x - CENTER, y - CENTER) for x, y in fg_pts)
              + feather_sw / 2)
    FG = SAFE_R_SVG * 0.92 / fg_far

    # ---------------- background ----------------
    bg_xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  「翎」启动图标 · 背景层
  纯色圆底（{bg_color}，取自主页 logo 的 primaryContainer），铺满 108dp 画布。
  自适应图标的外侧会被遮罩裁切，这正是背景层期望的行为 ——
  「铺满 + 遮罩裁切」在各遮罩形状下都呈现为纯色圆/方/圆角底。
  由 tools/gen_launcher_icon.py 生成，请勿手改。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path
        android:pathData="M0,0h108v108h-108z"
        android:fillColor="{bg_color}" />
</vector>
'''

    # ---------------- foreground ----------------
    # 只有描边羽毛（与主页 logo 一致）：fill=none + 主页取来的描边色
    # + round cap/join。必须逐条输出 —— 只取第一条会丢掉羽轴和羽枝缝。
    feather_d = [
        path_to_androiddata(p["d"], matrix=p.get("matrix"), transform="fg")
        for p in feather
    ]
    feather_sw_dp = sr(feather_sw)

    def feather_block(ds, color):
        return "\n\n".join(
            f'''    <path
        android:pathData="{d}"
        android:strokeColor="{color}"
        android:strokeWidth="{fmt(feather_sw_dp)}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="#00000000" />'''
            for d in ds
        )

    # 前景层用主页的羽毛描边色；单色层一律白色（系统会按壁纸重新着色）
    feather_xml = feather_block(feather_d, fg_stroke)
    mono_feather_xml = feather_block(feather_d, "#FFFFFF")

    fg_xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  「翎」启动图标 · 前景层
  只有一枚描边羽毛（与主页 logo 同款），**无外环**。
  整体已缩放到中心 72dp 安全区内（缩放系数 {FG:.4f}，留 8% 余量），
  因此无论 launcher 用圆形、方形还是圆角遮罩都不会被裁切。
  由 tools/gen_launcher_icon.py 生成，请勿手改。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- 羽毛：描边路径（羽片 + 羽轴 + 羽枝缝） -->
{feather_xml}
</vector>
'''

    # ---------------- monochrome（Android 13+ 主题图标）----------------
    mono_xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  「翎」启动图标 · 单色层（Android 13+ 主题化图标）
  只用羽毛剪影：系统按壁纸重新着色 —— 这是启动图标"跟随取色"的唯一路径
  （位图/矢量资源无法在运行时读到壁纸色）。
  由 tools/gen_launcher_icon.py 生成，请勿手改。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- 羽毛：描边路径（与前景层一致，颜色由系统决定） -->
{mono_feather_xml}
</vector>
'''

    files = {
        os.path.join(RES, "drawable", "ic_launcher_background.xml"): bg_xml,
        os.path.join(RES, "drawable", "ic_launcher_foreground.xml"): fg_xml,
        os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"): mono_xml,
    }
    for path, content in files.items():
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(content)
        print(f"  wrote {os.path.relpath(path, os.path.dirname(RES))}  ({len(content)} B)")

    print()
    print(f"前景缩放系数 = {FG:.4f}")
    print(f"背景: 纯色 {bg_color}（无外环、无渐变）")
    print(f"羽毛: {len(feather)} 条描边路径，stroke-width={fmt(feather_sw_dp)}dp，色 {fg_stroke}")


if __name__ == "__main__":
    main()
