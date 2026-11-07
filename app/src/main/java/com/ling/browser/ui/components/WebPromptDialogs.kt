package com.ling.browser.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ling.browser.util.UrlUtils
import com.ling.browser.web.CertPrompt
import com.ling.browser.web.FormResubmitPrompt
import com.ling.browser.web.JsDialogRequest
import com.ling.browser.web.SafeBrowsingPrompt

/**
 * 网页触发的裁决类弹窗：证书错误、POST 重提交、Safe Browsing、JS 对话框。
 *
 * 只负责画 UI 与收集选择，裁决本身通过回调交回调用方 ——
 * 排队、handler 兑现与状态机都留在 [com.ling.browser.web.WebTabManager]。
 */

/**
 * 证书错误（主框架 / 子资源）。
 *
 * 交互对齐 Via：警告 + 原因 +「本次浏览过程中不再询问」+ 取消/确定。
 * 必须明确二选一：onDismissRequest（返回键 / 点外部）按「取消」处理，
 * 否则 handler 一直挂着，页面会停在半加载状态。
 */
@Composable
internal fun CertErrorDialog(
    prompt: CertPrompt,
    remember: Boolean,
    onRememberChange: (Boolean) -> Unit,
    onResolve: (proceed: Boolean, remember: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onResolve(false, false) },
        title = { Text("警告") },
        text = {
            Column {
                Text("与 ${prompt.host} 的连接不安全：")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "— ${prompt.reason}",
                    color = MaterialTheme.colorScheme.primary,
                )
                if (!prompt.mainFrame) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "由页面内的图片 / 脚本等子资源发起，不是当前网页本身。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRememberChange(!remember) },
                ) {
                    Checkbox(
                        checked = remember,
                        onCheckedChange = onRememberChange,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("本次浏览过程中不再询问")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolve(true, remember) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(false, false) }) { Text("取消") }
        },
    )
}

/**
 * POST 结果页刷新。
 *
 * 与 Chrome 一致：明确问一次要不要重新提交表单数据。两个按钮都必须兑现
 * WebTabManager 里的 resend / dontResend，onDismissRequest 按取消处理。
 */
@Composable
internal fun FormResubmitDialog(
    prompt: FormResubmitPrompt,
    onResolve: (resend: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onResolve(false) },
        title = { Text("重新提交表单？") },
        text = {
            Text("${prompt.host} 需要重新发送刚才提交的表单数据才能刷新此页面。")
        },
        confirmButton = {
            TextButton(onClick = { onResolve(true) }) { Text("重新提交") }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(false) }) { Text("取消") }
        },
    )
}

/**
 * Safe Browsing 警告。
 *
 * 「返回安全页」是默认动作（onDismissRequest 同此），与 Chrome 的默认按钮一致。
 * 「仍要继续」只在用户明确点击时兑现 proceed —— 这一页仍可能被恶意内容利用。
 */
@Composable
internal fun SafeBrowsingDialog(
    prompt: SafeBrowsingPrompt,
    onResolve: (proceed: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onResolve(false) },
        title = { Text("安全警告") },
        text = {
            Column {
                Text(
                    text = if (prompt.host.isBlank()) "此页面" else prompt.host,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "— ${prompt.threat}",
                    color = MaterialTheme.colorScheme.error,
                )
                if (!prompt.mainFrame) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "由页面内的图片 / 脚本等子资源发起，不是当前网页本身。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolve(false) }) { Text("返回安全页") }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(true) }) { Text("仍要继续") }
        },
    )
}

/**
 * 网页 JS 对话框（alert / confirm / prompt / onbeforeunload）。
 *
 * 关闭对话框按取消处理：JsResult.cancel()，页面拿到 false / null。
 * prompt 的输入值由调用方持有（输入过程中对话框不消失）。
 */
@Composable
internal fun JsDialog(
    req: JsDialogRequest,
    input: String,
    onInputChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val hostLabel = UrlUtils.hostOf(req.url).ifBlank { req.url }
    AlertDialog(
        onDismissRequest = {
            when (req) {
                is JsDialogRequest.Alert -> req.result.cancel()
                is JsDialogRequest.Confirm -> req.result.cancel()
                is JsDialogRequest.Prompt -> req.result.cancel()
                is JsDialogRequest.BeforeUnload -> req.result.cancel()
            }
            onDismiss()
        },
        title = { Text(hostLabel) },
        text = {
            Column {
                if (req.message.isNotBlank()) {
                    Text(
                        text = req.message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (req is JsDialogRequest.Prompt) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (req) {
                    is JsDialogRequest.Alert -> req.result.confirm()
                    is JsDialogRequest.Confirm -> req.result.confirm()
                    is JsDialogRequest.Prompt -> req.result.confirm(input)
                    is JsDialogRequest.BeforeUnload -> req.result.confirm()
                }
                onDismiss()
            }) {
                Text(
                    when (req) {
                        is JsDialogRequest.Alert -> "确定"
                        is JsDialogRequest.Confirm -> "确定"
                        is JsDialogRequest.Prompt -> "确定"
                        is JsDialogRequest.BeforeUnload -> "离开"
                    }
                )
            }
        },
        dismissButton = if (req is JsDialogRequest.Alert) {
            null
        } else {
            {
                TextButton(onClick = {
                    when (req) {
                        is JsDialogRequest.Alert -> {} // alert 没有取消按钮
                        is JsDialogRequest.Confirm -> req.result.cancel()
                        is JsDialogRequest.Prompt -> req.result.cancel()
                        is JsDialogRequest.BeforeUnload -> req.result.cancel()
                    }
                    onDismiss()
                }) {
                    Text(if (req is JsDialogRequest.BeforeUnload) "留在本页" else "取消")
                }
            }
        },
    )
}
