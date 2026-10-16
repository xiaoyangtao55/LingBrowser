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
 *  - 页面背景透明，由原生背景层在 WebView 之下供色 —— 纯色档与自定义图片档
 *    走同一条路径，因此不存在"底与页面颜色不一致"的过渡帧。
 *
 * 页面内容刻意保持极简 —— **只有一个时钟 + 一个搜索框**（参照 OnyxBrowser 起始页
 * 的极简形态做了取舍，没有 logo、标题、快捷入口）：
 *  - 时钟由原生侧生成文本、并用 [clockJs] 每分钟原地刷新：主页是**零脚本** HTML
 *    （见 `tools/check_home_html.py` 对 `<script>` 的禁令），不能自带 setInterval；
 *  - 搜索框提交到内部地址 [SEARCH_URL]，由原生用同一套 UrlUtils 判定
 *    「网址还是搜索词」，不在网页里重写一遍判定逻辑。
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

    /**
     * 主页搜索框提交到的内部地址（表单 action）。
     *
     * 为什么不是真正的搜索地址：输入可能是**网址**也可能是**搜索词**，而判定逻辑
     * （[com.ling.browser.util.UrlUtils.toUrl]）在原生侧、且只该有一份 ——
     * 前端只把这个固定地址交回来，由原生决定去哪个网址，避免在网页里重写一遍判定。
     *
     * 为什么不用 JS 提交：主页是**零脚本**的自包含 HTML（`check_home_html.py`
     * 明令不许 `<script>`），而一次普通表单提交同样会走 `shouldOverrideUrlLoading`。
     */
    const val SEARCH_URL = "ling://search"

    /**
     * 透明背景的 CSS 颜色值。
     *
     * 主页**始终**用它当 `--bg`：背景一律交给原生层
     * （`com.ling.browser.ui.components.HomeBackgroundLayer`）画在 WebView 之下 ——
     * 纯色档画主题背景色，自定义图片档再叠上图片与顶部遮罩。
     *
     * 为什么不再分档：WebView 自身的底是**异步**转成 HTML 首帧的，
     * 只要两者颜色不一致（比如 WebView 白底 vs 主页深色），中间就必然闪一段。
     * 让 HTML 恒透明 + WebView 恒透明底，屏幕上从窗口建立到首帧都由原生层给色，
     * 这段闪烁就不存在了。
     */
    const val TRANSPARENT_BG = "transparent"

    /**
     * 时钟元素的 DOM id。
     *
     * 原生侧原地刷新时间（[clockJs]）时用它定位；抽成常量避免 HTML 与 JS 两处
     * 各写一份字符串，改了一处漏一处会导致"时间不走了"且不报任何错。
     */
    const val CLOCK_ID = "ling-clock"

    /** 该 URL 是否指向内置主页。 */
    fun isHomeUrl(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        return u.isEmpty() || u == URL || u == BASE_URL || u == LEGACY_URL
    }

    /**
     * 从主页搜索框提交的地址里取出查询串；不是这个地址就返回 null。
     *
     * 纯字符串逻辑（**不碰** Android 的 `Uri` —— 它在本地单测里是空壳），
     * 因此可以被单测覆盖。表单编码是 `application/x-www-form-urlencoded`，
     * 中文与空格会变成 `%XX` / `+`，必须先解码再交给 `UrlUtils.toUrl`，
     * 否则会被二次编码（搜"中文"会搜到 "%E4%B8%AD%E6%96%87"）。
     */
    fun searchQueryOf(url: String?): String? {
        val prefix = "$SEARCH_URL?"
        val u = url?.trim().orEmpty()
        if (!u.startsWith(prefix)) return null
        val raw = u.removePrefix(prefix)
            .split('&')
            .firstOrNull { it.startsWith("q=") }
            ?.removePrefix("q=")
            ?: return null
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull()
        return decoded?.takeIf { it.isNotBlank() }
    }

    /**
     * 生成主页 HTML。
     *
     * 背景恒定 [TRANSPARENT_BG]（不再由调用方传）：页面背景由原生背景层供色，
     * 见 [TRANSPARENT_BG] 与 [WebTabManager.loadHome] 的说明。
     *
     * @param onBackground        正文颜色
     * @param primaryContainer    搜索框底色
     * @param onPrimaryContainer  搜索框上的文字颜色
     * @param dark                是否深色模式
     * @param clock               初始时间文本（`HH:mm`），之后由 [clockJs] 原地刷新
     */
    fun html(
        onBackground: String,
        primaryContainer: String,
        onPrimaryContainer: String,
        dark: Boolean,
        clock: String = "",
    ): String {
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
    /* 背景恒透明：由原生层在 WebView 之下供色，见 TRANSPARENT_BG */
    --bg: $TRANSPARENT_BG;
    --fg: $onBackground;
    --pc: $primaryContainer;
    --on-pc: $onPrimaryContainer;
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  /* html 占满视口，body 只给 min-height、**不**固定 height：
     内容比视口高时（横屏 / 分屏 / 小屏 + 大号时钟），height:100% 会把
     body 固死在视口高度，flex 的 justify-content:center 就把溢出的那部分
     顶到滚动区**上方** —— 滚不上去，时钟和搜索框直接被裁掉看不到。
     用 min-height 让 body 随内容长高：装得下才居中，装不下就从头排、正常滚动。 */
  html { height: 100%; }
  html, body {
    margin: 0; padding: 0;
    background: var(--bg); color: var(--fg);
    font-family: -apple-system, "Noto Sans SC", "PingFang SC",
                 "Microsoft YaHei", Roboto, sans-serif;
  }
  /* 垂直居中：键盘弹出时 WebView 视口被原生侧的 imePadding 压矮，
     flex 居中会让时钟与搜索框一起上移，正好实现"搜索框跟随输入法"。 */
  body {
    min-height: 100%;
    display: flex; flex-direction: column;
    align-items: center; justify-content: center;
    padding: 32px 24px calc(32px + env(safe-area-inset-bottom));
    text-align: center;
  }
  .clock {
    font-size: 64px; font-weight: 300; line-height: 1;
    letter-spacing: 2px; color: var(--fg);
    /* 等宽数字：分钟跳动时各位不会左右抖 */
    font-variant-numeric: tabular-nums;
  }
  /* 搜索框：提交到内部地址 ling://search?q=…（见 HomePage.SEARCH_URL），
     由原生用同一套 UrlUtils 判定「网址还是搜索词」。 */
  .search { width: 100%; max-width: 320px; margin-top: 28px; }
  .search input {
    width: 100%; padding: 12px 16px;
    border: none; border-radius: 24px;
    background: var(--pc); color: var(--on-pc);
    font-size: 14px; font-family: inherit; text-align: center;
    -webkit-appearance: none; appearance: none;
  }
  .search input:focus { outline: none; }
  .search input::placeholder { color: var(--on-pc); opacity: .6; }
</style>
</head>
<body>
  <div class="clock" id="$CLOCK_ID">$clock</div>
  <!--
    主页搜索框。提交到内部地址 ling://search?q=…，
    由原生侧用 UrlUtils 判定「网址还是搜索词」（与地址栏同一套逻辑，不在网页里再写一份）。
    隐藏的 submit 按钮是为了保证回车一定触发提交。
  -->
  <form class="search" action="$SEARCH_URL" method="get" autocomplete="off">
    <input type="text" name="q" placeholder="搜索或输入网址" enterkeyhint="go">
    <button type="submit" hidden aria-hidden="true"></button>
  </form>
</body>
</html>
""".trimIndent()
    }

    /**
     * 生成"原地刷新主页主题"的 JS。
     *
     * 为什么需要这个：主页是用 `loadDataWithBaseURL` 渲染的，每次调用都会
     * 往 WebView 历史里**压入一个新条目**。若主题/背景变化时也走完整重渲染，
     * 用户按返回键就会回到"切换前那个浅色主页"（历史里残留的旧条目）。
     *
     * 原地刷新直接改 `:root` 上的 CSS 变量，不触发导航、不压历史，
     * 深浅色切换即时生效且返回键行为不变。
     *
     * 这一份脚本**也被阅读视图复用**（[WebTabManager.readerThemeJs]），
     * 因此 `--primary` / `--on-primary` 仍然要写入：主页 HTML 已不再使用
     * 这两个变量，但阅读视图的标题等元素还在用。
     */
    internal fun themeUpdateJs(
        background: String,
        onBackground: String,
        primary: String,
        onPrimary: String,
        primaryContainer: String,
        onPrimaryContainer: String,
        dark: Boolean,
    ): String {
        val scheme = if (dark) "dark" else "light"
        return """
(function () {
  var r = document.documentElement;
  if (!r) return;
  r.style.setProperty('--bg', '$background');
  r.style.setProperty('--fg', '$onBackground');
  r.style.setProperty('--primary', '$primary');
  r.style.setProperty('--on-primary', '$onPrimary');
  r.style.setProperty('--pc', '$primaryContainer');
  r.style.setProperty('--on-pc', '$onPrimaryContainer');
  r.style.colorScheme = '$scheme';
})();
        """.trimIndent()
    }

    /**
     * 生成"原地刷新时钟"的 JS。
     *
     * 主页是零脚本 HTML，时间只能由原生侧交回来：`WebTabManager` 每分钟
     * 把当前 `HH:mm` 传进来，这里只改那一个文本节点 —— 不碰 DOM 结构、
     * 不触发导航、不压历史。
     *
     * [clock] 由原生侧用固定格式生成（`HH:mm`，纯数字与冒号），不含引号等
     * 会破坏 JS 字符串字面量的字符，因此直接内插即可。
     */
    internal fun clockJs(clock: String): String = """
(function () {
  var el = document.getElementById('$CLOCK_ID');
  if (el) el.textContent = '$clock';
})();
    """.trimIndent()
}
