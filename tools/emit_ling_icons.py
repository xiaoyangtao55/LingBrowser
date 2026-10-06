"""把 import_material_icons.py 的产物组装成完整的 LingIcons.kt。

生成物与手写文件分开：
  - 图标路径数据由脚本从官方 XML 生成（_generated_icons.txt）
  - 本脚本负责拼上头文件、辅助函数、以及保留原有 API 形状

为什么要生成而不是手抄 22 个图标的上千个坐标：
  手抄必然出错且无法复核。生成后可以随时重跑、逐字节比对。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
THEME = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "ling", "browser", "ui", "theme",
)
# 与 import_material_icons.py 的 GEN_FILE 保持一致（临时目录，不进源码树）
ICON_DEFS = os.path.join(
    os.environ.get("TEMP", ROOT), "ling_generated_icons.txt",
)

HEADER = '''package com.ling.browser.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 「翎」的图标集。
 *
 * 全部来自 **Material Symbols Outlined** 官方图标（用户从 fonts.google.com
 * 导出的 Android VectorDrawable），由 `tools/import_material_icons.py`
 * 自动转换成 Compose 的 ImageVector 代码。
 *
 * ## 为什么不用字体，也不用 material-icons-extended
 *
 * | 方案 | 代价 |
 * |---|---|
 * | `material-icons-extended` | 上万个图标全部编进 dex，debug 包 18 MB |
 * | Material Symbols TTF | 单文件 948 KB，接近当前 APK 的 1.4 MB |
 * | **本方案（矢量代码）** | 26 个图标合计约 40 KB 源码 |
 *
 * 「翎」对标 Via 的极简定位（整个安装包几百 KB），为 20 多个图标背
 * 1 MB 的字体不划算。而且字体图标无法参与 Compose 的 tint 与
 * 交互动画，转成 ImageVector 后这些都能直接用，还能被单元测试检查。
 *
 * ## 坐标系
 *
 * 官方导出的是 **960x960** 视口。这里**不缩放到 24**：
 * 960 是 Google 的原始设计网格，保留它意味着坐标能与官方文件逐位对照，
 * 出了问题可以直接 diff。缩放反而引入浮点误差。
 * 渲染尺寸由 `defaultWidth/Height = 24.dp` 控制，与外层视口无关。
 *
 * ## 图形约定
 *
 * 全部为**实心填充**（`fill = SolidColor(Color.Black)`），
 * 颜色由 Compose 侧通过 `Icon(tint = ...)` 覆盖 —— 这是 Material 图标
 * 的标准用法。早期版本用描边（stroke）自绘，笔画粗细在小尺寸下不匀，
 * 且造型与官方有出入，已全部替换。
 *
 * 路径命令只用到 moveTo / lineTo / quadraticBezierTo / close：
 * 官方源文件里只有 M/L/Q/Z 四种命令，转换器遇到其它命令会直接报错，
 * 不会静默丢数据。特别注意**没有 arcTo** —— 那个指令的 largeArc 标志
 * 在「起点与终点几乎相对」时行为反直觉，曾把刷新图标画成一个扇形。
 */
object LingIcons {

    /** 所有图标的统一构建入口。 */
    private fun materialIcon(
        name: String,
        build: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        // 官方原始网格，不做缩放
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathBuilder = build,
        )
    }.build()

    // ------------------------------------------------------------ 图标

'''

FOOTER = "}\n"


def main():
    if not os.path.exists(ICON_DEFS):
        sys.exit(f"缺少 {ICON_DEFS}，请先运行 tools/import_material_icons.py")

    body = open(ICON_DEFS, encoding="utf-8").read().rstrip() + "\n"
    out = HEADER + body + FOOTER
    target = os.path.join(THEME, "LingIcons.kt")

    old = open(target, encoding="utf-8").read() if os.path.exists(target) else ""
    # 统计图标数，便于对账
    n_new = len(re.findall(r"^\s*val \w+: ImageVector", body, re.M))
    n_old = len(re.findall(r"^\s*val \w+: ImageVector", old, re.M))

    open(target, "w", encoding="utf-8").write(out)
    print(f"LingIcons.kt: {n_old} -> {n_new} 个图标")
    print(f"写出 {len(out)} 字节")


if __name__ == "__main__":
    main()
