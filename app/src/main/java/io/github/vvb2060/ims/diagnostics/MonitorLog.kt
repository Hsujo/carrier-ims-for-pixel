package io.github.vvb2060.ims.diagnostics

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 采样时间线的有界存储。
 *
 * 文档明确要求：持续监测只允许 ring-buffer / bounded storage，
 * 严禁无上限写日志造成存储增长。这里用固定容量的环形缓冲，
 * 超出容量即丢弃最旧的样本，落盘时整体重写，文件大小因此有硬上限。
 */
class MonitorLog(private val context: Context) {

    private val buffer = ArrayDeque<MonitorSample>()
    private val lock = Any()

    fun append(sample: MonitorSample) {
        synchronized(lock) {
            buffer.addLast(sample)
            while (buffer.size > MAX_SAMPLES) buffer.removeFirst()
        }
    }

    fun snapshotSamples(): List<MonitorSample> = synchronized(lock) { buffer.toList() }

    fun size(): Int = synchronized(lock) { buffer.size }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        runCatching { file().delete() }
    }

    /**
     * 整体重写而非追加：环形缓冲已经丢弃了旧样本，追加会让文件无限增长，
     * 与「有界存储」的要求相悖。
     */
    fun flush() {
        val rows = snapshotSamples()
        runCatching {
            file().writeText(
                buildString {
                    appendLine(MonitorSample.CSV_HEADER)
                    rows.forEach { appendLine(it.toCsvRow()) }
                }
            )
        }.onFailure { Log.w(TAG, "failed to flush monitor log", it) }
    }

    fun file(): File {
        val dir = File(SnapshotStore.rootDir(context), DIR_NAME).apply { mkdirs() }
        return File(dir, FILE_NAME)
    }

    companion object {
        private const val TAG = "MonitorLog"
        private const val DIR_NAME = "monitor"
        const val FILE_NAME = "timeline.csv"

        /**
         * 容量上限。按常态 20 秒一次采样，2880 条约覆盖 16 小时；
         * 异常触发的加密采样期间覆盖时长相应缩短。
         * 单行约 70 字节，文件上限约 200KB。
         */
        const val MAX_SAMPLES = 2880
    }
}
