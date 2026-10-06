package com.ling.browser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图标抓取的 URL 处理测试。
 *
 * 只测**纯函数**部分（`originOf` 与 `resolve`）。真正的网络请求不在
 * 单测里跑 —— 那会让测试依赖外网、变得又慢又不稳定，而且失败原因
 * 分不清是代码错还是网络抖。
 *
 * 但这两个函数恰恰是最容易出错的地方：拼错一个斜杠就会去请求
 * 一个 404，表现为"图标老是出不来"，很难查。
 */
class FaviconFetcherTest {

    // ------------------------------------------------------- originOf

    @Test
    fun `取出 scheme 与 host`() {
        assertEquals("https://example.com", FaviconFetcher.originOf("https://example.com/a/b?c=1"))
    }

    @Test
    fun `保留非默认端口`() {
        // 丢掉端口会连到一个完全不同的服务上
        assertEquals("http://example.com:8080", FaviconFetcher.originOf("http://example.com:8080/x"))
    }

    @Test
    fun `默认端口不显式写出`() {
        assertEquals("https://example.com", FaviconFetcher.originOf("https://example.com:443/"))
        assertEquals("http://example.com", FaviconFetcher.originOf("http://example.com:80/"))
    }

    @Test
    fun `丢弃路径与查询串`() {
        val origin = FaviconFetcher.originOf("https://news.example.com/2024/01/01/post.html?utm=x#top")
        assertEquals("https://news.example.com", origin)
    }

    @Test
    fun `非 http 协议返回 null`() {
        // 这些地址去请求 /favicon.ico 毫无意义，必须提前挡掉
        assertNull(FaviconFetcher.originOf("about:blank"))
        assertNull(FaviconFetcher.originOf("ling://home"))
        assertNull(FaviconFetcher.originOf("file:///sdcard/a.html"))
        assertNull(FaviconFetcher.originOf("data:text/html,<b>x</b>"))
        assertNull(FaviconFetcher.originOf("javascript:void(0)"))
    }

    @Test
    fun `畸形地址返回 null 而不是抛异常`() {
        assertNull(FaviconFetcher.originOf(""))
        assertNull(FaviconFetcher.originOf("不是网址"))
        assertNull(FaviconFetcher.originOf("http://"))
    }

    // ------------------------------------------------------- resolve

    @Test
    fun `绝对地址原样返回`() {
        assertEquals(
            "https://cdn.example.com/i.png",
            FaviconFetcher.resolve("https://example.com", "https://cdn.example.com/i.png"),
        )
    }

    @Test
    fun `协议相对地址补上 https`() {
        // <link href="//cdn.example.com/i.png"> 是很常见的写法
        assertEquals(
            "https://cdn.example.com/i.png",
            FaviconFetcher.resolve("https://example.com", "//cdn.example.com/i.png"),
        )
    }

    @Test
    fun `根相对地址拼到 host 后面`() {
        assertEquals(
            "https://example.com/static/icon.png",
            FaviconFetcher.resolve("https://example.com", "/static/icon.png"),
        )
    }

    @Test
    fun `文档相对地址补一个斜杠`() {
        // 少这个斜杠会变成 https://example.comstatic/... —— 一个 404，
        // 而且看不出哪里错了
        assertEquals(
            "https://example.com/static/icon.png",
            FaviconFetcher.resolve("https://example.com", "static/icon.png"),
        )
    }

    @Test
    fun `保留端口`() {
        assertEquals(
            "http://localhost:8080/favicon.ico",
            FaviconFetcher.resolve("http://localhost:8080", "/favicon.ico"),
        )
    }

    @Test
    fun `空的 href 返回 null`() {
        assertNull(FaviconFetcher.resolve("https://example.com", ""))
        assertNull(FaviconFetcher.resolve("https://example.com", "   "))
    }
}
