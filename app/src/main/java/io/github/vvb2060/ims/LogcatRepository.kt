package io.github.vvb2060.ims

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import io.github.vvb2060.ims.model.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LogcatRepository {
    private const val TAG = "LogcatRepository"
    private const val MAX_LOG_LINES = 2000
    private const val FLUSH_INTERVAL_MS = 200L

    private val _logs = mutableStateListOf<LogEntry>()
    val logs: List<LogEntry> = _logs

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()

    private var logProcess: Process? = null

    @Volatile
    private var isCapturing = false

    // 每次开始抓取递增，旧会话结束时不会误清理新会话的状态
    @Volatile
    private var sessionId = 0

    fun isCapturing(): Boolean = isCapturing

    fun startLogcat() {
        val currentSession = synchronized(lock) {
            if (isCapturing) return
            isCapturing = true
            ++sessionId
        }

        repositoryScope.launch(Dispatchers.IO) {
            val pending = ArrayList<LogEntry>()
            var process: Process? = null
            var flusher: Job? = null
            try {
                Log.i(TAG, "start read logcat")
                withContext(Dispatchers.Main) { _logs.clear() }
                val processBuilder = ProcessBuilder(listOf("logcat", "-v", "threadtime"))
                processBuilder.redirectErrorStream(true)
                process = processBuilder.start()
                synchronized(lock) {
                    if (sessionId == currentSession) {
                        logProcess = process
                    } else {
                        // 启动过程中已被 stopAndClear
                        process.destroy()
                        return@launch
                    }
                }

                // 解析在 IO 线程完成，按固定间隔批量提交到主线程，避免每行一次线程切换和重组
                flusher = launch {
                    while (isActive) {
                        delay(FLUSH_INTERVAL_MS)
                        flushPending(pending, currentSession)
                    }
                }

                val bufferedReader = process.inputStream.bufferedReader()

                var line: String? = null

                while (isActive && bufferedReader.readLine().also { line = it } != null) {
                    if (!isCapturing || sessionId != currentSession) break
                    line?.let { rawLog ->
                        val entry = LogEntry.parseLog(rawLog)
                        synchronized(pending) { pending.add(entry) }
                    }
                }
                flushPending(pending, currentSession)
            } catch (e: Exception) {
                Log.e(TAG, "read logcat error", e)
            } finally {
                flusher?.cancel()
                process?.destroy()
                synchronized(lock) {
                    if (sessionId == currentSession) {
                        logProcess = null
                        isCapturing = false
                    }
                }
            }
        }
    }

    private suspend fun flushPending(pending: MutableList<LogEntry>, session: Int) {
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            ArrayList(pending).also { pending.clear() }
        }
        withContext(Dispatchers.Main) {
            if (session != sessionId) return@withContext
            _logs.addAll(batch)
            val overflow = _logs.size - MAX_LOG_LINES
            if (overflow > 0) {
                _logs.removeRange(0, overflow)
            }
        }
    }

    fun clearLogs() {
        _logs.clear()
    }

    fun stopAndClear() {
        Log.d(TAG, "killing logcat process")
        synchronized(lock) {
            sessionId++
            logProcess?.destroy()
            logProcess = null
            isCapturing = false
        }
    }
}
