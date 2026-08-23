package io.github.vvb2060.ims.diagnostics

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import io.github.vvb2060.ims.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把已保存的快照打包成一个 ZIP，并通过 FileProvider 分享。
 *
 * 走应用私有的 external cache + FileProvider，不需要 MANAGE_EXTERNAL_STORAGE。
 */
object DiagnosticsExporter {
    private const val TAG = "DiagnosticsExporter"
    private const val EXPORT_DIR = "exports"
    private const val FILE_PROVIDER_SUFFIX = ".logcat_fileprovider"

    /** 导出目录只保留最近若干个包，避免反复导出把缓存撑满。 */
    private const val MAX_EXPORTS = 5

    private val stampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    data class Export(val file: File, val uri: Uri, val snapshotCount: Int)

    /**
     * 打包全部快照。
     *
     * @param mccMnc 用于文件名，便于区分不同运营商的采样。
     */
    suspend fun export(context: Context, mccMnc: String?): Result<Export> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshots = SnapshotStore.list(context)
                val timeline = MonitorLog(context).file()
                // 只跑过后台监测、还没有快照时，时间线本身也值得导出。
                require(snapshots.isNotEmpty() || timeline.isFile) { "没有可导出的快照或监测时间线" }

                val exportDir = File(context.externalCacheDir ?: context.cacheDir, EXPORT_DIR)
                    .apply { mkdirs() }
                val stamp = stampFormat.format(Date())
                val tag = mccMnc?.filter { it.isDigit() }?.ifBlank { null } ?: "unknown"
                val zipFile = File(exportDir, "TurboIMS_${deviceTag()}_${tag}_$stamp.zip")

                val summaries = mutableMapOf<SnapshotKind, SnapshotSummary>()

                ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                    snapshots.forEach { snapshot ->
                        val files = snapshot.directory.listFiles()?.filter { it.isFile }.orEmpty()
                        files.forEach { file ->
                            // 单个文件读写失败不应让整包导出失败。
                            runCatching {
                                zip.putNextEntry(ZipEntry("${snapshot.name}/${file.name}"))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }.onFailure { Log.w(TAG, "failed to add ${file.name}", it) }
                        }
                        // 取每种类型最新的一份用于对比。
                        if (!summaries.containsKey(snapshot.kind)) {
                            val texts = files.associate { it.name to runCatching { it.readText() }.getOrDefault("") }
                            summaries[snapshot.kind] = SnapshotSummary.fromFiles(
                                snapshot.name, snapshot.kind, texts
                            )
                        }
                    }

                    // 后台监测的时间线：自动捕获的快照只是若干个点，
                    // 时间线才能说明劣化持续了多久、是否周期性发生。
                    if (timeline.isFile) {
                        runCatching {
                            zip.putNextEntry(ZipEntry("monitor_timeline.csv"))
                            timeline.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }.onFailure { Log.w(TAG, "failed to add monitor timeline", it) }
                    }

                    writeEntry(zip, "metadata.json", buildMetadataJson(snapshots))
                    writeEntry(
                        zip,
                        "comparison.txt",
                        SnapshotComparison.toText(
                            summaries[SnapshotKind.BAD],
                            summaries[SnapshotKind.GOOD],
                        )
                    )
                    writeEntry(zip, "README.txt", buildReadme())
                }

                pruneOldExports(exportDir)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${BuildConfig.APPLICATION_ID}$FILE_PROVIDER_SUFFIX",
                    zipFile,
                )
                Export(zipFile, uri, snapshots.size)
            }
        }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        runCatching {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }.onFailure { Log.w(TAG, "failed to write $name", it) }
    }

    private fun deviceTag(): String =
        android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "").ifBlank { "device" }

    private fun buildMetadataJson(snapshots: List<SnapshotStore.StoredSnapshot>): String {
        val root = JSONObject()
        root.put("app_version", BuildConfig.VERSION_NAME)
        root.put("application_id", BuildConfig.APPLICATION_ID)
        root.put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        root.put("android_release", android.os.Build.VERSION.RELEASE)
        root.put("android_sdk", android.os.Build.VERSION.SDK_INT)
        root.put("build_display", android.os.Build.DISPLAY)
        root.put("build_fingerprint", android.os.Build.FINGERPRINT)
        // 记录 modem 固件版本：系统大版本升级会一并刷新它，
        // 而「问题是不是随升级出现的」只有留下这一项才能事后比对。
        root.put(
            "baseband",
            runCatching { android.os.Build.getRadioVersion() }.getOrNull()?.ifBlank { null }
                ?: "UNKNOWN"
        )
        root.put("exported_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))

        val array = JSONArray()
        snapshots.forEach { snapshot ->
            array.put(
                JSONObject().apply {
                    put("name", snapshot.name)
                    put("kind", snapshot.kind.name)
                    put("taken_at_millis", snapshot.takenAtMillis)
                    put("files", snapshot.directory.listFiles()?.size ?: 0)
                }
            )
        }
        root.put("snapshots", array)
        return root.toString(2)
    }

    private fun buildReadme(): String = """
        TurboIMS 5G 诊断包
        ==================

        目录结构：
          metadata.json      导出信息与快照清单
          monitor_timeline.csv  后台监测的采样时间线（启用过监测时才有）
                             含 rsrp/sinr/thermal 列，可直接判断卡顿
                             与信号强度、热降频是否相关
          comparison.txt     BAD / GOOD 对照（样本齐备时才有内容）
          BAD_5G_*/          故障态快照
          GOOD_*/            正常态快照
            metadata.txt     设备、SIM、CarrierConfig 关键项
            summary.txt      解析出的机器可读摘要
            probes.txt       链路状态与连通性探测
            dumpsys_*.txt    原始 dumpsys 输出（含 thermalservice 热状态）
            ip_*.txt         接口与路由
            logcat_*.txt     radio 日志（有界）

        关于隐私
        --------
        summary.txt 与 metadata.txt 只保留 ICCID 后四位、subId、slot、MCC/MNC，
        不含完整 ICCID、IMSI 或电话号码。

        但 dumpsys 与 radio 原始日志由系统生成，**可能包含 IMSI、完整 ICCID、
        IMEI 等身份标识**。保留原始输出是为了不丢证据（parser 认不出的格式
        仍可人工分析），代价是这些文件比摘要敏感。

        对外分享前请自行确认，或只发送 summary.txt / comparison.txt。

        关于 UNKNOWN
        ------------
        summary.txt 中的 UNKNOWN 表示 parser 未能从该 Android 版本的输出里
        识别出该字段，**不代表系统里没有对应状态**。请查阅同目录原始 dump。
    """.trimIndent()

    private fun pruneOldExports(dir: File) {
        val zips = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        zips.drop(MAX_EXPORTS).forEach {
            Log.i(TAG, "pruning old export ${it.name}")
            runCatching { it.delete() }
        }
    }
}
