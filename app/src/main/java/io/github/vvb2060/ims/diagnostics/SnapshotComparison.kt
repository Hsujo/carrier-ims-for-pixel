package io.github.vvb2060.ims.diagnostics

/**
 * BAD 与 GOOD 快照的对照。
 *
 * 现场排查最有用的不是某一次的绝对值，而是**故障态与正常态之间变了什么**。
 * 因此这里只回答一个问题：哪些字段发生了变化。
 */
object SnapshotComparison {

    /**
     * 文档要求重点关注的字段，排在最前，其余字段按原顺序跟在后面。
     */
    private val PRIORITY_FIELDS = listOf(
        "ps_registration_state",
        "nr_state",
        "nsa_sa_clue",
        "data_network_state",
        "data_connection_state",
        "apn",
        "ipv4",
        "ipv6",
        "default_route",
        "dns",
        "validated",
        "ip_probe",
        "dns_probe",
        "probe_verdict",
        "reject_cause",
        // 弱信号导致的「连接着但不通」，只有射频指标能区分。
        // 抖动是「连得上但卡」的指纹，只看通断会漏掉。
        "rtt_jitter_ms",
        "rtt_max_ms",
        "latency_verdict",
        "signal_quality",
        "lte_rsrp",
        "lte_rssnr",
        "is_endc_available",
    )

    data class FieldDiff(
        val field: String,
        val bad: String,
        val good: String,
        val priority: Boolean,
    ) {
        val changed: Boolean get() = bad != good
    }

    /**
     * @return null 表示缺少可比的样本对。
     */
    fun compare(bad: SnapshotSummary?, good: SnapshotSummary?): List<FieldDiff>? {
        if (bad == null || good == null) return null
        val keys = LinkedHashSet<String>().apply {
            addAll(PRIORITY_FIELDS.filter { bad.fields.containsKey(it) || good.fields.containsKey(it) })
            addAll(bad.fields.keys)
            addAll(good.fields.keys)
        }
        return keys.map { key ->
            FieldDiff(
                field = key,
                bad = bad.fields[key] ?: SnapshotSummary.UNKNOWN,
                good = good.fields[key] ?: SnapshotSummary.UNKNOWN,
                priority = PRIORITY_FIELDS.contains(key),
            )
        }
    }

    fun toText(bad: SnapshotSummary?, good: SnapshotSummary?): String {
        val diffs = compare(bad, good)
            ?: return buildString {
                appendLine("# BAD / GOOD 对比")
                appendLine()
                appendLine("样本不足，无法对比。")
                appendLine("bad=${bad?.name ?: "(缺失)"}")
                appendLine("good=${good?.name ?: "(缺失)"}")
                appendLine()
                appendLine("请在故障发生时点「记录 5G 故障」，恢复后点「记录正常状态」，各采一份。")
            }

        val changed = diffs.filter { it.changed }
        val changedPriority = changed.filter { it.priority }

        return buildString {
            appendLine("# BAD / GOOD 对比")
            appendLine("bad = ${bad!!.name}")
            appendLine("good = ${good!!.name}")
            appendLine()
            appendLine("## 重点字段中发生变化的（${changedPriority.size} 项）")
            if (changedPriority.isEmpty()) {
                appendLine("(无) —— 重点字段在两次采样间没有差异。")
                appendLine("若故障确实发生过，说明差异在本 parser 未覆盖的字段里，请查原始 dump。")
            } else {
                changedPriority.forEach {
                    appendLine("${it.field}:")
                    appendLine("    BAD  = ${it.bad}")
                    appendLine("    GOOD = ${it.good}")
                }
            }
            appendLine()
            appendLine("## 其余发生变化的字段（${changed.size - changedPriority.size} 项）")
            val others = changed.filterNot { it.priority }
            if (others.isEmpty()) appendLine("(无)")
            others.forEach { appendLine("${it.field}: BAD=${it.bad} | GOOD=${it.good}") }
            appendLine()
            appendLine("## 全部字段")
            diffs.forEach {
                val mark = if (it.changed) "*" else " "
                appendLine("$mark ${it.field}: BAD=${it.bad} | GOOD=${it.good}")
            }
            appendLine()
            appendLine("注：UNKNOWN 表示 parser 未能从该版本的 dumpsys 输出中识别出该字段，")
            appendLine("并不代表系统里没有对应状态。原始 dump 已完整保留在各快照目录下。")
        }
    }
}
