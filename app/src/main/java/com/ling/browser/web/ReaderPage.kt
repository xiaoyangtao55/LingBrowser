package com.ling.browser.web

/**
 * 阅读视图页面。
 *
 * 结构与 [HomePage] 一致：原生侧算好颜色传进来，页面本身不含任何主题逻辑，
 * 通过 `loadDataWithBaseURL` 渲染。这样做的三个好处：
 *  - 完全自包含，不联网、不请求外部资源；
 *  - 深色模式与 Material You 取色跟着原生走，不会白屏闪一下；
 *  - 版式逻辑是纯字符串拼接，**可以在单元测试里断言**（见 ReaderPageTest）。
 *
 * 为什么不把正文渲染成 Compose 组件：正文是任意 HTML（表格、图片、代码块、
 * 公式），Compose 没有现成的 HTML 渲染器，自己实现等于重写一个浏览器排版引擎。
 * 交给 WebView 渲染、只在外面套一层阅读排版是最务实的做法，也是 Via/Kiwi
 * 这类浏览器的共同选择。
 */
object ReaderPage {

    /**
     * 阅读视图的逻辑地址。
     *
     * 必须有独立地址：返回栈、`canGoBack` 判定、以及"当前是不是阅读视图"
     * 都依赖它。用自定义 scheme 与主页保持一致，便于统一识别。
     */
    const val URL = "ling://reader"

    /**
     * 交给 `loadDataWithBaseURL` 的基地址。
     *
     * 与 [HomePage.BASE_URL] 同理：必须是一个**确定合法**的地址，
     * 自定义 scheme 会让相对路径解析与同源判定进入未定义行为。
     * 用 `.invalid`（RFC 2606）保证永远不可解析。
     */
    const val BASE_URL = "https://reader.ling.invalid/"

    /** 字号档位。用离散档位而不是滑杆：手机上少数几档就够，且能记住。 */
    enum class FontSize(val label: String, val px: Int) {
        SMALL("小", 16),
        MEDIUM("中", 18),
        LARGE("大", 21),
        XLARGE("特大", 24),
    }

    /** 该 URL 是否指向阅读视图。 */
    fun isReaderUrl(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        return u == URL || u == BASE_URL
    }

