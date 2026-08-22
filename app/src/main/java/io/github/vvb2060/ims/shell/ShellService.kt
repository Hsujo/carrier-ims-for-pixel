package io.github.vvb2060.ims.shell

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * 由 Shizuku 在 shell uid 进程中实例化的用户服务。
 *
 * 应用自身的 uid 读不到 radio 日志缓冲区，也无权执行 dumpsys，
 * 诊断采集只能经由这里。该进程由 Shizuku 拉起，与应用进程分离。
 */
class ShellService : IShellService.Stub {
    /** Shizuku 要求存在无参或 (Context) 构造函数。 */
    constructor()

    @Suppress("UNUSED_PARAMETER")
    constructor(context: android.content.Context)

    override fun execToFd(
        command: Array<out String>?,
        stdoutFd: ParcelFileDescriptor?,
        stderrFd: ParcelFileDescriptor?,
        timeoutMillis: Int,
        maxOutputBytes: Int,
    ): String {
        if (command.isNullOrEmpty()) return EXIT_ERROR
        val timeout = timeoutMillis.coerceIn(1_000, MAX_TIMEOUT_MILLIS).toLong()
        val cap = maxOutputBytes.coerceIn(1_024, MAX_OUTPUT_BYTES)

        val out = stdoutFd?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
        val err = stderrFd?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
        var process: Process? = null
        return try {
            // 不经 shell 解析，参数按数组传递，避免注入。
            process = ProcessBuilder(command.toList()).start()
            val proc = process
            proc.outputStream.closeQuietly()

            // stdout 与 stderr 必须并发转发：任一管道写满都会让子进程阻塞，
            // 串行读取会直接死锁。
            val outThread = thread(name = "shell-stdout") { proc.inputStream.pipeTo(out, cap) }
            val errThread = thread(name = "shell-stderr") { proc.errorStream.pipeTo(err, cap) }

            val finished = proc.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!finished) proc.destroyForcibly()
            outThread.join(JOIN_TIMEOUT_MILLIS)
            errThread.join(JOIN_TIMEOUT_MILLIS)

            if (finished) proc.exitValue().toString() else EXIT_TIMEOUT
        } catch (t: Throwable) {
            Log.w(TAG, "exec failed: ${command.joinToString(" ")}", t)
            runCatching {
                err?.write("${t.javaClass.simpleName}: ${t.message ?: "no message"}\n".toByteArray())
            }
            EXIT_ERROR
        } finally {
            runCatching { process?.destroy() }
            out.closeQuietly()
            err.closeQuietly()
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    /**
     * 转发到上限为止，随后继续排空但不再写出。
     *
     * 直接停止读取会让子进程卡在写阻塞上，因此必须读完，
     * 只是不再落盘 —— 这样既有上限，又不会挂住进程。
     */
    private fun InputStream.pipeTo(target: OutputStream?, maxBytes: Int) {
        var total = 0
        var truncated = false
        try {
            use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    if (total >= maxBytes) continue
                    val take = minOf(read, maxBytes - total)
                    runCatching { target?.write(buffer, 0, take) }
                    total += take
                    if (total >= maxBytes && !truncated) {
                        truncated = true
                        runCatching {
                            target?.write("\n[truncated at $maxBytes bytes]\n".toByteArray())
                        }
                    }
                }
            }
            runCatching { target?.flush() }
        } catch (t: Throwable) {
            Log.w(TAG, "stream pipe failed", t)
        }
    }

    private fun OutputStream?.closeQuietly() {
        runCatching { this?.close() }
    }

    companion object {
        private const val TAG = "ShellService"
        const val EXIT_TIMEOUT = "timeout"
        const val EXIT_ERROR = "error"

        /** 单条命令最长执行时间。 */
        const val MAX_TIMEOUT_MILLIS = 60_000

        /** 单条命令各流的落盘上限，杜绝无上限写入。 */
        const val MAX_OUTPUT_BYTES = 8 * 1024 * 1024
        private const val JOIN_TIMEOUT_MILLIS = 5_000L
    }
}
