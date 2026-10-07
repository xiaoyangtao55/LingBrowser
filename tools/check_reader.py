"""从 ReaderExtractorJs.kt 抽出 JS，用 Node 做语法检查与算法自测。

为什么需要这个脚本：JS 是**字符串常量**嵌在 Kotlin 里的，Kotlin 编译器
完全不检查它 —— 写错一个括号、少一个分号，编译照样通过，只有真机跑到
那一步才会炸。而"跑到那一步"意味着用户点开阅读模式看到白屏。

所以这里做三件事：
  1. 抽出 JS 源码
  2. `node --check` 做语法校验（括号/引号/分号）
  3. 用内置的迷你 DOM 桩跑一遍**真实算法**，验证它能从一段
     典型网页里挑出正文、并剔除导航与评论区

第 3 步是重点：只做语法检查的话，算法写错了（比如打分公式反了）
一样检查不出来。
"""
import os
import re
import subprocess
import sys
import json
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
KT = os.path.join(ROOT, "app", "src", "main", "java", "com", "ling", "browser",
                  "web", "ReaderExtractorJs.kt")

problems = []


def bad(m):
    problems.append(m)
    print("  FAIL " + m)


def ok(m):
    print("  OK   " + m)


def find_node():
    for p in (r"C:\Program Files\nodejs\node.exe", "node"):
        try:
            subprocess.run([p, "--version"], capture_output=True, check=True)
            return p
        except Exception:
            continue
    return None


def extract_js(src, name):
    """取出 val NAME: String = ... 的内容，支持原始字符串与普通字符串两种写法。

    先试三引号（原始字符串），再退回单行双引号 —— CLEAR_FLAG 用的是后者的
    写法，只匹配三引号会漏掉它（踩过一次）。
    """
    m = re.search(
        r"val\s+" + name + r"\s*:\s*String\s*=\s*\"\"\"(.*?)\"\"\"",
        src, re.S,
    )
    if m:
        return m.group(1)
    m = re.search(r"val\s+" + name + r"\s*:\s*String\s*=\s*\"([^\"]*)\"", src)
    if m:
        return m.group(1)
    return None


src = open(KT, encoding="utf-8").read()

print("1. 能取出 JS 常量")
script = extract_js(src, "SCRIPT")
clear = extract_js(src, "CLEAR_FLAG")
if script is None:
    bad("取不到 SCRIPT")
    sys.exit(1)
ok("SCRIPT 已取出（%d 字符）" % len(script))
if clear is None:
    bad("取不到 CLEAR_FLAG")
else:
    ok("CLEAR_FLAG 已取出")

print("\n2. Kotlin 字符串转义一致性")
# Kotlin 的 ${...} 会在编译期被当模板求值 —— JS 里绝不能出现裸的 ${
if "${" in script:
    bad("JS 里含 ${...} —— Kotlin 会当作字符串模板求值，导致编译错误或内容被替换")
else:
    ok("不含 ${...}，不会被 Kotlin 当模板求值")
if '"""' in script:
    bad("JS 里含三引号，会提前结束 Kotlin 原始字符串")
else:
    ok("不含三引号")

print("\n3. Node 语法检查")
node = find_node()
if node is None:
    print("  SKIP 未找到 node，跳过（语法错误将无法提前发现）")
