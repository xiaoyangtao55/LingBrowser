package com.ling.browser.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ling.browser.util.UrlUtils
import com.ling.browser.web.WebAuthRequest
import com.ling.browser.web.WebTabManager

/**
 * 网页向宿主要能力的完整接管：HTTP 认证 / 定位 / 摄像头麦克风。
 *
 * 接线（[WebTabManager] 的委托）、待兑现的 handler、运行时权限申请与三个确认框
 * 都在这里闭环，宿主只需调用一次 [WebCapabilityRequests]。
 *
 * 必须保证每一个 handler 恰好兑现一次：不兑现就是页面永远等待
 * （401 卡住 / 定位不返回 / getUserMedia 挂起）。
 */
@Composable
internal fun WebCapabilityRequests(tabManager: WebTabManager) {
    val context = LocalContext.current

    var authRequest by remember { mutableStateOf<WebAuthRequest?>(null) }
    var authUser by remember { mutableStateOf("") }
    var authPassword by remember { mutableStateOf("") }

    // 用户已在应用内点过「允许」、正等系统运行时授权的请求。
    // 单独存一份而不是复用 authRequest：确认框这时已经关闭，但网络/页面
    // 那边的 handler 还挂着，必须等系统弹窗的结果回来才能兑现。
    var pendingGeo by remember { mutableStateOf<WebAuthRequest.Geolocation?>(null) }
    var pendingDevice by remember { mutableStateOf<WebAuthRequest.Device?>(null) }

    // 把当前挂着的请求按「拒绝/取消」兑现并清空。
    //
    // 三条用途缺一不可：用户点了拒绝、对话框被外部关闭、以及新请求到来时
    // 挤掉旧请求。
    val denyPendingAuth: () -> Unit = {
        val req = authRequest
        authRequest = null
        when (req) {
            is WebAuthRequest.HttpAuth -> runCatching { req.handler.cancel() }
            is WebAuthRequest.Geolocation ->
                runCatching { req.callback.invoke(req.origin, false, false) }
            is WebAuthRequest.Device -> runCatching { req.request.deny() }
            null -> Unit
        }
        pendingGeo?.let { runCatching { it.callback.invoke(it.origin, false, false) } }
        pendingDevice?.let { runCatching { it.request.deny() } }
        pendingGeo = null
        pendingDevice = null
        Unit
    }

    // 定位：应用内同意之后才向系统申请，两者都过才真正 allow。
    val geoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val req = pendingGeo
        pendingGeo = null
        if (req != null) {
            runCatching { req.callback.invoke(req.origin, grants.values.any { it }, false) }
        }
    }

    // 摄像头/麦克风：缺哪个系统权限就申请哪个；只要有一个被拒就整体 deny，
    // 避免「只给了摄像头」却把麦克风也 grant 出去。
    val devicePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val req = pendingDevice
        pendingDevice = null
        if (req != null) {
            if (grants.values.all { it }) {
                runCatching { req.request.grant(req.resources.toTypedArray()) }
            } else {
                runCatching { req.request.deny() }
            }
        }
    }

    // 新请求会先把旧请求按拒绝兑现 ——
    // 同一时刻只留一个待兑现的 handler，否则旧的那个永远等不到结果。
    DisposableEffect(Unit) {
        tabManager.onHttpAuthRequest = { host, realm, handler ->
            denyPendingAuth()
            authUser = ""
            authPassword = ""
            authRequest = WebAuthRequest.HttpAuth(host, realm, handler)
        }
        tabManager.onGeolocationRequest = { origin, callback ->
            denyPendingAuth()
            authRequest = WebAuthRequest.Geolocation(origin, callback)
        }
        tabManager.onPermissionRequest = { origin, resources, request ->
            denyPendingAuth()
            authRequest = WebAuthRequest.Device(origin, resources, request)
        }
        onDispose {
            tabManager.onHttpAuthRequest = null
            tabManager.onGeolocationRequest = null
            tabManager.onPermissionRequest = null
            // 界面都销毁了，还挂着的 handler 已无人能兑现，按拒绝处理。
            denyPendingAuth()
        }
    }

    authRequest?.let { req ->
        when (req) {
            is WebAuthRequest.HttpAuth -> AlertDialog(
                onDismissRequest = denyPendingAuth,
                title = { Text("需要登录") },
                text = {
                    Column {
                        Text(
                            text = req.origin + if (req.realm.isNotBlank()) "（${req.realm}）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = authUser,
                            onValueChange = { authUser = it },
                            label = { Text("用户名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = authPassword,
                            onValueChange = { authPassword = it },
                            label = { Text("密码") },
                            singleLine = true,
                            // 密码明文显示会在录屏/演示时直接泄露
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val user = authUser
                        val pass = authPassword
                        authRequest = null
                        runCatching { req.handler.proceed(user, pass) }
                    }) { Text("登录") }
                },
                dismissButton = {
                    TextButton(onClick = denyPendingAuth) { Text("取消") }
                },
            )

            is WebAuthRequest.Geolocation -> AlertDialog(
                onDismissRequest = denyPendingAuth,
                title = { Text("允许获取位置？") },
                text = {
                    Text("${originLabel(req.origin)} 想要获取你的位置信息。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        authRequest = null
                        if (hasLocationPermission(context)) {
                            runCatching { req.callback.invoke(req.origin, true, false) }
                        } else {
                            // 应用内同意 ≠ 系统已授权：还要过一遍运行时权限，
                            // 结果回来后才真正 allow（见 geoPermissionLauncher）。
                            pendingGeo = req
                            geoPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        }
                    }) { Text("允许") }
                },
                dismissButton = {
                    TextButton(onClick = denyPendingAuth) { Text("拒绝") }
                },
            )

            is WebAuthRequest.Device -> {
                val labels = req.resources.map(::webResourceLabel)
                val summary = if (labels.isEmpty()) "未知能力" else labels.joinToString("、")
                AlertDialog(
                    onDismissRequest = denyPendingAuth,
                    title = { Text("允许使用设备？") },
                    text = {
                        Text("${originLabel(req.origin)} 请求使用：$summary")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val needed = req.resources
                                .flatMap(::requiredRuntimePermissions)
                                .distinct()
                                .filter { !hasPermission(context, it) }
                            authRequest = null
                            if (needed.isEmpty()) {
                                runCatching { req.request.grant(req.resources.toTypedArray()) }
                            } else {
                                pendingDevice = req
                                devicePermissionLauncher.launch(needed.toTypedArray())
                            }
                        }) { Text("允许") }
                    },
                    dismissButton = {
                        TextButton(onClick = denyPendingAuth) { Text("拒绝") }
                    },
                )
            }
        }
    }
}

/**
 * 授权框里的来源标签。
 *
 * HTTP 认证给的是 host（可能带端口），定位与设备能力给的是 origin
 * （`https://example.com`）—— 两种都先试 host，取不到再原样显示。
 */
private fun originLabel(origin: String): String = UrlUtils.hostOf(origin).ifBlank { origin }

/** 把 `PermissionRequest` 的资源名翻成用户能看懂的词。 */
private fun webResourceLabel(resource: String): String = when (resource) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "摄像头"
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "麦克风"
    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "受保护媒体（DRM）"
    PermissionRequest.RESOURCE_MIDI_SYSEX -> "MIDI 设备"
    else -> resource
}

/**
 * 某个网页资源对应的系统运行时权限。
 *
 * DRM 与 MIDI 不需要运行时权限，返回空列表；摄像头/麦克风没有系统授权时
 * `PermissionRequest.grant()` 会静默失败（页面拿到 NotAllowedError），
 * 所以必须先申请。
 */
private fun requiredRuntimePermissions(resource: String): List<String> = when (resource) {
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
    else -> emptyList()
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** 粗略或精确定位任一已授权即可 —— 两种精度都能满足浏览器的定位请求。 */
private fun hasLocationPermission(context: Context): Boolean =
    hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
