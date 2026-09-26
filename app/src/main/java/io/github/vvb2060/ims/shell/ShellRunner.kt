package io.github.vvb2060.ims.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.vvb2060.ims.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 一条命令的执行结果。
 *
 * 三个字段分开保留：某条命令失败不应中断整次采集，
 * 因此失败信息必须随结果一起落盘，而不是抛出。
 */
data class CommandResult(
    val command: String,
    val exitCode: String,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == "0"

    /** 落盘用的完整文本，成功与否都保留原始输出。 */
    fun toReportText(): String = buildString {
        appendLine("$ $command")
        appendLine("exit: $exitCode")
        if (stdout.isNotEmpty()) {
            appendLine("--- stdout ---")
            appendLine(stdout)
        }
        if (stderr.isNotEmpty()) {
            appendLine("--- stderr ---")
            appendLine(stderr)
        }
    }

    companion object {
        fun unavailable(command: String, reason: String) =
            CommandResult(command, "unavailable", "", reason)
    }
}

/**
 * 通过 Shizuku 用户服务执行 shell 命令。
 *
 * 绑定是惰性的，且整个应用共用一个连接；调用方无需关心生命周期。
 * 任何失败都以 [CommandResult] 返回而不抛出，保证单条命令失败不会中断采集。
 */
object ShellRunner {
    private const val TAG = "ShellRunner"
    private const val BIND_TIMEOUT_MILLIS = 15_000L

    private val bindMutex = Mutex()

    @Volatile
    private var service: IShellService? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(false)
        .version(BuildConfig.VERSION_CODE)

    private var pending: CompletableDeferred<IShellService?>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = if (binder != null && binder.pingBinder()) {
                IShellService.Stub.asInterface(binder)
            } else {
                null
            }
            service = bound
            pending?.complete(bound)
            Log.i(TAG, "shell user service connected: ${bound != null}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "shell user service disconnected")
            service = null
            pending?.complete(null)
        }
    }

    /** Shizuku 是否已就绪到可以绑定用户服务。 */
    fun isShizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private suspend fun obtainService(): IShellService? {
        service?.let { if (runCatching { it.asBinder().pingBinder() }.getOrDefault(false)) return it }
        if (!isShizukuReady()) return null
        return bindMutex.withLock {
            service?.let { if (runCatching { it.asBinder().pingBinder() }.getOrDefault(false)) return it }
            val deferred = CompletableDeferred<IShellService?>()
            pending = deferred
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (t: Throwable) {
                Log.w(TAG, "bindUserService failed", t)
                pending = null
                return null
            }
            val bound = withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { deferred.await() }
            pending = null
            if (bound == null) Log.w(TAG, "shell user service bind timed out")
            bound
        }
    }

    /**
     * 执行一条命令。失败不抛出，原因随 [CommandResult] 返回。
     *
     * 输出经由临时文件与 ParcelFileDescriptor 传回，不走 Binder 返回值：
     * Binder 单次事务上限约 1MB，dumpsys telephony.registry 与 radio 日志
     * 都会超过，用返回值会以 DeadObjectException / binder buffer full 失败。
     */
    suspend fun exec(
        context: Context,
        command: List<String>,
        timeoutMillis: Int = 15_000,
        maxOutputBytes: Int = 4 * 1024 * 1024,
    ): CommandResult = withContext(Dispatchers.IO) {
        val display = command.joinToString(" ")
        val svc = obtainService()
            ?: return@withContext CommandResult.unavailable(
                display, "shell service unavailable (Shizuku not ready or bind failed)"
            )

        val dir = File(context.cacheDir, "shell").apply { mkdirs() }
        val stamp = System.nanoTime()
        val outFile = File(dir, "out_$stamp")
        val errFile = File(dir, "err_$stamp")
        try {
            val exit = ParcelFileDescriptor.open(
                outFile.apply { createNewFile() },
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
            ).use { outPfd ->
                ParcelFileDescriptor.open(
                    errFile.apply { createNewFile() },
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
                ).use { errPfd ->
                    svc.execToFd(
                        command.toTypedArray(), outPfd, errPfd, timeoutMillis, maxOutputBytes
                    )
                }
            }
            CommandResult(
                command = display,
                exitCode = exit ?: "error",
                stdout = runCatching { outFile.readText() }.getOrDefault(""),
                stderr = runCatching { errFile.readText() }.getOrDefault(""),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "exec failed: $display", t)
            // Binder 断了就丢弃缓存的引用，下次重新绑定。
            service = null
            CommandResult.unavailable(display, "${t.javaClass.simpleName}: ${t.message ?: "no message"}")
        } finally {
            runCatching { outFile.delete() }
            runCatching { errFile.delete() }
        }
    }
}