else:
    tmp = os.path.join(tempfile.gettempdir(), "ling_reader_check.js")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(script)
    r = subprocess.run([node, "--check", tmp], capture_output=True, text=True)
    if r.returncode == 0:
        ok("JS 语法正确（node --check）")
    else:
        bad("JS 语法错误：" + (r.stderr or "").strip()[:400])

    print("\n4. 算法真实运行（迷你 DOM 桩）")
    # 一个典型文章页：导航 + 正文 + 侧边栏 + 评论区 + 页脚
    #
    # ⚠️ 桩必须给**每个**节点都装上完整的方法（getElementsByTagName /
    # removeChild / getAttribute …）。早先的版本只在最后才补这些方法，
    # 于是早建出来的节点缺 removeChild，算法一调就抛 TypeError ——
    # 而那会被脚本自己的 try/catch 吞成 {status:'error'}，
    # 看起来像"算法没提取到正文"，实际是**桩的问题**。
    harness = r"""
function mkNode(tag, cls, text) {
  var n = {
    tagName: String(tag).toUpperCase(), className: cls || '', id: '',
    // nodeType 必须是 1（元素节点）：算法用 `nodeType !== 1` 过滤文本节点，
    // 漏了这个属性会让 addScore 全部提前返回，表现为"一个候选都没有"。
    nodeType: 1,
    childNodes: [], parentNode: null, attrs: {},
    textContent: text || '', innerHTML: text || ''
  };
  n.getElementsByTagName = function (t) {
    t = String(t).toUpperCase();
    var out = [];
    (function walk(m) {
      if (m.tagName === t) out.push(m);
      (m.childNodes || []).forEach(walk);
    })(n);
    return out;
  };
  n.getAttribute = function (k) { return (k in n.attrs) ? n.attrs[k] : null; };
  n.setAttribute = function (k, v) { n.attrs[k] = String(v); };
  n.removeAttribute = function (k) { delete n.attrs[k]; };
  n.removeChild = function (c) {
    var i = n.childNodes.indexOf(c);
    if (i >= 0) { n.childNodes.splice(i, 1); c.parentNode = null; }
    return c;
  };
  n.appendChild = function (c) { c.parentNode = n; n.childNodes.push(c); return c; };
  // 真实的 innerHTML 会**序列化子树**；桩里如果只存文本，
  // 算法最后取 article.innerHTML 就永远是空的，
  // 会让"提取成功但 html 为空"看起来像 bug（踩过）。
  // 叶子节点要把自身的文本吐出来，否则序列化结果只有空标签。
  n.serialize = function () {
    if (n.childNodes.length === 0) {
      return (n.rawHtml !== undefined) ? n.rawHtml : (n.textContent || '');
    }
    return n.childNodes.map(function (c) {
      if (c.nodeType === 3) return c.textContent;
      return '<' + c.tagName.toLowerCase() + '>' + c.serialize() + '</' + c.tagName.toLowerCase() + '>';
    }).join('');
  };
  Object.defineProperty(n, 'innerHTML', {
    get: function () { return n.serialize(); },
    set: function (v) { n.rawHtml = v; },
  });
  return n;
}

var N = mkNode;

function append(p, c) {
  c.parentNode = p;
  p.childNodes.push(c);
  // textContent 向上冒泡（算法靠它算长度与链接密度）
  var t = p;
  while (t) { t.textContent = (t.textContent || '') + (c.textContent || ''); t = t.parentNode; }
  return c;
}

function byTag(root, tag) {
  tag = String(tag).toUpperCase();
  var out = [];
  (function walk(n) {
    if (tag === '*' || n.tagName === tag) out.push(n);
    (n.childNodes || []).forEach(walk);
  })(root);
  return out;
}

var html = N('html'), body = N('body'), head = N('head');
append(html, head);
append(html, body);
function mk(tag, cls, text) { return append(body, N(tag, cls, text)); }

// —— 典型**中文**文章页 ——
//
// 段落长度刻意控制在 60~90 字：这是中文技术博客的常见段落长度。
// 它**低于** Readability 原始的英文阈值 100，却远高于中文语境的合理下限。
// 这样设计是为了让"是否做了 CJK 加权"真正影响结果 ——
// 段落如果都写到 150 字以上，去掉加权也照样通过，检查就形同虚设（踩过）。
var nav  = mk('nav', 'site-nav', '首页 分类 关于 联系');
var art  = mk('article', 'post-content', '');
append(art, N('h1', '', '这是一篇测试文章'));
append(art, N('p', '',
  '这是第一段正文，它需要足够长才能被算法认为是正文内容，因此这里多写一些字，'
  + '并且包含逗号、句号等标点，让打分公式能够给出较高的分数。'));
append(art, N('p', '',
  '这是第二段正文，同样需要足够的长度。阅读模式的核心目标，'
  + '是把广告、导航、评论区剔除掉，只留下真正值得阅读的内容，'
  + '这也是为什么需要按段落打分而不是随便选一个 div。'));
append(art, N('p', '',
  '第三段继续补充内容，让段落数量达到算法认为足够的标准，'
  + '三段以上且每段都超过一定长度，才能稳定通过可读性判断。'));
// 这两个块**故意用 div 而不是 nav/footer**：div 不在 junkTags 里，
// 只能靠 class/id 语义正则（UNLIKELY）淘汰。若去掉语义过滤，
// 它们就会被当成候选并可能污染正文 —— 这样那一项检查才有意义。
var side = mk('div', 'sidebar-related', '相关阅读：一篇文章 另一篇文章 第三篇文章');
var com  = mk('div', 'comments', '评论区：用户甲说好，用户乙说不错，用户丙说一般');
var foot = mk('div', 'site-footer', '版权所有 备案号 联系方式');

var document = {
  title: '测试文章 - 示例站点',
  body: body,
  documentElement: html,
  getElementsByTagName: function (t) { return byTag(html, t); },
  getElementById: function (id) {
    var all = byTag(html, '*');
    for (var i = 0; i < all.length; i++) if (all[i].id === id) return all[i];
    return null;
  }
};
var window = {};
var location = { href: 'https://example.com/post/1' };

var RESULT = eval(__SCRIPT__);
console.log('__RESULT__' + RESULT);
"""
    harness = harness.replace("__SCRIPT__", json.dumps(script))
    tmp2 = os.path.join(tempfile.gettempdir(), "ling_reader_harness.js")
    with open(tmp2, "w", encoding="utf-8") as f:
        f.write(harness)
    r2 = subprocess.run([node, tmp2], capture_output=True, text=True)
    out = (r2.stdout or "").strip()
    if "__RESULT__" not in out:
        bad("算法运行失败（无返回值）: " + (r2.stderr or out).strip()[:500])
    else:
        payload = out.split("__RESULT__", 1)[1]
        try:
            res = json.loads(payload)
        except Exception as e:
            bad("返回值不是合法 JSON: %s / %r" % (e, payload[:200]))
            res = None
        if res:
            if res.get("status") == "ok":
                ok("成功提取正文（%d 字）" % res.get("textLength", 0))
            else:
                bad("未能提取正文: %s" % res.get("reason"))
            # 正文里不能出现导航/评论/侧栏
            h = res.get("html", "")
            for word, label in (("分类", "导航"), ("用户甲", "评论"),
                                ("另一篇文章", "侧边栏")):
                if word in h:
                    bad("正文里残留了%s内容（'%s'）" % (label, word))
                else:
                    ok("已剔除%s内容" % label)
            if "第一段正文" in h and "第二段正文" in h:
                ok("保留了全部正文段落")
            else:
                bad("正文段落丢失")

        # ---- 原文地址（canonical）----
        #
        # 这一段对应真机报错 net::ERR_UNKNOWN_URL_SCHEME：
        # 知乎用 zhihu://answers/... 做内部跳转，location.href 就是那个
        # 打不开的地址。提取脚本必须优先给出真实的 https 原文地址。
        # 注意：要**真的换掉 location.href 重跑**才算验证，只查源码
        # 特征无法证明解析逻辑正确。
        print("\n4b. 原文地址解析（真机 ERR_UNKNOWN_URL_SCHEME 的修复）")

        def run_with(location_href, canonical="", og_url="", base_uri=""):
            """换掉 location / canonical / og:url 后重跑，取回提取结果里的 url 字段。"""
            h2 = harness
            h2 = h2.replace(
                "var location = { href: 'https://example.com/post/1' };",
                "var location = { href: %s };" % json.dumps(location_href),
            )
            # document.baseURI 模拟"文档的真实来源"。知乎这类壳里
            # location.href 是 zhihu://...，但文档其实由 https 站点提供，
            # 相对 canonical 必须相对于这个 https 地址解析。
            h2 = h2.replace(
                "  getElementById: function (id) {",
                "  baseURI: %s,\n  getElementById: function (id) {" % json.dumps(base_uri),
            )
            # 用既有的 setAttribute 往 head 里塞 link/meta。
            # 插在 `append(html, head);` 之后，此时 head 已经挂在 html 上，
            # getElementsByTagName 能沿树找到它们。
            extra = ""
            if canonical:
                extra += ("var _lk = N('LINK', ''); _lk.setAttribute('rel','canonical');"
                          " _lk.setAttribute('href', %s); append(head, _lk);\n"
                          % json.dumps(canonical))
            if og_url:
                extra += ("var _mt = N('META', ''); _mt.setAttribute('property','og:url');"
                          " _mt.setAttribute('content', %s); append(head, _mt);\n"
                          % json.dumps(og_url))
            if extra:
                h2 = h2.replace("append(html, head);", "append(html, head);\n" + extra, 1)
            h2 = h2.replace("__SCRIPT__", json.dumps(script))
            p2 = os.path.join(tempfile.gettempdir(), "ling_reader_canon.js")
            with open(p2, "w", encoding="utf-8") as f:
                f.write(h2)
            rr = subprocess.run([node, p2], capture_output=True, text=True)
            o = (rr.stdout or "").strip()
            if "__RESULT__" not in o:
                return None
            try:
                return json.loads(o.split("__RESULT__", 1)[1]).get("url")
            except Exception:
                return None

        canonical_url = "https://www.zhihu.com/question/1/answer/2090101772673085808"
        evil = "zhihu://answers/2090101772673085808?mcid=c58f0c59&callback_source=search_main"

        got = run_with(evil, canonical=canonical_url)
        if got == canonical_url:
            ok("有 canonical 时，自定义协议被替换成真实 https 原文地址")
        else:
            bad("canonical 未被采用：期望 %s，实际 %r" % (canonical_url, got))

        got = run_with(evil, og_url=canonical_url)
        if got == canonical_url:
            ok("无 canonical 时退回 og:url")
        else:
            bad("og:url 未被采用：实际 %r" % (got,))

        got = run_with(evil, canonical="/post/123",
                       base_uri="https://www.zhihu.com/question/1")
        if got == "https://www.zhihu.com/post/123":
            ok("相对 canonical 按文档真实来源解析成绝对地址")
        else:
            bad("相对 canonical 解析错误：实际 %r" % (got,))

        got = run_with(evil, canonical="foo://bar")
        if got == evil:
            ok("畸形 canonical（自定义协议）不会顶掉可用的 location.href")
        else:
            bad("畸形 canonical 覆盖了 location.href：实际 %r" % (got,))

        got = run_with(evil)
        if got == evil:
            ok("无 canonical 时保留 location.href（原生侧还有兜底）")
        else:
            bad("无 canonical 时地址异常：实际 %r" % (got,))

        got = run_with("https://example.com/plain?utm=1")
        if got == "https://example.com/plain?utm=1":
            ok("普通 https 地址原样保留")
        else:
            bad("普通地址被改动：实际 %r" % (got,))

    print("\n5. 关键常量与语义过滤（防止改坏而测试仍过）")
    # ⚠️ 第 4 节的页面是"理想页面"，把 CJK 加权去掉后它**照样**能通过 ——
    # 于是那项检查抓不到这个回归（实测确认）。这里改成直接检查源码特征，
    # 因为"阈值是否按中文折算"无法从一次成功提取里反推出来。
    if "weightedLength" in script and "\\u4e00-\\u9fff" in script:
        ok("段落长度按 CJK 折算（英文阈值 100 会误杀中文短文章）")
    else:
        bad("缺少 CJK 长度折算 —— 中文文章会被判成'没有正文'")

    if re.search(r"weightedLength\(inner\)\s*<", script):
        ok("段落最短阈值用的是加权长度")
    else:
        bad("段落阈值未用加权长度")

    # 标点正则必须包含中文标点，否则中文段落分数极低
    if re.search(r"match\(/\[[^\]]*，[^\]]*\]/g\)", script):
        ok("打分用的标点正则包含中文标点（否则中文段落逗号数为 0）")
    else:
        bad("标点正则缺中文标点 —— 中文段落会被低估")

    if "weightedLength(article.textContent) < 200" in script:
        ok("最终长度校验也用加权长度")
    else:
        bad("最终长度校验未用加权长度")

    # 语义淘汰：UNLIKELY 与 MAYBE 同时命中时要保留（"article-comment" 这类）
    if re.search(r"UNLIKELY\.test\(cid\)\s*&&\s*!MAYBE\.test\(cid\)", script):
        ok("语义淘汰同时考虑 UNLIKELY/MAYBE（避免误删 article-comment）")
    else:
        bad("语义淘汰条件不完整，可能误删正文容器")

    # 必须真的从 DOM 里移除垃圾节点，而不是只打标记
    if re.search(r"removeChild\(all\[q\]\)", script):
        ok("被判定为垃圾的节点真的从 DOM 移除")
    else:
        bad("垃圾节点未从 DOM 移除")

    # 链接密度两处阈值
    if "linkDensity(para) > 0.25" in script or "ld > 0.25" in script:
        ok("段落链接密度阈值 0.25")
    else:
        bad("缺少段落链接密度过滤")

    if re.search(r"linkDensity\(cand\.node\)\s*>\s*0\.33", script):
        ok("容器链接密度阈值 0.33（整块都是链接的不算正文）")
    else:
        bad("缺少容器链接密度过滤")

    # 图片懒加载属性提升
    for attr in ("data-src", "data-original"):
        if attr in script:
            ok("提升懒加载属性 %s 为 src" % attr)
            break
    else:
        bad("未处理图片懒加载 —— 阅读视图里图片会是占位图")

    # 原文地址必须优先 canonical，不能直接用 location.href
    #
    # 这是 ERR_UNKNOWN_URL_SCHEME 的根因：App 内嵌页会用自定义协议做跳转
    # （zhihu://answers/123），location.href 就是那个打不开的地址。
    if re.search(r"rel'\)\s*\|\|\s*''\)\.toLowerCase\(\) === 'canonical'", script):
        ok("读取 link[rel=canonical]")
    else:
        bad("未读取 canonical —— 原文地址可能是 custom scheme")

    if "og:url" in script:
        ok("canonical 缺失时退回 og:url")
    else:
        bad("未读 og:url 兜底")

    if re.search(r"url: articleUrl", script):
        ok("返回的 url 用的是净化后的 articleUrl，而不是 location.href 原始值")
    else:
        bad("仍然直接返回 location.href —— 自定义协议会流到原生侧")

    if re.search(r"if \(/\^https\?:/i\.test\(abs\)\) articleUrl = abs", script):
        ok("只采纳能解析成 http(s) 的 canonical（畸形值不会覆盖）")
    else:
        bad("未校验 canonical 的协议，畸形 canonical 会顶掉可用的 location.href")

    if re.search(r"new URL\(canonical, base\)", script):
        ok("canonical 按相对地址解析成绝对地址")
    else:
        bad("canonical 未解析成绝对地址，相对路径会导致原文链接失效")

    # 相对 canonical 的 base 必须先净化：直接用 location.href 时，
    # zhihu://answers/1 + /post/2 会解析成 zhihu://answers/post/2 ——
    # 仍是自定义协议，等于白读了 canonical。
    if re.search(r"document\.baseURI", script) and re.search(r"!?/\^https\?:/i\.test\(base\)", script):
        ok("相对 canonical 的 base 会先净化成 http(s)（否则自定义协议下解析结果仍是自定义协议）")
    else:
        bad("相对 canonical 直接用 location.href 当 base —— 自定义协议壳里解析结果仍不可用")

    # 失败必须返回结构化原因，不能抛异常
    if re.search(r"catch \(e\)[\s\S]{0,200}?status: 'error'", script):
        ok("异常转成结构化结果（抛出去只会得到 null，原生侧无从判断）")
    else:
        bad("异常未兜底 —— evaluateJavascript 会拿到 null")

    # 已提取标记
    if "__lingReaderDone" in script and "__lingReaderDone" in (clear or ""):
        ok("重复提取有标记保护，且可通过 CLEAR_FLAG 复位")
    else:
        bad("缺少重复提取保护或复位标记")

