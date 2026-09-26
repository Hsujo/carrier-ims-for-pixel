package io.github.vvb2060.ims.privileged

import android.app.IActivityManager
import android.system.Os
import android.telephony.TelephonyFrameworkInitializer
import android.util.Log
import com.android.internal.telephony.ITelephony
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

// Instrumentation 与 App 同进程运行，Shizuku binder 已由 ShizukuProvider 收到，
// 未就绪时直接返回失败，不再 sleep 轮询等待。
internal fun isShizukuBinderReady(): Boolean = Shizuku.pingBinder()

internal fun readImsRegistered(subId: Int): Boolean {
    val telephony = ITelephony.Stub.asInterface(
        ShizukuBinderWrapper(
            TelephonyFrameworkInitializer
                .getTelephonyServiceManager()
                .getTelephonyServiceRegisterer()
                .get()!!
        )
    )
    return telephony.isImsRegistered(subId)
}

/**
 * Shell 权限委托的兼容封装。
 *
 * Android 17 (API 37) 的部分构建上，`IActivityManager` 不再提供与本应用编译期一致的
 * `stopDelegateShellPermissionIdentity()`，直接调用会抛出 [NoSuchMethodError]。
 * 该错误若从 `finally` 块逸出，会覆盖掉一次**已经成功**的主操作
 * （例如 `CarrierConfigManager.overrideConfig()`），使调用方误判为失败。
 *
 * 因此这里把「主操作」和「权限委托清理」严格分开：
 *  - [tryStartShellPermissionDelegation] 是主操作的前置条件，失败即返回 false，由调用方判定失败；
 *  - [tryStopShellPermissionDelegation] 只做清理，**永不抛出**，失败时返回 Throwable 供调用方记录为警告。
 */
internal object ShellPermissionDelegation {
    private const val TAG = "ShellPermissionDelegation"
    private const val METHOD_STOP = "stopDelegateShellPermissionIdentity"

    // 同一进程只打印一次设备上的候选签名，避免每次特权调用都刷屏
    @Volatile
    private var stopCandidatesLogged = false

    /**
     * 尝试开启 shell 权限委托。
     *
     * 这是主操作的前置条件：返回 false 表示后续特权调用注定失败，调用方应当直接判定为失败，
     * 而不是继续执行并收到一个更难解释的 SecurityException。
     *
     * @return 成功开启返回 true；返回 false 时 [failure] 携带原因。
     */
    fun start(am: IActivityManager, tag: String, failure: (Throwable) -> Unit = {}): Boolean {
        return try {
            am.startDelegateShellPermissionIdentity(Os.getuid(), null)
            Log.i(tag, "started shell permission delegation")
            true
        } catch (t: Throwable) {
            if (!isCompatFailure(t)) throw t
            Log.w(tag, "failed to start shell permission delegation", t)
            failure(t)
            false
        }
    }

    /**
     * 尝试关闭 shell 权限委托。**永不抛出。**
     *
     * 委托停不掉是真实副作用（本应用的 uid 会继续持有 shell 权限），所以在直接调用因
     * 签名不匹配而失败后，再用反射按方法名兜底一次，尽量真正停下来。
     *
     * 另：AOSP 中由 shell 启动、带 UiAutomationConnection 的 instrumentation 结束（finish）时，
     * AMS 也会撤销该委托，而各特权类都在清理后立即 finish；这里主动停止是为了尽早收回。
     *
     * @return 清理成功返回 null；失败返回原因，调用方应当记录为警告而非主操作失败。
     */
    fun stop(am: IActivityManager, tag: String): Throwable? {
        try {
            am.stopDelegateShellPermissionIdentity()
            Log.i(tag, "stopped shell permission delegation")
            return null
        } catch (t: Throwable) {
            if (!isCompatFailure(t)) {
                Log.w(tag, "failed to stop shell permission delegation", t)
                return t
            }
            Log.w(tag, "stopDelegateShellPermissionIdentity() unavailable, trying reflection", t)
            val reflected = stopByReflection(am, tag)
            if (reflected == null) {
                Log.i(tag, "stopped shell permission delegation via reflection")
                return null
            }
            reflected.addSuppressed(t)
            return reflected
        }
    }

    /**
     * 按方法名而非编译期签名查找并调用停止方法，用于兼容参数列表发生变化的框架版本。
     */
    private fun stopByReflection(am: IActivityManager, tag: String): Throwable? {
        return try {
            val method = am.javaClass.methods.firstOrNull {
                it.name == METHOD_STOP && it.parameterTypes.isEmpty()
            } ?: run {
                logStopCandidates(am, tag)
                throw NoSuchMethodException("$METHOD_STOP() not found on ${am.javaClass.name}")
            }
            method.invoke(am)
            null
        } catch (t: Throwable) {
            Log.w(tag, "reflective $METHOD_STOP failed, delegation may still be active", t)
            t
        }
    }

    /**
     * 无参的停止方法不存在时，打印设备上与委托相关的全部方法签名。
     *
     * 新系统上的替代签名无法事先得知，不能靠猜参数去调用；
     * 先把真实签名记进日志，再据此做精确适配。
     */
    private fun logStopCandidates(am: IActivityManager, tag: String) {
        if (stopCandidatesLogged) return
        stopCandidatesLogged = true
        runCatching {
            val candidates = am.javaClass.methods
                .filter { it.name.contains("DelegateShellPermission") }
                .map { method ->
                    "${method.name}(${method.parameterTypes.joinToString { it.name }})"
                }
                .sorted()
            Log.w(tag, "delegate shell permission methods on ${am.javaClass.name}: $candidates")
        }.onFailure { Log.w(tag, "failed to list delegate shell permission methods", it) }
    }

    /**
     * 判断异常是否属于 hidden API 兼容性失败或权限问题。
     *
     * 只有这一类才允许被降级处理；其他异常（例如 NPE、IllegalState）说明代码本身有问题，
     * 必须继续向上抛出，避免「简单吞掉所有异常」。
     */
    internal fun isCompatFailure(t: Throwable): Boolean = when (t) {
        is LinkageError,
        is ReflectiveOperationException,
        is SecurityException,
        is UnsupportedOperationException,
            -> true

        else -> t.javaClass.name == "android.os.RemoteException" ||
            t.javaClass.name == "android.os.DeadObjectException"
    }
}

/**
 * @return 成功开启返回 true；失败返回 false，调用方应判定主操作失败。
 */
internal fun IActivityManager.tryStartShellPermissionDelegation(
    tag: String,
    failure: (Throwable) -> Unit = {},
): Boolean = ShellPermissionDelegation.start(this, tag, failure)

/**
 * @return 清理成功返回 null；失败返回原因（应记录为警告，不得覆盖主操作结果）。
 */
internal fun IActivityManager.tryStopShellPermissionDelegation(tag: String): Throwable? =
    ShellPermissionDelegation.stop(this, tag)
