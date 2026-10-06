package com.ling.browser.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 「翎」自带的图标集。
 *
 * 为什么不直接用 material-icons-extended：那个库会把**上万个**图标类
 * 全部编进 dex，实测单个 classes.dex 就有 40+ MB，debug 包体积 18 MB。
 * Via 这类极简浏览器整个安装包才几百 KB —— 为了 20 个图标背这么大的包袱
 * 完全违背「翎」的定位。
 *
 * 实现约定（很重要，踩过坑）：
 *  1. **只用 moveTo / lineTo / close**，不用 arcTo。
 *     arcTo 的大弧标志（largeArc）在「起点与终点几乎相对」时行为反直觉，
 *     曾把刷新图标画成了一个闭合的扇形（视觉上像水桶）。圆弧一律用
 *     多段短直线近似，几何完全可预测、可验证。
 *  2. 每个图标都是 24x24 视口、单一描边路径，线宽 1.8。
 *  3. 坐标都取整或半整数，方便对照渲染结果逐点核对。
 */
object LingIcons {

    // ---------------------------------------------------------------- 基础几何

    /** 描边图标。 */
    private fun icon(
        name: String,
        build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = build,
        )
    }.build()

    /** 实心图标。 */
    private fun solidIcon(
        name: String,
        build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathBuilder = build,
        )
    }.build()

    /**
     * 用折线近似一段圆弧（角度制，0° 指向 +X，顺时针为正）。
     * 返回的点序列可直接 lineTo。
     */
    private fun androidx.compose.ui.graphics.vector.PathBuilder.arcPolyline(
        cx: Float,
        cy: Float,
        r: Float,
        startDeg: Float,
        endDeg: Float,
        steps: Int = 12,
    ) {
        for (i in 0..steps) {
            val t = startDeg + (endDeg - startDeg) * i / steps
            val rad = Math.toRadians(t.toDouble())
            val x = cx + r * kotlin.math.cos(rad).toFloat()
            val y = cy + r * kotlin.math.sin(rad).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }

    // ---------------------------------------------------------------- 导航

    /** 后退箭头（圆形底 + 左箭头）。 */
    val ArrowBack: ImageVector = icon("ArrowBack") {
        moveTo(19f, 12f)
        horizontalLineTo(5.5f)
        moveTo(11f, 5.5f)
        lineTo(4.5f, 12f)
        lineTo(11f, 18.5f)
    }

    /** 前进箭头。 */
    val ArrowForward: ImageVector = icon("ArrowForward") {
        moveTo(5f, 12f)
        horizontalLineTo(18.5f)
        moveTo(13f, 5.5f)
        lineTo(19.5f, 12f)
        lineTo(13f, 18.5f)
    }

    /** 关闭 / 清除。 */
    val Close: ImageVector = icon("Close") {
        moveTo(6f, 6f)
        lineTo(18f, 18f)
        moveTo(18f, 6f)
        lineTo(6f, 18f)
    }

    /**
     * 刷新：一个带箭头的圆环（顺时针缺口 + 箭头）。
     *
     * 用 arcPolyline 画 ~300° 的圆环，缺口留在右上；
     * 再用两条短线拼出箭头指向缺口处 —— 完全避开 arcTo。
     */
    val Refresh: ImageVector = icon("Refresh") {
        // 圆环：从 -60° 顺时针绕到 -120°（即留出右上 60° 的缺口）
        arcPolyline(cx = 12f, cy = 12f, r = 7f, startDeg = -60f, endDeg = 240f, steps = 24)
        // 箭头（位于缺口的上端，指向右下方）
        moveTo(14.2f, 3.2f)
        lineTo(19.0f, 5.6f)
        lineTo(16.6f, 10.4f)
    }

    /** 主页：屋顶 + 墙体 + 门。 */
    val Home: ImageVector = icon("Home") {
        // 屋顶
        moveTo(3.5f, 10.8f)
        lineTo(12f, 3.8f)
        lineTo(20.5f, 10.8f)
        // 两侧墙体 + 底部
        moveTo(5.8f, 9.2f)
        verticalLineTo(19.5f)
        lineTo(18.2f, 19.5f)
        verticalLineTo(9.2f)
        // 门
        moveTo(9.8f, 19.5f)
        verticalLineTo(14.2f)
        lineTo(14.2f, 14.2f)
        verticalLineTo(19.5f)
    }

    /** 标签页（多层堆叠）。 */
    val Layers: ImageVector = icon("Layers") {
        moveTo(12f, 3.5f)
        lineTo(21f, 8f)
        lineTo(12f, 12.5f)
        lineTo(3f, 8f)
        close()
        moveTo(3f, 12.5f)
        lineTo(12f, 17f)
        lineTo(21f, 12.5f)
        moveTo(3f, 16.5f)
        lineTo(12f, 21f)
        lineTo(21f, 16.5f)
    }

    /** 更多（竖排三点）。 */
    val MoreVert: ImageVector = solidIcon("MoreVert") {
        // 三个实心圆点（直接写三份，避免 repeat 作用域变量带来的歧义）
        arcPolyline(cx = 12f, cy = 5f, r = 1.7f, startDeg = 0f, endDeg = 360f, steps = 14)
        close()
        arcPolyline(cx = 12f, cy = 12f, r = 1.7f, startDeg = 0f, endDeg = 360f, steps = 14)
        close()
        arcPolyline(cx = 12f, cy = 19f, r = 1.7f, startDeg = 0f, endDeg = 360f, steps = 14)
        close()
    }

    /** 新建（加号）。 */
    val Add: ImageVector = icon("Add") {
        moveTo(12f, 5f)
        verticalLineTo(19f)
        moveTo(5f, 12f)
        horizontalLineTo(19f)
    }

    // ---------------------------------------------------------------- 内容

    /** 书签（描边）。 */
    val BookmarkBorder: ImageVector = icon("BookmarkBorder") {
        moveTo(7.5f, 4f)
        horizontalLineTo(16.5f)
        lineTo(17.5f, 5f)
        verticalLineTo(20f)
        lineTo(12f, 15.8f)
        lineTo(6.5f, 20f)
        verticalLineTo(5f)
        close()
    }

    /** 历史（时钟 + 回退指针）。 */
    val History: ImageVector = icon("History") {
        // 完整的圆环（不做缺口，避免与箭头互相挤压）
        arcPolyline(cx = 12f, cy = 12.5f, r = 8f, startDeg = 0f, endDeg = 360f, steps = 30)
        // 回退箭头（左上角，指向圆内）
        moveTo(3.2f, 4.5f)
        verticalLineTo(10f)
        horizontalLineTo(8.7f)
        // 时针 + 分针
        moveTo(12f, 8.2f)
        verticalLineTo(12.9f)
        lineTo(15.4f, 15.1f)
    }

    /** 删除（垃圾桶）。 */
    val DeleteOutline: ImageVector = icon("DeleteOutline") {
        moveTo(4.5f, 7f)
        horizontalLineTo(19.5f)
        // 桶盖提手
        moveTo(9.5f, 7f)
        verticalLineTo(4.8f)
        lineTo(10.3f, 4f)
        horizontalLineTo(13.7f)
        lineTo(14.5f, 4.8f)
        verticalLineTo(7f)
        // 桶身
        moveTo(6.5f, 7f)
        verticalLineTo(19.2f)
        lineTo(7.3f, 20f)
        horizontalLineTo(16.7f)
        lineTo(17.5f, 19.2f)
        verticalLineTo(7f)
        // 两道竖纹
        moveTo(10.2f, 10.8f)
        verticalLineTo(16.2f)
        moveTo(13.8f, 10.8f)
        verticalLineTo(16.2f)
    }

    /** 搜索（放大镜）。 */
    val Search: ImageVector = icon("Search") {
        arcPolyline(cx = 10.8f, cy = 10.8f, r = 6.8f, startDeg = 0f, endDeg = 360f, steps = 26)
        moveTo(15.7f, 15.7f)
        lineTo(20f, 20f)
    }

    /** 下载（向下的箭头 + 托盘）。 */
    val Download: ImageVector = icon("Download") {
        // 箭头竖杆
        moveTo(12f, 3.5f)
        verticalLineTo(14f)
        // 箭头两翼
        moveTo(7.6f, 9.8f)
        lineTo(12f, 14.2f)
        lineTo(16.4f, 9.8f)
        // 托盘：底部一条横线
        moveTo(4.5f, 19f)
        horizontalLineTo(19.5f)
    }

    /** 文件夹。 */
    val Folder: ImageVector = icon("Folder") {
        // 左上角的标签页（folder tab）
        moveTo(3.2f, 6.6f)
        lineTo(9.4f, 6.6f)
        lineTo(11f, 8.6f)
        horizontalLineTo(20.8f)
        // 主体右侧下行
        verticalLineTo(18.2f)
        lineTo(19.6f, 19.4f)
        horizontalLineTo(4.4f)
        lineTo(3.2f, 18.2f)
        close()
    }

    /** 文件夹（打开态），用于「移入文件夹」。 */
    val FolderOpen: ImageVector = icon("FolderOpen") {
        moveTo(3.2f, 6.6f)
        lineTo(9.4f, 6.6f)
        lineTo(11f, 8.6f)
        horizontalLineTo(19.4f)
        verticalLineTo(11.4f)
        // 前倾的开口
        moveTo(3.2f, 18.4f)
        lineTo(5.4f, 11.4f)
        horizontalLineTo(21.6f)
        lineTo(19.4f, 18.4f)
        close()
    }

    /** 锁（安全标识）。 */
    val Lock: ImageVector = icon("Lock") {
        // 锁体：圆角矩形（用直线+小折角近似圆角，避免 arcTo 退化）
        moveTo(7.5f, 10.5f)
        horizontalLineTo(16.5f)
        lineTo(17.5f, 11.5f)
        lineTo(17.5f, 19.5f)
        lineTo(16.5f, 20.5f)
        horizontalLineTo(7.5f)
        lineTo(6.5f, 19.5f)
        lineTo(6.5f, 11.5f)
        close()
        // 锁梁：半圆弧 + 两条竖边
        moveTo(9f, 10.5f)
        verticalLineTo(8f)
        arcPolyline(cx = 12f, cy = 8f, r = 3f, startDeg = 180f, endDeg = 360f, steps = 10)
        verticalLineTo(10.5f)
    }

    /** 地球（主页品牌 / 网络）。 */
    val Public: ImageVector = icon("Public") {
        // 外圈
        arcPolyline(cx = 12f, cy = 12f, r = 8.5f, startDeg = 0f, endDeg = 360f, steps = 28)
        // 赤道
        moveTo(3.5f, 12f)
        horizontalLineTo(20.5f)
        // 经线（椭圆，用较扁的两段弧近似）
        arcPolyline(cx = 12f, cy = 12f, r = 4.2f, startDeg = 0f, endDeg = 360f, steps = 24)
    }

    // ---------------------------------------------------------------- 设置

    /** 夜间模式（月亮）。 */
    val Nightlight: ImageVector = icon("Nightlight") {
        // 月牙 = 外圆减去内圆的差集。
        // 外圆 (cx12, cy12, r8.6) 与内圆 (cx17.2, cy12, r9.0) 相交于相对圆心 ±77.1° 处：
        // 先沿外圆走左侧大弧（77.1° → 282.9°，逆时针经左半圆），
        // 再沿内圆原路方向兜回起点，闭合后右侧被"咬掉"一口，即为新月。
        arcPolyline(cx = 12f, cy = 12f, r = 8.6f, startDeg = 77.1f, endDeg = 282.9f, steps = 26)
        arcPolyline(cx = 17.2f, cy = 12f, r = 9.0f, startDeg = 248.6f, endDeg = 111.4f, steps = 26)
        close()
    }

    /** 动态取色 / 魔法。 */
    val AutoAwesome: ImageVector = solidIcon("AutoAwesome") {
        // 四角星
        moveTo(12f, 2.5f)
        lineTo(13.9f, 8.6f)
        lineTo(20f, 10.5f)
        lineTo(13.9f, 12.4f)
        lineTo(12f, 18.5f)
        lineTo(10.1f, 12.4f)
        lineTo(4f, 10.5f)
        lineTo(10.1f, 8.6f)
        close()
        // 右上角小星
        moveTo(18.8f, 15.4f)
        lineTo(19.5f, 17.5f)
        lineTo(21.6f, 18.2f)
        lineTo(19.5f, 18.9f)
        lineTo(18.8f, 21f)
        lineTo(18.1f, 18.9f)
        lineTo(16f, 18.2f)
        lineTo(18.1f, 17.5f)
        close()
    }

    /** JavaScript。 */
    val Javascript: ImageVector = icon("Javascript") {
        moveTo(4f, 4f)
        horizontalLineTo(20f)
        verticalLineTo(20f)
        horizontalLineTo(4f)
        close()
        // J：竖钩
        moveTo(10f, 9.5f)
        verticalLineTo(14.5f)
        lineTo(9f, 15.5f)
        lineTo(7.2f, 14.8f)
        // S：三段折线
        moveTo(17.5f, 10.5f)
        lineTo(15.8f, 9.8f)
        lineTo(14.6f, 10.8f)
        lineTo(15.9f, 12f)
        lineTo(17.2f, 13.2f)
        lineTo(16f, 14.6f)
        lineTo(14.3f, 14.2f)
    }

    /** 图片。 */
    val Image: ImageVector = icon("Image") {
        // 圆角矩形边框
        moveTo(5.5f, 5f)
        horizontalLineTo(18.5f)
        lineTo(19.5f, 6f)
        verticalLineTo(18f)
        lineTo(18.5f, 19f)
        horizontalLineTo(5.5f)
        lineTo(4.5f, 18f)
        verticalLineTo(6f)
        close()
        // 太阳
        arcPolyline(cx = 8.8f, cy = 10.2f, r = 1.4f, startDeg = 0f, endDeg = 360f, steps = 12)
        // 山峦
        moveTo(4.6f, 17f)
        lineTo(9.5f, 12.5f)
        lineTo(13.5f, 15.8f)
        lineTo(16.5f, 13.2f)
        lineTo(19.6f, 16.2f)
    }

    /** 清除数据（扫帚 / 擦拭）。 */
    val DeleteSweep: ImageVector = icon("DeleteSweep") {
        moveTo(3.5f, 6.5f)
        horizontalLineTo(13.5f)
        moveTo(6f, 10.5f)
        horizontalLineTo(13.5f)
        moveTo(8.5f, 14.5f)
        horizontalLineTo(13.5f)
        moveTo(16.5f, 5f)
        lineTo(20.5f, 9f)
        moveTo(20.5f, 5f)
        lineTo(16.5f, 9f)
    }

    /** 隐私（盾牌 + 眼睛），用于无痕模式。 */
    val PrivacyTip: ImageVector = icon("PrivacyTip") {
        moveTo(12f, 3.2f)
        lineTo(19.5f, 6.2f)
        verticalLineTo(11.5f)
        curveTo(19.5f, 16.2f, 16.3f, 19.6f, 12f, 20.8f)
        curveTo(7.7f, 19.6f, 4.5f, 16.2f, 4.5f, 11.5f)
        verticalLineTo(6.2f)
        close()
        moveTo(12f, 10.6f)
        verticalLineTo(15.6f)
        moveTo(12f, 8f)
        verticalLineTo(8.1f)
    }

    /** 电脑模式（显示器）。 */
    val DesktopWindows: ImageVector = icon("DesktopWindows") {
        moveTo(4.5f, 5f)
        horizontalLineTo(19.5f)
        lineTo(20.5f, 6f)
        verticalLineTo(15f)
        lineTo(19.5f, 16f)
        horizontalLineTo(4.5f)
        lineTo(3.5f, 15f)
        verticalLineTo(6f)
        close()
        // 底座
        moveTo(8.5f, 20f)
        horizontalLineTo(15.5f)
        moveTo(12f, 16f)
        verticalLineTo(20f)
    }

    /** 设置（齿轮）。 */
    val Settings: ImageVector = icon("Settings") {
        // 中心圆孔
        arcPolyline(cx = 12f, cy = 12f, r = 3.2f, startDeg = 0f, endDeg = 360f, steps = 20)
        // 外圈齿形
        moveTo(12f, 3f)
        lineTo(13.2f, 5.6f)
        lineTo(16f, 5.2f)
        lineTo(16.2f, 8f)
        lineTo(18.6f, 9.4f)
        lineTo(17.2f, 12f)
        lineTo(18.6f, 14.6f)
        lineTo(16.2f, 16f)
        lineTo(16f, 18.8f)
        lineTo(13.2f, 18.4f)
        lineTo(12f, 21f)
        lineTo(10.8f, 18.4f)
        lineTo(8f, 18.8f)
        lineTo(7.8f, 16f)
        lineTo(5.4f, 14.6f)
        lineTo(6.8f, 12f)
        lineTo(5.4f, 9.4f)
        lineTo(7.8f, 8f)
        lineTo(8f, 5.2f)
        lineTo(10.8f, 5.6f)
        close()
    }
}