# ---------- 6. 接线完整 ----------
print("\n6. 功能接线（缺一环就点不出效果）")
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "ling", "browser")


def read(rel):
    return open(os.path.join(SRC, rel), encoding="utf-8").read()


mgr = read("web/WebTabManager.kt")
vm = read("ui/BrowserViewModel.kt")
screen = read("ui/screens/BrowserScreen.kt")
settings_kt = read("data/prefs/SettingsStore.kt")
activity = read("MainActivity.kt")
settings_ui = read("ui/screens/SettingsScreen.kt")
page = read("web/ReaderPage.kt")
lwv = read("web/LingWebView.kt")

if "ReaderExtractorJs.SCRIPT" in mgr and "evaluateJavascript" in mgr:
    ok("WebTabManager 真的执行了提取脚本")
else:
    bad("提取脚本没有被执行 —— 阅读模式会永远拿不到正文")

if re.search(r"fun enterReaderMode[\s\S]{0,400}?HomePage\.isHomeUrl", mgr):
    ok("对主页拒绝进入阅读模式（否则会递归提取）")
else:
    bad("未拦截主页，读阅模式可能在主页上递归")

if re.search(r"fun enterReaderMode[\s\S]{0,900}?javaScriptEnabled", mgr):
    ok("JS 被关闭时给出明确原因（用户能自己去设置里打开）")
