package com.ling.browser.ui.screens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ling.browser.data.db.PasswordEntry
import com.ling.browser.ui.components.LingPageTopBar
import com.ling.browser.ui.components.authenticateForPasswordVault
import com.ling.browser.ui.components.findFragmentActivity
import com.ling.browser.ui.theme.LingIcons
import kotlinx.coroutines.launch

/**
 * 密码库管理页。
 *
 * 两个硬性约束贯穿整页：
 *  1. **进入页面先验证身份**（指纹/人脸，回退设备凭据）；
 *  2. **查看或复制某条密码前再验证一次** —— 页面解锁只代表"人可以看列表"，
 *     不代表"可以把密码显示出来"。验证失败不会返回任何明文。
 *
 * 列表里永远只有站点/用户名/标题，明文密码只存在于完成二次验证后的
 * 那个弹窗状态里，关闭弹窗即丢弃。
 */
@Composable
fun PasswordsScreen(
    entries: List<PasswordEntry>,
    onSave: suspend (site: String, username: String, password: String, title: String?) -> Long?,
    onUpdate: suspend (id: Long, username: String, password: String?, title: String?) -> Boolean,
    onDelete: suspend (id: Long) -> Unit,
    onDecrypt: suspend (id: Long) -> String?,
    /** 加密导出到 SAF 选定的文件，返回给用户看的结果文案。 */
    onExport: suspend (target: Uri, passphrase: CharArray) -> String,
    /** 从 SAF 选定的加密备份导入，返回给用户看的结果文案。 */
    onImport: suspend (source: Uri, passphrase: CharArray) -> String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var unlocked by remember { mutableStateOf(false) }
    var authenticating by remember { mutableStateOf(false) }
    var unlockHint by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    var menuFor by remember { mutableStateOf<Long?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PasswordEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<PasswordEntry?>(null) }
    var revealed by remember { mutableStateOf<Pair<PasswordEntry, String>?>(null) }

    // 导入导出：口令对话框与后台处理状态。
    var menuOpen by remember { mutableStateOf(false) }
    var passphraseMode by remember { mutableStateOf<PassphraseMode?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var backupBusy by remember { mutableStateOf(false) }

    // 导出：CreateDocument 让用户选保存位置并起名。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            pendingUri = uri
            passphraseMode = PassphraseMode.EXPORT
        }
    }

    // 导入：OpenDocument 让用户挑一个已有的备份文件。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingUri = uri
            passphraseMode = PassphraseMode.IMPORT
        }
    }

    suspend fun tryUnlock(subtitle: String): Boolean {
        val host = activity
        if (host == null) {
            unlockHint = "当前界面无法发起身份验证"
            return false
        }
        authenticating = true
        val result = authenticateForPasswordVault(host, subtitle)
        authenticating = false
        unlockHint = result.message
        if (result.success) unlocked = true
        return result.success
    }

    // 进入页面立即验证；失败则停在锁定态，提供手动重试。
    LaunchedEffect(Unit) {
        if (!unlocked) tryUnlock("验证身份后查看已保存的密码")
    }

    val visible = remember(entries, query) {
        val q = query.trim()
        if (q.isEmpty()) entries
        else entries.filter {
            it.site.contains(q, ignoreCase = true) ||
                it.username.contains(q, ignoreCase = true) ||
                it.title.orEmpty().contains(q, ignoreCase = true)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            LingPageTopBar(
                title = "密码管理器",
                onBack = onBack,
                action = {
                    if (unlocked) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showAdd = true }) { Text("添加") }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(LingIcons.MoreVert, contentDescription = "导出或导入")
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("加密导出") },
                                        onClick = {
                                            menuOpen = false
                                            scope.launch {
                                                val host = activity
                                                if (host == null) {
                                                    snackbar.showSnackbar("当前界面无法发起身份验证")
                                                    return@launch
                                                }
                                                val result = authenticateForPasswordVault(
                                                    host,
                                                    "验证身份后导出密码备份",
                                                )
                                                if (!result.success) {
                                                    result.message?.let { snackbar.showSnackbar(it) }
                                                    return@launch
                                                }
                                                exportLauncher.launch("ling-passwords.lpb")
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("从文件导入") },
                                        onClick = {
                                            menuOpen = false
                                            scope.launch {
                                                val host = activity
                                                if (host == null) {
                                                    snackbar.showSnackbar("当前界面无法发起身份验证")
                                                    return@launch
                                                }
                                                val result = authenticateForPasswordVault(
                                                    host,
                                                    "验证身份后导入密码备份",
                                                )
                                                if (!result.success) {
                                                    result.message?.let { snackbar.showSnackbar(it) }
                                                    return@launch
                                                }
                                                importLauncher.launch(arrayOf("*/*"))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        if (!unlocked) {
            LockedState(
                hint = unlockHint,
                authenticating = authenticating,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                onUnlock = {
                    scope.launch { tryUnlock("验证身份后查看已保存的密码") }
                },
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        text = "密码只保存在这台设备上",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "使用系统 Keystore 加密，仅支持 HTTPS 站点。" +
                            "查看或复制密码前会再次验证身份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索站点或用户名") },
                        leadingIcon = {
                            Icon(
                                imageVector = LingIcons.Search,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (visible.isEmpty()) {
                item {
                    if (entries.isEmpty()) {
                        EmptyState(
                            icon = LingIcons.Lock,
                            title = "还没有保存的密码",
                            hint = "浏览网页时提交登录表单会提示保存\n也可以点右上角「添加」手动录入",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                        )
                    } else {
                        Text(
                            text = "没有匹配的密码",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                        )
                    }
                }
            } else {
                items(visible, key = { it.id }) { entry ->
                    PasswordRow(
                        entry = entry,
                        menuOpen = menuFor == entry.id,
                        onOpenMenu = { menuFor = entry.id },
                        onDismissMenu = { menuFor = null },
                        onReveal = {
                            scope.launch {
                                val host = activity
                                if (host == null) {
                                    snackbar.showSnackbar("当前界面无法发起身份验证")
                                    return@launch
                                }
                                val result = authenticateForPasswordVault(
                                    host,
                                    "验证身份后查看 ${entry.site} 的密码",
                                )
                                if (!result.success) {
                                    result.message?.let { snackbar.showSnackbar(it) }
                                    return@launch
                                }
                                val password = onDecrypt(entry.id)
                                if (password == null) {
                                    snackbar.showSnackbar("这条密码已损坏，无法解密")
                                } else {
                                    revealed = entry to password
                                }
                            }
                        },
                        onEdit = {
                            menuFor = null
                            editing = entry
                        },
                        onDelete = {
                            menuFor = null
                            pendingDelete = entry
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }

    if (showAdd) {
        PasswordEditorDialog(
            entry = null,
            onDismiss = { showAdd = false },
            onSubmit = { site, username, password, title ->
                if (onSave(site, username, password.orEmpty(), title) == null) {
                    "保存失败：站点需为 HTTPS，或密码为空"
                } else {
                    null
                }
            },
        )
    }

    editing?.let { entry ->
        PasswordEditorDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSubmit = { _, username, password, title ->
                if (onUpdate(entry.id, username, password, title)) {
                    null
                } else {
                    "保存失败：同一站点下用户名不能重复"
                }
            },
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条密码？") },
            text = {
                Text(
                    "将从本机删除 ${entry.site}" +
                        (if (entry.username.isBlank()) "" else "（${entry.username}）") +
                        " 的密码，删除后不可恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch { onDelete(entry.id) }
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    revealed?.let { (entry, password) ->
        AlertDialog(
            onDismissRequest = { revealed = null },
            title = { Text(entry.site) },
            text = {
                Column {
                    if (entry.username.isNotBlank()) {
                        Text(
                            text = "用户名：${entry.username}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        text = "密码",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = password,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        copyPassword(context, password)
                        revealed = null
                        scope.launch {
                            snackbar.showSnackbar("已复制密码，用完请及时清除剪贴板")
                        }
                    },
                ) { Text("复制密码") }
            },
            dismissButton = {
                TextButton(onClick = { revealed = null }) { Text("关闭") }
            },
        )
    }

    passphraseMode?.let { mode ->
        PassphraseDialog(
            mode = mode,
            busy = backupBusy,
            onDismiss = {
                if (!backupBusy) {
                    passphraseMode = null
                    pendingUri = null
                }
            },
            onConfirm = { passphrase ->
                val uri = pendingUri
                if (uri == null) {
                    passphraseMode = null
                    return@PassphraseDialog
                }
                backupBusy = true
                scope.launch {
                    val message = try {
                        if (mode == PassphraseMode.EXPORT) {
                            onExport(uri, passphrase)
                        } else {
                            onImport(uri, passphrase)
                        }
                    } finally {
                        // 口令用完立刻抹掉；明文不在任何状态里多留一秒。
                        passphrase.fill('\u0000')
                        backupBusy = false
                    }
                    passphraseMode = null
                    pendingUri = null
                    snackbar.showSnackbar(message)
                }
            },
        )
    }
}

/** 备份口令的用途：导出时设置新口令，导入时输入已有口令。 */
private enum class PassphraseMode { EXPORT, IMPORT }

/**
 * 备份口令输入框。
 *
 * 导出要输入两次（输错一次就永久拿不回备份）；导入只输一次。
 * 口令只以 [CharArray] 交给调用方，对话框自身不保留任何副本。
 */
@Composable
private fun PassphraseDialog(
    mode: PassphraseMode,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    val export = mode == PassphraseMode.EXPORT
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (export) "设置备份口令" else "输入备份口令") },
        text = {
            Column {
                Text(
                    text = if (export) {
                        "备份文件用这个口令加密，至少 8 位。" +
                            "口令不会被保存，忘记后无法恢复备份。"
                    } else {
                        "输入导出这份备份时设置的口令。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text("口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (export) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = it },
                        label = { Text("确认口令") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (first.length < 8) {
                        error = "口令至少 8 位"
                        return@TextButton
                    }
                    if (export && first != second) {
                        error = "两次输入的口令不一致"
                        return@TextButton
                    }
                    val chars = first.toCharArray()
                    first = ""
                    second = ""
                    error = null
                    onConfirm(chars)
                },
            ) {
                Text(
                    when {
                        busy -> "处理中…"
                        export -> "导出"
                        else -> "导入"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/** 锁定态：自动验证失败后停在这里，不提供任何"跳过"入口。 */
@Composable
private fun LockedState(
    hint: String?,
    authenticating: Boolean,
    modifier: Modifier = Modifier,
    onUnlock: () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 36.dp),
        ) {
            Icon(
                imageVector = LingIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "密码库已锁定",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint ?: "验证指纹、人脸或锁屏密码后查看已保存的密码",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onUnlock, enabled = !authenticating) {
                Text(if (authenticating) "正在等待验证…" else "解锁")
            }
        }
    }
}

/** 列表行：只显示站点/用户名/标题，点击才走二次验证并解出明文。 */
@Composable
private fun PasswordRow(
    entry: PasswordEntry,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onReveal: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onReveal)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.site,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.username.ifBlank { "（无用户名）" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = onOpenMenu) {
                Icon(LingIcons.MoreVert, contentDescription = "更多操作")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(text = { Text("编辑") }, onClick = onEdit)
                DropdownMenuItem(text = { Text("删除") }, onClick = onDelete)
            }
        }
    }
}

/**
 * 新增/编辑弹窗。
 *
 * 站点只在**新增**时可填：已有记录的站点（eTLD+1）是它的安全边界，
 * 允许改站点等于允许把密码"搬家"到另一个域，没有正当用途。
 */
@Composable
private fun PasswordEditorDialog(
    entry: PasswordEntry?,
    onDismiss: () -> Unit,
    /** 返回 null 表示保存成功，否则是给用户看的失败原因。 */
    onSubmit: suspend (site: String, username: String, password: String?, title: String?) -> String?,
) {
    val scope = rememberCoroutineScope()
    var site by remember { mutableStateOf(entry?.site.orEmpty()) }
    var username by remember { mutableStateOf(entry?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var title by remember { mutableStateOf(entry?.title.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (entry == null) "添加密码" else "编辑密码") },
        text = {
            Column {
                if (entry == null) {
                    OutlinedTextField(
                        value = site,
                        onValueChange = { site = it },
                        label = { Text("站点（如 example.com）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text(
                        text = "站点：${entry.site}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = {
                        Text(if (entry == null) "密码" else "新密码（留空则不修改）")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("备注（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    val trimmedSite = site.trim()
                    if (entry == null && trimmedSite.isEmpty()) {
                        error = "请填写站点"
                        return@TextButton
                    }
                    if (entry == null && password.isEmpty()) {
                        error = "请填写密码"
                        return@TextButton
                    }
                    submitting = true
                    error = null
                    scope.launch {
                        val message = onSubmit(
                            trimmedSite,
                            username.trim(),
                            password.takeIf { it.isNotEmpty() },
                            title.trim().takeIf { it.isNotEmpty() },
                        )
                        submitting = false
                        if (message == null) onDismiss() else error = message
                    }
                },
            ) {
                Text(if (submitting) "保存中…" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        },
    )
}

/**
 * 把密码写入剪贴板。
 *
 * Android 13 起标记为敏感内容，系统的剪贴板浮层不会把密码明文显示出来；
 * 更低版本没有这个标记，因此复制后的提示会提醒用户尽快清除。
 */
private fun copyPassword(context: Context, password: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("密码", password)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
}
