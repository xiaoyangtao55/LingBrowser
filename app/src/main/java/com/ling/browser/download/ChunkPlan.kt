package com.ling.browser.download

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 一个文件的分块下载计划。
 *
 * 把整个文件按 [chunkSize] 切成连续的几段，每段用一条独立的 Range 请求下载，
 * 多条连接并行跑 —— 这是单文件下载提速的主要手段（尤其是服务端按连接限速时）。
 *
 * 关键约束：分块写入会让文件出现「空洞」，[DownloadDestination.Target.length]
 * 在这种状态下**不能**当作进度用（它只会返回最高写入位置）。所以分块任务必须
 * 依赖 [ChunkPlan.written] 这个逐块进度表来续传，而进度表由 [ChunkStore] 落在磁盘上。
 *
 * @property total     文件总字节数（探测阶段从服务器得到）
 * @property chunkSize 每块长度；最后一块可能不足
 * @property written   每块已写入的字节数（相对该块起点）
 * @property path      目标落点（[DownloadDestination.Target.displayPath]），
 *                     用于确认现场没有张冠李戴到别的文件
 */
internal data class ChunkPlan(
    val total: Long,
    val chunkSize: Long,
    val written: LongArray,
    val path: String,
) {
    val chunkCount: Int get() = written.size

    fun chunkStart(index: Int): Long = index * chunkSize

    /** 该块的完整长度（最后一块可能短于 [chunkSize]）。 */
    fun chunkLength(index: Int): Long =
        minOf(total, chunkStart(index) + chunkSize) - chunkStart(index)

    fun downloaded(): Long = written.indices.sumOf { written[it] }

    fun isComplete(): Boolean = written.indices.all { written[it] >= chunkLength(it) }

    companion object {
        /**
         * 分块数量。
         *
         * 4 是「提速」与「服务端反感」之间的折中：再多的连接对单文件收益递减，
         * 还容易触发站点限流。整机并发上限（3 个任务）会与它相乘，实际连接数
         * 最多 12 条，仍在合理范围。
         */
        const val CHUNK_COUNT = 4

        /**
         * 启用分块的最小文件体积。
         *
         * 小文件用多条连接只会多付几次握手与请求头，反而更慢；8 MB 以下一律
         * 走原来的单流下载。
         */
        const val MIN_TOTAL_BYTES = 8L * 1024 * 1024

        /** 为 [total] 生成一个全新（进度全 0）的计划；太小则不适用，返回 null。 */
        fun of(total: Long, path: String): ChunkPlan? {
            if (total < MIN_TOTAL_BYTES) return null
            val size = (total + CHUNK_COUNT - 1) / CHUNK_COUNT
            return ChunkPlan(total, size, LongArray(CHUNK_COUNT), path)
        }
    }
}

/**
 * 分块进度的落盘。
 *
 * 为什么不用数据库：进度是高频写的临时数据，塞进 downloads 表要么加列、要么
 * 每次都更新整行；而且它是「下载现场」而不是用户可见的记录，任务删除时应当
 * 一并清掉。单独一个小 JSON 文件最贴合这个生命周期。
 *
 * 文件按 taskId 命名（主键在进程重启后不变），所以崩溃后能精确配对回原任务。
 */
internal object ChunkStore {

    private const val DIR_NAME = "download-state"

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun file(context: Context, taskId: Long): File =
        File(dir(context), "$taskId.json")

    /** 读回现场；文件不存在、内容损坏、或字段缺失时返回 null（当作没有现场）。 */
    fun load(context: Context, taskId: Long): ChunkPlan? = runCatching {
        val f = file(context, taskId)
        if (!f.exists()) return null
        val json = JSONObject(f.readText())
        val writtenJson = json.getJSONArray("written")
        val written = LongArray(writtenJson.length()) { writtenJson.getLong(it) }
        ChunkPlan(
            total = json.getLong("total"),
            chunkSize = json.getLong("chunkSize"),
            written = written,
            path = json.getString("path"),
        )
    }.getOrNull()

    /** 保存现场。写临时文件再改名，避免进程在写一半时被杀留下半个 JSON。 */
    fun save(context: Context, taskId: Long, plan: ChunkPlan) {
        runCatching {
            val d = dir(context)
            if (!d.exists()) d.mkdirs()
            val json = JSONObject().apply {
                put("total", plan.total)
                put("chunkSize", plan.chunkSize)
                put("path", plan.path)
                put("written", org.json.JSONArray(plan.written.toList()))
            }
            val target = file(context, taskId)
            val tmp = File(d, "$taskId.json.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(target)) {
                // 个别文件系统 rename 覆盖会失败，退化为直接写
                target.writeText(json.toString())
                tmp.delete()
            }
        }
    }

    fun delete(context: Context, taskId: Long) {
        runCatching { file(context, taskId).delete() }
    }

    /** 清空所有现场（用于「清除全部下载」）。 */
    fun clearAll(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }
}