else:
    bad("未处理用户关闭 JavaScript 的情况")

if "readerContent" in mgr and re.search(r"private val readerContent", mgr):
    ok("缓存已提取正文（换字号要重排版，不能重新提取）")
else:
    bad("未缓存正文 —— 调字号会得到空页面")

if re.search(r"fun refreshReaderFontSize\(fontSize: ReaderPage\.FontSize\)", mgr):
    ok("重排版接收字号参数，而不是去读异步落盘的 settings")
else:
    bad("重排版直接读 settings —— 设置异步落盘，会表现为'改了没反应'")

if re.search(r"private fun recycleTab[\s\S]{0,300}?readerContent\.remove", mgr):
    ok("关闭标签时清掉正文缓存（否则内存一直涨）")
else:
    bad("关闭标签未清理正文缓存")

if re.search(r"fun exitReaderMode[\s\S]{0,400}?loadUrl\(id, cached\.url\)", mgr):
    ok("退出阅读模式回到原文")
else:
    bad("退出阅读模式没有回到原文")

# ViewModel 侧
if "toggleReaderMode" in vm and "enterReaderMode" in vm and "exitReaderMode" in vm:
    ok("ViewModel 暴露了进入/退出/切换三个入口")
else:
    bad("ViewModel 入口不完整")

# ⚠️ 这一条是实打实踩过的坑：getter 不是 Compose 状态，菜单文案不会刷新
if re.search(r"val readerActive: StateFlow<Boolean>", vm):
    ok("readerActive 是 StateFlow（getter 写法不会触发重组，文案不刷新）")
else:
    bad("readerActive 不是 StateFlow —— 进入阅读模式后菜单文案不会变")

if re.search(r"readerActive[\s\S]{0,400}?stateIn", vm):
    ok("readerActive 用 stateIn 派生")
else:
    bad("readerActive 未派生自可观察状态")

if re.search(r"val readerActive by viewModel\.readerActive\.collectAsStateWithLifecycle\(\)", screen):
    ok("界面 collect 了 readerActive")
else:
    bad("界面未 collect readerActive，文案不会刷新")

if re.search(r"LingIcons\.Article[\s\S]{0,200}?toggleReaderMode\(\)", screen):
    ok("菜单项已接到 toggleReaderMode")
else:
    bad("菜单项未接到 toggleReaderMode（点不出效果）")

# 设置项三件套：字段 / 键 / 解码 / setter
if "readerFontSize" in settings_kt:
    ok("设置里有 readerFontSize 字段")
else:
    bad("缺少 readerFontSize 设置字段")
if "READER_FONT_SIZE" in settings_kt and settings_kt.count("READER_FONT_SIZE") >= 3:
    ok("键、解码、setter 三处都写全了")
else:
    bad("READER_FONT_SIZE 未写全（键/解码/setter 缺项）")

if "onReaderFontSize" in settings_ui and "ReaderFontSize.entries" in settings_ui:
    ok("设置界面能选字号档位")
else:
    bad("设置界面没有字号选项")

if "onReaderFontSize = viewModel::setReaderFontSize" in activity:
    ok("MainActivity 接上了字号设置")
else:
    bad("MainActivity 未接字号设置")

# 字号档位只有一份定义
if "typealias ReaderFontSize" in settings_kt:
    ok("字号档位复用 ReaderPage.FontSize，只有一份定义")
else:
    bad("字号档位重复定义，两边会不同步")

if "Article" in read("ui/theme/LingIcons.kt"):
    ok("Article 图标已生成")
else:
    bad("缺少 Article 图标")

# ---------- 7. 主题与内部页面的四个静默失效点 ----------
# 这四条的共同点是：错了不报错、不崩，只是"看起来没生效"，
# 因此必须靠静态检查盯着。
print("\n7. 主题跟随与内部页面处理")


def body_of(src, pattern, span=1200):
    m = re.search(pattern, src)
    return src[m.start():m.start() + span] if m else ""


# (1) 切主题时要重绘阅读视图，否则停在旧配色
ensure = body_of(mgr, r"fun ensureHomeRendered\(\)")
if "ReaderPage.isReaderUrl" in ensure:
    ok("切主题时阅读视图跟着重新配色（否则要退出重进才更新）")
else:
    bad("ensureHomeRendered 只重绘主页 —— 阅读模式在切换夜间模式后配色不会变")

# (1b) 已渲染的主页要原地刷新（改 CSS 变量），不能重导航 ——
# 重导航（loadDataWithBaseURL）会压入新历史条目，导致"切深色后按返回回到浅色主页"。
# 判据绑定到"调用点"：evaluateJavascript(homeThemeJs() 必须真的出现在
# ensureHomeRendered 里。只查 "homeThemeJs()" 会撞上函数定义（homeThemeJs():），
# 定义在、调用没了也照样误报通过。
if "evaluateJavascript(homeThemeJs()" in ensure:
    ok("主题切换走原地刷新（不压历史，返回键行为不变）")
else:
    bad("主题切换仍走重导航 —— 切深色后按返回会回到浅色主页")

# (2) 阅读视图不能被强制夜间模式的反色 CSS 二次处理
# 判据是 injectDarkMode 位于 `if (!isInternal)` 块内 ——
# 中间可能夹着注释，所以允许跨行但不允许出现右花括号。
if re.search(r"if \(!isInternal\) \{(?:[^{}]|\n)*?wv\.injectDarkMode", mgr):
    ok("强制夜间模式的反色 CSS 不注入内部页面")
