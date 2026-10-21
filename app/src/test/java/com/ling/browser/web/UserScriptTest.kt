package com.ling.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户脚本解析与匹配的单元测试。
 *
 * 重点不在"能解析出来"，而在**语义跟 Chrome / Tampermonkey 对齐**：
 * `*.example.com` 不含裸域、端口与 fragment 不参与匹配、query 不会让
 * path 规则失效。这些点一旦错了，用户看到的就是"脚本装了却不生效"。
 */
class UserScriptTest {

    private fun parse(body: String) = UserScriptParser.parse(body.trimIndent())

    @Test
    fun `解析基本元数据与正文`() {
        val script = parse(
            """
            // ==UserScript==
            // @name         测试脚本
            // @namespace    com.example
            // @version      1.2.3
            // @description  只是注释
            // @match        https://example.com/*
            // @run-at       document-idle
            // ==/UserScript==

            console.log('hello');
            """.trimIndent(),
        )
        assertNotNull(script)
        assertEquals("测试脚本", script!!.name)
        assertEquals("1.2.3", script.version)
        assertEquals(ScriptRunAt.IDLE, script.runAt)
        assertEquals(1, script.patternLabels.size)
        // 元数据头被剥掉，正文保留
        assertFalse(script.code.contains("==UserScript=="))
        assertTrue(script.code.contains("console.log('hello');"))
    }

    @Test
    fun `缺少元数据头返回 null`() {
        assertNull(UserScriptParser.parse("console.log('x');"))
    }

