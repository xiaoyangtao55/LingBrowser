package com.ling.browser.res

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 启动图标资源的回归测试。
 *
 * 图标造型现在与主页 logo 完全一致：**纯色圆底（主页 primaryContainer）
 * + 一笔描边羽毛（主页 onPrimaryContainer），没有外环、没有渐变**。
 * 主题化图标（monochrome 单色层）由 Android 13+ 按壁纸重新着色。
 *
 * 图标是"改一次就可能踩坑"的资源：自适应图标只保证中心 72/108 可见，
 * 前景一旦超出就会被 launcher 裁掉，而这个问题在预览图上不明显。
 * 这里把 `tools/check_launcher_icons.py` 的核心约束固化进单元测试。
 *
 * 注意：测试通过相对路径读取源码树中的资源文件。
 */
class LauncherIconTest {

    private val res = File("src/main/res")

    private fun drawable(name: String) = File(res, "drawable/$name")

    // 用普通字符串 + 转义引号，避免 raw string 结尾紧跟引号造成的可读性问题
    private val PATH_DATA_RE = Regex("android:pathData=\"([^\"]+)\"")

    private fun pathData(xml: String): List<String> =
        PATH_DATA_RE.findAll(xml).map { it.groupValues[1] }.toList()

    /**
     * 解析 pathData，返回所有**端点**坐标。
     *
     * 不能简单地把数字两两配对 —— 圆弧指令 `a rx,ry rot laf,sf dx,dy`
     * 里的半径和标志位不是坐标，混进来会算出离谱的包围盒。
     * 这里按指令逐个消费参数，只保留真正的端点。
     */
    private fun endpoints(d: String): List<Pair<Float, Float>> {
        val tokens = Regex("""[MmLlHhVvCcSsQqTtAaZz]|[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?""")
            .findAll(d).map { it.value }.toList()
        val out = mutableListOf<Pair<Float, Float>>()
        var i = 0
        var cmd = ' '
        var cur = 0f to 0f

        fun num() = tokens[i++].toFloat()

        while (i < tokens.size) {
            val t = tokens[i]
            if (t.length == 1 && t[0].isLetter()) {
                cmd = t[0]
                i++
                continue
            }
            when (cmd) {
                'M', 'm' -> {
                    val x = num(); val y = num()
                    cur = if (cmd == 'm') cur.first + x to cur.second + y else x to y
                    out += cur
                    cmd = if (cmd == 'M') 'L' else 'l'
                }
                'L', 'l' -> {
                    val x = num(); val y = num()
                    cur = if (cmd == 'l') cur.first + x to cur.second + y else x to y
                    out += cur
                }
                'H', 'h' -> {
                    val x = num()
                    cur = if (cmd == 'h') cur.first + x to cur.second else x to cur.second
                    out += cur
                }
                'V', 'v' -> {
                    val y = num()
                    cur = if (cmd == 'v') cur.first to cur.second + y else cur.first to y
                    out += cur
                }
                'C', 'c' -> {
                    val n = List(6) { num() }
                    cur = if (cmd == 'c') cur.first + n[4] to cur.second + n[5] else n[4] to n[5]
                    out += cur
                }
                'A', 'a' -> {
                    // rx ry rot largeArc sweep dx dy —— 只有最后两个是端点
                    num(); num(); num(); num(); num()
                    val dx = num(); val dy = num()
                    cur = if (cmd == 'a') cur.first + dx to cur.second + dy else dx to dy
                    out += cur
                }
                else -> i++
            }
        }
        return out
    }

    private fun maxRadius(paths: List<String>, center: Float = 54f): Float =
        paths.flatMap { endpoints(it) }
            .maxOfOrNull { (x, y) -> kotlin.math.hypot(x - center, y - center) }
            ?: 0f

    @Test
    fun `三层图标文件都存在`() {
        // 自适应图标需要 background + foreground；monochrome 供 Android 13+ 主题图标
        assertTrue("缺少 background 层", drawable("ic_launcher_background.xml").exists())
        assertTrue("缺少 foreground 层", drawable("ic_launcher_foreground.xml").exists())
        assertTrue("缺少 monochrome 层", drawable("ic_launcher_monochrome.xml").exists())
        assertTrue(
            "缺少自适应图标描述文件",
            File(res, "mipmap-anydpi-v26/ic_launcher.xml").exists(),
        )
    }