else:
    bad("内部页面被注入反色 CSS —— 深色阅读视图会被反成亮底白字")

if re.search(r"val isInternal = HomePage\.isHomeUrl\(url\) \|\| ReaderPage\.isReaderUrl\(url\)", mgr):
    ok("用统一的 isInternal 判定内部页面（主页 + 阅读视图）")
else:
    bad("未统一判定内部页面，容易出现只处理主页、漏掉阅读视图的情况")

# (3) 回调不能把逻辑地址改写成 baseUrl，也不能记下自定义协议
#
# 判据是"url 回写被 keep 分支挡住"。变量名会随实现调整，
# 所以不绑定具体写法，而是检查**行为**：两处回调（onPageStarted /
# onPageFinished）都保留 `keep -> it.url` 分支，且主页显式回写逻辑地址。
if mgr.count("keep -> it.url") >= 2:
    ok("onPageStarted/onPageFinished 都不会覆盖掉逻辑地址")
else:
    bad("有回调会把阅读视图的逻辑地址改写掉，导致返回栈与识别错乱")

# 回到主页（前进/后退触发）必须显式回写 HomePage.URL，否则旧网站 URL 残留在地址栏
if mgr.count("isHome -> HomePage.URL") >= 2:
    ok("回到主页时显式回写 ling://home（地址栏不残留旧网址）")
else:
    bad("回到主页未回写逻辑地址 —— 地址栏会残留上一个网页的 URL")

if re.search(r"val keep = isInternal \|\| !isNavigable\(url\)", mgr):
    ok("自定义协议（zhihu:// 等）不写回 TabState.url")
else:
    bad("自定义协议会写进 TabState.url —— 地址栏显示打不开的地址，退出阅读模式时会回填给 WebView")

# 自定义协议绝对不能交给 WebView，否则就是 ERR_UNKNOWN_URL_SCHEME 白屏。
# 判定逻辑本身在 UrlScheme.kt（可单测），这里检查**调用点是否真的拦了**。
if re.search(r"fun loadUrl\(id: String, url: String\)[\s\S]{0,900}?!isNavigable\(url\)", mgr):
    ok("loadUrl 入口拒绝自定义协议")
else:
    bad("loadUrl 缺少协议检查 —— 自定义协议会导致 ERR_UNKNOWN_URL_SCHEME")

if "UrlScheme.isNavigable" in mgr:
    ok("协议判定委托给 UrlScheme（可单测，见 UrlSchemeTest）")
else:
    bad("协议判定未抽到可测试的位置")

# ---------- 8. shouldOverrideUrlLoading 的返回值语义 ----------
#
# 真机现象"知乎回答页一直闪"的根因就在这里：
# 把 onExternalScheme 的返回值直接当 handleUri 的结果返回。
# 系统没有能处理该协议的应用时它是 false，等于告诉 WebView
# "你来加载" —— WebView 不认识 zhihu://，渲染 ERR_UNKNOWN_URL_SCHEME，
# 页面反复发起该导航就一直在"尝试加载 → 报错"之间循环。
print("\n8. 外部协议接管（'一直闪'的根因）")

client = read("web/LingWebViewClient.kt")

# 绝不能出现 `else -> onExternalScheme(uri)` 这种直接返回外部结果的写法
if re.search(r"else\s*->\s*onExternalScheme\(uri\)", client):
    bad("handleUri 直接返回外部处理结果 —— 失败时会放行给 WebView，报 ERR_UNKNOWN_URL_SCHEME")
else:
    ok("handleUri 没有直接返回外部处理结果")

if re.search(r"if \(openExternal\(\)\) \{\s*\n\s*onExternalScheme\(uri\)\s*\n\s*\}\s*\n\s*return true", client):
    ok("外部协议一律接管（开关关掉时也返回 true，不放行给 WebView）")
else:
    bad("外部协议未无条件接管 —— 打不开时会白屏或一直闪")

if re.search(r"if \(!UrlScheme\.shouldTakeOver\(uri\.toString\(\)\)\) return false", client):
    ok("接管判定走 UrlScheme.shouldTakeOver（可单测）")
else:
    bad("接管判定没走 UrlScheme，无法单测")

# ⚠️ 必须先剥掉 // 注释再找：源码里那句 `// intent:// 必须走 Intent.parseUri…`
# 注释本身就含这个字符串，直接 in 判断会被注释骗过 ——
# 把真正的调用删掉、只留注释，检查照样通过（踩过）。
screen_code = re.sub(r"//[^\n]*", "", screen)
if re.search(r"Intent\.parseUri\(uri\.toString\(\), 0\)", screen_code):
    ok("intent:// 走 Intent.parseUri（ACTION_VIEW 包 intent: 地址没有应用能接）")
else:
    bad("intent:// 未特殊处理，会静默失败（点了没反应）")

# 未加载完就提取会拿到半成品 DOM
if re.search(r"fun enterReaderMode[\s\S]{0,2200}?tab\.isLoading \|\| wv\.progress < 100", mgr):
    ok("页面未加载完时拒绝进入阅读模式（否则提取到半成品 DOM）")
else:
    bad("未检查加载状态 —— 加载中进阅读模式会拿到残缺正文，且 canonical 还没解析到")

if "page_loading" in vm:
    ok("'页面还在加载' 有对用户可见的提示文案")
else:
    bad("加载中进入阅读模式没有提示，用户不知道为什么没反应")

# 异步提取回来时用户可能已经切走
if re.search(r"if \(_activeId\.value != id\) return@evaluateJavascript", mgr):
    ok("提取回调校验标签未切换（否则会把 A 页正文渲染到 B 页）")
else:
    bad("提取回调未校验标签，切页后可能把旧正文渲染到新页面")

# ---------- 9. 外部 App 开关 ----------
#
# 真机上"交给系统"这条路在本机没装对应 App 时只会失败，且失败后
# 会搅乱 WebView 导航状态。所以加了个默认关的开关绕开它。
print("\n9. 外部 App 打开链接（默认关）")


if re.search(r"val openLinksInExternalApp: Boolean = false", settings_kt):
    ok("设置项默认关")
else:
    bad("缺少 openLinksInExternalApp 设置项，或默认值不是 false")

# 三处都要有，且 setter 必须真的存在（只数 OPEN_LINKS_EXTERNAL 出现次数
# 会被"键和解码都在、setter 被改名/删掉"骗过 —— 踩过）。
has_key = re.search(r'val OPEN_LINKS_EXTERNAL = booleanPreferencesKey\("open_links_external"\)', settings_kt)
has_decode = re.search(r"openLinksInExternalApp = p\[Keys\.OPEN_LINKS_EXTERNAL\] \?\: false", settings_kt)
has_setter = re.search(r"suspend fun setOpenLinksInExternalApp\(enabled: Boolean\)", settings_kt)
if has_key and has_decode and has_setter:
    ok("键、解码、setter 三处都写全了")
