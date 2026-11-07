package com.ling.browser.web

/**
 * document-start 注入的登录表单监听脚本。
 *
 * 只在**主框架**、用户真实提交表单（`isTrusted`）时上报一次
 * `{u: 用户名, p: 密码}`。站点归属不在这里判断：原生侧会按当前页面 URL
 * 重新求 eTLD+1，页面无法通过伪造消息把密码记到别的站点名下。
 *
 * 刻意不做的事：
 *  - 不监听输入过程（keystroke），更不缓存按键 —— 那既耗性能也扩大明文暴露面；
 *  - 不自动保存，只上报给原生弹「保存密码？」；
 *  - 不向页面暴露任何历史凭据。
 *
 * 桥不存在（无痕标签不挂桥 / 系统 WebView 不支持）时静默放行，
 * 页面行为与没有这段脚本时完全一致。
 */
internal const val PASSWORD_CAPTURE_JS: String = """
(function () {
  if (window.top !== window) return;
  if (window.__lingPwdCapture) return;
  window.__lingPwdCapture = 1;

  function visible(el) {
    if (!el || !el.isConnected) return false;
    if ((el.type || '').toLowerCase() === 'hidden') return false;
    var rect = el.getBoundingClientRect();
    if (!rect || rect.width <= 0 || rect.height <= 0) return false;
    var style = window.getComputedStyle(el);
    if (!style) return true;
    return style.visibility !== 'hidden' && style.display !== 'none' && style.opacity !== '0';
  }

  function passwordField(form) {
    var list = form.querySelectorAll('input[type=password]');
    for (var i = 0; i < list.length; i++) {
      if (list[i].value && visible(list[i])) return list[i];
    }
    return null;
  }

  function usernameField(form, pwd) {
    var inputs = Array.prototype.slice.call(form.querySelectorAll('input'));
    var i, el, type, hint;
    for (i = 0; i < inputs.length; i++) {
      el = inputs[i];
      if (el === pwd || el.disabled || el.readOnly || !el.value) continue;
      type = (el.type || 'text').toLowerCase();
      if (type !== 'text' && type !== 'email' && type !== 'tel') continue;
      hint = ((el.name || '') + ' ' + (el.id || '') + ' ' +
        (el.getAttribute('autocomplete') || '') + ' ' + (el.placeholder || '')).toLowerCase();
      if (/user|email|mail|login|account|phone|mobile/.test(hint)) return el.value;
    }
    // 没有命名线索时，取密码框之前最近的一个非空文本/邮箱框。
    var index = inputs.indexOf(pwd);
    for (i = index - 1; i >= 0; i--) {
      el = inputs[i];
      type = (el.type || 'text').toLowerCase();
      if ((type === 'text' || type === 'email' || type === 'tel') && el.value) return el.value;
    }
    return '';
  }

  document.addEventListener('submit', function (e) {
    if (!e.isTrusted) return;
    var form = e.target;
    if (!form || form.nodeType !== 1) return;
    var pwd = passwordField(form);
    if (!pwd) return;
    try {
      if (window.LingPassword) {
        window.LingPassword.postMessage(JSON.stringify({
          u: usernameField(form, pwd),
          p: pwd.value
        }));
      }
    } catch (err) {}
  }, true);
})();
"""
