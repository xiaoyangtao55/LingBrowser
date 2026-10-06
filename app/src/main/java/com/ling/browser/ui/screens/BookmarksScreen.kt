package com.ling.browser.ui.screens

import com.ling.browser.ui.theme.LingIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ling.browser.data.db.Bookmark

/**
 * 书签页，支持一层文件夹。
 *
 * 只有**一层**：进入某个文件夹后看到的就是书签本身，不能再往下钻。
 * 这是刻意取舍 —— 「翎」的定位是极简，而手机屏幕上的多层树需要
 * 面包屑 + 深度指示，收益远不如实现与心智成本。Chrome 手机版
 * 同样是扁平列表。
 *
 * 用一个 [currentFolder] 状态而不是嵌套导航栈：只有一层，
 * `null = 根目录`、非空 = 在某文件夹内，返回键就是回到 null。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    bookmarks: List<Bookmark>,
    folders: List<String>,
    onOpen: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onMoveToFolder: (Long, String?) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // null = 根目录
    var currentFolder by remember { mutableStateOf<String?>(null) }

    // 若当前文件夹被删空（里面最后一个书签被删/被移走），
    // derivedFolders 里就没有它了。此时必须退回根目录，
    // 否则用户会卡在一个标题显示着、内容永远为空的页面上出不去。
    if (currentFolder != null && currentFolder !in folders) {
        currentFolder = null
    }

    val inCurrent = bookmarks.filter { it.folder == currentFolder }
    val loose = bookmarks.filter { it.folder == null }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(currentFolder ?: "书签") },
                navigationIcon = {
                    IconButton(
                        onClick = { if (currentFolder != null) currentFolder = null else onBack() },
                    ) {
                        Icon(LingIcons.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (currentFolder != null) {
                        FolderMenu(
                            folder = currentFolder!!,
                            onRenameFolder = onRenameFolder,
                            onDeleteFolder = { onDeleteFolder(it); currentFolder = null },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { inner ->
        // 根目录下既没有书签也没有文件夹时才是真空
        if (bookmarks.isEmpty()) {
            EmptyState(
                icon = LingIcons.BookmarkBorder,
                title = "还没有书签",
                hint = "浏览网页时点击「更多 → 添加书签」",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // 文件夹只出现在根目录
            if (currentFolder == null && folders.isNotEmpty()) {
                item {
                    Text(
                        text = "文件夹",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp),
                    )
                }
                items(folders, key = { "folder:$it" }) { folder ->
                    val count = bookmarks.count { it.folder == folder }
                    ListItemRow(
                        title = folder,
                        subtitle = "$count 个书签",
                        onClick = { currentFolder = folder },
                        leading = {
                            Icon(
                                LingIcons.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
                if (loose.isNotEmpty()) {
                    item {
                        Text(
                            text = "未分类",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
                        )
                    }
                }
            }

            items(inCurrent, key = { it.id }) { bookmark ->
                BookmarkRow(
                    bookmark = bookmark,
                    folders = folders,
                    showFolderTag = currentFolder == null,
                    onOpen = { onOpen(bookmark.url) },
                    onDelete = { onDelete(bookmark.id) },
                    onMoveToFolder = { onMoveToFolder(bookmark.id, it) },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }

            // 空文件夹（理论上进不来，因为 folders 由书签派生；
            // 但删除过程中可能出现短暂的空窗，给个提示而不是白屏）
            if (inCurrent.isEmpty() && currentFolder != null) {
                item {
                    Text(
                        text = "这个文件夹是空的",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }
}

/** 单条书签：尾部是「移到文件夹」+「删除」。 */
@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    folders: List<String>,
    showFolderTag: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFolder: (String?) -> Unit,
) {
    var showMove by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    ListItemRow(
        title = bookmark.title,
        subtitle = bookmark.url,
        onClick = onOpen,
        leading = {
            Icon(
                LingIcons.BookmarkBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        trailing = {
            // 在根目录时显示归属文件夹的小标签，让用户一眼看出这条属于哪
            if (showFolderTag && bookmark.folder != null) {
                FolderTag(bookmark.folder)
                Spacer(Modifier.width(4.dp))
            }
            Box {
                IconButton(onClick = { showMove = true }) {
                    Icon(
                        LingIcons.FolderOpen,
                        contentDescription = "移到文件夹",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = showMove, onDismissRequest = { showMove = false }) {
                    DropdownMenuItem(
                        text = { Text("未分类") },
                        enabled = bookmark.folder != null,
                        onClick = { onMoveToFolder(null); showMove = false },
                    )
                    folders.forEach { f ->
                        DropdownMenuItem(
                            text = { Text(f) },
                            // 已经在里面了就不给点，避免"移了个寂寞"
                            enabled = f != bookmark.folder,
                            onClick = { onMoveToFolder(f); showMove = false },
                        )
                    }
                    HorizontalDivider()
                    // 没有这一项的话就**永远建不出第一个文件夹** ——
                    // 文件夹由"有书签归属"派生，而把书签放进新文件夹
                    // 这条路径只能从这里进入。
                    DropdownMenuItem(
                        text = { Text("新建文件夹…") },
                        leadingIcon = { Icon(LingIcons.Add, contentDescription = null) },
                        onClick = { creating = true; showMove = false },
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    LingIcons.DeleteOutline,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )

    if (creating) {
        TextInputDialog(
            title = "新建文件夹",
            initial = "",
            label = "文件夹名",
            confirmText = "创建",
            // 新建 = 把这条书签移进去，文件夹随之产生。
            // 这里不再单独校验重名：同名就相当于"移进已有文件夹"，
            // 结果符合直觉，报错反而多余。
            onConfirm = { onMoveToFolder(it); creating = false },
            onDismiss = { creating = false },
        )
    }
}

/** 文件夹名的小标签。 */
@Composable
private fun FolderTag(name: String) {
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 80.dp),
        )
    }
}

/** 文件夹的重命名 / 删除菜单。 */
@Composable
private fun FolderMenu(
    folder: String,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(LingIcons.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("重命名文件夹") },
                onClick = { renaming = true; expanded = false },
            )
            DropdownMenuItem(
                text = { Text("删除文件夹") },
                onClick = { confirmDelete = true; expanded = false },
            )
        }
    }

    if (renaming) {
        TextInputDialog(
            title = "重命名文件夹",
            initial = folder,
            label = "文件夹名",
            confirmText = "重命名",
            onConfirm = { onRenameFolder(folder, it); renaming = false },
            onDismiss = { renaming = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除文件夹") },
            // 明确说清书签不会跟着被删 —— 这是用户最容易担心的点
            text = { Text("删除「$folder」后，其中的书签会移到未分类，不会被删除。") },
            confirmButton = {
                TextButton(onClick = { onDeleteFolder(folder); confirmDelete = false }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 通用单行文本输入对话框。 */
@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            // 空名字直接禁用按钮，比弹一个"不能为空"的提示更省事
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 空状态占位。 */
@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 通用的「标题 + 副标题 + 尾部操作」列表行。 */
@Composable
fun ListItemRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}
