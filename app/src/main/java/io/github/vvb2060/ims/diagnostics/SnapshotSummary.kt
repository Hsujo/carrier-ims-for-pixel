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

        /** dumpsys 主动打码的值（如 nrState=****），与「解析不出」是两回事。 */
        const val REDACTED = "REDACTED (dumpsys 打码)"

        /** 字段顺序即报告顺序：先无线侧，再数据面，最后连通性。 */
        private val FIELD_ORDER = listOf(
            "rat",
            "lte_state",
            "nr_state",
            "nsa_sa_clue",
            "ps_registration_state",
            "registration_state",
            "roaming_type",
            "is_nr_available",
            "is_endc_available",
            "is_dc_nr_restricted",
            "lte_rsrp",
            "lte_rsrq",
            "lte_rssnr",
            "lte_level",
            "nr_ss_rsrp",
            "nr_ss_sinr",
            "signal_quality",
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
            "rtt_min_ms",
            "rtt_max_ms",
            "rtt_jitter_ms",
            "rtt_samples",
            "latency_verdict",
            "probe_verdict",
            "nr_mode_config",
            // 热状态与 modem 固件版本进摘要：BAD/GOOD 对照时最需要一眼看到的
            // 就是「两次采样是不是同一套固件、当时热不热」。
            "thermal_status",
            "baseband",
            "target_sub_id",
            "probed_sub_id",
            "registry_scope",
        )

        /**
         * 每个字段对应一组候选正则，按顺序尝试，命中即止。
         * 多写几种写法是刻意的：不同 Android 版本字段名不一致，
         * 宁可多试也不要因为一处不匹配就丢掉整个字段。
         */
        private val TELEPHONY_PATTERNS = mapOf(
            "nr_state" to listOf(
                // 真机上该值被 dumpsys 打码为 ****，需与「解析不出」区分开。
                """\bnrState=(\*{2,})""",
                """\bnrState=(\w+)""",
                """\bmNrState=(\w+)""",
                """\bnrStatus=(\w+)""",
            ),
            "rat" to listOf(
                """\bgetRilDataRadioTechnology=(\S+?)[,\s]""",
                """\brilDataRadioTechnology=(\w+)""",
                """\bdataRat=(\w+)""",
                """\bdataNetworkType=(\w+)""",
            ),
            // 这三项直接回答「为什么没有 5G」：EN-DC 不可用时 NSA 无从建立。
            "is_nr_available" to listOf("""\bisNrAvailable\s*=\s*(\w+)"""),
            "is_endc_available" to listOf("""\bisEnDcAvailable\s*=\s*(\w+)"""),
            "is_dc_nr_restricted" to listOf("""\bisDcNrRestricted\s*=\s*(\w+)"""),
            "registration_state" to listOf(
                """domain=PS\s+transportType=WWAN\s+registrationState=(\w+)""",
                """\bregistrationState=(\w+)""",
            ),
            "roaming_type" to listOf(
                """domain=PS\s+transportType=WWAN[^}]*?roamingType=(\w+)""",
                """\broamingType=(\w+)""",
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
            // 只取 mSignalStrength= 这一条当前值；registry 里还有历史记录，
            // 混进去会让「现在信号如何」变得不可判读。
            "lte_rsrp" to listOf("""mSignalStrength=[^\n]*?mLte=CellSignalStrengthLte:[^,]*?\brsrp=(-?\d+)"""),
            "lte_rsrq" to listOf("""mSignalStrength=[^\n]*?mLte=CellSignalStrengthLte:[^,]*?\brsrq=(-?\d+)"""),
            "lte_rssnr" to listOf("""mSignalStrength=[^\n]*?mLte=CellSignalStrengthLte:[^,]*?\brssnr=(-?\d+)"""),
            "lte_level" to listOf("""mSignalStrength=[^\n]*?mLte=CellSignalStrengthLte:[^,]*?\blevel=(\d+)"""),
            "nr_ss_rsrp" to listOf("""mNr=CellSignalStrengthNr:\{[^}]*?ssRsrp\s*=\s*(-?\d+)"""),
            "nr_ss_sinr" to listOf("""mNr=CellSignalStrengthNr:\{[^}]*?ssSinr\s*=\s*(-?\d+)"""),
            "reject_cause" to listOf(
                """\brejectCause=(\S+)""",
                """\bmRejectCause=(\S+)""",
                """\bdataRejectCause=(\S+)""",
            ),
        )

        /**
         * 真机（Pixel Fold / Android 17）的 dumpsys connectivity 实际格式：
         *
         *   NetworkAgentInfo{network{109} ni{MOBILE[NR] CONNECTED extra: 3gnet}
         *     lp{{InterfaceName: rmnet1 LinkAddresses: [...] DnsAddresses: [...] Routes: [...]}}
         *     nc{[ Transports: CELLULAR Capabilities: SUPL&INTERNET&...&VALIDATED
         *          Specifier: <TelephonyNetworkSpecifier [mSubId = 2]> ]}}
         *
         * 没有 apnName= / nrState= / TRANSPORT_CELLULAR 这些字段，
         * 早先照 AOSP 常见写法猜的正则全部落空。以下按实际输出重写。
         */
        private val CONNECTIVITY_PATTERNS = mapOf(
            // APN 出现在 NetworkInfo 的 extra 里，且只取带 INTERNET 能力的那条 PDN。
            "apn" to listOf(
                """ni\{MOBILE\[[^\]]*\][^}]*extra:\s*([^\s}]+)[^}]*\}(?=(?:(?!NetworkAgentInfo).)*?INTERNET)""",
                """ni\{MOBILE\[[^\]]*\][^}]*extra:\s*([^\s}]+)""",
                """\bapnName=(\S+)""",
            ),
            "data_network_state" to listOf(
                """ni\{MOBILE\[[^\]]*\]\s+(\w+)""",
                """\bmState=(\S+)""",
            ),
            // MOBILE[NR] / MOBILE[LTE] 就是当前数据 RAT。
            "rat_from_connectivity" to listOf(
                """ni\{MOBILE\[(\w+)\][^}]*\}(?=(?:(?!NetworkAgentInfo).)*?INTERNET)""",
                """ni\{MOBILE\[(\w+)\]""",
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

            // 双卡设备上 telephony.registry 同时包含两张卡的记录。
            // 不做限定就会匹配到先出现的那一张 —— 例如把 3HK 在国内的
            // 正常国际漫游，误读成联通卡在漫游。必须先裁到目标卡。
            val telephonyRaw = textOf(snapshot.commands, "dumpsys telephony.registry")
            val targetSubId = snapshot.metadata["sub_id"]?.toIntOrNull()
            val slotIndex = snapshot.metadata["slot_index"]?.toIntOrNull()
            val scoped = scopeToPhone(telephonyRaw, targetSubId, slotIndex)
            fields["registry_scope"] = scoped.second
            val telephony = scoped.first
            val connectivity = textOf(snapshot.commands, "dumpsys connectivity")

            TELEPHONY_PATTERNS.forEach { (field, patterns) ->
                firstMatch(telephony, patterns)?.let { fields[field] = it }
            }
            CONNECTIVITY_PATTERNS.forEach { (field, patterns) ->
                firstMatch(connectivity, patterns)?.let { fields[field] = it }
            }

            // telephony.registry 可能采集失败（输出过大）或格式不识别，
            // 此时 connectivity 里的 MOBILE[NR] / MOBILE[LTE] 仍能给出数据 RAT。
            // connectivity 的 ni{MOBILE[NR]} 可能是上一次连接遗留的陈旧值，
            // registry 的 getRilDataRadioTechnology 才是当前实际 RAT，优先采用。
            val ratFallback = fields.remove("rat_from_connectivity")
            if (fields["rat"] == UNKNOWN && !ratFallback.isNullOrBlank() && ratFallback != UNKNOWN) {
                fields["rat"] = "$ratFallback (from connectivity, may be stale)"
            }

            // SA 下根本没有 LTE 腿，lte_* 全是无效占位；必须按当前 RAT 选对应的指标，
            // 否则整段 SA 采样的信号质量都会是 UNKNOWN（实测就是如此）。
            val onNr = fields["rat"]?.uppercase()?.contains("NR") == true
            fields["signal_quality"] = if (onNr) {
                gradeSignal(fields["nr_ss_rsrp"], fields["nr_ss_sinr"], "NR")
            } else {
                gradeSignal(fields["lte_rsrp"], fields["lte_rssnr"], "LTE")
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
                connectivity.contains("Transports: CELLULAR") ||
                    connectivity.contains("TRANSPORT_CELLULAR") -> "true"
                else -> "false"
            }

            // 元数据与探测结果是应用自己产出的，格式可控，直接采用。
            snapshot.metadata["ims_registered"]?.let { fields["ims_registered"] = it }
            snapshot.metadata["carrier_nr_availabilities_int_array"]?.let {
                fields["nr_mode_config"] = it
            }
            snapshot.metadata["thermal_status"]?.let { fields["thermal_status"] = it }
            snapshot.metadata["baseband"]?.let { fields["baseband"] = it }

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
                probe.latency?.let { lat ->
                    fields["rtt_min_ms"] = lat.minMs?.toString() ?: UNKNOWN
                    fields["rtt_max_ms"] = lat.maxMs?.toString() ?: UNKNOWN
                    // 有样本被超时截断时，抖动只是下界，必须标出来 ——
                    // 否则读到的数会比真实情况小，而且恰好小在最差的时刻。
                    fields["rtt_jitter_ms"] = lat.jitterMs?.let {
                        if (lat.jitterIsLowerBound) ">=$it (${lat.censored} 次探测超时)" else "$it"
                    } ?: UNKNOWN
                    fields["latency_verdict"] = lat.verdict
                    fields["rtt_samples"] = "${lat.samples.size} 次" +
                        (if (lat.censored > 0) "（含 ${lat.censored} 次超时）" else "") +
                        (if (lat.failures > 0) "，失败 ${lat.failures} 次" else "")
                }
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

        /**
         * 把 RSRP / SINR 归纳成一句人能直接用的判断。
         *
         * PDU session 在弱信号下往往不会拆除，IP、路由、DNS 全都照旧，
         * Android 的 VALIDATED 也是上次成功检测的粘滞结果 ——
         * 于是界面显示「已连接」而实际收发不通。此时只有射频指标能说明问题。
         */
        private fun gradeSignal(rsrpRaw: String?, sinrRaw: String?, band: String): String {
            val rsrp = rsrpRaw?.toIntOrNull()
            val sinr = sinrRaw?.toIntOrNull()
            if (rsrp == null || rsrp == Int.MAX_VALUE) return UNKNOWN
            val quality = when {
                rsrp <= -120 -> "VERY_POOR"
                rsrp <= -110 -> "POOR"
                rsrp <= -100 -> "FAIR"
                else -> "GOOD"
            }
            val sinrNote = when {
                sinr == null || sinr == Int.MAX_VALUE -> ""
                sinr < 0 -> "，SINR 为负（噪声高于信号）"
                sinr < 10 -> "，SINR 偏低"
                else -> ""
            }
            return "$quality ($band rsrp=${rsrp}dBm$sinrNote)"
        }

        private fun deriveNsaSaClue(nrState: String?, rat: String?): String {
            if (nrState == null || nrState == UNKNOWN) {
                // 没有 nrState 时，仅凭数据 RAT 也能给出粗判，但必须说明依据较弱。
                val radio = rat?.uppercase().orEmpty()
                return when {
                    radio.contains("NR") -> "NR_DATA_RAT (仅据 connectivity 的 MOBILE[NR]，无 nrState 佐证)"
                    radio.contains("LTE") -> "LTE_DATA_RAT (数据走 LTE，无 nrState 佐证)"
                    else -> UNKNOWN
                }
            }
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

        /**
         * 把 telephony.registry 裁剪到目标 SIM 对应的 Phone Id 段。
         *
         * @return 裁剪后的文本，以及一段说明裁剪依据的字符串 —— 裁剪失败时
         *         必须让阅读者知道后续字段可能来自另一张卡。
         */
        private fun scopeToPhone(
            text: String,
            subId: Int?,
            slotIndex: Int?,
        ): Pair<String, String> {
            if (text.isBlank()) return text to "empty dump"
            val markers = Regex("""Phone Id=(\d+)""").findAll(text).toList()
            if (markers.isEmpty()) return text to "no Phone Id markers, using whole dump"

            val sections = markers.mapIndexed { index, match ->
                val start = match.range.first
                val end = if (index + 1 < markers.size) markers[index + 1].range.first else text.length
                match.groupValues[1].toIntOrNull() to text.substring(start, end)
            }

            // Phone Id 直接对应卡槽，是可靠映射，必须优先。
            //
            // 不能按 "subId=N" 子串匹配：真机上联通那段完全不含 subId=，
            // 而另一段列出了包括 2 在内的一串 subId，于是会命中错误的卡，
            // 把另一张卡的漫游状态安到目标卡头上（本工具已犯过一次）。
            if (slotIndex != null) {
                sections.firstOrNull { it.first == slotIndex }?.let {
                    return it.second to "scoped to Phone Id=$slotIndex (slot)"
                }
            }
            if (subId != null) {
                val hits = sections.filter { it.second.contains("subId=$subId") }
                // 只有唯一命中才可信；多段都提到同一个 subId 说明这不是归属标识。
                if (hits.size == 1) {
                    return hits.first().second to "scoped to subId=$subId (unique match)"
                }
            }
            return text to "could not scope to target SIM; fields may come from another SIM"
        }

        private fun textOf(commands: List<CommandResult>, command: String): String =
            commands.firstOrNull { it.command == command }?.stdout.orEmpty()

        private fun firstMatch(text: String, patterns: List<String>): String? {
            if (text.isBlank()) return null
            for (pattern in patterns) {
                val match = runCatching {
                    Regex(pattern, RegexOption.IGNORE_CASE).find(text)
                }.getOrNull()
                val raw = match?.groupValues?.getOrNull(1)?.trim()?.trimEnd(',', '}')
                if (raw.isNullOrBlank()) continue
                // dumpsys 主动打码的值（nrState=****）与「解析不出」含义不同。
                if (raw.all { it == '*' }) return REDACTED
                // 2147483647 是 Android 的「无效值」占位，绝不能当成真实读数报出去。
                if (raw == "2147483647") return UNKNOWN
                // 形如 14(LTE) / 0(IN_SERVICE) 的取值，取括号内的可读名称。
                val readable = Regex("""^\d+\((\w+)\)?$""").find(raw)?.groupValues?.get(1)
                return readable ?: raw.trimEnd(')')
            }
            return null
        }
    }
}
