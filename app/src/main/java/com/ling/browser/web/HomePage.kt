package com.ling.browser.web

/**
 * 内置主页。
 *
 * 为什么不用 `about:home`：WebView 只认识 `about:blank`，喂给它 `about:home`
 * 会直接走 `onReceivedError`，页面区一片空白 —— 这正是之前"主页打不开"的原因。
 *
 * 现在改成用 `loadDataWithBaseURL` 直接渲染下面这段 HTML，主页完全不依赖网络：
 *  - 不联网、不请求任何外部资源，打开即显示；
 *  - 主题色由原生侧通过 CSS 变量注入，跟随 Material You 动态取色；
 *  - 深色模式同步切换，不会白屏闪一下。
 */
object HomePage {

    /**
     * 内置主页的逻辑地址（用于地址栏判断与展示）。
     *
     * 用自定义 scheme 而不是 about: —— WebView 只认识 about:blank，
     * 早期版本把 about:home 直接交给它，结果走 onReceivedError 白屏。
     */
    const val URL = "ling://home"

    /**
     * 交给 `loadDataWithBaseURL` 的基地址。
     *
     * 为什么不用 [URL] 本身：baseUrl 的 scheme 会参与相对路径解析与同源判定，
     * 传一个 WebView 不认识的自定义 scheme 属于未定义行为
     * （Chromium 对非法 baseUrl 的处理是实现自定义的，可能加载失败）。
     * 这里给一个**确定合法**的 https 地址，主页 HTML 又是完全自包含的
     * （无外链、无相对路径），因此 baseUrl 只起"占位"作用，不影响渲染。
     *
     * 用 `.invalid` 而非 `.local`：后者是 mDNS 保留域，在部分网络环境下
     * 可能真的被解析；`.invalid`（RFC 2606）保证永远不可解析，更安全。
     */
    const val BASE_URL = "https://home.ling.invalid/"

    /** 兼容旧值：早期版本用的是 about:home。 */
    private const val LEGACY_URL = "about:home"

    /** 该 URL 是否指向内置主页。 */
    fun isHomeUrl(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        return u.isEmpty() || u == URL || u == BASE_URL || u == LEGACY_URL
    }

