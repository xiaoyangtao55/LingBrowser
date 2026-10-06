"""把 tools/icon.xml 转成 Android 自适应图标的两层 vector drawable。

设计要点（见 tools/icon_scale_math.py 的推导）：
  - background: 渐变圆铺满 108dp 画布，会被遮罩裁切 —— 正合预期
  - foreground: 白环 + 羽毛，整体缩到中心 72dp 安全区内，任何遮罩都不裁
  - monochrome: Android 13+ 主题图标，只保留羽毛剪影（单色）
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

# 前景缩放：让白环外沿刚好贴住安全区边界。系数在 main() 里按 icon.xml
# 里实测到的圆环参数算出来 —— 写死常数时，环一改就会静默超出安全区。
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
        # 孤立 "M" 指令（此前生成的前景层里就有两个），直接丢掉。
        if len(sp["pts"]) < 3:
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

    bg_circle = circles[0]                                     # 渐变底
    ring = next(c for c in circles if c["stroke"])             # 白环
    dot = next(c for c in circles if c["fill"] and c["fill"].startswith("#") and not c["stroke"])
    feather = paths[0]

    # 白环外沿离画布中心的最远距离，决定前景缩放系数
    ring_far = (math.hypot(ring["cx"] - CENTER, ring["cy"] - CENTER)
                + ring["r"] + ring["sw"] / 2)
    FG = SAFE_R_SVG / ring_far

    # ---------------- background ----------------
    bg_xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  「翎」启动图标 · 背景层
  渐变取自 tools/icon.xml（青 #37E0C8 -> 蓝 #1E88E5），铺满 108dp 画布。
  自适应图标的外侧会被遮罩裁切，这正是背景层期望的行为。
  由 tools/gen_launcher_icon.py 生成，请勿手改。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0" android:startY="0"
                android:endX="108" android:endY="108">
                <item android:offset="0" android:color="#37E0C8" />
                <item android:offset="1" android:color="#1E88E5" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''

    # ---------------- foreground ----------------
    # 羽毛由多条白色路径组成（羽片 + 羽轴），必须全部输出 ——
    # 只取第一条会丢掉羽轴，羽毛就退化成一片叶子。
    feather_paths = [
        (
            path_to_androiddata(p["d"], matrix=p.get("matrix"), transform="fg"),
            # SVG 的 fill-rule="evenodd" 必须转成 Android 的 fillType="evenOdd"，
            # 否则羽轴的负空间子路径会变成**实心**填充，羽轴反而糊掉。
            "evenOdd" if p.get("fill-rule") == "evenodd" else "nonZero",
        )
        for p in paths
    ]
    feather_xml = "\n\n".join(
        f'''    <path
        android:fillColor="#FFFFFF"
        android:fillAlpha="{paths[i]['opacity']}"
        android:fillType="{fill_type}"
        android:pathData="{d}" />'''
        for i, (d, fill_type) in enumerate(feather_paths)
    )
    # 单色层不带透明度：系统会重新着色，半透明反而会显得脏
    mono_feather_xml = "\n\n".join(
        f'''    <path
        android:fillColor="#FFFFFF"
        android:fillType="{fill_type}"
        android:pathData="{d}" />'''
        for d, fill_type in feather_paths
    )
    dot_cx, dot_cy, dot_r = sx(dot["cx"]), sy(dot["cy"]), sr(dot["r"])

    fg_xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  「翎」启动图标 · 前景层
  浏览器外环 + 羽毛 + 高光点。
  整体已缩放到中心 72dp 安全区内（缩放系数 {FG:.4f}），
  因此无论 launcher 用圆形、方形还是圆角遮罩都不会被裁切。
  由 tools/gen_launcher_icon.py 生成，请勿手改。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- 浏览器外环 -->
    <path
        android:pathData="M{fmt(sx(ring['cx']))},{fmt(sy(ring['cy']) - sr(ring['r']))}
a{fmt(sr(ring['r']))},{fmt(sr(ring['r']))} 0 1,0 0,{fmt(2 * sr(ring['r']))}
a{fmt(sr(ring['r']))},{fmt(sr(ring['r']))} 0 1,0 0,{fmt(-2 * sr(ring['r']))}z"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="{fmt(sr(ring['sw']))}"
        android:strokeAlpha="{ring['opacity']}"
        android:fillColor="#00000000" />

    <!-- 羽毛：羽片 + 羽轴 -->
{feather_xml}

    <!-- 高光点 -->
    <path
        android:pathData="M{fmt(dot_cx)},{fmt(dot_cy - dot_r)}
a{fmt(dot_r)},{fmt(dot_r)} 0 1,0 0,{fmt(2 * dot_r)}
a{fmt(dot_r)},{fmt(dot_r)} 0 1,0 0,{fmt(-2 * dot_r)}z"
        android:fillColor="{dot['fill']}"
        android:fillAlpha="{dot['opacity']}" />
</vector>
'''

    # ---------------- monochrome（Android 13+ 主题图标）----------------
    mono_xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  「翎」启动图标 · 单色层（Android 13+ 主题化图标）
  只用羽毛剪影：主题图标由系统重新着色，细节多了会糊成一团。
  由 tools/gen_launcher_icon.py 生成，请勿手改。
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- 外环（描边，与前景一致） -->
    <path
        android:pathData="M{fmt(sx(ring['cx']))},{fmt(sy(ring['cy']) - sr(ring['r']))}
a{fmt(sr(ring['r']))},{fmt(sr(ring['r']))} 0 1,0 0,{fmt(2 * sr(ring['r']))}
a{fmt(sr(ring['r']))},{fmt(sr(ring['r']))} 0 1,0 0,{fmt(-2 * sr(ring['r']))}z"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="{fmt(sr(ring['sw']))}"
        android:fillColor="#00000000" />

    <!-- 羽毛：羽片 + 羽轴（与前景层一致） -->
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
    print(f"白环: 圆心({fmt(sx(ring['cx']))},{fmt(sy(ring['cy']))}) r={fmt(sr(ring['r']))} sw={fmt(sr(ring['sw']))}")
    print(f"高光: 圆心({fmt(dot_cx)},{fmt(dot_cy)}) r={fmt(dot_r)}")


if __name__ == "__main__":
    main()
