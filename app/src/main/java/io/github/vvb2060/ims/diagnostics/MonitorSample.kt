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
    /**
     * 采样时刻的设备热状态（PowerManager.getCurrentThermalStatus 的名称）。
     *
     * 实测 radio 日志里热缓解一直在下发 SET_DATA_THROTTLING，因此必须能验证
     * 「卡顿是不是热降频引起的」。之前只能靠 radio 日志的时间窗反推，
     * 140 条样本里只有 110 条落在日志覆盖范围内，其余无从判断。
     */
    val thermal: String,
    /**
     * 采样时刻生效的 CarrierConfig 指纹，见 [MonitorConfigTag]。
     *
     * 配置可能在监测途中被改（实测一次 6 小时的监测里 NR 模式变了两次），
     * 没有这一列就无法把时间线切成可比的 A/B 分段。
     */
    val config: String,
    val note: String,
    /**
     * 采样针对的 subId；未指定目标卡时为 null。
     *
     * 时间线会跨会话保留，先后监测过两张卡时，没有这一列就分不清哪条样本属于哪张卡。
     */
    val subId: Int? = null,
) {
    /**
     * 这条样本的探测没测到目标卡（或无法证明测到了），链路指标已置空。
     * 它说明的是「测不到目标卡」，不是目标卡的链路故障。
     */
    val probedOtherSim: Boolean
        get() = note == NetworkProbe.VERDICT_WRONG_SIM || note == NetworkProbe.VERDICT_UNVERIFIED_SIM

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
        thermal,
        config,
        subId?.toString() ?: "",
        note.replace(',', ';').replace('\n', ' '),
    ).joinToString(",")

    companion object {
        // 信号必须与抖动同行记录：只有并排看才能判断卡顿是不是弱信号导致的。
        // 实测证明两者不相关（-88dBm/SINR21 时抖动 4681ms），
        // 而当初时间线没有信号列，只能逐个打开快照才发现。
        // note 是自由文本，保持在最后一列；sub_id 紧挨在它前面。
        const val CSV_HEADER =
            "at_millis,rat,validated,ipv4,rtt_ms,jitter_ms,probe_ok,rsrp,sinr,thermal,config,sub_id,note"

        /**
         * [toCsvRow] 的逆操作，重新开始监测时用来把已落盘的时间线装回内存。
         *
         * 各列在写入时已保证不含逗号，因此可以直接按逗号切分。
         * 列数不对或时间戳、probe_ok 解析失败时返回 null，由调用方跳过该行。
         */
        fun fromCsvRow(row: String): MonitorSample? {
            val values = row.split(",")
            if (values.size != CSV_HEADER.split(",").size) return null
            return MonitorSample(
                atMillis = values[0].toLongOrNull() ?: return null,
                rat = values[1],
                validated = values[2],
                ipv4 = values[3],
                rttMs = values[4].toLongOrNull(),
                jitterMs = values[5].toLongOrNull(),
                probeOk = values[6].toBooleanStrictOrNull() ?: return null,
                rsrp = values[7].toIntOrNull(),
                sinr = values[8].toIntOrNull(),
                thermal = values[9],
                config = values[10],
                subId = values[11].toIntOrNull(),
                note = values[12],
            )
        }
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
        // 没测到目标卡时既不算「通」也不算「不通」，不能当作目标卡的故障去抓快照。
        if (sample.probedOtherSim) return null
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
