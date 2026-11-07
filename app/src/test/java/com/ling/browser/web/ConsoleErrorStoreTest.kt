package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控制台错误的留存量与分桶规则。
 *
 * 从 WebTabManager 抽出后，这些规则第一次有了独立的测试入口 ——
 * 以前它们夹在 WebView 创建流程里，只能靠真机看「页面错误」面板。
 */
class ConsoleErrorStoreTest {

    @Test
    fun `每条标签只保留最近 30 条`() {
        val store = ConsoleErrorStore()
        repeat(40) { i -> store.record("t1", "err$i", "a.js", i) }
        val list = store.errors.value
        assertEquals(30, list.size)
        assertEquals("err10", list.first().message)
        assertEquals("err39", list.last().message)
    }

    @Test
    fun `连续重复的同消息同行号不刷屏`() {
        val store = ConsoleErrorStore()
        store.record("t1", "boom", "a.js", 7)
        store.record("t1", "boom", "a.js", 7)
        store.record("t1", "boom", "a.js", 8)
        assertEquals(2, store.errors.value.size)
    }

    @Test
    fun `不同标签互不影响`() {
        val store = ConsoleErrorStore()
        store.record("t1", "a", "x.js", 1)
        store.record("t2", "b", "y.js", 2)
        assertEquals(2, store.errors.value.size)

        store.clear("t1")
        val remain = store.errors.value
        assertEquals(1, remain.size)
        assertEquals("t2", remain.first().tabId)
    }

    @Test
    fun `空消息被忽略`() {
        val store = ConsoleErrorStore()
        store.record("t1", "   ", "a.js", 1)
        assertTrue(store.errors.value.isEmpty())
    }

    @Test
    fun `超长消息截断到 500 字符`() {
        val store = ConsoleErrorStore()
        store.record("t1", "x".repeat(900), "a.js", 1)
        assertEquals(500, store.errors.value.single().message.length)
    }

    @Test
    fun `clearAll 清空所有标签`() {
        val store = ConsoleErrorStore()
        store.record("t1", "a", "x.js", 1)
        store.record("t2", "b", "y.js", 2)
        store.clearAll()
        assertTrue(store.errors.value.isEmpty())
    }
}
