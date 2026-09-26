package io.github.vvb2060.ims.diagnostics

import io.github.vvb2060.ims.shell.CommandResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotSummaryTest {

    private fun snapshot(
        kind: SnapshotKind = SnapshotKind.BAD,
        telephony: String = "",
        connectivity: String = "",
        metadata: Map<String, String> = emptyMap(),
    ) = Snapshot(
        kind = kind,
        name = "${kind.prefix}_test",
        takenAtMillis = 0L,
        metadata = metadata,
        commands = listOf(
            CommandResult("dumpsys telephony.registry", "0", telephony, ""),
            CommandResult("dumpsys connectivity", "0", connectivity, ""),
        ),
        probe = null,
        probeError = null,
    )

    @Test
    fun parsesRadioFieldsFromRegistryDump() {
        val summary = SnapshotSummary.from(
            snapshot(
                telephony = """
                    mServiceState=1 1 home 46001 nrState=CONNECTED rilDataRadioTechnology=LTE
                    mDataRegState=0 rejectCause=0
                    mDataConnectionState=2
                """.trimIndent()
            )
        )
        assertEquals("CONNECTED", summary.fields["nr_state"])
        assertEquals("LTE", summary.fields["rat"])
        assertEquals("0", summary.fields["ps_registration_state"])
        assertEquals("2", summary.fields["data_connection_state"])
        assertEquals("0", summary.fields["reject_cause"])
    }

    @Test
    fun nrConnectedOnLteAnchorReadsAsNsa() {
        val summary = SnapshotSummary.from(
            snapshot(telephony = "nrState=CONNECTED rilDataRadioTechnology=LTE")
        )
        assertTrue(summary.fields["nsa_sa_clue"]!!.startsWith("NSA_LIKELY"))
    }

    @Test
    fun notRestrictedIsNotTreatedAsConnected() {
        // 状态栏显示 5G 常常只是 NOT_RESTRICTED，不代表真的建立了 NR。
        val summary = SnapshotSummary.from(
            snapshot(telephony = "nrState=NOT_RESTRICTED rilDataRadioTechnology=LTE")
        )
        assertTrue(summary.fields["nsa_sa_clue"]!!.startsWith("NSA_AVAILABLE_NOT_CONNECTED"))
    }

    @Test
    fun unrecognisedOutputYieldsUnknownRatherThanGuessing() {
        val summary = SnapshotSummary.from(
            snapshot(telephony = "some future android format we do not understand")
        )
        listOf("nr_state", "rat", "ps_registration_state", "reject_cause").forEach {
            assertEquals(SnapshotSummary.UNKNOWN, summary.fields[it], "field $it")
        }
    }

    @Test
    fun emptyDumpsKeepEveryFieldUnknown() {
        val summary = SnapshotSummary.from(snapshot())
        // registry_scope 描述的是解析过程本身，不是被解析出的字段。
        summary.fields.filterKeys { it != "registry_scope" }.forEach { (key, value) ->
            assertEquals(SnapshotSummary.UNKNOWN, value, "field $key")
        }
    }

    @Test
    fun summaryTextRoundTripsThroughFiles() {
        val original = SnapshotSummary.from(
            snapshot(telephony = "nrState=CONNECTED rilDataRadioTechnology=NR")
        )
        val restored = SnapshotSummary.fromFiles(
            "BAD_5G_test",
            SnapshotKind.BAD,
            mapOf("summary.txt" to original.toText()),
        )
        assertEquals(original.fields["nr_state"], restored.fields["nr_state"])
        assertEquals(original.fields["nsa_sa_clue"], restored.fields["nsa_sa_clue"])
    }

    private fun probe(probedSubId: Int?, verdict: String) = NetworkProbe.Result(
        link = NetworkProbe.LinkSnapshot(
            unreadable = false,
            hasCellularNetwork = true,
            validated = true,
            interfaceName = "rmnet1",
            ipv4 = listOf("10.1.2.3"),
            ipv6 = emptyList(),
            hasDefaultRouteV4 = true,
            hasDefaultRouteV6 = false,
            routes = emptyList(),
            dnsServers = emptyList(),
            capabilities = null,
            subId = probedSubId,
            hasInternetCapability = true,
            error = null,
        ),
        ipReachability = NetworkProbe.ProbeResult(
            label = "ip", target = "223.5.5.5", attempts = 2, successes = 2,
            lastError = null, boundToCellular = true, details = emptyList(),
        ),
        dnsResolution = NetworkProbe.ProbeResult(
            label = "dns", target = "www.baidu.com", attempts = 0, successes = 0,
            lastError = null, boundToCellular = true, details = emptyList(),
        ),
        latency = null,
        verdict = verdict,
        targetSubId = 1,
        probedSubId = probedSubId,
        onCellular = true,
    )

    @Test
    fun probeFieldsStayUnknownWhenAnotherSimWasMeasured() {
        // 测到的是另一张卡时，链路与探测指标不能记在目标卡的摘要里，只留判定。
        val wrongSim = SnapshotSummary.from(
            snapshot(metadata = mapOf("sub_id" to "1"))
                .copy(probe = probe(probedSubId = 2, verdict = "WRONG_SIM_PROBED: subId=2"))
        )
        assertEquals(SnapshotSummary.UNKNOWN, wrongSim.fields["ipv4"])
        assertEquals(SnapshotSummary.UNKNOWN, wrongSim.fields["ip_probe"])
        assertEquals("WRONG_SIM_PROBED", wrongSim.fields["probe_verdict"])

        val targetSim = SnapshotSummary.from(
            snapshot(metadata = mapOf("sub_id" to "1"))
                .copy(probe = probe(probedSubId = 1, verdict = "OK: fine"))
        )
        assertEquals("10.1.2.3", targetSim.fields["ipv4"])
        assertEquals("2/2", targetSim.fields["ip_probe"])
    }
}
