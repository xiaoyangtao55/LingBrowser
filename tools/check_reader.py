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

# (3) 回调不能把逻辑地址改写成 baseUrl
# 必须**逐个回调**检查，不能只数总数：onPageStarted 与 onPageFinished
# 都要各自处理，只修一个的话另一个仍会把 ling://reader 改写掉。
# （踩过：一开始用 `count(...) >= 2` 计数，退化掉其中一个仍能凑够数。）
if mgr.count("if (isInternal) it.url else url") >= 2:
    ok("onPageStarted/onPageFinished 都不会把 ling://reader 改写成 baseUrl")
else:
    bad("有回调会把阅读视图的逻辑地址改写掉，导致返回栈与识别错乱")

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
    if "ReaderPage.isReaderUrl(url)" in blk:
        ok(f"{cb} 内部页面判定含阅读视图")
    else:
        bad(f"{cb} 的判定漏了阅读视图 —— 会把 ling://reader 改写成 baseUrl")

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

print()
print("=" * 55)
if problems:
    print("%d 个问题：" % len(problems))
    for p in problems:
        print("  ! " + p)
    sys.exit(1)
print("阅读模式提取脚本校验通过")
