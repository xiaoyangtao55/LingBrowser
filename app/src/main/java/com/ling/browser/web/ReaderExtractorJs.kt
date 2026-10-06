/**
 * 阅读模式的正文提取脚本。
 *
 * 算法移植自 Mozilla Readability（Firefox 阅读视图的核心），但做了大幅精简：
 * 去掉多页合并、iframe、脚注、语言统计等分支，只保留"找出正文容器"这个核心。
 *
 * 为什么不用现成的 Readability.js：它压缩后约 90 KB，而整个 APK 才 1.5 MB ——
 * 引进来会让包体积增加 6%，与「轻量」定位冲突。这里重写的版本不到 8 KB。
 *
 * 核心思路（Readability 的精髓，不是随便抓一个 div）：
 *   1. 给每个 <p> 按**文本长度**打分，分数**向上累加给两级祖先**
 *      —— 正文段落本身就长，且它们的共同祖先才是正文容器
 *   2. 累加时按标签类型给权重：div 是通用容器（权重低），
 *      article/main 更可能是正文（权重高）
 *   3. 用链接密度（linkDensity）把导航/相关阅读剔除：
 *      全是链接的块不是正文
 *   4. 按 class/id 语义直接淘汰明显不是正文的节点（comment/menu/sidebar…）
 *
 * 返回 JSON 字符串，由原生侧解析后交给 Kotlin 生成阅读视图。
 * 注意：本文件是 JS 源码字符串，不参与 Kotlin 编译，因此没有 IDE 检查 ——
 * tools/check_reader.py 会做语法与结构校验。
 */
package com.ling.browser.web

object ReaderExtractorJs {

    /**
     * 提取结果的状态码，与 JS 返回值一一对应。
     *
     * 分开表示"没找到"和"出错了"：前者是正常情况（比如首页、图片站），
     * 后者说明脚本本身有问题，需要区别对待。
     */
    object Status {
        const val OK = "ok"
        const val NO_CONTENT = "no_content"
        const val ERROR = "error"
    }

