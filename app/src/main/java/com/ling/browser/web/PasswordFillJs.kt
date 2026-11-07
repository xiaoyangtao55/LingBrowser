package com.ling.browser.web

/**
 * 显式填充脚本：把 `{u: 用户名, p: 密码}` 填进当前页面的登录表单。
 *
 * 只在用户主动选择"填充密码"并且完成身份验证后执行**一次**，
 * 不常驻页面、不自动触发。返回值 `true/false` 表示是否找到可填的密码框。
 *
 * 用原型上的 `value` setter 赋值再派发 `input` / `change`：
 * React 等框架会劫持实例上的 value setter，直接 `el.value = x` 界面看着变了，
 * 框架内部状态却没更新，提交时仍是旧值。
 */
internal fun passwordFillScript(credentialsJson: String): String = """
(function (a) {
  function visible(el) {
    if (!el || !el.isConnected) return false;
    if ((el.type || '').toLowerCase() === 'hidden') return false;
    var rect = el.getBoundingClientRect();
    if (!rect || rect.width <= 0 || rect.height <= 0) return false;
    var style = window.getComputedStyle(el);
    if (!style) return true;
    return style.visibility !== 'hidden' && style.display !== 'none' && style.opacity !== '0';
  }

  function setValue(el, value) {
    el.focus();
    var proto = Object.getPrototypeOf(el);
    var desc = proto && Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(el, value); else el.value = value;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  var list = document.querySelectorAll('input[type=password]');
  var pwd = null, i;
  for (i = 0; i < list.length; i++) {
    if (visible(list[i])) { pwd = list[i]; break; }
  }
  if (!pwd) return false;

  var form = pwd.form || document;
  var inputs = Array.prototype.slice.call(form.querySelectorAll('input'));
  var user = null, el, type, hint;
  for (i = 0; i < inputs.length; i++) {
    el = inputs[i];
    if (el === pwd || el.disabled || el.readOnly) continue;
    type = (el.type || 'text').toLowerCase();
    if (type !== 'text' && type !== 'email' && type !== 'tel') continue;
    hint = ((el.name || '') + ' ' + (el.id || '') + ' ' +
      (el.getAttribute('autocomplete') || '') + ' ' + (el.placeholder || '')).toLowerCase();
    if (/user|email|mail|login|account|phone|mobile/.test(hint) && visible(el)) {
      user = el;
      break;
    }
  }
  if (!user) {
    var index = inputs.indexOf(pwd);
    for (i = index - 1; i >= 0; i--) {
      el = inputs[i];
      type = (el.type || 'text').toLowerCase();
      if ((type === 'text' || type === 'email' || type === 'tel') && visible(el)) {
        user = el;
        break;
      }
    }
  }

  if (user && a.u) setValue(user, a.u);
  setValue(pwd, a.p);
  return true;
})($credentialsJson)
""".trimIndent()