    /** HTML 转义。正文来自第三方网页，必须转义后再拼进模板。 */
    private fun escape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /**
     * 生成阅读视图 HTML。
     *
     * @param title    文章标题
     * @param site     站点名（可为空）
     * @param url      原文地址，显示在标题下方
     * @param content  正文 HTML（**来自第三方页面**，见下方安全说明）
     * @param background / onBackground / primary / ... 主题色（CSS 颜色字符串）
     * @param dark     是否深色模式
     * @param fontPx   正文字号（px）
     */
    fun html(
        title: String,
        site: String,
        url: String,
        content: String,
        background: String,
        onBackground: String,
        primary: String,
        primaryContainer: String,
        onPrimaryContainer: String,
        dark: Boolean,
        fontPx: Int,
    ): String {
        // 标题为空时退回站点名，再为空就退回"阅读模式"，
        // 避免渲染出一个空标题把版式顶歪。
        val heading = title.trim().ifBlank { site.trim().ifBlank { "阅读模式" } }
        val siteLine = site.trim()
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>${escape(heading)}</title>
<style>
  /* color-scheme 让滚动条等内置控件跟随明暗，否则深色模式下滚动条仍是白的 */
  :root {
    color-scheme: ${if (dark) "dark" else "light"};
    --bg: $background;
    --fg: $onBackground;
    --primary: $primary;
    /* 这里刻意**没有** --on-primary：阅读视图里 primary 只作为
       「页面底色上的文字色」出现（链接），从来没有"primary 实心块上的字"。
       定义却不使用是种隐性失效 —— 换主题时它纹丝不动，看着像坏了。
       ReaderPageTest 里有一条断言专门防止变量定义了却没人用。 */
    --pc: $primaryContainer;
    --on-pc: $onPrimaryContainer;
    --font-size: ${fontPx}px;
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }

  html, body {
    margin: 0; padding: 0;
    background: var(--bg); color: var(--fg);
    font-family: -apple-system, "Noto Serif SC", "Songti SC", Georgia,
                 "Noto Sans SC", "PingFang SC", "Microsoft YaHei", Roboto, serif;
    /* 中文正文用较小行高会显得拥挤；1.8 是中文长文阅读的常见取值 */
    line-height: 1.8;
    font-size: var(--font-size);
    /* 阅读视图的核心：限制行宽。一行过长会让眼睛换行时找错行 */
    word-wrap: break-word;
    -webkit-text-size-adjust: 100%;
  }

  /* 正文容器限制最大宽度并居中。大屏上满宽排版很难读，
     40em 约等于中文 35~40 字一行，接近纸质书的舒适区。 */
  .wrap {
    max-width: 40em;
    margin: 0 auto;
    padding: 20px 20px calc(48px + env(safe-area-inset-bottom));
  }

  header { margin-bottom: 28px; }
  h1 {
    font-size: 1.5em;
    line-height: 1.35;
    margin: 0 0 10px;
    font-weight: 650;
  }
  .meta {
    font-size: 0.78em;
    color: var(--fg);
    opacity: 0.6;
    display: flex; flex-wrap: wrap; gap: 4px 10px;
    align-items: center;
  }
  .meta .dot { opacity: 0.5; }

  /* 正文元素样式。站点的原始 class 可能带来奇怪样式，
     因此这里对常见标签统一重置一遍。 */
  article p { margin: 0 0 1.1em; }
  article h2, article h3, article h4 {
    line-height: 1.4;
    margin: 1.6em 0 0.6em;
    font-weight: 650;
  }
  article h2 { font-size: 1.25em; }
  article h3 { font-size: 1.1em; }
  article img, article video, article iframe {
    max-width: 100%; height: auto;
    display: block; margin: 1.2em auto;
    border-radius: 8px;
  }
  article figure { margin: 1.2em 0; }
  article figcaption {
    font-size: 0.8em; opacity: 0.65; text-align: center; margin-top: 6px;
  }
  article a { color: var(--primary); text-decoration: none; }
  article blockquote {
    margin: 1.2em 0; padding: 0 0 0 14px;
    border-left: 3px solid var(--pc);
    opacity: 0.9;
  }
  article pre {
    background: var(--pc); color: var(--on-pc);
    padding: 12px 14px; border-radius: 8px;
    overflow-x: auto; font-size: 0.86em; line-height: 1.6;
    /* 代码块不是中文，用等宽字体 */
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  }
  article code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 0.88em;
  }
  /* 行内 code 给个底色，代码块里的 code 不要再套一层 */
  article :not(pre) > code {
    background: var(--pc); color: var(--on-pc);
    padding: 1px 5px; border-radius: 4px;
  }
  article ul, article ol { padding-left: 1.5em; margin: 0 0 1.1em; }
  article li { margin-bottom: 0.4em; }
  article table {
    width: 100%; border-collapse: collapse;
    margin: 1.2em 0; font-size: 0.9em;
    display: block; overflow-x: auto;
  }
  article th, article td {
    border: 1px solid var(--pc); padding: 7px 10px; text-align: left;
  }
  article hr {
    border: none; border-top: 1px solid var(--pc);
    margin: 2em 0; opacity: 0.6;
  }

  /* 底部提示：说明这是提取后的正文，点它回原网页 */
  .foot {
    margin-top: 40px; padding-top: 16px;
    border-top: 1px solid var(--pc);
    font-size: 0.78em; opacity: 0.6; text-align: center;
  }
  .foot a { color: var(--primary); text-decoration: none; }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <h1>${escape(heading)}</h1>
    <div class="meta">${
            listOfNotNull(
                siteLine.takeIf { it.isNotEmpty() }?.let { escape(it) },
                host.takeIf { it.isNotEmpty() }?.let { escape(it) },
            ).joinToString(separator = "<span class=\"dot\">·</span>")
        }</div>
  </header>
  <article id="ling-reader-body">$content</article>
  <div class="foot">已由「翎」阅读模式重新排版 · <a href="${escape(url)}">查看原网页</a></div>
</div>
</body>
</html>
        """.trimIndent()
    }
}
