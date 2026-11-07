package com.ling.browser.ui

import com.ling.browser.data.repo.PasswordMatch
import com.ling.browser.data.repo.PasswordRepository
import com.ling.browser.data.security.SiteMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 「保存密码？」弹窗的内容。
 *
 * 这是明文密码在 UI 层的**唯一**落脚点：由捕获消息构造，弹窗关闭即丢弃，
 * 不进任何持久化状态，也不参与列表展示。
 */
internal data class PasswordSavePrompt(
    val site: String,
    val username: String,
    val password: String,
    val title: String?,
    /** true 表示该站点已有同一账号但密码不同，文案改为「更新密码？」。 */
    val update: Boolean,
)

/** [PasswordCaptureCoordinator] 对宿主的依赖，保持与其它 delegate 同构。 */
internal interface PasswordCaptureHost {
    val passwords: PasswordRepository
    val scope: CoroutineScope

    fun isIncognito(tabId: String): Boolean

    fun showMessage(text: String)
}

/**
 * 登录表单捕获的裁决层。
 *
 * 页面只负责上报"用户在这个表单里填了什么"，这里负责所有安全判断：
 *  - 无痕标签直接丢弃；
 *  - 站点必须能从当前 URL 解析出 HTTPS 的 eTLD+1（页面消息里没有站点字段，
 *    伪造不了归属）；
 *  - 与库里已有记录完全一致时不弹窗，避免每次登录都打扰。
 */
internal class PasswordCaptureCoordinator(private val host: PasswordCaptureHost) {

    private val _prompt = MutableStateFlow<PasswordSavePrompt?>(null)
    val prompt: StateFlow<PasswordSavePrompt?> = _prompt.asStateFlow()

    fun onSubmitted(tabId: String, url: String, title: String, payload: String) {
        if (host.isIncognito(tabId)) return
        val site = SiteMatcher.siteFromUrl(url) ?: return
        val (username, password) = parse(payload) ?: return

        // 同一份凭据已经在弹了，就别叠第二个框。
        _prompt.value?.let { current ->
            if (current.site == site &&
                current.username == username &&
                current.password == password
            ) {
                return
            }
        }

        host.scope.launch {
            val match = runCatching {
                host.passwords.match(site, username, password)
            }.getOrNull() ?: return@launch
            if (match == PasswordMatch.SAME) return@launch

            _prompt.value = PasswordSavePrompt(
                site = site,
                username = username,
                password = password,
                title = title.trim().takeIf { it.isNotEmpty() },
                update = match == PasswordMatch.DIFFERENT,
            )
        }
    }

    fun confirm() {
        val pending = _prompt.value ?: return
        // 先清掉明文再写库：写库失败也不会把它留在状态里。
        _prompt.value = null
        host.scope.launch {
            val saved = runCatching {
                host.passwords.save(
                    site = pending.site,
                    username = pending.username,
                    password = pending.password,
                    title = pending.title,
                )
            }.getOrNull()
            host.showMessage(
                if (saved != null) "已保存到密码库" else "保存失败，请检查站点或密码",
            )
        }
    }

    fun dismiss() {
        _prompt.value = null
    }

    /**
     * 只接受 `{u, p}` 两个字段。
     *
     * 超长输入直接丢弃而不是截断 —— 截断会把错误的密码存进库，
     * 比不保存更糟。
     */
    private fun parse(payload: String): Pair<String, String>? = runCatching {
        val obj = JSONObject(payload)
        val username = obj.optString("u", "")
        val password = obj.optString("p", "")
        when {
            password.isEmpty() -> null
            password.length > MAX_PASSWORD_LENGTH -> null
            username.length > MAX_USERNAME_LENGTH -> null
            else -> username to password
        }
    }.getOrNull()

    private companion object {
        const val MAX_PASSWORD_LENGTH = 1024
        const val MAX_USERNAME_LENGTH = 320
    }
}
