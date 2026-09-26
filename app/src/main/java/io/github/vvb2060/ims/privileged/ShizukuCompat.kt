package io.github.vvb2060.ims.privileged

import android.telephony.TelephonyFrameworkInitializer
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
