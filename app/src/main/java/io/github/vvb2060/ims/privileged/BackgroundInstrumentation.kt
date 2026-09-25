package io.github.vvb2060.ims.privileged

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log

/**
 * 以 INSTR_FLAG_NO_RESTART 启动的 Instrumentation，onCreate 运行在 App 主线程。
 * 这里只保存参数并调用 start()，实际的特权调用放到 Instrumentation 自带线程的 onStart() 中执行，
 * 避免 CarrierConfig 读写、APN 写入、配置导出等 binder 调用阻塞界面。
 */
abstract class BackgroundInstrumentation : Instrumentation() {
    private var pendingArguments: Bundle? = null

    @Volatile
    private var finished = false

    final override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        pendingArguments = arguments
        start()
    }

    final override fun onStart() {
        super.onStart()
        try {
            execute(pendingArguments)
        } catch (t: Throwable) {
            // 主线程 onCreate 抛出的异常会被框架吞掉，后台线程则会让进程崩溃，这里兜底并结束本次调用
            Log.e(javaClass.simpleName, "instrumentation failed", t)
            if (!finished) {
                finish(Activity.RESULT_CANCELED, Bundle())
            }
        }
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        finished = true
        super.finish(resultCode, results)
    }

    /**
     * 在 Instrumentation 线程执行，结束时必须调用 finish()。
     */
    protected abstract fun execute(arguments: Bundle?)
}
