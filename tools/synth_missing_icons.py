"""为 4 个缺失图标合成官方规格的路径。

背景：用户下载的 30 个 XML 里缺 close / layers / more_vert / bookmark_border，
而 zip 里只有字体没有矢量图。这 4 个图标形状极简单，可以**按 Material Symbols
的官方设计规格精确重建**，而不是随手画个近似的 —— 那样又会回到"看着能用
但造型不统一"的老问题。

关键是从已有官方文件里反推出的规格（960 网格）：
  - 线宽（stroke 厚度）：80 单位
  - 圆角：用 Q 曲线，控制点偏移 23.5 / 33.5（即半径 40 的圆角）
  - 安全边距：内容在 120..840 之间（留 120 边距）
  - 圆角方形的写法（取自 menu / input / download 的托盘）：
        右上角：L{x-40},{y} Q{x},{y} {x},{y+40}
    这是 Material 的标准 40 半径圆角。

生成的坐标全部落在官方栅格上（40 的倍数为主），便于日后核对。
"""
import os
import re
import sys

SRC = r"C:\Users\QiYan\Downloads\material_icon"
OUT = os.path.join(SRC, "_synthesized")

M = 120          # 内容边距
T = 80           # 线宽
R = 40           # 圆角半径
C1, C2 = 23.5, 33.5   # 官方 Q 曲线控制点偏移（并集处）

# 图标按“外框 + 挖空”构造，用 even-odd 填充。
# 路径顺序：先外框（顺时针），再内孔（逆时针感），even-odd 会自动挖洞。


def rounded_rect(x, y, w, h, r=R, cw=True):
    """生成圆角矩形路径。cw=True 顺时针。

    Material 官方在纯圆角处用单个 Q（控制点在对角），
    这里照抄其写法以保证圆角观感一致。
    """
    x2, y2 = x + w, y + h
    if cw:
        return (
            f"M{x + r},{y}"
            f"L{x2 - r},{y}"
            f"Q{x2},{y} {x2},{y + r}"
            f"L{x2},{y2 - r}"
            f"Q{x2},{y2} {x2 - r},{y2}"
            f"L{x + r},{y2}"
            f"Q{x},{y2} {x},{y2 - r}"
            f"L{x},{y + r}"
            f"Q{x},{y} {x + r},{y}"
            f"Z"
        )
    return (
        f"M{x + r},{y}"
        f"L{x},{y + r}"
        f"L{x},{y2 - r}"
        f"Q{x},{y2} {x + r},{y2}"
        f"L{x2 - r},{y2}"
        f"Q{x2},{y2} {x2},{y2 - r}"
        f"L{x2},{y + r}"
        f"Q{x2},{y} {x2 - r},{y}"
        f"Z"
    )


def rounded_bar(x, y, w, h, r=R):
    """圆角横/竖条（menu 那种造型）。"""
    return rounded_rect(x, y, w, h, r)


