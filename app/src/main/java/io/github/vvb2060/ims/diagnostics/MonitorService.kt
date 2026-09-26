package io.github.vvb2060.ims.diagnostics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.os.IBinder
import android.util.Log
import io.github.vvb2060.ims.R
import io.github.vvb2060.ims.ShizukuProvider
import io.github.vvb2060.ims.ui.DiagnosticsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.ShizukuSystemProperties
import java.util.concurrent.atomic.AtomicInteger

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

    // 由采样协程在旧循环停下后切换，主线程也会读取
    @Volatile
    private var targetSubId: Int = -1

    /**
     * 正在进行的异常快照。
     *
     * 完整快照要跑多条 dumpsys / logcat 和一次完整探测，可能持续数十秒；
     * 放在独立协程里执行，采样循环照常按加密节奏继续，劣化期间不会停摆。
     */
    private var captureJob: Job? = null

    /** 唯一的采样循环；重复的启动请求不能再起第二个循环。 */
    private var loopJob: Job? = null

    /**
     * 当前生效的 CarrierConfig 指纹。
     *
     * 读一次要走 Shizuku instrumentation，开销远大于一次采样，
     * 所以只在启动时和收到变更广播时刷新，采样循环直接取这个缓存值。
     */
    @Volatile
    private var configTag: String = MonitorConfigTag.UNKNOWN

    /**
     * 每次发起读取都递增。读取要走 instrumentation，可能排队或变慢，
     * 只有最新一次发起的读取才能发布结果。
     */
    private val configTagGeneration = AtomicInteger()

    /**
     * CarrierConfig 变更广播。
     *
     * 必须监听而不是只信本应用写过什么：实测配置被另一个应用改掉过，
     * 只记自己写的会把两段完全不同的配置错并成一段。
     */
    private val configChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshConfigTag()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        log = MonitorLog(this)
        createChannel()
        // CARRIER_CONFIG_CHANGED 是受保护的系统广播，只有系统能发。
        runCatching {
            registerReceiver(
                configChanged,
                IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED),
                RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "failed to watch carrier config changes", it) }
    }

    private fun refreshConfigTag() {
        val subId = targetSubId
        scope.launch { loadConfigTag(subId) }
    }

    /**
     * 读取 [subId] 的配置指纹并发布到 [configTag]。
     *
     * 读取期间可能已经换卡，或者又发起了更新的读取：这时结果已经过时，直接丢弃，
     * 不能让较早发起、较晚返回的读取用旧值覆盖新值。
     */
    private suspend fun loadConfigTag(subId: Int) {
        val generation = configTagGeneration.incrementAndGet()
        val tag = MonitorConfigTag.read(this@MonitorService, subId)
        if (generation != configTagGeneration.get() || subId != targetSubId) return
        if (tag != configTag) {
            Log.i(TAG, "carrier config now: $tag (was $configTag)")
            configTag = tag
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 拿不到启动 intent 就无从知道目标卡：盲测会把另一张卡的数据混进时间线，不如停止。
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val subId = intent.getIntExtra(EXTRA_SUB_ID, -1)
        // 同一张卡的重复启动（例如连点两次）沿用正在运行的循环，不再另起一个。
        val sameTargetRunning = loopJob?.isActive == true && subId == targetSubId
        startForeground(NOTIFICATION_ID, buildNotification("正在监测 subId=$subId", subId))
        _running.value = true
        if (!sameTargetRunning) {
            // 换卡时先等旧循环完全停下再起新循环，避免两个循环交替探测、交错写同一条时间线。
            // 目标卡也要等旧循环停下之后才切换：否则旧循环里正在进行的探测，
            // 会和新卡的 RAT、信号、subId 拼成同一条样本。
            val previousLoop = loopJob
            loopJob = scope.launch {
                previousLoop?.cancelAndJoin()
                targetSubId = subId
                // 先读到新卡的配置指纹再开始采样：否则头几条样本会带着旧卡的指纹。
                // 读取结果被更新的读取取代时，宁可暂记未知，也不沿用旧卡的值。
                configTag = MonitorConfigTag.UNKNOWN
                loadConfigTag(subId)
                runLoop()
            }
        }
        // 进程被系统回收后重启时，START_STICKY 会以 null intent 回调，目标 subId 随之丢失；
        // START_REDELIVER_INTENT 会重投原始 intent，重启后仍监测同一张卡。
        return START_REDELIVER_INTENT
    }

    private suspend fun runLoop() {
        var previous: MonitorSample? = null
        var lastCaptureAt = 0L
        var fastRemaining = 0
        // 循环可能被单独取消（换卡），因此看本协程自己的状态，而不是整个服务作用域。
        while (currentCoroutineContext().isActive) {
            val startedAt = System.currentTimeMillis()
            val sample = runCatching { takeSample() }.getOrElse {
                // 取消（停止监测或换卡）必须照常向上抛出，不能记成一条采样失败，
                // 否则会凭空多出一条 probe_failed 并触发一次多余的快照。
                if (it is CancellationException) throw it
                Log.w(TAG, "sample failed", it)
                MonitorSample(
                    atMillis = System.currentTimeMillis(),
                    rat = "", validated = "", ipv4 = "",
                    rttMs = null, jitterMs = null, probeOk = false,
                    rsrp = null, sinr = null,
                    thermal = readThermal(),
                    config = configTag,
                    note = "sample_error:${it.javaClass.simpleName}",
                    subId = targetSubId.takeIf { id -> id >= 0 },
                )
            }
            log.append(sample)
            log.flush()

            val reason = AnomalyRule.reasonFor(sample, previous)
            val now = System.currentTimeMillis()
            if (reason != null && now - lastCaptureAt >= CAPTURE_COOLDOWN_MILLIS &&
                captureJob?.isActive != true
            ) {
                lastCaptureAt = now
                _lastTrigger.value = reason
                // 目标卡在发起时就固定下来：快照耗时较长，期间可能已经换卡。
                val captureSubId = targetSubId
                captureJob = scope.launch { captureOnAnomaly(reason, captureSubId) }
            }
            previous = sample
            _sampleCount.value = log.size()

            // 判定为异常就转入加密采样，把这一段劣化的形状测出来。
            // 实测 16 段高抖动里有 13 段只落到一个样本上 —— 在原来的节奏下，
            // 无从判断它持续了 3 秒还是 25 秒。
            if (reason != null) fastRemaining = FAST_BURST_SAMPLES
            val period = if (fastRemaining > 0) {
                fastRemaining--
                FAST_INTERVAL_MILLIS
            } else {
                SAMPLE_INTERVAL_MILLIS
            }
            _fastMode.value = fastRemaining > 0
            updateNotification(sample, reason, fastRemaining > 0)

            // 定频而非「跑完再等固定时长」。
            // 旧写法的真实周期是 15s + 探测耗时，实测中位 27.8s、最长 76.6s，
            // 而且链路越差探测越慢、采样越稀 —— 恰好在最该密集采样时丢分辨率。
            val elapsed = System.currentTimeMillis() - startedAt
            delay((period - elapsed).coerceAtLeast(MIN_GAP_MILLIS))
        }
    }

    /**
     * 轻量采样：只用不需要 shell、开销很低的来源。
     * 完整 dumpsys 留给异常触发时的快照。
     */
    private suspend fun takeSample(): MonitorSample {
        val probe = NetworkProbe.run(
            this,
            targetSubId.takeIf { it >= 0 },
            NetworkProbe.Profile.MONITOR,
        )
        val rat = readRat()
        val signal = readSignal()
        // 测到的不是目标卡（或无法证明是）时，链路指标不属于目标卡：置空，只在 note 里留下判定。
        // 否则会把另一张卡的健康状态记在目标卡名下，还会压掉目标卡本该触发的异常抓取。
        // RAT 与信号按目标卡单独读取，不受影响。
        val measured = probe.measuresTarget
        return MonitorSample(
            atMillis = System.currentTimeMillis(),
            rat = rat,
            validated = if (measured) probe.link.validated?.toString().orEmpty() else "",
            ipv4 = if (measured) probe.link.ipv4.firstOrNull().orEmpty() else "",
            rttMs = if (measured) probe.latency?.avgMs else null,
            jitterMs = if (measured) probe.latency?.jitterMs else null,
            probeOk = measured && probe.ipReachability.ok,
            rsrp = signal?.first,
            sinr = signal?.second,
            thermal = readThermal(),
            config = configTag,
            note = probe.verdict.substringBefore(":"),
            subId = targetSubId.takeIf { it >= 0 },
        )
    }

    /**
     * 读取目标卡当前的 RAT。
     *
     * `gsm.network.type` 在多卡设备上是按逻辑卡槽（phone）序号逗号分隔的列表。
     * 固定取第一项会把卡槽 0 的 RAT 记到卡槽 1 的采样名下，还会据此误触发 rat_change 抓取。
     * 未指定目标卡时沿用第一项；目标卡的卡槽取不到时留空，不拿另一张卡的值充数。
     */
    private fun readRat(): String {
        val values = runCatching { ShizukuSystemProperties.get("gsm.network.type", "") }
            .getOrDefault("")
            .split(',')
        if (targetSubId < 0) return values.firstOrNull().orEmpty()
        return values.getOrNull(SubscriptionManager.getSlotIndex(targetSubId)).orEmpty()
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

    private suspend fun captureOnAnomaly(reason: String, subId: Int) {
        val sim = runCatching { ShizukuProvider.readSimInfoList(this) }
            .getOrDefault(emptyList())
            .firstOrNull { it.subId == subId }
        val snapshot = runCatching {
            // 读 SIM 列表偶发失败时 sim 为 null，目标卡照样按已知的 subId 传下去。
            SnapshotCollector.collect(
                this,
                SnapshotKind.BAD,
                sim,
                targetSubId = subId.takeIf { it >= 0 },
            )
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

    private fun buildNotification(text: String, subId: Int = targetSubId): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            DiagnosticsActivity.intent(this, subId),
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

    private fun updateNotification(sample: MonitorSample, reason: String?, fast: Boolean = false) {
        val text = buildString {
            append(sample.rat.ifBlank { "RAT?" })
            sample.rsrp?.let { append(" · ").append(it).append("dBm") }
            append(" · RTT ").append(sample.rttMs?.toString() ?: "-").append("ms")
            append(" · 抖动 ").append(sample.jitterMs?.toString() ?: "-").append("ms")
            if (sample.thermal.isNotBlank() && sample.thermal != "NONE") {
                append(" · 热 ").append(sample.thermal)
            }
            append(" · 样本 ").append(log.size())
            if (fast) append(" · 加密采样")
            if (reason != null) append(" · 已捕获")
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(configChanged) }
        log.flush()
        scope.cancel()
        _running.value = false
        _fastMode.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "diagnostics_monitor"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "io.github.vvb2060.ims.MONITOR_STOP"
        const val EXTRA_SUB_ID = "sub_id"

        /**
         * 常态采样周期（**定频**，含探测自身耗时）。
         *
         * 不宜再压：探测本身要发起真实连接，而实测这台机在 NR SA 下
         * 86.5% 的发射时间顶在最高功率档，采样越密越热，
         * 热缓解就越频繁地对 modem 下发节流 —— 那是拿观测去扰动被观测对象。
         * 常态留稀、异常转密，比一味缩短间隔更划算。
         */
        const val SAMPLE_INTERVAL_MILLIS = 20_000L

        /** 异常后的加密采样周期。 */
        const val FAST_INTERVAL_MILLIS = 5_000L

        /** 加密采样的持续条数；按 5 秒一条约覆盖 1 分钟。 */
        const val FAST_BURST_SAMPLES = 12

        /**
         * 两次采样之间的最小间隔。
         *
         * 定频调度下，若探测耗时超过周期，delay 会算出负数而变成连续背靠背探测。
         * 满功率发射的情况下那会明显加剧发热，必须留出硬下限。
         */
        const val MIN_GAP_MILLIS = 1_000L

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

        private val _fastMode = MutableStateFlow(false)
        val fastMode: StateFlow<Boolean> = _fastMode.asStateFlow()

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
