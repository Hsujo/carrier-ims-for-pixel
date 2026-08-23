package io.github.vvb2060.ims.diagnostics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.github.vvb2060.ims.R
import io.github.vvb2060.ims.ShizukuProvider
import io.github.vvb2060.ims.ui.DiagnosticsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.ShizukuSystemProperties

/**
 * 后台持续监测。
 *
 * 人工点击必然错过窗口 —— 抖动、瞬时掉数据这类现象往往几秒就过去了。
 * 该服务以固定间隔做**轻量**采样写入有界时间线，只有在采样判定为异常时
 * 才触发一次完整快照，并对触发做限流，避免既费电又堆满存储。
 *
 * 仅做读取，不修改任何系统配置；必须由用户显式启动，通知栏常驻可随时停止。
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var log: MonitorLog
    private var targetSubId: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        log = MonitorLog(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        targetSubId = intent?.getIntExtra(EXTRA_SUB_ID, -1) ?: -1
        startForeground(NOTIFICATION_ID, buildNotification("正在监测 subId=$targetSubId"))
        _running.value = true
        scope.launch { runLoop() }
        return START_STICKY
    }

    private suspend fun runLoop() {
        var previous: MonitorSample? = null
        var lastCaptureAt = 0L
        while (scope.isActive) {
            val sample = runCatching { takeSample() }.getOrElse {
                Log.w(TAG, "sample failed", it)
                MonitorSample(
                    atMillis = System.currentTimeMillis(),
                    rat = "", validated = "", ipv4 = "",
                    rttMs = null, jitterMs = null, probeOk = false,
                    rsrp = null, sinr = null,
                    thermal = readThermal(),
                    note = "sample_error:${it.javaClass.simpleName}",
                )
            }
            log.append(sample)
            log.flush()

            val reason = AnomalyRule.reasonFor(sample, previous)
            val now = System.currentTimeMillis()
            if (reason != null && now - lastCaptureAt >= CAPTURE_COOLDOWN_MILLIS) {
                lastCaptureAt = now
                _lastTrigger.value = reason
                captureOnAnomaly(reason)
            }
            previous = sample
            _sampleCount.value = log.size()
            updateNotification(sample, reason)
            delay(SAMPLE_INTERVAL_MILLIS)
        }
    }

    /**
     * 轻量采样：只用不需要 shell、开销很低的来源。
     * 完整 dumpsys 留给异常触发时的快照。
     */
    private suspend fun takeSample(): MonitorSample {
        val probe = NetworkProbe.run(this, targetSubId.takeIf { it >= 0 })
        val rat = runCatching { ShizukuSystemProperties.get("gsm.network.type", "") }
            .getOrDefault("")
            .split(',')
            .firstOrNull()
            .orEmpty()
        val signal = readSignal()
        return MonitorSample(
            atMillis = System.currentTimeMillis(),
            rat = rat,
            validated = probe.link.validated?.toString().orEmpty(),
            ipv4 = probe.link.ipv4.firstOrNull().orEmpty(),
            rttMs = probe.latency?.avgMs,
            jitterMs = probe.latency?.jitterMs,
            probeOk = probe.ipReachability.ok,
            rsrp = signal?.first,
            sinr = signal?.second,
            thermal = readThermal(),
            note = probe.verdict.substringBefore(":"),
        )
    }

    /**
     * 读取服务小区的 RSRP / SINR。
     *
     * 用 TelephonyManager 直读而非 dumpsys：后者输出数百 KB，按采样频率跑不现实。
     * Android 17 上部分 telephony 接口会抛 SecurityException，
     * 因此读不到就返回 null，绝不让它中断采样。
     *
     * @return (rsrp, sinr)；NR 优先，回落 LTE。
     */
    private fun readSignal(): Pair<Int?, Int?>? = runCatching {
        val tm = getSystemService(android.telephony.TelephonyManager::class.java)
            ?.createForSubscriptionId(targetSubId)
            ?: return null
        val cells = tm.signalStrength?.cellSignalStrengths ?: return null
        val nr = cells.filterIsInstance<android.telephony.CellSignalStrengthNr>().firstOrNull()
        if (nr != null && nr.ssRsrp != Int.MAX_VALUE) {
            return nr.ssRsrp to nr.ssSinr.takeIf { it != Int.MAX_VALUE }
        }
        val lte = cells.filterIsInstance<android.telephony.CellSignalStrengthLte>().firstOrNull()
        if (lte != null && lte.rsrp != Int.MAX_VALUE) {
            return lte.rsrp to lte.rssnr.takeIf { it != Int.MAX_VALUE }
        }
        null
    }.getOrNull()

    /**
     * 读取设备热状态。
     *
     * 真机 radio 日志里热缓解一直在下发 SET_DATA_THROTTLING（0/1/2 反复切换），
     * 所以「卡是不是热降频造成的」必须能直接验证，而不是靠 radio 日志时间窗反推。
     * 该 API 无需权限、开销极低，适合按采样频率调用。
     */
    private fun readThermal(): String = runCatching {
        when (val status = getSystemService(android.os.PowerManager::class.java)
            ?.currentThermalStatus ?: return "") {
            android.os.PowerManager.THERMAL_STATUS_NONE -> "NONE"
            android.os.PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            android.os.PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            android.os.PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "STATUS_$status"
        }
    }.getOrDefault("")

    private suspend fun captureOnAnomaly(reason: String) {
        val sim = runCatching { ShizukuProvider.readSimInfoList(this) }
            .getOrDefault(emptyList())
            .firstOrNull { it.subId == targetSubId }
        val snapshot = runCatching {
            SnapshotCollector.collect(this, SnapshotKind.BAD, sim)
        }.getOrNull() ?: return
        SnapshotStore.write(this, snapshot)
        Log.i(TAG, "auto-captured snapshot ${snapshot.name} due to $reason")
        _autoCaptures.value = _autoCaptures.value + 1
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitor_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.monitor_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            DiagnosticsActivity.intent(this, targetSubId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.monitor_stop), stop).build()
            )
            .build()
    }

    private fun updateNotification(sample: MonitorSample, reason: String?) {
        val text = buildString {
            append(sample.rat.ifBlank { "RAT?" })
            sample.rsrp?.let { append(" · ").append(it).append("dBm") }
            append(" · RTT ").append(sample.rttMs?.toString() ?: "-").append("ms")
            append(" · 抖动 ").append(sample.jitterMs?.toString() ?: "-").append("ms")
            if (sample.thermal.isNotBlank() && sample.thermal != "NONE") {
                append(" · 热 ").append(sample.thermal)
            }
            append(" · 样本 ").append(log.size())
            if (reason != null) append(" · 已捕获")
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    override fun onDestroy() {
        log.flush()
        scope.cancel()
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "diagnostics_monitor"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "io.github.vvb2060.ims.MONITOR_STOP"
        const val EXTRA_SUB_ID = "sub_id"

        /** 采样间隔。太密会明显耗电与流量，15 秒足以抓住持续数十秒的劣化。 */
        const val SAMPLE_INTERVAL_MILLIS = 15_000L

        /** 触发完整快照的冷却时间，避免持续劣化时反复采集塞满存储。 */
        const val CAPTURE_COOLDOWN_MILLIS = 5 * 60_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _sampleCount = MutableStateFlow(0)
        val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

        private val _autoCaptures = MutableStateFlow(0)
        val autoCaptures: StateFlow<Int> = _autoCaptures.asStateFlow()

        private val _lastTrigger = MutableStateFlow<String?>(null)
        val lastTrigger: StateFlow<String?> = _lastTrigger.asStateFlow()

        fun start(context: Context, subId: Int) {
            val intent = Intent(context, MonitorService::class.java).putExtra(EXTRA_SUB_ID, subId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MonitorService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