else:
    missing = [n for n, ok_ in (("键", has_key), ("解码", has_decode), ("setter", has_setter)) if not ok_]
    bad("OPEN_LINKS_EXTERNAL 缺项：" + "、".join(missing))

# 客户端必须真的用开关把转发包起来，而不是"加了开关但照样转发"
if re.search(r"if \(openExternal\(\)\) \{\s*\n\s*onExternalScheme\(uri\)", client):
    ok("转发被开关真正包住（关掉时连 onExternalScheme 都不调用）")
else:
    bad("开关没包住转发 —— 关掉了还是会把链接交给系统")

# 必须用 lambda 传，不能传值：WebViewClient 是建 WebView 时构造的
if re.search(r"openExternal = \{ this@WebTabManager\.settings\.openLinksInExternalApp \}", mgr):
    ok("用 lambda 传递开关（传值会变成'改完要重启才生效'）")
else:
    bad("开关按值传递，改完不重启不生效")

# 无论开关状态，都必须返回 true（否则退回 ERR_UNKNOWN_URL_SCHEME 一直闪）
if re.search(r"if \(openExternal\(\)\) \{[\s\S]{0,200}?\}\s*\n\s*return true", client):
    ok("开关关掉时依然返回 true（不会退回 ERR_UNKNOWN_URL_SCHEME）")
else:
    bad("开关关掉时未返回 true —— 会退回 ERR_UNKNOWN_URL_SCHEME 一直闪")

if "onOpenLinksExternal" in settings_ui and "onOpenLinksExternal = viewModel::setOpenLinksInExternalApp" in activity:
    ok("设置界面与 MainActivity 都接上了")
else:
    bad("设置界面或 MainActivity 未接上开关")

if re.search(r"fun exitReaderMode\(\)[\s\S]{0,900}?isNavigable\(cached\.url\)", mgr):
    ok("退出阅读模式前先校验原文地址可加载性")
else:
    bad("退出阅读模式直接 loadUrl —— 自定义协议会让用户卡在阅读视图")

if re.search(r"fun exitReaderMode\(\)[\s\S]{0,1100}?canGoBack\(\)[\s\S]{0,300}?loadHome", mgr):
    ok("原文地址不可加载时有回退路径（历史 → 主页），不会把用户困住")
else:
    bad("原文地址不可加载时没有回退，用户会卡在阅读视图")

# 逐个回调独立校验：每个回调块内都必须算出 isInternal。
#
# 这里按"从回调名起、到下一个回调名（或 webViewClient 块结束）为止"切片，
# 而不是去找 `\n                },` 这种缩进敏感的行尾 —— 回调体长短不一，
# 用固定长度窗口会漏掉较长的 onPageFinished，导致**永远误报失败**（踩过）。
for cb, nxt in (("onPageStarted", "onPageFinished"),
                ("onPageFinished", "onReceivedTitle")):
    start = mgr.find(cb + " = {")
    end = mgr.find(nxt, start + 1) if start >= 0 else -1
    blk = mgr[start:end] if start >= 0 and end > start else ""
    if "ReaderPage.isReaderUrl(url)" in blk and "isNavigable(url)" in blk:
        ok(f"{cb} 同时挡住内部页面与自定义协议")
    else:
        bad(f"{cb} 的判定不完整 —— 会把 ling://reader 或 zhihu:// 写回状态")

# (4) 合成地址不写入历史
if re.search(r"onVisited\?\.invoke[\s\S]{0,500}?合成", mgr):
    ok("合成地址（ling://reader）不写入历史记录")
else:
    bad("未说明合成地址是否写入历史 —— ling://reader 记进去点不开")

# (5) 主题确实传到了阅读视图
if re.search(r"private fun readerHtml[\s\S]{0,900}?dark = homeColors\.dark", mgr):
    ok("阅读视图的明暗由 homeColors.dark 驱动（跟随夜间模式与动态取色）")
else:
    bad("阅读视图未接入主题明暗")

if re.search(r"dark = colorScheme\.background\.luminance\(\) < 0\.5f", screen):
    ok("dark 由实际背景亮度推导，而不是只读设置项")
else:
    bad("dark 未按亮度推导，动态取色/跟随系统时可能判断错")

# ---------- 10. 广告拦截 ----------
#
# 广告拦截的入口在 LingWebViewClient.shouldInterceptRequest，
# 判定逻辑抽到 AdBlocker（纯 Kotlin 可单测）。这里静态校验三件事：
# 主文档保护、开关动态生效、判定委托给 AdBlocker。
print("\n10. 广告拦截")

ab = read("web/AdBlocker.kt")
client = read("web/LingWebViewClient.kt")

# 主文档必须永不拦截：否则用户点广告落地页会整页白屏
if re.search(r"if \(isMainFrame\) return false", ab):
    ok("主文档永不拦截（否则广告落地页白屏）")
else:
    bad("AdBlocker 未保护主文档 —— 点广告落地页会白屏")

# 判定委托给 AdBlocker.shouldBlock，而不是在 client 里内联规则
if re.search(r"AdBlocker\.shouldBlock\(url, request\.isForMainFrame", client):
    ok("拦截判定委托给 AdBlocker.shouldBlock（可单测）")
else:
    bad("shouldInterceptRequest 未委托 AdBlocker —— 规则无法单测")

# 开关动态生效：必须用 lambda 现读设置，不能传值（改完要重启才生效）
if re.search(r"adBlockEnabled = \{ this@WebTabManager\.settings\.adBlockEnabled \}", mgr):
    ok("广告拦截开关用 lambda 传递（动态生效，无需重启）")
else:
    bad("广告拦截开关按值传递 —— 改完不重启不生效")

# 设置三件套：字段 / 键 / 解码 / setter
has_field = "val adBlockEnabled: Boolean = true" in settings_kt
has_key = 'val AD_BLOCK = booleanPreferencesKey("ad_block")' in settings_kt
has_decode = "adBlockEnabled = p[Keys.AD_BLOCK] ?: true" in settings_kt
has_setter = "suspend fun setAdBlockEnabled(enabled: Boolean)" in settings_kt
if has_field and has_key and has_decode and has_setter:
    ok("adBlockEnabled 设置四件套齐全（字段/键/解码/setter）")
else:
    miss = [n for n, b in (("字段", has_field), ("键", has_key),
                           ("解码", has_decode), ("setter", has_setter)) if not b]
    bad("adBlockEnabled 设置缺项：" + "、".join(miss))

