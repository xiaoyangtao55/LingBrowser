package com.ling.browser.download

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.widget.Toast
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用 [PackageInstaller] 会话安装 APK。
 *
 * 为什么不直接 `ACTION_VIEW` 一个 apk MIME：各家 ROM 对
 * `application/vnd.android.package-archive` 的注册并不一致，部分机型干脆
 * 没有应用认领这个类型 —— 表现为选择框里全是别的应用、或者点下去毫无反应。
 * 会话方式把字节直接交给系统安装器，既不需要 MIME 匹配，也不需要目标应用
 * 拿到我们 FileProvider 的读权限，是所有 ROM 上最稳的一条路。
 *
 * 前置条件：manifest 里声明 [android.Manifest.permission.REQUEST_INSTALL_PACKAGES]。
 * 首次安装时系统会引导用户打开「允许安装未知应用」，这是浏览器安装 APK 的
 * 正常流程。
 *
 * @return true 表示安装会话已提交（系统随后会弹确认界面）；false 表示会话
 *         建立失败，调用方应退回 `ACTION_VIEW`。
 */
internal suspend fun installApkFromUri(context: Context, uri: Uri): Boolean =
    withContext(Dispatchers.IO) {
        // 大小只用于给会话一个预期值；未知时传 -1，安装器按流式处理。
        val size = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L

        runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                if (size > 0) setSize(size)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                context.contentResolver.openInputStream(uri)?.use { input ->
                    session.openWrite(APK_ENTRY, 0, size).use { output ->
                        input.copyTo(output, COPY_BUFFER_SIZE)
                        // 与下载落盘同理：提交前先把字节刷下去，避免安装器
                        // 读到截断的包，报"解析失败"。
                        session.fsync(output)
                    }
                } ?: throw IOException("无法读取安装包")
                val result = Intent(context, ApkInstallReceiver::class.java)
                    .setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    result,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            true
        }.getOrDefault(false)
    }

/** 安装会话的结果回调：成功不打扰用户，失败或需要确认时才提示。 */
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        if (status == PackageInstaller.STATUS_SUCCESS) return

        // Android 12 以下由系统把这个确认页交回来，需要我们自己去拉。
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intentExtra(intent)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                return
            }
        }

        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?.takeIf { it.isNotBlank() }
        Toast.makeText(
            context,
            "安装未完成" + (detail?.let { "：$it" } ?: ""),
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun intentExtra(intent: Intent): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }

/** 会话里的条目名，任意非空字符串即可。 */
private const val APK_ENTRY = "base.apk"

private const val COPY_BUFFER_SIZE = 64 * 1024
