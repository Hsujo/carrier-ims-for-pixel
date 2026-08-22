package io.github.vvb2060.ims.diagnostics

import android.content.Context
import android.os.Build
import android.telephony.CarrierConfigManager
import android.util.Log
import io.github.vvb2060.ims.BuildConfig
import io.github.vvb2060.ims.ShizukuProvider
import io.github.vvb2060.ims.model.FeatureConfigMapper
import io.github.vvb2060.ims.model.NrMode
import io.github.vvb2060.ims.model.SimSelection
import io.github.vvb2060.ims.shell.CommandResult
import io.github.vvb2060.ims.shell.ShellRunner
import rikka.shizuku.ShizukuSystemProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 快照类型。BAD 记录故障现场，GOOD 记录正常状态，两者用于对照。 */
enum class SnapshotKind(val prefix: String) {
    BAD("BAD_5G"),
    GOOD("GOOD"),
}

/** 一次采集的全部产物。原始输出一律保留，解析失败不得丢证据。 */
data class Snapshot(
    val kind: SnapshotKind,
    val name: String,
    val takenAtMillis: Long,
    val metadata: Map<String, String>,
    val commands: List<CommandResult>,
    val probe: NetworkProbe.Result?,
    val probeError: String?,
)

/**
 * 现场诊断采集。
 *
 * 原则：
 * - 单条命令失败绝不终止整次采集，exit / stdout / stderr 分别保留；
 * - 所有输出有上限，禁止无上限增长；
 * - 只在用户显式点击时执行，不做任何后台持续采集。
 */
object SnapshotCollector {
    private const val TAG = "SnapshotCollector"

    /** radio 缓冲区只取最近若干行，避免体积失控。 */
    private const val RADIO_LOG_LINES = 3000
    private const val RADIO_LOG_MAX_BYTES = 2 * 1024 * 1024
    private const val DUMPSYS_MAX_BYTES = 1024 * 1024
    private const val SMALL_MAX_BYTES = 128 * 1024

    /** IMS dump 的服务名各版本不一致，按可能性依次尝试。 */
    private val IMS_SERVICE_CANDIDATES = listOf("telephony.ims", "ims", "imsbinder")

    private val nameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    suspend fun collect(
        context: Context,
        kind: SnapshotKind,
        selectedSim: SimSelection?,
        onProgress: (String) -> Unit = {},
    ): Snapshot {
        val now = System.currentTimeMillis()
        val name = "${kind.prefix}_${nameFormat.format(Date(now))}"

        onProgress("采集设备与 SIM 元数据")
        val metadata = collectMetadata(context, selectedSim)

        val commands = mutableListOf<CommandResult>()

        // dumpsys：Android 17 上普通 TelephonyManager 会抛 SecurityException，
        // 这些才是实时驻网状态的可靠来源。
        val dumpsysTargets = listOf(
            "telephony.registry" to DUMPSYS_MAX_BYTES,
            "connectivity" to DUMPSYS_MAX_BYTES,
            "carrier_config" to DUMPSYS_MAX_BYTES,
            "isub" to SMALL_MAX_BYTES,
        )
        for ((svc, cap) in dumpsysTargets) {
            onProgress("dumpsys $svc")
            commands += ShellRunner.exec(context, listOf("dumpsys", svc), timeoutMillis = 20_000, maxOutputBytes = cap)
        }

        // 真机确认该设备上没有名为 "ims" 的服务（Can't find service: ims），
        // 服务名各版本不一致，因此先列出全部服务再逐个尝试候选名。
        onProgress("dumpsys ims")
        commands += ShellRunner.exec(
            context, listOf("dumpsys", "-l"), maxOutputBytes = SMALL_MAX_BYTES
        )
        for (candidate in IMS_SERVICE_CANDIDATES) {
            val result = ShellRunner.exec(
                context,
                listOf("dumpsys", candidate),
                timeoutMillis = 20_000,
                maxOutputBytes = DUMPSYS_MAX_BYTES,
            )
            commands += result
            // 命中即止；"Can't find service" 说明这个名字在本版本不存在。
            if (result.isSuccess && !result.stderr.contains("Can't find service")) break
        }

        onProgress("采集网络接口与路由")
        commands += ShellRunner.exec(context, listOf("ip", "addr"), maxOutputBytes = SMALL_MAX_BYTES)
        // Android 用策略路由，默认表通常是空的（真机上 ip route 返回空且 exit=0），
        // 必须查全部路由表才能看到每条 PDN 的默认路由。
        commands += ShellRunner.exec(
            context, listOf("ip", "route", "show", "table", "all"), maxOutputBytes = SMALL_MAX_BYTES
        )
        commands += ShellRunner.exec(
            context, listOf("ip", "-6", "route", "show", "table", "all"),
            maxOutputBytes = SMALL_MAX_BYTES,
        )
        commands += ShellRunner.exec(context, listOf("ip", "rule", "show"), maxOutputBytes = SMALL_MAX_BYTES)

        onProgress("采集 radio 日志")
        commands += ShellRunner.exec(
            context,
            listOf("logcat", "-b", "radio", "-d", "-v", "threadtime", "-t", RADIO_LOG_LINES.toString()),
            timeoutMillis = 30_000,
            maxOutputBytes = RADIO_LOG_MAX_BYTES,
        )

        onProgress("连通性探测")
        var probe: NetworkProbe.Result? = null
        var probeError: String? = null
        try {
            probe = NetworkProbe.run(context, selectedSim?.subId)
        } catch (t: Throwable) {
            // 探测失败也必须让快照生成，其余证据仍然有价值。
            Log.w(TAG, "network probe failed", t)
            probeError = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
        }

        return Snapshot(kind, name, now, metadata, commands, probe, probeError)
    }