    /**
     * 提取脚本。
     *
     * 用 IIFE + 显式 return JSON.stringify(...)：`evaluateJavascript` 取的是
     * 表达式的值，因此脚本最后必须是**一个表达式**，不能靠 console.log。
     */
    val SCRIPT: String = """
(function () {
  try {
    // ---------------------------------------------------------------- 工具

    /** 是否已提取过：重复提取会因 DOM 已被替换而得到空结果。 */
    if (window.__lingReaderDone) {
      return JSON.stringify({ status: 'error', reason: 'already_extracted' });
    }

    var UNLIKELY = /combx|comment|community|disqus|extra|foot|header|menu|remark|rss|shoutbox|sidebar|sponsor|ad-break|agegate|pagination|pager|popup|tweet|twitter|social|share|related|recommend|nav|breadcrumb|copyright|footer|banner|promo|meta|masthead|byline|dateline|tags?|widget/i;
    var MAYBE = /and|article|body|column|main|shadow|content|entry|hentry|post|text|blog|story/i;
    var POSITIVE = /article|body|content|entry|hentry|main|page|pagination|post|text|blog|story/i;
    var NEGATIVE = /hidden|banner|combx|comment|com-|contact|foot|footer|footnote|masthead|media|meta|outbrain|promo|related|scroll|shoutbox|sidebar|sponsor|shopping|tags|tool|widget|nav|menu|share|social/i;

    /** 取节点的 class + id，统一小写，供语义正则匹配。 */
    function classId(node) {
      if (!node || node.nodeType !== 1) return '';
      return ((node.className || '') + ' ' + (node.id || '')).toString().toLowerCase();
    }

    /** 链接密度：链接文字长度 / 总文字长度。导航栏接近 1，正文接近 0。 */
    function linkDensity(node) {
      var textLen = (node.textContent || '').trim().length;
      if (textLen === 0) return 0;
      var linkLen = 0;
      var links = node.getElementsByTagName('a');
      for (var i = 0; i < links.length; i++) {
        linkLen += (links[i].textContent || '').trim().length;
      }
      return linkLen / textLen;
    }

    /** 节点里的直接文本长度（不含子元素），用于判断空容器。 */
    function directTextLen(node) {
      var len = 0;
      for (var i = 0; i < node.childNodes.length; i++) {
        var c = node.childNodes[i];
        if (c.nodeType === 3) len += (c.textContent || '').trim().length;
      }
      return len;
    }

    /**
     * 估算"相当于多少个英文字符"。
     *
     * Readability 原文用 `textLength >= 100` 判断段落是否够长，但那个阈值
     * 是**为英文调的**。中文一个字承载的信息量远大于一个字母：
     * 100 个汉字的篇幅约等于 300+ 英文单词，对中文段落而言门槛过高，
     * 结果是**大部分中文文章被判成"没有正文"**（实测：三段正常中文段落
     * 全都不达标）。
     *
     * 折中做法是把 CJK 字符按 2.5 倍折算。这个系数不追求精确 ——
     * 它只需要区分"一句话的说明文字"和"真正在叙述的段落"。
     */
    function weightedLength(text) {
      var s = text || '';
      var cjk = (s.match(/[\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]/g) || []).length;
      var rest = s.length - cjk;
      return Math.round(cjk * 2.5 + rest);
    }

    /** 是否"看起来"像正文（用于快速判断本页值不值得进阅读模式）。 */
    function isProbablyReaderable() {
      var ps = document.getElementsByTagName('p');
      var good = 0;
      for (var i = 0; i < ps.length; i++) {
        if (weightedLength((ps[i].textContent || '').trim()) >= 100 &&
            linkDensity(ps[i]) < 0.25) {
          good++;
        }
      }
      return good >= 3;
    }

    // ------------------------------------------------- 第一步：语义淘汰

    // 先删掉明确无意义的标签。注意**不能删 <p>**：正文段落就是 p。
    var junkTags = ['script', 'style', 'noscript', 'iframe', 'form', 'button',
                    'input', 'select', 'textarea', 'svg', 'canvas', 'nav',
                    'aside', 'footer', 'header'];
    for (var j = 0; j < junkTags.length; j++) {
      var els = document.getElementsByTagName(junkTags[j]);
      for (var k = els.length - 1; k >= 0; k--) {
        if (els[k].parentNode) els[k].parentNode.removeChild(els[k]);
      }
    }

    // 再按 class/id 语义淘汰。这条正则是 Readability 的关键经验值：
    // "comment"/"sidebar"/"related" 这类词几乎总是指向非正文区块。
    var all = document.getElementsByTagName('*');
    var candidates = [];
    for (var m = 0; m < all.length; m++) {
      var el = all[m];
      var cid = classId(el);
      if (!cid) continue;
      // 匹配 UNLIKELY 但不匹配 MAYBE 的才删 —— "article-comment" 这种
      // 同时命中两边的要保留，否则会误删正文。
      if (UNLIKELY.test(cid) && !MAYBE.test(cid)) {
        el.__lingJunk = true;
      } else {
        candidates.push(el);
      }
    }
    for (var n = 0; n < candidates.length; n++) {
      var p = candidates[n].parentNode;
      while (p) {
        if (p.__lingJunk) { candidates[n].__lingJunk = true; break; }
        p = p.parentNode;
      }
    }
    for (var q = all.length - 1; q >= 0; q--) {
      if (all[q].__lingJunk && all[q].parentNode) {
        all[q].parentNode.removeChild(all[q]);
      }
    }

    if (!isProbablyReaderable()) {
      return JSON.stringify({ status: 'no_content', reason: 'not_readerable' });
    }

    // ------------------------------------------------- 第二步：打分

    var scores = [];
    function getScore(node) {
      for (var i = 0; i < scores.length; i++) {
        if (scores[i].node === node) return scores[i];
      }
      return null;
    }
    function addScore(node, delta) {
      if (!node || node.nodeType !== 1) return;
      var tag = node.tagName;
      if (tag === 'HTML' || tag === 'BODY') return;
      var s = getScore(node);
      if (!s) {
        // 初始分按标签给：div/section 是通用容器，起点低；
        // article/main 语义上更可能是正文，起点高。
        var base = 0;
        if (tag === 'DIV') base = 5;
        else if (tag === 'ARTICLE') base = 10;
        else if (tag === 'MAIN') base = 10;
        else if (tag === 'SECTION') base = 5;
        else if (tag === 'BLOCKQUOTE') base = 3;
        else if (tag === 'PRE') base = 3;
        else if (tag === 'TD') base = 3;
        else base = -5;
        s = { node: node, score: base };
        scores.push(s);
      }
      s.score += delta;
    }

    var paragraphs = document.getElementsByTagName('p');
    for (var i2 = 0; i2 < paragraphs.length; i2++) {
      var para = paragraphs[i2];
      var inner = (para.textContent || '').trim();
      // 阈值同样按 CJK 折算，否则中文短段会被全部丢弃（见 weightedLength）
      if (weightedLength(inner) < 25) continue;
      var ld = linkDensity(para);
      if (ld > 0.25) continue;           // 链接多的段落是导航/列表

      // 段落基础分 = 1 + 逗号数 + min(加权长度/100, 3)
      // 逗号数能有效区分"真正在叙述"和"一堆短句拼起来"。
      // 注意标点正则**必须含中文标点**：只匹配 , . 的话中文段落
      // 逗号数为 0，分数会低到被当成噪声。
      var commas = (inner.match(/[，,。\.；;！!？?、]/g) || []).length;
      var score = 1 + commas + Math.min(Math.floor(weightedLength(inner) / 100), 3);

      // 分数向上累加给**两级祖先**：正文段的父节点可能是
      // <div class="para-wrap">，祖父才是正文容器。
      var parent = para.parentNode;
      var grand = parent ? parent.parentNode : null;
      addScore(parent, score);
      addScore(grand, Math.floor(score / 2));
    }

    if (scores.length === 0) {
      return JSON.stringify({ status: 'no_content', reason: 'no_paragraphs' });
    }

    // 语义正则加/减分，并剔除链接密度过高的候选。
    var best = null;
    for (var s2 = 0; s2 < scores.length; s2++) {
      var cand = scores[s2];
      var cid2 = classId(cand.node);
      // 用 weight 而不是直接加分：一个节点可能同时命中正负两边，
      // 直接加分会让"article comment"被两边各加一次而虚高。
      var weight = 1;
      if (NEGATIVE.test(cid2)) weight -= 0.5;
      if (POSITIVE.test(cid2)) weight += 0.25;
      cand.score *= weight;

      // 链接密度超过 1/3 的容器不是正文（整块都是链接）
      if (linkDensity(cand.node) > 0.33) cand.score *= 0.3;

      if (!best || cand.score > best.score) best = cand;
    }

    if (!best || best.score <= 0) {
      return JSON.stringify({ status: 'no_content', reason: 'low_score' });
    }

    // ------------------------------------------------- 第三步：收拾正文

    var article = best.node;

    // 候选容器可能只含一小段（比如正文被包在更深一层）。
    // Readability 的做法是向上找到分数更高的祖先，这里简化：
    // 若父节点分数更高则上移一层。
    var bp = article.parentNode;
    var bs = bp ? getScore(bp) : null;
    if (bs && bs.score > best.score) article = bp;

    // 清理正文内部残留的垃圾：空 div、只有链接的块、作废的 figure。
    var kids = article.getElementsByTagName('*');
    for (var d = kids.length - 1; d >= 0; d--) {
      var kid = kids[d];
      var tag2 = kid.tagName;
      if (tag2 === 'DIV' || tag2 === 'SECTION' || tag2 === 'SPAN') {
        var txt = (kid.textContent || '').trim();
        // 空容器、或纯链接且很短 -> 删
        if (txt.length === 0 && kid.getElementsByTagName('img').length === 0) {
          if (kid.parentNode) kid.parentNode.removeChild(kid);
          continue;
        }
        if (linkDensity(kid) > 0.5 && txt.length < 200) {
          if (kid.parentNode) kid.parentNode.removeChild(kid);
          continue;
        }
      }
      // 去掉内联样式：站点的字号/宽度会破坏阅读视图排版
      if (kid.getAttribute && kid.getAttribute('style')) {
        kid.removeAttribute('style');
      }
    }

    // 图片：懒加载站点把真实地址放在 data-src 等属性上，
    // 直接取 src 会得到占位图。这里逐一把候选属性提升为 src。
    var imgs = article.getElementsByTagName('img');
    for (var im = 0; im < imgs.length; im++) {
      var img = imgs[im];
      var attrs = ['data-src', 'data-original', 'data-lazy-src', 'data-actualsrc'];
      for (var a = 0; a < attrs.length; a++) {
        var v = img.getAttribute(attrs[a]);
        if (v && v.length > 0 && v.indexOf('data:') !== 0) {
          img.setAttribute('src', v);
          break;
        }
      }
      // 去掉站点设置的固定宽高，交给阅读视图自适应
      img.removeAttribute('width');
      img.removeAttribute('height');
      img.removeAttribute('style');
      img.removeAttribute('srcset');
    }

    // 收集正文 HTML。用 innerHTML 而不是 DOM 序列化整棵树：
    // 后者会带上命名空间属性，体积也更大。
    var html = article.innerHTML || '';
    var textLen2 = (article.textContent || '').trim().length;

    // 内容太少说明提取失败 —— 宁可告诉用户"没找到正文"，也不要
    // 给他一个几乎空白的阅读视图（那看起来像 bug）。
    // 阈值用加权长度，理由同上：中文 200 字已是相当长的文章。
    if (weightedLength(article.textContent) < 200) {
      return JSON.stringify({ status: 'no_content', reason: 'too_short' });
    }

    // 标题：优先 h1，其次 title。
    var title = '';
    var h1 = document.getElementsByTagName('h1')[0];
    if (h1 && (h1.textContent || '').trim().length > 0) {
      title = h1.textContent.trim();
    } else {
      title = (document.title || '').trim();
    }

    // 作者/站点名：og:site_name 最常见，其次 meta author。
    var site = '';
    var metas = document.getElementsByTagName('meta');
    for (var mt = 0; mt < metas.length; mt++) {
      var prop = (metas[mt].getAttribute('property') || metas[mt].getAttribute('name') || '').toLowerCase();
      if (prop === 'og:site_name') { site = metas[mt].getAttribute('content') || ''; break; }
    }

    window.__lingReaderDone = true;
    return JSON.stringify({
      status: 'ok',
      title: title,
      site: site,
      url: location.href,
      textLength: textLen2,
      html: html
    });
  } catch (e) {
    // 任何异常都要转成结构化结果：抛出去 evaluateJavascript 只会给 null，
    // 那样原生侧完全不知道发生了什么。
    return JSON.stringify({ status: 'error', reason: String(e && e.message ? e.message : e) });
  }
})();
    """.trimIndent()

    /**
     * 还原标记：进入阅读视图前设置，退出时清掉。
     *
     * 单独一个脚本而不是复用 [SCRIPT]：退出阅读模式时页面要重新加载
     * （DOM 已被改造），这个标记必须在**重新加载后**才能清除，
     * 因此由调用方在 onPageFinished 里显式执行。
     */
    const val CLEAR_FLAG: String = "window.__lingReaderDone = false;"
}
