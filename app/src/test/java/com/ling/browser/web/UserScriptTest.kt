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
        // 内部页（主页 / 阅读视图的假域名）不是网页，@match *://*/* 也不该圈进来；
        // 守卫必须放在标记之前，否则会把内部页误标成"已注入"。
        assertTrue(js.contains("u.hostname==='home.ling.invalid'||u.hostname==='reader.ling.invalid'"))
        // 同一文档内只跑一次的标记：END 的探测式补注入靠它避免重复执行。
        assertTrue(js.contains("window.__lingGMInjected[\"u123\"]=1"))
        assertTrue(js.indexOf("if(!inc)return;") < js.indexOf("window.__lingGMInjected[\"u123\"]=1"))
        // 元数据头只作为 `GM_info.script.metaStr` 的数据带进页面（供 GM_config
        // 之类解析自定义字段的库使用），不会被当成脚本正文执行。
        assertTrue(js.contains("metaStr:\"// ==UserScript=="))
        assertEquals(1, Regex("==UserScript==").findAll(js).count())
    }

    @Test
    fun `GM_info 暴露元数据并正确转义`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  引号"脚本
            // @version 2.3
            // @description 说明
            // @match https://example.com/*
            // @grant GM_getValue
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val js = scriptWrapper(script, "u1", "{}")
        assertTrue(js.contains("var GM_info={script:{name:\"引号\\\"脚本\""))
        assertTrue(js.contains(",version:\"2.3\""))
        assertTrue(js.contains("scriptHandler:'LingBrowser'"))
        // unsafeWindow 无条件可用，指向页面自身 window
        assertTrue(js.contains("var unsafeWindow=window;"))
        // GM_addElement 是纯 DOM 能力，同样无条件提供
        assertTrue(js.contains("function GM_addElement"))
    }

    @Test
    fun `存储与剪贴板 API 按 grant 发放`() {
        val granted = parse(
            """
            // ==UserScript==
            // @name  授权
            // @match https://example.com/*
            // @grant GM_getValue
            // @grant GM_deleteValue
            // @grant GM_listValues
            // @grant GM_setClipboard
            // @grant GM_xmlhttpRequest
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val js = scriptWrapper(granted, "u1", "{}")
        assertTrue(js.contains("function GM_deleteValue"))
        assertTrue(js.contains("function GM_listValues"))
        assertTrue(js.contains("function GM_setClipboard"))
        // 第二个参数 type 要透传：'html' 走富文本剪贴板。
        assertTrue(js.contains("ty:String(ty||'text')"))
        assertTrue(js.contains("function GM_xmlhttpRequest"))
        assertTrue(js.contains("window.__lingGMDispatch"))

        val none = parse(
            """
            // ==UserScript==
            // @name  无授权
            // @match https://example.com/*
            // @grant none
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val noJs = scriptWrapper(none, "u1", "{}")
        assertFalse(noJs.contains("function GM_setClipboard"))
        assertFalse(noJs.contains("function GM_deleteValue"))
        assertFalse(noJs.contains("function GM_listValues"))
    }

    @Test
    fun `解析 @resource 名称与地址`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  资源
            // @match https://example.com/*
            // @resource logo https://example.com/logo.png
            // @resource styles https://cdn.example.com/a.css?v=1
            // @resource 坏地址 ftp://example.com/x
            // @grant GM_getResourceText
            // ==/UserScript==
            """,
        )!!
        assertEquals(
            mapOf(
                "logo" to "https://example.com/logo.png",
                "styles" to "https://cdn.example.com/a.css?v=1",
            ),
            script.resources,
        )
    }

    @Test
    fun `资源 API 按 grant 发放并内联资源表`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  资源
            // @match https://example.com/*
            // @grant GM_getResourceText
            // ==/UserScript==
            """,
        )!!
        val js = scriptWrapper(
            script,
            "u1",
            "{}",
            "",
            """{"logo":{"m":"image/png","b":"aGVsbG8="}}""",
        )
        assertTrue(js.contains("var RES={\"logo\""))
        assertTrue(js.contains("function GM_getResourceText"))
        assertTrue(js.contains("function GM_getResourceURL"))
        assertTrue(js.contains("data:'+r.m+';base64,'+r.b"))
    }

    @Test
    fun `GM_info 带 scriptMetaStr 与 resources 清单`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  元数据
            // @match https://example.com/*
            // @resource logo https://cdn.example.com/logo.png
            // @grant GM_getResourceText
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val js = scriptWrapper(
            script,
            "u1",
            "{}",
            "",
            """{"logo":{"m":"image/png","b":"aGVsbG8="}}""",
        )
        // 声明地址与内容一起进页面：前者给 GM_info 的清单，后者给 GM_getResourceText。
        assertTrue(js.contains("var RESU={\"logo\":\"https://cdn.example.com/logo.png\"}"))
        assertTrue(
            js.contains(
                "RSRC.push({name:rk,mimetype:(RES[rk]&&RES[rk].m)||'',url:RESU[rk]||''})",
            ),
        )
        assertTrue(js.contains(",resources:RSRC"))
    }

    @Test
    fun `GM_getTab 家族按 grant 发放并向原生取数据`() {
        val granted = parse(
            """
            // ==UserScript==
            // @name  标签态
            // @match https://example.com/*
            // @grant GM_getTab
            // @grant GM_saveTab
            // @grant GM_getTabs
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val js = scriptWrapper(granted, "u1", "{}")
        assertTrue(js.contains("function GM_getTab(cb)"))
        assertTrue(js.contains("function GM_saveTab(o)"))
        assertTrue(js.contains("function GM_getTabs(cb)"))
        // 回调由原生投递：id 挂在 CB 上，取完即删，避免重复回调。
        assertTrue(js.contains("post({t:'tabGet',id:i})"))
        assertTrue(js.contains("post({t:'tabSave',v:o||{}})"))
        assertTrue(js.contains("post({t:'tabsGet',id:i})"))

        val none = parse(
            """
            // ==UserScript==
            // @name  无授权
            // @match https://example.com/*
            // @grant none
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val noJs = scriptWrapper(none, "u1", "{}")
        assertFalse(noJs.contains("function GM_getTab"))
        assertFalse(noJs.contains("function GM_saveTab"))
    }

    @Test
    fun `GM_getTab 的新名字也发同一能力`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  Promise 标签态
            // @match https://example.com/*
            // @grant GM.getTab
            // @grant GM.saveTab
            // @grant GM.getTabs
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val js = scriptWrapper(script, "u1", "{}")
        assertTrue(js.contains("function GM_getTab(cb)"))
        assertTrue(js.contains("GM.getTab=function(){return new Promise("))
        assertTrue(js.contains("GM.saveTab=function(o){"))
        assertTrue(js.contains("GM.getTabs=function(){return new Promise("))
    }

    @Test
    fun `window_close 接到原生桥`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  关窗
            // @match https://example.com/*
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val js = scriptWrapper(script, "u1", "{}")
        // 无条件提供：不写 @grant 的脚本也在用 window.close。
        assertTrue(js.contains("window.close=function(){post({t:'winClose'})};"))
    }

    @Test
    fun `解析 @connect 白名单`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  跨域
            // @match https://example.com/*
            // @connect API.Example.com
            // @connect *.cdn.example.com, self
            // @grant GM_xmlhttpRequest
            // ==/UserScript==
            """,
        )!!
        assertEquals(
            setOf("api.example.com", "*.cdn.example.com", "self"),
            script.connects,
        )
    }

    @Test
    fun `connect 白名单匹配语义`() {
        // 没写 @connect 时放行（兼容老脚本）；写了就只认白名单。
        assertTrue(connectAllowed(emptySet(), "anything.test", "page.test"))
        assertTrue(connectAllowed(setOf("*"), "anything.test", "page.test"))

        val connects = setOf("example.com", "self")
        assertTrue(connectAllowed(connects, "example.com", "page.test"))
        assertTrue(connectAllowed(connects, "api.example.com", "page.test"))
        assertFalse(connectAllowed(connects, "notexample.com", "page.test"))
        // self 只认当前页主机
        assertTrue(connectAllowed(connects, "page.test", "page.test"))
        assertFalse(connectAllowed(connects, "page.test", "other.test"))

        // *. 与裸域名等价；目标主机缺失一律拒绝。
        assertTrue(connectAllowed(setOf("*.example.com"), "example.com", null))
        assertTrue(connectAllowed(setOf("*.example.com"), "a.b.example.com", null))
        assertFalse(connectAllowed(setOf("example.com"), null, "example.com"))
    }

    @Test
    fun `菜单与通知 API 始终发放`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  挂件
            // @match https://example.com/*
            // @grant  GM_registerMenuCommand
            // @grant  GM_notification
            // ==/UserScript==
            """,
        )!!
        val js = scriptWrapper(script, "u1", "{}")
        assertTrue(js.contains("function GM_registerMenuCommand"))
        assertTrue(js.contains("function GM_unregisterMenuCommand"))
        // options.accessKey：对象与老式的裸按键两种写法都认，只留单个字母/数字。
        assertTrue(js.contains("o&&typeof o==='object'&&o.accessKey!=null"))
        assertTrue(js.contains("ak:m?m[1].toUpperCase():''"))
        assertTrue(js.contains("function GM_notification"))
        assertTrue(js.contains("window.__lingGMMenuMap"))
        assertTrue(js.contains("window.__lingGMNoticeMap"))
        // 通知的完整参数：大图 / 超时 / 静默 / 点击打开的地址 / ondone。
        assertTrue(js.contains("img:String(d.image||'')"))
        assertTrue(js.contains("to:Number(d.timeout)||0"))
        assertTrue(js.contains("silent:!!d.silent"))
        assertTrue(js.contains("NT[id]={click:d.onclick,done:d.ondone}"))
        assertTrue(js.contains("ev==='done'?e.done:e.click"))
    }

    @Test
    fun `值变更监听按 grant 发放并广播到其他标签`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  同步
            // @match https://example.com/*
            // @grant  GM_setValue
            // @grant  GM_addValueChangeListener
            // ==/UserScript==
            """,
        )!!
        val js = scriptWrapper(script, "u1", "{}")
        assertTrue(js.contains("function GM_addValueChangeListener"))
        assertTrue(js.contains("function GM_removeValueChangeListener"))
        assertTrue(js.contains("window.__lingGMValueMap[SID]="))
        assertTrue(js.contains("fire(k,o,v,false)"))
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

    @Test
    fun `require 只保留 http 与 https 并去重`() {
        val script = parse(
            """
            // ==UserScript==
            // @name     依赖
            // @match    https://example.com/*
            // @require  https://cdn.example.com/jquery.min.js
            // @require  file:///sdcard/local.js
            // @require  https://cdn.example.com/jquery.min.js
            // @require  HTTP://cdn.example.com/util.js
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertEquals(
            listOf(
                "https://cdn.example.com/jquery.min.js",
                "HTTP://cdn.example.com/util.js",
            ),
            script.requireUrls,
        )
    }

    @Test
    fun `依赖代码注入在脚本正文之前`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  依赖顺序
            // @match https://example.com/*
            // @require https://cdn.example.com/jquery.min.js
            // ==/UserScript==

            console.log('user-script');
            """.trimIndent(),
        )!!
        val js = scriptWrapper(script, "u1", "{}", "var jQuery={version:'3.7.1'};")
        val dependencyAt = js.indexOf("var jQuery=")
        val bodyAt = js.indexOf("console.log('user-script')")
        assertTrue(dependencyAt >= 0)
        assertTrue(bodyAt > dependencyAt)
        // 依赖与正文同处一个 IIFE：依赖声明的顶层变量脚本才能看见
        assertTrue(js.startsWith("(function(){try{"))
    }

    @Test
    fun `GM 新旧两套名字互认`() {
        // 只写新名字也应该拿到旧实现（能力是同一个）。
        val modern = parse(
            """
            // ==UserScript==
            // @name  新名字
            // @match https://example.com/*
            // @grant GM.getValue
            // @grant GM.openInTab
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val modernJs = scriptWrapper(modern, "u1", "{}")
        assertTrue(modernJs.contains("function GM_getValue"))
        assertTrue(modernJs.contains("function GM_openInTab"))
        assertTrue(modernJs.contains("GM.getValue=function"))

        // 反方向：只写旧名字仍然拿到旧实现；GM.* 命名空间不因此被打开
        // （避免给老脚本往页面里多塞一个全局 `GM`）。
        val legacy = parse(
            """
            // ==UserScript==
            // @name  旧名字
            // @match https://example.com/*
            // @grant GM_getValue
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val legacyJs = scriptWrapper(legacy, "u1", "{}")
        assertTrue(legacyJs.contains("function GM_getValue"))
        assertFalse(legacyJs.contains("var GM={}"))
    }

    @Test
    fun `只声明新名字即可打开 GM 命名空间`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  新名字
            // @match https://example.com/*
            // @grant GM.setValue
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val js = scriptWrapper(script, "u1", "{}")
        assertTrue(js.contains("var GM={}"))
        assertTrue(js.contains("GM.setValue=function"))
        assertTrue(js.contains("function GM_setValue"))
    }

    @Test
    fun `GM 星号发放全部能力`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  全量
            // @match https://example.com/*
            // @grant GM.*
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val js = scriptWrapper(script, "u1", "{}")
        assertTrue(js.contains("function GM_getValue"))
        assertTrue(js.contains("function GM_openInTab"))
        assertTrue(js.contains("function GM_download"))
        assertTrue(js.contains("function GM_xmlhttpRequest"))
        assertTrue(js.contains("GM.download=function"))
        assertTrue(js.contains("GM.xmlHttpRequest=function"))
    }

    @Test
    fun `GM_openInTab 支持对象参数并返回句柄`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  开标签
            // @match https://example.com/*
            // @grant GM_openInTab
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val js = scriptWrapper(script, "u1", "{}")
        // 三种调用形态：字符串 / 字符串 + 布尔 / 对象。
        assertTrue(js.contains("if(typeof u==='string')"))
        assertTrue(js.contains("d={active:!o}"))
        // 第二参数是对象时必须逐字段合并：onload / onclose / insert 只写在 o 上，
        // 只认 active/incognito 会让这些回调永远不触发。
        assertTrue(js.contains("for(var k in o)d[k]=o[k]"))
        assertTrue(js.contains("if(d.active===undefined)d.active=o.active"))
        // 对象里的 active / incognito / onload 都要透传到原生。
        assertTrue(js.contains("active:act!==false"))
        assertTrue(js.contains("inc:!!inc"))
        assertTrue(js.contains("typeof d.onload==='function'"))
        // onclose 与 insert：回调要登记，位置要透传，且要告诉原生"这个脚本等着关标签"。
        assertTrue(js.contains("e==='close'&&typeof d.onclose==='function'"))
        assertTrue(js.contains("var ins=d.insert"))
        assertTrue(js.contains("oc:typeof d.onclose==='function'"))
        // 句柄只能关掉自己开出去的标签。
        assertTrue(js.contains("t:'tabClose'"))
        assertTrue(js.contains("return{close:function()"))
    }

    @Test
    fun `GM_download 按 grant 发放并分发回调`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  下载
            // @match https://example.com/*
            // @grant GM_download
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val js = scriptWrapper(script, "u1", "{}")
        assertTrue(js.contains("function GM_download"))
        assertTrue(js.contains("t:'dl'"))
        assertTrue(js.contains("typeof d.onload==='function'"))
        assertTrue(js.contains("typeof d.onerror==='function'"))
        // headers 透传给原生；进度回调与 abort 句柄也要有。
        assertTrue(js.contains("h:d.headers||{}"))
        assertTrue(js.contains("e==='progress'"))
        assertTrue(js.contains("return{abort:function()"))
        assertTrue(js.contains("t:'dlAbort'"))
        // saveAs / conflictAction 也要透传，否则原生只能按默认落点写。
        assertTrue(js.contains("sa:!!d.saveAs"))
        assertTrue(js.contains("ca:String(d.conflictAction||'')"))

        // 没授权就不发放。
        val none = parse(
            """
            // ==UserScript==
            // @name  无授权
            // @match https://example.com/*
            // @grant none
            // ==/UserScript==
            """.trimIndent(),
        )!!
        assertFalse(scriptWrapper(none, "u1", "{}").contains("function GM_download"))
    }

    @Test
    fun `GM_xmlhttpRequest 声明二进制响应类型`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  二进制
            // @match https://example.com/*
            // @grant GM_xmlhttpRequest
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val js = scriptWrapper(script, "u1", "{}")
        // responseType 原样透传给原生，arraybuffer / blob 由 [UserScriptHttp] 兑现。
        assertTrue(js.contains("rt:d.responseType||''"))
        assertTrue(js.contains("e==='progress'"))
        // 二进制响应体以 base64 过桥，垫片负责解回 ArrayBuffer / Blob。
        assertTrue(js.contains("d.responseType==='arraybuffer'"))
        assertTrue(js.contains("new Blob([aa],{type:ct})"))
        // Blob.type 从 Content-Type 响应头取，空 type 会让 image/* 判断落空。
        assertTrue(js.contains("/content-type:"))
        // 老写法 binary:true 等价于 arraybuffer；回调 this 绑定 context；
        // loadstart / loadend 覆盖请求的起止两端。
        assertTrue(js.contains("if(d.binary&&!d.responseType)d.responseType='arraybuffer'"))
        assertTrue(js.contains("var c=d.context||d"))
        assertTrue(js.contains("f.call(c,p)"))
        // onloadstart 是同步触发的，作用域里没有 CB 内部的 `c`，必须自己重算 context。
        assertTrue(js.contains("d.onloadstart.call(d.context||d,"))
        assertTrue(js.contains("if(e!=='progress')call(d.onloadend)"))
    }

    @Test
    fun `GM 命名空间的通知与下载返回 Promise 句柄`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  新版
            // @match https://example.com/*
            // @grant GM.*
            // ==/UserScript==
            """.trimIndent(),
        )!!
        val js = scriptWrapper(script, "u1", "{}")
        // GM.notification 返回 Promise；GM.download 的 Promise 额外带 abort。
        assertTrue(js.contains("GM.notification=function"))
        assertTrue(js.contains("return Promise.resolve(GM_notification"))
        assertTrue(js.contains("p.abort=function()"))
        assertTrue(js.contains("GM.setClipboard=function(t,ty)"))
        // 新版命名空间要把 options 一并转给底层实现。
        assertTrue(js.contains("GM.registerMenuCommand=function(n,f,o)"))
    }

    @Test
    fun `全量 grant 的注入代码不会因为缺少分号而整段失效`() {
        val script = parse(
            """
            // ==UserScript==
            // @name  全量
            // @match https://example.com/*
            // @grant GM.*
            // @grant GM_openInTab
            // @grant GM_setClipboard
            // @grant GM_download
            // @grant GM_xmlhttpRequest
            // @grant GM_getResourceText
            // @grant GM_getResourceURL
            // ==/UserScript==

            var marker = 1;
            """.trimIndent(),
        )!!
        val js = scriptWrapper(script, "u1", "{}")
        // 垫片是**无缝拼接**的：赋值表达式（`x = function(){…}`）后面紧跟
        // `function …` 且同一行内没有分号时，解析器会把它当成表达式的延续，
        // 整个 IIFE 抛 SyntaxError、脚本一行都不执行（真机已踩过）。
        assertFalse(js.contains("catch(x){}}}function"))
        // 每个顶层声明都得以 `;` 或 `}` 正确收尾，抽查几处拼接边界。
        assertTrue(js.contains("}}};function GM_log"))
        assertTrue(js.contains("return{abort:function(){post({t:'xa',id:i})}}}"))
    }
}