    private suspend fun collectMetadata(
        context: Context,
        selectedSim: SimSelection?,
    ): Map<String, String> {
        val meta = linkedMapOf<String, String>()
        meta["app_version"] = BuildConfig.VERSION_NAME
        meta["application_id"] = BuildConfig.APPLICATION_ID
        meta["device"] = "${Build.MANUFACTURER} ${Build.MODEL}"
        meta["android_release"] = Build.VERSION.RELEASE
        meta["android_sdk"] = Build.VERSION.SDK_INT.toString()
        meta["build_display"] = Build.DISPLAY
        meta["security_patch"] = Build.VERSION.SECURITY_PATCH
        meta["taken_at"] = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

        // 双卡时「哪张卡在承载数据」直接决定该看谁的状态。
        meta["default_data_sub_id"] = runCatching {
            android.telephony.SubscriptionManager.getDefaultDataSubscriptionId().toString()
        }.getOrElse { "UNKNOWN (${it.javaClass.simpleName})" }

        if (selectedSim != null) {
            meta["sub_id"] = selectedSim.subId.toString()
            meta["slot_index"] = selectedSim.simSlotIndex.toString()
            meta["mcc_mnc"] = "${selectedSim.mcc}/${selectedSim.mnc}"
            meta["carrier"] = selectedSim.carrierName
            // 只保留 ICCID 后四位，完整 ICCID / IMSI / 号码一律不落盘。
            meta["iccid_last4"] = selectedSim.iccId.takeLast(4)
        } else {
            meta["sub_id"] = "(none selected)"
        }

        // getprop 走 Shizuku 直读，不需要外部进程。
        listOf(
            "gsm.network.type",
            "gsm.operator.numeric",
            "gsm.operator.alpha",
            "gsm.sim.state",
        ).forEach { key ->
            meta["prop.$key"] = runCatching { ShizukuSystemProperties.get(key, "") }
                .getOrElse { "(read failed: ${it.javaClass.simpleName})" }
                .ifBlank { "(empty)" }
        }

        val subId = selectedSim?.subId ?: -1
        if (subId >= 0) {
            val bundle = runCatching {
                ShizukuProvider.readCarrierConfig(context, subId, FeatureConfigMapper.readKeys)
            }.getOrNull()
            if (bundle == null) {
                meta["carrier_config"] = "(read failed)"
            } else {
                val nr = bundle.getIntArray(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY)
                meta["carrier_nr_availabilities_int_array"] = NrMode.formatAvailabilities(nr)
                meta["nr_mode"] = NrMode.fromAvailabilities(nr)?.name ?: "UNKNOWN"
                FeatureConfigMapper.readKeys.forEach { key ->
                    if (bundle.containsKey(key)) {
                        meta["cc.$key"] = formatBundleValue(bundle.get(key))
                    }
                }
            }
            meta["ims_registered"] = runCatching {
                ShizukuProvider.readImsRegistrationStatus(context, subId)?.toString() ?: "UNKNOWN"
            }.getOrElse { "UNKNOWN (${it.javaClass.simpleName})" }
        }
        return meta
    }

    @Suppress("DEPRECATION")
    private fun formatBundleValue(value: Any?): String = when (value) {
        null -> "(null)"
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]")
        else -> value.toString()
    }
}
