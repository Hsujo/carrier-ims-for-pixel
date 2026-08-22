package io.github.vvb2060.ims.diagnostics

import io.github.vvb2060.ims.shell.CommandResult

/**
 * 从原始 dump 中解析出的机器可读摘要。
 *
 * 设计前提：**解析必然会有不认识的格式**。Android 各版本的 dumpsys 输出没有
 * 稳定契约，因此这里每个字段都可能是 [UNKNOWN]，且原始 dump 一律另存，
 * 摘要只是索引，绝不是证据本身 —— parser 认不出来不等于证据不存在。
 */
data class SnapshotSummary(
    val name: String,
    val kind: SnapshotKind,
    val fields: LinkedHashMap<String, String>,
) {
    fun toText(): String = buildString {
        appendLine("# summary for $name ($kind)")
        appendLine("# 本文件由 parser 生成，字段为 UNKNOWN 时请查阅同目录下的原始 dump。")
        appendLine()
        fields.forEach { (key, value) -> appendLine("$key=$value") }
    }

    companion object {
        const val UNKNOWN = "UNKNOWN"

        /** 字段顺序即报告顺序：先无线侧，再数据面，最后连通性。 */
        private val FIELD_ORDER = listOf(
            "rat",
            "lte_state",
            "nr_state",
            "nsa_sa_clue",
            "ps_registration_state",
            "data_connection_state",
            "data_available",
            "reject_cause",
            "ims_registered",
            "apn",
            "data_network_state",
            "cellular_network_present",
            "validated",
            "ipv4",
            "ipv6",
            "default_route",
            "dns",
            "ip_probe",
            "dns_probe",
            "probe_verdict",
            "nr_mode_config",
            "target_sub_id",
            "probed_sub_id",
        )

        /**
         * 每个字段对应一组候选正则，按顺序尝试，命中即止。
         * 多写几种写法是刻意的：不同 Android 版本字段名不一致，
         * 宁可多试也不要因为一处不匹配就丢掉整个字段。
         */
        private val TELEPHONY_PATTERNS = mapOf(
            "nr_state" to listOf(
                """\bnrState=(\w+)""",
                """\bmNrState=(\w+)""",
                """\bnrStatus=(\w+)""",
            ),
            "rat" to listOf(
                """\brilDataRadioTechnology=(\w+)""",
                """\bdataRat=(\w+)""",
                """\bgetDataNetworkType=(\w+)""",
                """\bdataNetworkType=(\w+)""",
            ),
            "lte_state" to listOf(
                """\bmVoiceRegState=(\S+)""",
                """\bvoiceRegState=(\S+)""",
            ),
            "ps_registration_state" to listOf(
                """\bmDataRegState=(\S+)""",
                """\bdataRegState=(\S+)""",
                """\bregState=(\S+)""",
            ),
            "data_connection_state" to listOf(
                """\bmDataConnectionState=(\S+)""",
                """\bdataConnectionState=(\S+)""",
            ),
            "reject_cause" to listOf(
                """\brejectCause=(\S+)""",
                """\bmRejectCause=(\S+)""",
                """\bdataRejectCause=(\S+)""",
            ),
        )

        private val CONNECTIVITY_PATTERNS = mapOf(
            "apn" to listOf(
                """\bapnName=(\S+)""",
                """\bmApnSetting=.*?apnName=(\S+)""",
                """\bapn=(\S+)""",
            ),
            "data_network_state" to listOf(
                """\bDataNetwork\S*\s+state[=:]\s*(\S+)""",
                """\bmState=(\S+)""",
            ),
        )

        /**
         * 由一次快照生成摘要。
         *
         * @param snapshot 采集结果；命令失败或输出为空时相关字段保持 UNKNOWN。
         */
        fun from(snapshot: Snapshot): SnapshotSummary {
            val fields = LinkedHashMap<String, String>()
            FIELD_ORDER.forEach { fields[it] = UNKNOWN }

            val telephony = textOf(snapshot.commands, "dumpsys telephony.registry")
            val connectivity = textOf(snapshot.commands, "dumpsys connectivity")

            TELEPHONY_PATTERNS.forEach { (field, patterns) ->
                firstMatch(telephony, patterns)?.let { fields[field] = it }
            }
            CONNECTIVITY_PATTERNS.forEach { (field, patterns) ->
                firstMatch(connectivity, patterns)?.let { fields[field] = it }
            }

            // NSA / SA 线索：NR 已连接但注册在 LTE 上，是 EN-DC（NSA）的典型特征。
            fields["nsa_sa_clue"] = deriveNsaSaClue(fields["nr_state"], fields["rat"])

            fields["data_available"] = when {
                connectivity.isBlank() && telephony.isBlank() -> UNKNOWN
                fields["data_connection_state"] != UNKNOWN -> fields["data_connection_state"]!!
                else -> UNKNOWN
            }

            fields["cellular_network_present"] = when {
                connectivity.isBlank() -> UNKNOWN
                connectivity.contains("TRANSPORT_CELLULAR") || connectivity.contains("CELLULAR") -> "true"
                else -> "false"
            }

            // 元数据与探测结果是应用自己产出的，格式可控，直接采用。
            snapshot.metadata["ims_registered"]?.let { fields["ims_registered"] = it }
            snapshot.metadata["carrier_nr_availabilities_int_array"]?.let {
                fields["nr_mode_config"] = it
            }

            val probe = snapshot.probe
            if (probe != null) {
                val link = probe.link
                fields["validated"] = link.validated?.toString() ?: UNKNOWN
                fields["ipv4"] = link.ipv4.joinToString().ifBlank { "(none)" }
                fields["ipv6"] = link.ipv6.joinToString().ifBlank { "(none)" }
                fields["default_route"] = "v4=${link.hasDefaultRouteV4},v6=${link.hasDefaultRouteV6}"
                fields["dns"] = link.dnsServers.joinToString().ifBlank { "(none)" }
                fields["ip_probe"] = "${probe.ipReachability.successes}/${probe.ipReachability.attempts}"
                fields["dns_probe"] = "${probe.dnsResolution.successes}/${probe.dnsResolution.attempts}"
                fields["probe_verdict"] = probe.verdict.substringBefore(":")
                fields["target_sub_id"] = probe.targetSubId?.toString() ?: UNKNOWN
                fields["probed_sub_id"] = probe.probedSubId?.toString() ?: UNKNOWN
            }

            return SnapshotSummary(snapshot.name, snapshot.kind, fields)
        }

        /** 从已落盘的快照目录重建摘要，用于导出时对比历史快照。 */
        fun fromFiles(name: String, kind: SnapshotKind, files: Map<String, String>): SnapshotSummary {
            val existing = files["summary.txt"]
            val fields = LinkedHashMap<String, String>()
            FIELD_ORDER.forEach { fields[it] = UNKNOWN }
            existing?.lineSequence()
                ?.filterNot { it.startsWith("#") || it.isBlank() }
                ?.forEach { line ->
                    val key = line.substringBefore('=', "")
                    val value = line.substringAfter('=', "")
                    if (key.isNotBlank()) fields[key] = value
                }
            return SnapshotSummary(name, kind, fields)
        }

        private fun deriveNsaSaClue(nrState: String?, rat: String?): String {
            if (nrState == null || nrState == UNKNOWN) return UNKNOWN
            val nr = nrState.uppercase()
            val radio = rat?.uppercase().orEmpty()
            return when {
                radio.contains("NR") && !nr.contains("NONE") -> "SA_LIKELY (RAT 已是 NR)"
                nr.contains("CONNECTED") -> "NSA_LIKELY (LTE 锚点 + NR 已连接，EN-DC)"
                nr.contains("NOT_RESTRICTED") ->
                    "NSA_AVAILABLE_NOT_CONNECTED (仅表示允许，未实际建立 NR)"

                nr.contains("RESTRICTED") -> "NR_RESTRICTED (网络侧限制)"
                nr.contains("NONE") -> "NO_NR"
                else -> UNKNOWN
            }
        }

        private fun textOf(commands: List<CommandResult>, command: String): String =
            commands.firstOrNull { it.command == command }?.stdout.orEmpty()

        private fun firstMatch(text: String, patterns: List<String>): String? {
            if (text.isBlank()) return null
            for (pattern in patterns) {
                val match = runCatching {
                    Regex(pattern, RegexOption.IGNORE_CASE).find(text)
                }.getOrNull()
                val value = match?.groupValues?.getOrNull(1)?.trim()?.trimEnd(',', '}', ')')
                if (!value.isNullOrBlank()) return value
            }
            return null
        }
    }
}