# 设置界面与 MainActivity 接上
if "onAdBlock" in settings_ui and "onAdBlock = viewModel::setAdBlockEnabled" in activity:
    ok("广告拦截设置界面与 MainActivity 都接上了")
else:
    bad("广告拦截设置界面或 MainActivity 未接上")

# 拦截响应必须是空响应（吞掉请求），不能返回一个"看似正常"的假资源
if re.search(r"shouldInterceptRequest", client) and "WebResourceResponse(" in client:
    ok("shouldInterceptRequest 已实现，拦截时返回空响应")
else:
    bad("shouldInterceptRequest 未实现或未返回拦截响应")

# ---------- 10.1 广告拦截：自定义规则 ----------
print("\n10.1 广告拦截自定义规则")

rules_screen = read("ui/screens/AdBlockRulesScreen.kt")

# 自定义规则必须真的传给判定（而不是加了 UI 却没用）
if re.search(r"shouldBlock\(url, request\.isForMainFrame, adBlockRules\(\)\)", client):
    ok("自定义规则传进了判定（否则加了规则也不生效）")
else:
    bad("自定义规则未传进 shouldBlock —— 加了规则等于没加")

if re.search(r"adBlockRules = \{ this@WebTabManager\.settings\.adBlockRules \}", mgr):
    ok("自定义规则用 lambda 传递（动态生效）")
else:
    bad("自定义规则按值传递 —— 改了规则不重启不生效")

# 规则保存前必须清洗（normalizeRule），否则脏数据落库
if re.search(r"normalizeRule\(raw\)", vm):
    ok("新增规则前用 normalizeRule 清洗（去协议/端口/path、转小写）")
else:
    bad("新增规则未清洗 —— 用户粘贴整段 URL 会变成无效规则")

# normalizeRule 本身要拒绝非法域名
if re.search(r"if \(!host\.contains\('\.'\)\) return null", ab):
    ok("normalizeRule 拒绝单段域名（避免误伤 localhost/纯 IP）")
else:
    bad("normalizeRule 未拒绝单段域名")

# 设置三件套：字段 / 键 / 解码 / setter
rf = "val adBlockRules: Set<String> = emptySet()" in settings_kt
rk = 'val AD_BLOCK_RULES = stringSetPreferencesKey("ad_block_rules")' in settings_kt
rd = "adBlockRules = p[Keys.AD_BLOCK_RULES] ?: emptySet()" in settings_kt
rs = "suspend fun setAdBlockRules(rules: Set<String>)" in settings_kt
if rf and rk and rd and rs:
    ok("adBlockRules 设置四件套齐全（字段/键/解码/setter）")
else:
    miss = [n for n, b in (("字段", rf), ("键", rk), ("解码", rd), ("setter", rs)) if not b]
    bad("adBlockRules 设置缺项：" + "、".join(miss))

# 二级页接上：Route + 屏幕 + 入口
if "AdBlockRules" in activity and "AdBlockRulesScreen(" in activity:
    ok("AdBlockRules 二级页在 MainActivity 接上（Route + 屏幕）")
else:
    bad("AdBlockRules 二级页未接入导航")

if "onOpenAdBlockRules" in settings_ui and "自定义规则" in settings_ui:
    ok("设置页有「自定义规则」入口")
else:
    bad("设置页缺少自定义规则入口")

if "fun AdBlockRulesScreen(" in rules_screen and "onAdd" in rules_screen and "onRemove" in rules_screen:
    ok("AdBlockRulesScreen 提供增/删两个能力")
else:
    bad("AdBlockRulesScreen 能力不全（缺增或删）")

# ---------- 10.2 资源嗅探 ----------
print("\n10.2 资源嗅探")

sniffer_kt = read("web/ResourceSnifferJs.kt")
sniff_screen = read("ui/screens/SniffedResourcesScreen.kt")

# JS 常量与执行
if "ResourceSnifferJs.SCRIPT" in mgr and "evaluateJavascript" in mgr:
    ok("WebTabManager 真的执行了嗅探脚本")
else:
    bad("嗅探脚本没有被执行 —— 资源嗅探永远拿不到结果")

# 结果解析委托给 SniffResult（可单测）
if "SniffResult.parse(raw)" in mgr:
    ok("嗅探结果委托给 SniffResult.parse（可单测）")
else:
    bad("嗅探结果未委托 SniffResult —— 无法单测解析逻辑")

# ViewModel 状态与触发
if "_sniffResults" in vm and "fun sniffResources()" in vm:
    ok("ViewModel 有嗅探状态与触发入口")
else:
    bad("ViewModel 缺少嗅探状态或触发入口")

# 菜单入口 + 二级页
# 只查"资源嗅探"字符串不够：注释里也含这个词，会漏掉"菜单项被删"的回归。
# 必须确认菜单项本身（Text("资源嗅探") + 触发嗅探）还在。
if 'text = { Text("资源嗅探") }' in screen and "viewModel.sniffResources()" in screen \
        and "onOpenSniff" in screen:
    ok("浏览器菜单有「资源嗅探」入口（菜单项 + 触发 + 跳转）")
else:
    bad("浏览器菜单缺少资源嗅探入口")

if "Route.SniffedResources" in activity and "SniffedResourcesScreen(" in activity:
    ok("SniffedResources 二级页在 MainActivity 接上（Route + 屏幕）")
else:
    bad("SniffedResources 二级页未接入导航")

# 屏幕能力：打开 + 下载
if "onOpen" in sniff_screen and "onDownload" in sniff_screen:
    ok("SniffedResourcesScreen 提供打开/下载两个能力")
else:
    bad("SniffedResourcesScreen 能力不全（缺打开或下载）")

# ---- 嗅探脚本本身的校验：抽 JS、node --check、DOM 桩运行 ----
sniff_script = extract_js(sniffer_kt, "SCRIPT")
if sniff_script is None:
    bad("取不到 ResourceSnifferJs.SCRIPT")