    @Test
    fun `自适应图标引用三层`() {
        val xml = File(res, "mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue(xml.contains("@drawable/ic_launcher_background"))
        assertTrue(xml.contains("@drawable/ic_launcher_foreground"))
        assertTrue("Android 13+ 主题图标需要 monochrome", xml.contains("monochrome"))
    }

    @Test
    fun `前景层落在 72dp 安全区内`() {
        // 自适应图标画布 108dp，只有中心 72dp 直径对所有遮罩可见。
        // 前景一旦超出，方形/圆形遮罩都会把图形裁掉。
        val xml = drawable("ic_launcher_foreground.xml").readText()
        val paths = pathData(xml)
        assertTrue("前景层没有 path", paths.isNotEmpty())

        val maxR = maxRadius(paths)
        assertTrue(
            "前景超出安全区：最远 $maxR dp > 36 dp。会被 launcher 裁切。",
            maxR <= 36.5f,
        )
    }

    @Test
    fun `monochrome 层也落在安全区内`() {
        val xml = drawable("ic_launcher_monochrome.xml").readText()
        val paths = pathData(xml)
        assertTrue("monochrome 层没有 path", paths.isNotEmpty())
        val maxR = maxRadius(paths)
        assertTrue("monochrome 超出安全区：最远 $maxR dp", maxR <= 36.5f)
    }

    @Test
    fun `背景层是纯色圆底并铺满画布`() {
        // 背景必须铺满，否则遮罩裁切后会露出透明边。
        // 已从"渐变"改为"纯色"（与主页 logo 的 primaryContainer 一致）。
        val xml = drawable("ic_launcher_background.xml").readText()
        assertFalse("背景层不应再用渐变", xml.contains("<gradient"))
        assertTrue("背景层应有纯色填充", xml.contains("android:fillColor"))
        assertTrue("纯色应铺满 108dp 画布", xml.contains("M0,0h108v108h-108z"))
    }

    @Test
    fun `背景色取自主页 logo 的 primaryContainer`() {
        // 与 HomePage.kt 的 --pc（HomeColors.primaryContainer 默认值）保持一致
        val xml = drawable("ic_launcher_background.xml").readText()
        assertTrue("圆底应为 #A8F2CB（主页 primaryContainer）", xml.contains("#A8F2CB"))
    }

    @Test
    fun `前景层只有描边羽毛且没有外环`() {
        val xml = drawable("ic_launcher_foreground.xml").readText()
        assertFalse("外环已去掉，不应再有圆弧", xml.contains("a30.19"))
        assertTrue(
            "羽毛应用主页的描边色 #00210F",
            xml.contains("android:strokeColor=\"#00210F\""),
        )
        assertTrue("羽毛 fill 应为空（描边线画）", xml.contains("android:fillColor=\"#00000000\""))
        assertTrue("羽毛用圆角端帽", xml.contains("android:strokeLineCap=\"round\""))
        assertTrue("羽毛用圆角拐角", xml.contains("android:strokeLineJoin=\"round\""))
    }

    @Test
    fun `前景层羽毛是多条描边路径`() {
        // 描边羽毛（与主页 logo 一致）由多条 stroke 路径组成：
        // 3 条羽片/羽轴曲线 + 3 条羽枝缝线段。只取一条会丢掉羽枝缝，
        // 羽毛就退化成一片叶子。
        val xml = drawable("ic_launcher_foreground.xml").readText()
        assertTrue(
            "描边路径不足 3 条，羽片/羽轴/羽枝缝丢失",
            Regex("android:strokeColor=\"#00210F\"").findAll(xml).count() >= 3,
        )
        assertTrue(
            "M 子路径不足 6 个，羽枝缝可能丢失",
            Regex("M").findAll(xml).count() >= 6,
        )
    }

    @Test
    fun `单色层也是描边羽毛`() {
        val xml = drawable("ic_launcher_monochrome.xml").readText()
        assertTrue(
            "单色层描边路径不足 3 条",
            Regex("android:strokeColor=\"#FFFFFF\"").findAll(xml).count() >= 3,
        )
        assertTrue("单色层缺羽枝缝", Regex("M").findAll(xml).count() >= 6)
    }

    @Test
    fun `羽毛比例与大小合理`() {
        // 羽毛是主页那枚描边羽毛（长宽比 1.18），这里守住两条底线：
        // 不要退化成方方正正的一坨，也不要缩成一个小点或撑爆安全区。
        val xml = drawable("ic_launcher_foreground.xml").readText()
        val pts = pathData(xml).flatMap { endpoints(it) }
        assertTrue("前景层取不到路径点", pts.isNotEmpty())
        val xs = pts.map { it.first }
        val ys = pts.map { it.second }
        val w = xs.max() - xs.min()
        val h = ys.max() - ys.min()
        val ratio = maxOf(w, h) / minOf(w, h)
        assertTrue(
            "羽毛包围盒 ${"%.1f".format(w)}x${"%.1f".format(h)}，长宽比 $ratio 偏低（应 >= 1.1）",
            ratio >= 1.1f,
        )

        val reach = maxRadius(pathData(xml))
        assertTrue("羽毛最远触达 $reach dp，偏小（应 >= 20dp）", reach >= 20f)
        assertTrue("羽毛最远触达 $reach dp，超出安全余量（应 <= 34.5dp）", reach <= 34.5f)
    }

    @Test
    fun `五档 PNG 图标齐备`() {
        // API 23~25 不支持自适应图标，必须有位图 fallback
        for (dir in listOf(
            "mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi",
            "mipmap-xxhdpi", "mipmap-xxxhdpi",
        )) {
            assertTrue("缺少 $dir/ic_launcher.png", File(res, "$dir/ic_launcher.png").exists())
        }
    }

    @Test
    fun `不再引用已删除的纯色资源`() {
        // 图标背景是纯色 drawable，旧的 @color/ic_launcher_background 应彻底移除，
        // 否则要么编译报错，要么留下死资源。
        val colors = File(res, "values/colors.xml").readText()
        assertFalse(
            "colors.xml 仍定义 ic_launcher_background（已改用 drawable）",
            colors.contains("name=\"ic_launcher_background\""),
        )
        for (f in listOf(
            "mipmap-anydpi-v26/ic_launcher.xml",
            "drawable/ic_launcher_background.xml",
            "drawable/ic_launcher_foreground.xml",
        )) {
            val text = File(res, f).readText()
            assertFalse("$f 仍引用 @color/ic_launcher_background", text.contains("@color/ic_launcher_background"))
        }
    }
}
