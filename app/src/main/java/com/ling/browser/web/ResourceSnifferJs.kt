/**
 * 资源嗅探脚本：扫描页面里的媒体资源链接。
 *
 * 用途：用户想看"这个页面里有哪些视频 / 音频 / 图片 / 可下载文件"，
 * 而不是让广告拦截或阅读模式替用户决定看什么。Via 这类浏览器都带这个能力。
 *
 * ## 嗅探什么
 *
 * 按优先级收集四类资源，每类带"类型 + 地址"：
 *   - **视频**：`<video>` 的 src、`<source>` 子标签，以及 `<video poster>`
 *   - **音频**：`<audio>` 的 src、`<source>` 子标签
 *   - **图片**：`<img>` 的 src（跳过 data: URI 和太小的占位图）
 *   - **下载**：`<a href>` 里以媒体扩展名结尾的链接（mp4/mp3/pdf/zip…）
 *
 * ## 为什么用 JS 而不是解析 HTML
 *
 * DOM 只在 WebView 那边。而且很多站点是 JS 动态渲染的，静态 HTML 里根本没有
 * `<video>` 标签 —— 必须等页面加载完、DOM 就绪后再从**活的 DOM**里读。
 *
 * ## 返回格式
 *
 * JSON 字符串，与阅读模式同一套约定（`evaluateJavascript` 取表达式的值）：
 *   { status: 'ok', resources: [{ type, url, title? }] }
 * 没找到时 status 仍是 'ok' 但 resources 为空数组 —— 空结果不是错误，
 * 是"这页没有可嗅探的媒体"，UI 应温和提示而不是报错。
 *
 * 注意：本文件是 JS 源码字符串，不参与 Kotlin 编译 ——
 * tools/check_reader.py 会做语法与结构校验（含真实 DOM 桩运行）。
 */
package com.ling.browser.web

object ResourceSnifferJs {

    object Status {
        const val OK = "ok"
        const val ERROR = "error"
    }

    /**
     * 嗅探脚本。IIFE + 显式 return JSON.stringify(...)。
     */
    val SCRIPT: String = """
(function () {
  try {
    var seen = {};          // 去重：同一地址只报一次
    var resources = [];
    var MAX = 200;          // 上限：防止某些站塞几万个节点把结果撑爆

    function push(type, url, title) {
      if (!url || typeof url !== 'string') return;
      var abs;
      try {
        // 相对地址解析成绝对地址；解析失败就跳过（不拿一个残废地址糊弄用户）
        abs = new URL(url, document.baseURI || location.href).href;
      } catch (e) { return; }
      // 只收 http(s)：data:/blob: 之类没有"下载/打开"的意义
      if (!/^https?:/i.test(abs)) return;
      if (resources.length >= MAX) return;
      var key = type + '|' + abs;
      if (seen[key]) return;
      seen[key] = true;
      resources.push({ type: type, url: abs, title: title || '' });
    }

    // ---- 1. 视频：<video> 的 src + <source> + poster ----
    var videos = document.getElementsByTagName('video');
    for (var i = 0; i < videos.length; i++) {
      var v = videos[i];
      var vsrc = v.getAttribute('src') || v.currentSrc || '';
      push('video', vsrc, v.getAttribute('title'));
      var srcs = v.getElementsByTagName('source');
      for (var s = 0; s < srcs.length; s++) {
        push('video', srcs[s].getAttribute('src'), v.getAttribute('title'));
      }
      var poster = v.getAttribute('poster');
      if (poster) push('image', poster, v.getAttribute('title'));
    }

    // ---- 2. 音频：<audio> 的 src + <source> ----
    var audios = document.getElementsByTagName('audio');
    for (var a = 0; a < audios.length; a++) {
      var au = audios[a];
      push('audio', au.getAttribute('src') || au.currentSrc, au.getAttribute('title'));
      var asrcs = au.getElementsByTagName('source');
      for (var s = 0; s < asrcs.length; s++) {
        push('audio', asrcs[s].getAttribute('src'), au.getAttribute('title'));
      }
    }

    // ---- 3. 图片：<img> 的 src（跳过 data: 和 1x1 占位） ----
    var imgs = document.getElementsByTagName('img');
    for (var k = 0; k < imgs.length; k++) {
      var im = imgs[k];
      var src = im.getAttribute('src');
      if (!src || /^data:/i.test(src)) continue;
      // 跳过明显的占位/像素图（很多站用 1x1 gif 当 tracking pixel）
      var w = parseInt(im.getAttribute('width') || im.width || '0', 10);
      var h = parseInt(im.getAttribute('height') || im.height || '0', 10);
      if (w === 1 && h === 1) continue;
      push('image', src, im.getAttribute('alt'));
    }

    // ---- 4. 下载链接：<a> 里媒体扩展名结尾 ----
    var MEDIA_EXT = /\.(mp4|m3u8|webm|mkv|mov|avi|flv|mp3|wav|aac|flac|ogg|m4a|jpg|jpeg|png|gif|webp|pdf|zip|rar|7z|apk|torrent)(\?|#|$)/i;
    var anchors = document.getElementsByTagName('a');
    for (var n = 0; n < anchors.length; n++) {
      var href = anchors[n].getAttribute('href') || '';
      if (!MEDIA_EXT.test(href)) continue;
      var ext = (href.match(MEDIA_EXT) || [])[1] || '';
      var type = /mp4|m3u8|webm|mkv|mov|avi|flv/.test(ext) ? 'video'
               : /mp3|wav|aac|flac|ogg|m4a/.test(ext) ? 'audio'
               : /jpg|jpeg|png|gif|webp/.test(ext) ? 'image'
               : 'download';
      push(type, href, anchors[n].textContent ? anchors[n].textContent.trim().slice(0, 80) : '');
    }

    return JSON.stringify({ status: 'ok', resources: resources });
  } catch (e) {
    return JSON.stringify({ status: 'error', reason: String(e && e.message ? e.message : e) });
  }
})();
    """.trimIndent()
}
