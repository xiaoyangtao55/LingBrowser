package com.ling.browser.ui.screens

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.ling.browser.ui.theme.LingIcons

/**
 * 「关于」页：应用图标、版本信息、WebView 内核版本与开源许可。
 *
 * 内核版本读的是系统里实际承载 WebView 的那个包（Android System WebView / Chrome），
 * 而不是本应用打包进去的东西 —— 「翎」不自带内核，网页渲染全交给系统 WebView，
 * 所以版本取决于用户设备。写清楚这一点，用户遇到渲染差异时才有据可查。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // PackageManager / WebView 取值只做一次即可，不必每次重组都重新读。
    val app = remember { loadAppInfo(context) }
    val webView = remember { loadWebViewInfo(context) }

    // 内核信息里"提供方"可能拿不到（Android 8.0 以下无该接口），拿到才显示。
    val kernelRows = listOfNotNull(
        "WebView 版本" to webView.version,
        webView.packageName?.let { "WebView 提供方" to it },
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LingIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // ---- 应用图标与版本 ----
            item {
                val icon = app.icon
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (icon != null) {
                        Image(
                            bitmap = icon,
                            contentDescription = "应用图标",
                            modifier = Modifier.size(72.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "翎 · 极简浏览器",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "版本 ${app.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "轻巧、干净、无推送",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- 内核 ----
            item { AboutSectionHeader("内核") }
            kernelRows.forEach { (label, value) ->
                item { InfoRow(label = label, value = value) }
            }

            // ---- 开源许可 ----
            item { AboutSectionHeader("开源许可") }
            item { InfoRow(label = "OkHttp", value = "网络与下载 · Apache-2.0") }
        }
    }
}

@Composable
private fun AboutSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

private data class AppInfo(
    val versionName: String,
    val packageName: String,
    val icon: ImageBitmap?,
)

private data class WebViewInfo(
    val version: String,
    val packageName: String?,
)

private fun loadAppInfo(context: Context): AppInfo {
    val pm = context.packageManager
    val packageName = context.packageName
    @Suppress("DEPRECATION")
    val info = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
    // 图标用 PackageManager 拿（而不是 painterResource(R.mipmap.ic_launcher)）：
    // Android 8.0+ 的启动图标是 <adaptive-icon>，painterResource 解析不了那种 XML，
    // 走 PackageManager 会由系统把它渲染成位图，各版本都稳。
    val icon = runCatching {
        pm.getApplicationIcon(packageName).toBitmap().asImageBitmap()
    }.getOrNull()
    return AppInfo(
        versionName = info?.versionName ?: "未知",
        packageName = packageName,
        icon = icon,
    )
}

private fun loadWebViewInfo(context: Context): WebViewInfo {
    // Android 8.0+ 能直接问到承载 WebView 的包（Android System WebView / Chrome）。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val pkg = WebView.getCurrentWebViewPackage()
        if (pkg != null) {
            return WebViewInfo(version = pkg.versionName ?: "未知", packageName = pkg.packageName)
        }
    }
    // 更低版本没有该接口，从默认 UA 里截 Chrome/xx 作为近似版本。
    val ua = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
    val version = ua?.let { Regex("Chrome/([\\d.]+)").find(it)?.groupValues?.get(1) }
    return WebViewInfo(version = version ?: "未知", packageName = null)
}