    /**
     * 生成主页 HTML。
     *
     * @param background      页面背景色（CSS 颜色）
     * @param onBackground    正文颜色
     * @param primary         强调色（品牌标记背景）
     * @param onPrimary       强调色上的文字颜色
     * @param primaryContainer 次级强调色（快捷入口背景）
     * @param onPrimaryContainer 其次级上的文字颜色
     * @param dark            是否深色模式
     * @param links           常用站点（书签），最多取 8 个
     */
    fun html(
        background: String,
        onBackground: String,
        primary: String,
        onPrimary: String,
        primaryContainer: String,
        onPrimaryContainer: String,
        dark: Boolean,
        links: List<Pair<String, String>> = emptyList(),
    ): String {
        val quickLinks = if (links.isEmpty()) {
            ""
        } else {
            buildString {
                append("""<div class="links">""")
                links.take(8).forEach { (title, url) ->
                    val label = title.ifBlank { url }
                    append(
                        """<a class="link" href="${escape(url)}">""" +
                            """<span class="avatar">${escape(label.take(1).uppercase())}</span>""" +
                            """<span class="link-label">${escape(label)}</span></a>"""
                    )
                }
                append("</div>")
            }
        }

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>翎</title>
<style>
  /* color-scheme 决定浏览器内置控件（下拉框、滚动条、光标）与
     "系统默认色"的明暗。不写这一行，深色模式下滚动条依然是白的。
     早期版本把它做成了 prefersDarkCss() 函数却从未调用 ——
     那种"定义了但没人用"的代码最危险，测试还会因为它存在而变绿。 */
  :root {
    color-scheme: ${if (dark) "dark" else "light"};
    --bg: $background;
    --fg: $onBackground;
    --primary: $primary;
    --on-primary: $onPrimary;
    --pc: $primaryContainer;
    --on-pc: $onPrimaryContainer;
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body {
    margin: 0; padding: 0; height: 100%;
    background: var(--bg); color: var(--fg);
    font-family: -apple-system, "Noto Sans SC", "PingFang SC",
                 "Microsoft YaHei", Roboto, sans-serif;
  }
  body {
    display: flex; flex-direction: column;
    align-items: center; justify-content: center;
    padding: 32px 24px calc(32px + env(safe-area-inset-bottom));
    text-align: center;
  }
  .mark {
    width: 76px; height: 76px; border-radius: 50%;
    background: var(--pc);
    display: flex; align-items: center; justify-content: center;
    margin-bottom: 18px;
  }
  .mark svg { width: 40px; height: 40px; display: block; }
  /* SVG 的 stroke 作为"表现属性"不解析 var()，必须走 CSS 才能拿到主题色 */
  .mark svg path { stroke: var(--on-pc); }
  h1 {
    font-size: 40px; font-weight: 600; margin: 0;
    letter-spacing: 8px; text-indent: 8px;
    color: var(--fg);
  }
  .tagline {
    margin-top: 8px; font-size: 14px;
    color: var(--fg); opacity: .6; letter-spacing: 1px;
  }
  .links {
    display: flex; flex-wrap: wrap; gap: 14px 10px;
    justify-content: center; margin-top: 34px;
    max-width: 320px;
  }
  .link {
    width: 62px; text-decoration: none; color: inherit;
    display: flex; flex-direction: column; align-items: center;
    border-radius: 12px; padding: 6px 0;
    transition: background .15s;
  }
  /* 用主色做极轻的按压反馈，保持"干净"的观感 */
  .link:active { background: var(--pc); }
  .avatar {
    width: 48px; height: 48px; border-radius: 50%;
    background: var(--primary); color: var(--on-primary);
    display: flex; align-items: center; justify-content: center;
    font-size: 20px; font-weight: 600;
  }
  .link-label {
    margin-top: 6px; font-size: 11px;
    color: var(--fg); opacity: .7;
    max-width: 62px; overflow: hidden;
    text-overflow: ellipsis; white-space: nowrap;
  }
  .hint {
    position: fixed; left: 0; right: 0;
    bottom: calc(16px + env(safe-area-inset-bottom));
    font-size: 12px; color: var(--fg); opacity: .45;
    text-align: center; padding: 0 24px;
  }
</style>
</head>
<body>
  <div class="mark">
    <!--
      羽毛：与启动图标同一造型（裸羽柄 + 弯曲羽轴 + 朝根部斜切的羽枝缝）。
      描边色走上面的 CSS 规则 —— 写成 stroke="var(--on-pc)" 表现属性是无效的。
    -->
    <svg viewBox="0 0 24 24" fill="none"
         stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
      <!-- 羽片：凸侧（右下）饱满 -->
      <path d="M21.27 5.21 C20.17 7.27 19.61 8.3 19.23 8.98 C18.86 9.65 18.47 10.33 18.08 10.99 C17.69 11.66 17.3 12.32 16.89 12.96 C16.48 13.6 16.07 14.25 15.63 14.86 C15.19 15.47 14.78 16.12 14.24 16.63 C13.7 17.14 13.04 17.6 12.38 17.91 C11.73 18.21 11.04 18.38 10.32 18.44 C9.59 18.49 8.42 18.25 8.05 18.22"/>
      <!-- 羽片：平侧（左上）近直，根部比凸侧收得早，形成斜切 -->
      <path d="M21.27 5.21 C18.9 5.96 17.74 6.35 16.99 6.64 C16.23 6.93 15.49 7.23 14.77 7.55 C14.04 7.86 13.33 8.19 12.64 8.54 C11.95 8.9 11.26 9.25 10.62 9.66 C9.98 10.06 9.33 10.45 8.79 10.96 C8.24 11.47 7.75 12.12 7.37 12.7 C6.98 13.28 6.68 13.85 6.47 14.44 C6.26 15.02 6.17 15.91 6.1 16.2"/>
      <!-- 羽轴：从羽尖穿过羽片，伸出成一段裸羽柄 -->
      <path d="M21.27 5.21 C18.95 6.83 17.58 7.82 16.69 8.5 C15.8 9.19 14.92 9.89 14.06 10.61 C13.2 11.32 12.35 12.06 11.52 12.8 C10.68 13.54 9.8 14.35 9.04 15.06 C8.28 15.77 7.58 16.45 6.94 17.06 C6.29 17.68 5.46 18.49 5.17 18.77 L4.2 19.7"/>
      <!-- 羽枝缝：凸侧 3 道，从边缘向内、朝根部斜切，止于羽轴 -->
      <path d="M15.18 15.47 L11.28 13.28"/>
      <path d="M16.99 12.8 L13.66 11.21"/>
      <path d="M18.66 9.99 L16.11 9.21"/>
    </svg>
  </div>
  <h1>翎</h1>
  <div class="tagline">轻巧 · 干净 · 快</div>
  $quickLinks
  <div class="hint">在地址栏输入网址或搜索内容</div>
</body>
</html>
""".trimIndent()
    }

    /** HTML 属性/文本转义，避免书签标题里的引号破坏结构。 */
    private fun escape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