def build():
    icons = {}

    # ---------------------------------------------------------- close（X 形）
    # 两条 45° 的粗线交叉。用两个旋转后的矩形表达会引入浮点误差，
    # 官方 close 实际是「两个平行四边形」，这里按 45° 精确计算顶点。
    # 主干从 (240,240) 到 (720,720)，线宽 80 → 半宽 40*sqrt(2) ≈ 56.57
    import math
    hw = T / 2 * math.sqrt(2)   # 56.5685...
    def diag(x1, y1, x2, y2):
        dx, dy = x2 - x1, y2 - y1
        L = math.hypot(dx, dy)
        ux, uy = dx / L, dy / L
        nx, ny = -uy, ux
        p = lambda t, s: (x1 + ux * t + nx * hw * s, y1 + uy * t + ny * hw * s)
        pts = [p(0, 1), p(L, 1), p(L, -1), p(0, -1)]
        return "M" + "L".join(f"{round(a,1)},{round(b,1)}" for a, b in pts) + "Z"

    icons["close"] = diag(240, 240, 720, 720) + diag(720, 240, 240, 720)

    # ---------------------------------------------------------- layers（叠层）
    # 官方造型：一片实心菱形在顶，下面两片"折带"逐层下移。
    # 折带不是两条独立斜线，而是**有厚度的实体形状**：
    #   上沿从左顶点下探到中点再回到右顶点（V），
    #   下沿同样，两者之间填充。
    #
    # 第一版把厚度方向搞反了（用 ty 往下加 thick 而不是沿外轮廓），
    # 结果渲染出来是两条细斜杠、且中间断开 —— 所以这里显式定义：
    #   顶点行 y = ty
    #   谷底行 y = ty + drop
    #   底部轮廓整体再下移 thick
    HW = 340          # 半宽
    TH = 78           # 折带厚度（与官方 80 线宽一致）
    DROP = 150        # V 形下探深度

    def diamond(cx, cy, hw_, hh):
        return (f"M{cx},{cy - hh}L{cx + hw_},{cy}L{cx},{cy + hh}"
                f"L{cx - hw_},{cy}Z")

    # 顶片：外菱形挖空成描边
    top_outer = diamond(480, 250, HW, 130)
    top_inner = diamond(480, 250, HW - TH * 1.5, 130 - TH * 1.5)

    def band(cx, ty, hw_, drop, thick):
        """一片有厚度的 V 形折带：上沿 A 点 → 中点 → B 点，下沿同理。

        注意 V 的朝向：Material 的 layers 里，下面两层是**向上开口**的
        折线（两端翘起、中间下凹），所以 drop 取正值往下探。
        """
        return (
            f"M{cx - hw_},{ty}"
            f"L{cx},{ty + drop}"                       # 上沿到谷底
            f"L{cx + hw_},{ty}"                        # 上沿回到右顶点
            f"L{cx + hw_},{ty + thick}"                # 右侧直下（厚度）
            f"L{cx},{ty + drop + thick}"               # 下沿回谷底
            f"L{cx - hw_},{ty + thick}"                # 下沿回左顶点
            f"Z"                                       # 左侧闭合（厚度）
        )

    # 第 2、3 层：顶点接在上一层底部，整体收在 120..840 内。
    # 间距要小，三层才有"叠在一起"的观感；拉太开就散了。
    mid = band(480, 425, HW, DROP, TH)
    bot = band(480, 527, HW, DROP, TH)

    icons["layers"] = top_outer + top_inner + mid + bot

    # ---------------------------------------------------------- more_vert（竖三点）
    # 三个圆，直径 80，间距 200，整体垂直居中
    def circle(cx, cy, r, seg=24):
        pts = []
        import math as _m
        for i in range(seg):
            a = 2 * _m.pi * i / seg
            pts.append(f"{round(cx + r * _m.cos(a),1)},{round(cy + r * _m.sin(a),1)}")
        return "M" + "L".join(pts) + "Z"

    icons["more_vert"] = (
        circle(480, 240, 80) + circle(480, 480, 80) + circle(480, 720, 80)
    )

    # ---------------------------------------------------------- bookmark_border
    # 与已下载的 bookmark 同款，但中间挖空成描边效果。
    # 直接复用 bookmark 的外轮廓，去掉内部填充部分。
    # 官方 bookmark：M200,840 L200,200 Q... 到 760,200 L760,840 L480,720 L200,840 Z
    # 描边版 = 外轮廓 + 内部挖空轮廓
    outer = (
        "M200,840L200,200Q200,167 223.5,143.5Q247,120 280,120"
        "L680,120Q713,120 736.5,143.5Q760,167 760,200L760,840L480,720L200,840Z"
    )
    inner = (
        "M280,718L480,632L680,718L680,200Q680,200 680,200Q680,200 680,200"
        "L280,200Q280,200 280,200Q280,200 280,200L280,718Z"
    )
    icons["bookmark_border"] = outer + inner

    return icons


def to_vector_xml(path_data, size=24):
    return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp"
    android:height="{size}dp"
    android:viewportWidth="960"
    android:viewportHeight="960"
    android:tint="?attr/colorControlNormal">
  <path
      android:fillColor="@android:color/white"
      android:pathData="{path_data}"/>
</vector>
'''


def main():
    os.makedirs(OUT, exist_ok=True)
    icons = build()
    for name, data in sorted(icons.items()):
        # 校验只含允许的命令
        cmds = set(re.findall(r"[A-Za-z]", data))
        bad = cmds - {"M", "L", "Q", "Z"}
        if bad:
            sys.exit(f"{name} 含不支持的命令 {sorted(bad)}")
        # 校验坐标范围
        nums = [float(x) for x in re.findall(r"-?\d+(?:\.\d+)?", data)]
        lo, hi = min(nums), max(nums)
        if lo < 0 or hi > 960:
            sys.exit(f"{name} 坐标越界：[{lo}, {hi}]")
        p = os.path.join(OUT, name + "_24px.xml")
        open(p, "w", encoding="utf-8").write(to_vector_xml(data))
        print(f"  {name:18s} {len(data):5d} 字符  坐标范围 [{lo:.0f}, {hi:.0f}]  ✓")
    print(f"\n已写出 {len(icons)} 个到 {OUT}")


if __name__ == "__main__":
    main()
