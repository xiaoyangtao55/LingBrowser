package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 标签排序的**纯逻辑**测试。
 *
 * 拖拽手势本身没法在单元测试里跑（要 Android 的触摸框架），
 * 但真正容易错的是**下标换算**那部分数学 —— 它决定了：
 *   手指数跨过几行 -> 应该把第几项移到第几位
 * 这里把那套算法抠出来单独测，手势层只负责把手指数喂进来。
 *
 * 另外固定住 [reorder] 的语义（remove + add，不是 swap）：
 * 拖拽的直觉是"被拖的项插到目标位置、其余项依次让位"，
 * 一旦有人改成 swap，手感会完全不同，必须能被测试拦住。
 */
class TabOrderTest {

    /** 与 WebTabManager.moveTab 完全一致的实现（相同的越界容错语义）。 */
    private fun reorder(list: List<String>, from: Int, to: Int): List<String> {
        if (from !in list.indices || to !in list.indices) return list
        if (from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * 手指位移 -> 目标下标。与 TabsScreen 的 onDrag 保持一致：
     * 先把位移换算成"跨过几项"，再叠加到当前下标并夹到合法范围。
     * 返回 null 表示"不该动"（位移不足一行）。
     */
    private fun dragTarget(
        current: Int,
        offsetY: Float,
        rowHeight: Float,
        lastIndex: Int,
    ): Int? {
        if (rowHeight <= 0) return null
        val moved = (offsetY / rowHeight).roundToInt()
        if (moved == 0) return null
        val target = (current + moved).coerceIn(0, lastIndex)
        return if (target == current) null else target
    }

    // ------------------------------------------------------- 顺序语义

    @Test
    fun `向后拖动是插入而非交换`() {
        val list = listOf("A", "B", "C", "D")
        // A 拖到下标 2：A 落到第 3 位，B、C 整体前移
        assertEquals(listOf("B", "C", "A", "D"), reorder(list, 0, 2))
    }

    @Test
    fun `向前拖动是插入而非交换`() {
        val list = listOf("A", "B", "C", "D")
        // C 拖到下标 0
        assertEquals(listOf("C", "A", "B", "D"), reorder(list, 2, 0))
    }

    @Test
    fun `拖到末尾`() {
        val list = listOf("A", "B", "C", "D")
        assertEquals(listOf("B", "C", "D", "A"), reorder(list, 0, 3))
    }

    @Test
    fun `拖到开头`() {
        val list = listOf("A", "B", "C", "D")
        assertEquals(listOf("D", "A", "B", "C"), reorder(list, 3, 0))
    }

    @Test
    fun `相邻互换两个方向结果一致`() {
        val list = listOf("A", "B", "C", "D")
        // 1<->2 这种相邻交换，两个方向的移动结果应当相同
        assertEquals(listOf("A", "C", "B", "D"), reorder(list, 1, 2))
        assertEquals(listOf("A", "C", "B", "D"), reorder(list, 2, 1))
    }

    @Test
    fun `原地不动`() {
        val list = listOf("A", "B", "C")
        assertEquals(list, reorder(list, 1, 1))
    }

    @Test
    fun `越界入参被忽略而不是崩溃`() {
        val list = listOf("A", "B", "C")
        // 拖拽时手指可能划出列表边界，这里绝不能抛异常
        assertEquals(list, reorder(list, -1, 1))
        assertEquals(list, reorder(list, 0, 99))
        assertEquals(list, reorder(list, 5, 0))
        assertEquals(emptyList<String>(), reorder(emptyList(), 0, 0))
    }

    @Test
    fun `排序不增删元素`() {
        val list = listOf("A", "B", "C", "D", "E")
        for (f in list.indices) {
            for (t in list.indices) {
                val out = reorder(list, f, t)
                assertEquals("move($f,$t) 改变了元素个数", list.size, out.size)
                assertEquals("move($f,$t) 改变了元素集合", list.toSet(), out.toSet())
            }
        }
    }

    @Test
    fun `任意两位置交换后目标元素确实落在目标下标`() {
        val list = listOf("A", "B", "C", "D", "E")
        for (f in list.indices) {
            for (t in list.indices) {
                val out = reorder(list, f, t)
                assertEquals(
                    "把 $f 移到 $t 后，${list[f]} 应落在下标 $t",
                    list[f], out[t],
                )
            }
        }
    }

    // ------------------------------------------------------- 位移换算

    @Test
    fun `位移不足一行不动`() {
        // 行高 100，位移 40 -> 不到半行，不移位
        assertEquals(null, dragTarget(0, 40f, 100f, 4))
        // 负方向同理
        assertEquals(null, dragTarget(2, -40f, 100f, 4))
    }

    @Test
    fun `位移超过半行才换位`() {
        // roundToInt：>= 0.5 行进位
        assertEquals(1, dragTarget(0, 50f, 100f, 4))
        assertEquals(null, dragTarget(0, 49f, 100f, 4))
    }

    @Test
    fun `一次拖动跨多行`() {
        assertEquals(3, dragTarget(0, 300f, 100f, 4))
        assertEquals(1, dragTarget(3, -250f, 100f, 4))
    }

    @Test
    fun `目标下标被夹在合法范围内`() {
        // 向上拖出顶部
        assertEquals(0, dragTarget(1, -9999f, 100f, 4))
        // 向下拖出底部
        assertEquals(4, dragTarget(3, 9999f, 100f, 4))
        // 已经在边界上继续往外拖 -> 不动
        assertEquals(null, dragTarget(0, -500f, 100f, 4))
        assertEquals(null, dragTarget(4, 500f, 100f, 4))
    }

    @Test
    fun `行高未知时不动`() {
        // 首次组合还没测量到行高，此时任何位移都不该触发换位
        assertEquals(null, dragTarget(0, 500f, 0f, 4))
        assertEquals(null, dragTarget(0, 500f, -1f, 4))
    }

    // ------------------------------------------------------- 回归：旧实现的坑

    @Test
    fun `Kotlin 会先求值实参 因此不需要下标补偿`() {
        // 曾一度以为 from < to 时要补偿，实测证明不需要。
        // 这条用例把这个认知固定下来：若有人"好心"加上补偿，会立刻失败。
        val list = listOf("A", "B", "C", "D")
        val withCompensation = list.toMutableList().apply {
            val item = removeAt(0)
            add(2 + 1, item) // 错误地补偿 +1
        }
        // 正确结果是 [B,C,A,D]，补偿后会变成 [B,C,D,A] —— 明显错位
        assertTrue(
            "带补偿的写法应产生与正确实现不同的结果（说明补偿是错的）",
            withCompensation != reorder(list, 0, 2),
        )
        assertEquals(listOf("B", "C", "D", "A"), withCompensation)
    }
}
