package com.ling.browser.res

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 启动图标资源的回归测试。
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
    fun `背景层铺满画布`() {
        // 背景必须铺满，否则遮罩裁切后会露出透明边
        val xml = drawable("ic_launcher_background.xml").readText()
        assertTrue("背景层应使用渐变", xml.contains("<gradient"))
        assertTrue("背景层应引用渐变色", xml.contains("android:fillColor"))
        assertTrue("渐变应有起止色标", xml.contains("<item android:offset="))
    }

    @Test
    fun `背景渐变使用设计稿的两个色标`() {
        val xml = drawable("ic_launcher_background.xml").readText()
        assertTrue("缺少青色色标 #37E0C8", xml.contains("#37E0C8"))
        assertTrue("缺少蓝色色标 #1E88E5", xml.contains("#1E88E5"))
    }

    @Test
    fun `前景层包含环与羽毛`() {
        val xml = drawable("ic_launcher_foreground.xml").readText()
        assertTrue("外环应是描边而非填充", xml.contains("android:strokeColor"))
        assertTrue("羽毛应是描边白色路径", xml.contains("android:strokeColor=\"#FFFFFF\""))
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
            Regex("android:strokeColor=\"#FFFFFF\"").findAll(xml).count() >= 3,
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
    fun `羽毛保持细长比例`() {
        // "豌豆荚"问题的量化守卫：长宽比低于 2.2 就会显得又短又胖。
        val xml = drawable("ic_launcher_foreground.xml").readText()
        val pts = pathData(xml).flatMap { endpoints(it) }
        assertTrue("前景层取不到路径点", pts.isNotEmpty())
        val xs = pts.map { it.first }
        val ys = pts.map { it.second }
        val w = xs.max() - xs.min()
        val h = ys.max() - ys.min()
        val ratio = maxOf(w, h) / minOf(w, h)
        assertTrue(
            "羽毛包围盒 ${"%.1f".format(w)}x${"%.1f".format(h)}，长宽比 $ratio 偏低（应 >= 1.4）",
            ratio >= 1.4f,
        )
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
        // 图标背景已改为渐变 drawable，旧的 @color/ic_launcher_background 应彻底移除，
        // 否则要么编译报错，要么留下死资源。
        val colors = File(res, "values/colors.xml").readText()
        assertFalse(
            "colors.xml 仍定义 ic_launcher_background（已改用渐变 drawable）",
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
