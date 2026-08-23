package io.github.vvb2060.ims.diagnostics

/**
 * 一次轻量采样。
 *
 * 持续监测必须便宜：完整 dumpsys 有数百 KB，每十几秒跑一次既费电又无意义。
 * 因此常态只采这些低成本项，只有在判定异常时才触发一次完整快照。
 */
data class MonitorSample(
    val atMillis: Long,
    val rat: String,
    val validated: String,
    val ipv4: String,
    val rttMs: Long?,
    val jitterMs: Long?,
    val probeOk: Boolean,
    /** 服务小区的 RSRP / SINR；读不到时为 null。 */
    val rsrp: Int?,
    val sinr: Int?,
    val note: String,
) {
    fun toCsvRow(): String = listOf(
        atMillis.toString(),
        rat,
        validated,
        ipv4,
        rttMs?.toString() ?: "",
        jitterMs?.toString() ?: "",
        probeOk.toString(),
        rsrp?.toString() ?: "",
        sinr?.toString() ?: "",
        note.replace(',', ';').replace('\n', ' '),
    ).joinToString(",")

    companion object {
        // 信号必须与抖动同行记录：只有并排看才能判断卡顿是不是弱信号导致的。
        // 实测证明两者不相关（-88dBm/SINR21 时抖动 4681ms），
        // 而当初时间线没有信号列，只能逐个打开快照才发现。
        const val CSV_HEADER =
            "at_millis,rat,validated,ipv4,rtt_ms,jitter_ms,probe_ok,rsrp,sinr,note"
    }
}

/**
 * 判断一次采样是否构成「现场」，值得触发完整快照。
 *
 * 判据刻意与实测症状对齐：用户报告的故障是「连得上但卡」，
 * 只看通断会漏掉，因此抖动与 RTT 同样计入。
 */
object AnomalyRule {
    /** 秒级停顿：实测 5G 抖动 4663ms，而正常 4G 仅 46.9ms。 */
    const val JITTER_TRIGGER_MS = 1_500L

    /** 单次 RTT 上限；超过这个值网页基本已经不可用。 */
    const val RTT_TRIGGER_MS = 3_000L

    fun reasonFor(sample: MonitorSample, previous: MonitorSample?): String? {
        if (!sample.probeOk) return "probe_failed"
        sample.jitterMs?.let { if (it >= JITTER_TRIGGER_MS) return "jitter_${it}ms" }
        sample.rttMs?.let { if (it >= RTT_TRIGGER_MS) return "rtt_${it}ms" }
        // RAT 切换本身不是故障，但前后各一份样本能解释「切过去之后为什么变差」。
        if (previous != null && previous.rat != sample.rat &&
            sample.rat.isNotBlank() && previous.rat.isNotBlank()
        ) {
            return "rat_change_${previous.rat}_to_${sample.rat}"
        }
        return null
    }
}