else:
    if "${" in sniff_script:
        bad("嗅探 JS 里含 ${...} —— 会被 Kotlin 当模板求值")
    else:
        ok("嗅探 JS 不含 ${...}")
    if '"""' in sniff_script:
        bad("嗅探 JS 里含三引号")
    else:
        ok("嗅探 JS 不含三引号")

    if node is None:
        print("  SKIP 未找到 node，跳过嗅探 JS 语法与运行检查")
    else:
        tmp_s = os.path.join(tempfile.gettempdir(), "ling_sniffer_check.js")
        with open(tmp_s, "w", encoding="utf-8") as f:
            f.write(sniff_script)
        r_s = subprocess.run([node, "--check", tmp_s], capture_output=True, text=True)
        if r_s.returncode == 0:
            ok("嗅探 JS 语法正确（node --check）")
        else:
            bad("嗅探 JS 语法错误：" + (r_s.stderr or "").strip()[:400])

        # 迷你 DOM 桩：只需脚本用到的几个方法
        harness_s = r"""
function mkNode(tag, attrs, text) {
  var n = {
    tagName: String(tag).toUpperCase(),
    attrs: attrs || {},
    textContent: text || '',
    width: 0, height: 0, currentSrc: ''
  };
  n.getAttribute = function (k) {
    if (k === 'width') return String(n.width || '');
    if (k === 'height') return String(n.height || '');
    return (k in n.attrs) ? n.attrs[k] : null;
  };
  n.getElementsByTagName = function (t) {
    t = String(t).toUpperCase();
    var out = [];
    (function walk(m) {
      if (m.tagName === t) out.push(m);
      (m.childNodes || []).forEach(walk);
    })(n);
    return out;
  };
  n.childNodes = [];
  return n;
}
function append(p, c) { c.parentNode = p; p.childNodes.push(c); return c; }
function byTag(root, tag) {
  tag = String(tag).toUpperCase();
  var out = [];
  (function walk(n) {
    if (tag === '*' || n.tagName === tag) out.push(n);
    (n.childNodes || []).forEach(walk);
  })(root);
  return out;
}

var html = mkNode('html'), body = mkNode('body');
append(html, body);

var video = mkNode('video', { src: '/media/clip.mp4', title: '演示视频' });
append(body, video);
append(video, mkNode('source', { src: 'https://cdn.example.com/clip_hd.mp4' }));
var audio = mkNode('audio', { src: 'https://cdn.example.com/bgm.mp3' });
append(body, audio);
var img1 = mkNode('img', { src: 'https://cdn.example.com/photo.jpg', alt: '照片' });
img1.width = 800; img1.height = 600;
append(body, img1);
var img2 = mkNode('img', { src: 'https://cdn.example.com/pixel.gif' });
img2.width = 1; img2.height = 1;   // 1x1 占位图，应被跳过
append(body, img2);
var img3 = mkNode('img', { src: 'data:image/gif;base64,AAAA' });  // data: 应跳过
append(body, img3);
var link = mkNode('a', { href: '/files/manual.pdf' }, '使用手册');
append(body, link);

var document = {
  baseURI: 'https://example.com/page',
  getElementsByTagName: function (t) { return byTag(html, t); }
};
var location = { href: 'https://example.com/page' };

var RESULT = eval(__SCRIPT__);
console.log('__SNIFF__' + RESULT);
"""
        harness_s = harness_s.replace("__SCRIPT__", json.dumps(sniff_script))
        tmp_h = os.path.join(tempfile.gettempdir(), "ling_sniffer_harness.js")
        with open(tmp_h, "w", encoding="utf-8") as f:
            f.write(harness_s)
        r_h = subprocess.run([node, tmp_h], capture_output=True, text=True)
        out_h = (r_h.stdout or "").strip()
        if "__SNIFF__" not in out_h:
            bad("嗅探运行失败（无返回值）: " + (r_h.stderr or out_h).strip()[:500])
        else:
            payload = out_h.split("__SNIFF__", 1)[1]
            try:
                res_s = json.loads(payload)
            except Exception as e:
                bad("嗅探返回值不是合法 JSON: %s" % e)
                res_s = None
            if res_s:
                if res_s.get("status") != "ok":
                    bad("嗅探返回非 ok: %s" % res_s.get("reason"))
                else:
                    items = res_s.get("resources", [])
                    urls = [it.get("url", "") for it in items]
                    ok("嗅探到 %d 个资源" % len(items))
                    # 相对地址应解析成绝对地址
                    if "https://example.com/media/clip.mp4" in urls:
                        ok("相对地址已解析成绝对地址")
                    else:
                        bad("相对地址未解析（<video src=/media/...> 应成绝对）")
                    # data: 与 1x1 占位图应被剔除
                    if any("pixel.gif" in u for u in urls):
                        bad("1x1 占位图未被剔除")
                    else:
                        ok("1x1 占位图已剔除")
                    if any("data:" in u for u in urls):
                        bad("data: URI 未被剔除")
                    else:
                        ok("data: URI 已剔除")
                    # 下载链接应被识别
                    if any("manual.pdf" in u for u in urls):
                        ok("媒体扩展名链接已识别为资源")
                    else:
                        bad("媒体扩展名链接未识别")

# ---------- 11. 夜间模式跟随 ----------
#
# 三个体验点：设置里能选三态（跟随系统/开/关）、网页暗化跟随夜间模式、
# 切换时组件同步。这里静态校验接线是否到位。
print("\n11. 夜间模式跟随")

# (1) 设置页的夜间模式必须是三态选择，而不是二态开关（二态丢"跟随系统"）
if 'showNightModeDialog = true' in settings_ui and \
        'NightMode.entries.forEach' in settings_ui:
    ok("设置页夜间模式是三态选择（跟随系统/始终开/始终关）")
else:
    bad("设置页夜间模式不是三态 —— 用户无法在设置里选「跟随系统」")

# (2) 网页暗化必须跟随夜间模式：shouldDarkenPages = forceDarkWebPages && resolvedDark
if "fun shouldDarkenPages()" in mgr and "resolvedDark()" in mgr:
    ok("网页暗化由 shouldDarkenPages 驱动（意图开关 × 夜间模式解析）")
else:
    bad("网页暗化未跟随夜间模式 —— 关掉夜间模式网页仍会暗")

# (3) 暗化结果传入 applySettings（系统级）且立即注入 CSS（兜底层）
if "darkTheme: Boolean" in lwv and "injectDarkMode(darkTheme)" in lwv:
    ok("applySettings 接收解析后的 darkTheme 并立即注入/移除 CSS")
else:
    bad("applySettings 未接收 darkTheme，或未立即同步 CSS —— 切换会不同步")

# (4) 移除路径必须存在：关掉夜间模式要能摘掉已注入的样式。
# 判据用"常量定义 + 该常量被引用"：只查 removeChild 会误判（它在 DARK_CSS_JS
# 的守卫里没有，但可能在别处出现），必须确认"移除脚本真的被挂进了
# injectDarkMode 的分支"。
if re.search(r"REMOVE_DARK_CSS_JS\s*=", lwv) and \
        re.search(r"evaluateJavascript\(if \(enabled\) DARK_CSS_JS else REMOVE_DARK_CSS_JS", lwv):
    ok("关夜间模式能移除已注入的反色样式（否则网页残留暗色）")
else:
    bad("缺少反色样式的移除路径 —— 切回浅色模式网页仍是暗的")

print()
print("=" * 55)
if problems:
    print("%d 个问题：" % len(problems))
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("阅读模式提取脚本校验通过")