    @Test
    fun `元数据头未闭合返回 null`() {
        assertNull(
            UserScriptParser.parse(
                """
                // ==UserScript==
                // @name 半截
                console.log('x');
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `match 的星号子域不包含裸域`() {
        val script = parse(
            """
            // ==UserScript==
            // @name   子域
            // @match  https://*.example.com/*
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertTrue(script.matches("https://a.example.com/page"))
        assertTrue(script.matches("https://a.b.example.com/page"))
        assertFalse(script.matches("https://example.com/page"))
        assertFalse(script.matches("https://notexample.com/page"))
    }

    @Test
    fun `match 的路径规则生效`() {
        val script = parse(
            """
            // ==UserScript==
            // @name   路径
            // @match  https://example.com/api/*
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertTrue(script.matches("https://example.com/api/users"))
        assertFalse(script.matches("https://example.com/other"))
        assertFalse(script.matches("https://example.com/apiary"))
    }

    @Test
    fun `query 不会让 path 规则失效`() {
        // Chrome 的 match pattern 只比对 pathname，这里额外允许可选 query。
        val script = parse(
            """
            // ==UserScript==
            // @name   查询串
            // @match  https://example.com/page
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertTrue(script.matches("https://example.com/page"))
        assertTrue(script.matches("https://example.com/page?x=1"))
        assertFalse(script.matches("https://example.com/page/2"))
    }

    @Test
    fun `scheme 与主机大小写不敏感、端口与 fragment 不参与`() {
        val script = parse(
            """
            // ==UserScript==
            // @name   归一化
            // @match  https://Example.COM/*
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertTrue(script.matches("HTTPS://EXAMPLE.com/Path"))
        assertTrue(script.matches("https://example.com:8443/Path"))
        assertTrue(script.matches("https://example.com/Path#section"))
    }

    @Test
    fun `path 大小写敏感`() {
        val script = parse(
            """
            // ==UserScript==
            // @name   区分大小写
            // @match  https://example.com/Page
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertTrue(script.matches("https://example.com/Page"))
        assertFalse(script.matches("https://example.com/page"))
    }

    @Test
    fun `include 通配符与 exclude 例外`() {
        val script = parse(
            """
            // ==UserScript==
            // @name     通配
            // @include  https://example.com/articles/*
            // @exclude  https://example.com/articles/private/*
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertTrue(script.matches("https://example.com/articles/1"))
        assertFalse(script.matches("https://example.com/articles/private/1"))
        assertFalse(script.matches("https://example.com/other"))
    }

    @Test
    fun `include 支持斜杠包裹的正则`() {
        val script = parse(
            """
            // ==UserScript==
            // @name    正则
            // @include /https:\/\/example\.com\/item\/\d+/
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertTrue(script.matches("https://example.com/item/42"))
        assertFalse(script.matches("https://example.com/item/abc"))
    }

    @Test
    fun `没有匹配规则时不匹配任何页面`() {
        val script = parse(
            """
            // ==UserScript==
            // @name 空规则
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertTrue(script.includes.isEmpty())
        assertFalse(script.matches("https://example.com/"))
        assertTrue(script.allowedOrigins.isEmpty())
    }

    @Test
    fun `非 http 地址不匹配`() {
        val script = parse(
            """
            // ==UserScript==
            // @name 全匹配
            // @match *://*/*
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertTrue(script.matches("https://anything.test/x"))
        assertFalse(script.matches("file:///sdcard/a.html"))
        assertFalse(script.matches("data:text/html,hi"))
        assertFalse(script.matches("ling://home"))
    }

    @Test
    fun `noframes grant 与 run-at 变体`() {
        val script = parse(
            """
            // ==UserScript==
            // @name      变体
            // @match     https://example.com/*
            // @run-at    document-start
            // @noframes
            // @grant     GM_setValue
            // @grant     GM_getValue
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertEquals(ScriptRunAt.START, script.runAt)
        assertTrue(script.noFrames)
        assertEquals(setOf("GM_setValue", "GM_getValue"), script.grants)
    }

    @Test
    fun `grant none 视为不申请任何能力`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  无授权
            // @match https://example.com/*
            // @grant none
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertTrue(script.grants.isEmpty())
    }

    @Test
    fun `allowedOrigins 从 match 推导、整串通配退回星号`() {
        val scoped = parse(
            """
            // ==UserScript==
            // @name  限定
            // @match https://a.example.com/*
            // @match *://b.example.com/*
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertEquals(
            setOf("https://a.example.com", "http://b.example.com", "https://b.example.com"),
            scoped.allowedOrigins,
        )

        val everywhere = parse(
            """
            // ==UserScript==
            // @name  全局
            // @match *://*/*
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertEquals(setOf("*"), everywhere.allowedOrigins)
    }

    @Test
    fun `BOM 与 CRLF 不影响解析`() {
        val source = "\uFEFF// ==UserScript==\r\n// @name BOM\r\n// @match https://a.test/*\r\n// ==/UserScript==\r\n"
        val script = UserScriptParser.parse(source)
        assertNotNull(script)
        assertEquals("BOM", script!!.name)
        assertTrue(script.matches("https://a.test/x"))
    }

    @Test
    fun `包装代码带协议守卫、正则与值快照`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  包装
            // @match https://example.com/*
            // @grant GM_getValue
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val js = scriptWrapper(script, "u123", """{"k":"v"}""")
        assertTrue(js.startsWith("(function(){try{"))
        assertTrue(js.contains("u.protocol!=='http:'"))
        assertTrue(js.contains("new RegExp("))
        assertTrue(js.contains("var V={\"k\":\"v\"}"))
        // 元数据头不该被带进页面
        assertFalse(js.contains("==UserScript=="))
    }

    @Test
    fun `canonicalUrl 归一化规则`() {
        assertEquals("https://example.com/p?q=1", canonicalUrl("https://Example.com:443/p?q=1#h"))
        assertEquals("http://example.com/", canonicalUrl("http://example.com"))
        assertNull(canonicalUrl("ftp://example.com/x"))
        assertNull(canonicalUrl(""));
    }

    @Test
    fun `jsString 转义反斜杠与引号`() {
        assertEquals("\"a\\\\b\\\"c\"", jsString("a\\b\"c"))
    }

    @Test
    fun `安装链接按路径后缀识别`() {
        assertTrue(isUserScriptUrl("https://greasyfork.icu/scripts/583582/583582-LinkSwift.user.js"))
        // 查询串 / fragment 不影响；大小写不敏感
        assertTrue(isUserScriptUrl("https://example.com/a.USER.JS?version=2"))
        assertTrue(isUserScriptUrl("http://example.com/a.user.js#top"))
        // 只看路径后缀，目录名或查询串里出现不算
        assertFalse(isUserScriptUrl("https://example.com/a.user.js/page"))
        assertFalse(isUserScriptUrl("https://example.com/download?file=a.user.js"))
        // 非 http(s) 一律不算
        assertFalse(isUserScriptUrl("file:///sdcard/a.user.js"))
    }
}
