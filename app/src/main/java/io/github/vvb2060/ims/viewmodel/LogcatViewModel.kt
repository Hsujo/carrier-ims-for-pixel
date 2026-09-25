package io.github.vvb2060.ims.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import io.github.vvb2060.ims.BuildConfig
import io.github.vvb2060.ims.LogcatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class LogcatViewModel(application: Application) : AndroidViewModel(application) {
    val logs = LogcatRepository.logs

    init {
        LogcatRepository.startLogcat()
    }

    fun clearLogs() {
        LogcatRepository.clearLogs()
    }

    override fun onCleared() {
        super.onCleared()
        // 离开日志页即停止抓取；再次进入时 logcat 会先输出 logd 缓冲区，已有日志不会丢失
        LogcatRepository.stopAndClear()
    }

    fun exportLogFile() {
        // 在主线程取快照，避免 IO 线程遍历时与新日志写入冲突
        val rawLines = logs.map { it.raw }
        viewModelScope.launch(Dispatchers.IO) {
            File(application.externalCacheDir, "turbo_ims.log").apply {
                bufferedWriter().use { writer ->
                    writer.write("App Version: ${BuildConfig.VERSION_NAME}\n")
                    writer.write("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                    writer.write("Android Version: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                    writer.write("System Build Version: ${Build.DISPLAY}\n")
                    writer.write("Security Patch Version: ${Build.VERSION.SECURITY_PATCH}\n")
                    writer.write("-----------------------------------------------------------------")
                    writer.write("TurboIms Logcat:\n")
                    rawLines.forEach {
                        writer.write(it)
                        writer.write("\n")
                    }
                }

                val authority = "${application.packageName}.logcat_fileprovider"
                val uri = FileProvider.getUriForFile(application, authority, this)
                application.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .putExtra(Intent.EXTRA_STREAM, uri),
                        "Export Logcat"
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}