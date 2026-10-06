package com.ling.browser.data.repo

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.ling.browser.data.db.DownloadEntry
import com.ling.browser.data.db.DownloadStatus
import com.ling.browser.data.db.LingDatabase
import com.ling.browser.data.db.downloadValues
import com.ling.browser.data.db.toDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载仓库。
 *
 * 职责划分：真正的下载交给系统 [DownloadManager]（断点续传、通知栏进度、
 * 网络切换重试都由它兜底，自己写一遍毫无胜算），本类只负责
 *   1. 把下载请求交给系统并记下返回的 ID；
 *   2. 需要展示时回查系统，把状态/进度/落盘路径同步回本地表。
 *
 * 为什么每次刷新都要回查系统：DownloadManager 没有推送式回调，
 * 只能轮询查询。因此 [refresh] 是「拉」而不是「推」，由 UI 在可见时定时调用。
 */
class DownloadRepository(
    private val context: Context,
    private val db: LingDatabase,
) {

    private val manager: DownloadManager? =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    init {
        refresh()
    }

    /** 从数据库读出记录，并向系统补齐每个记录的实时状态。 */
    fun refresh() {
        val rows = queryAll()
        val synced = rows.map { syncFromSystem(it) }
        // 只有真的变了才写回，避免每次轮询都打一遍数据库
        synced.filterIndexed { i, e -> e != rows[i] }.forEach { persist(it) }
        _downloads.value = synced.sortedByDescending { it.createdAt }
    }

    /**
     * 发起一次下载。
     *
     * 返回 false 表示请求没能交给系统（常见原因：SDK < 29 且用户未授予
     * 存储权限，或系统下载服务不可用）。调用方据此提示用户。
     */
    suspend fun enqueue(url: String, fileName: String?, mimeType: String?): Boolean =
        withContext(Dispatchers.IO) {
            val dm = manager ?: return@withContext false
            // 非 http(s) 一律不接（blob: 与 data: 需要 JS 侧另行处理）
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext false
            }

            val name = fileName?.takeIf { it.isNotBlank() }
                ?: url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
                ?: "download"

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(name)
                setDescription("来自「翎」浏览器")
                // 允许在移动网络下下载；大文件用户可在系统设置里限制
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                mimeType?.let { setMimeType(it) }
                // 落到应用外部私有目录（Android/data/<pkg>/files/Download），
                // 无需任何存储权限，卸载时随之清理。
                setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    name,
                )
            }

            val id = runCatching { dm.enqueue(request) }.getOrNull()
                ?: return@withContext false

            db.writableDatabase.insertWithOnConflict(
                "downloads",
                null,
                downloadValues(
                    downloadId = id,
                    fileName = name,
                    url = url,
                    totalBytes = 0,
                    downloadedBytes = 0,
                    mimeType = mimeType,
                    status = DownloadStatus.PENDING,
                    localPath = null,
                    createdAt = System.currentTimeMillis(),
                ),
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
            refresh()
            true
        }

    /** 取消一个进行中的下载。 */
    suspend fun cancel(entry: DownloadEntry) = withContext(Dispatchers.IO) {
        manager?.remove(entry.downloadId)
        // 本地状态直接标失败，不必等系统回查 —— 系统那边记录已经没了
        persist(entry.copy(status = DownloadStatus.FAILED))
        refresh()
    }

    /** 删除记录。若文件还在且调用方要求，一并删除本地文件。 */
    suspend fun remove(entry: DownloadEntry, deleteFile: Boolean) = withContext(Dispatchers.IO) {
        if (entry.isRunning) manager?.remove(entry.downloadId)
        if (deleteFile) {
            entry.localPath?.let { path -> runCatching { File(path).delete() } }
        }
        db.writableDatabase.delete("downloads", "id = ?", arrayOf(entry.id.toString()))
        refresh()
    }

    suspend fun clearAll(deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        _downloads.value.forEach { entry ->
            if (entry.isRunning) manager?.remove(entry.downloadId)
            if (deleteFiles) {
                entry.localPath?.let { path -> runCatching { File(path).delete() } }
            }
        }
        db.writableDatabase.delete("downloads", null, null)
        refresh()
    }

    // ---------------------------------------------------------------- 内部

    private fun queryAll(): List<DownloadEntry> = runCatching {
        db.readableDatabase.rawQuery(
            "SELECT * FROM downloads ORDER BY created_at DESC",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toDownload()) } }
    }.getOrDefault(emptyList())

    /**
     * 向系统查询单条下载的实时状态。
     *
     * 查询失败（记录已被用户从系统下载列表里清掉）时保留本地状态，
     * 但把仍在进行中的标记为失败 —— 否则会出现永远转圈的条目。
     */
    private fun syncFromSystem(entry: DownloadEntry): DownloadEntry {
        val dm = manager ?: return entry
        val cursor = runCatching {
            dm.query(DownloadManager.Query().setFilterById(entry.downloadId))
        }.getOrNull() ?: return entry

        cursor.use { c ->
            if (!c.moveToFirst()) {
                return if (entry.isRunning) entry.copy(status = DownloadStatus.FAILED) else entry
            }

            val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val doneIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val localIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

            val status = if (statusIdx >= 0) c.getInt(statusIdx) else -1
            val total = if (totalIdx >= 0) c.getLong(totalIdx) else 0L
            val done = if (doneIdx >= 0) c.getLong(doneIdx) else 0L
            val localUri = if (localIdx >= 0 && !c.isNull(localIdx)) c.getString(localIdx) else null

            val mapped = when (status) {
                DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.SUCCESS
                DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                else -> entry.status
            }

            return entry.copy(
                status = mapped,
                // 系统在未拿到 Content-Length 时会返回 -1，统一夹到 0
                totalBytes = total.coerceAtLeast(0L),
                downloadedBytes = done.coerceAtLeast(0L),
                localPath = localUri?.removePrefix("file://") ?: entry.localPath,
            )
        }
    }

    private fun persist(entry: DownloadEntry) {
        runCatching {
            val values = ContentValues().apply {
                put("status", entry.status.name)
                put("total_bytes", entry.totalBytes)
                put("downloaded_bytes", entry.downloadedBytes)
                if (entry.localPath == null) putNull("local_path")
                else put("local_path", entry.localPath)
            }
            db.writableDatabase.update(
                "downloads",
                values,
                "id = ?",
                arrayOf(entry.id.toString()),
            )
        }
    }
}
