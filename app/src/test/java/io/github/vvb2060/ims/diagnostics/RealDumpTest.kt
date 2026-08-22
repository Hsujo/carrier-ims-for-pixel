package io.github.vvb2060.ims.diagnostics

import io.github.vvb2060.ims.shell.CommandResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用真机（Pixel Fold / Android 17 / CP41.260731.005.A2）导出的 dumpsys connectivity
 * 片段回归 parser。早先的正则是照 AOSP 常见写法猜的，在该设备上全部落空。
 */
class RealDumpTest {
    private val realConnectivity = """
  NetworkAgentInfo{network{106}  handle{458672230413}  ni{MOBILE[LTE] CONNECTED ROAMING extra: ims} created=2026-08-20T13:51:19.609Z Score(Policies : EVER_EVALUATED&IS_UNMETERED&EVER_VALIDATED&IS_VALIDATED ; KeepConnected : 0)  created 614825 firstValidated 617415 lastValidated 617415  lp{{InterfaceName: rmnet16 LinkAddresses: [ 10.100.148.241/32 ] DnsAddresses: [ ] PcscfAddresses: [ /10.5.236.174 ] Domains: null MTU: 1500 Routes: [ 0.0.0.0/0 -> 0.0.0.0 rmnet16 mtu 1500 ]}}  nc{[ Transports: CELLULAR Capabilities: IMS&NOT_METERED&TRUSTED&NOT_VPN&VALIDATED&FOREGROUND&MMTEL Specifier: <TelephonyNetworkSpecifier [mSubId = 5]> SubscriptionIds: {5}]}  factorySerialNumber=12}
  NetworkAgentInfo{network{109}  handle{471557132301}  ni{MOBILE[NR] CONNECTED extra: 3gnet} created=2026-08-20T13:52:23.802Z Score(Policies : TRANSPORT_PRIMARY&EVER_EVALUATED&EVER_VALIDATED&IS_VALIDATED ; KeepConnected : 0)  created 679067 firstValidated 695384 lastValidated 695384  lp{{InterfaceName: rmnet1 LinkAddresses: [ 10.31.209.250/32 ] DnsAddresses: [ /202.99.96.68,/202.99.104.68 ] Domains: null MTU: 1500 Routes: [ 0.0.0.0/0 -> 0.0.0.0 rmnet1 mtu 1500 ]}}  nc{[ Transports: CELLULAR Capabilities: SUPL&INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN&VALIDATED&NOT_ROAMING&FOREGROUND Specifier: <TelephonyNetworkSpecifier [mSubId = 2]> SubscriptionIds: {2}]}  factorySerialNumber=12}
    """.trimIndent()

    private fun summaryOf(connectivity: String) = SnapshotSummary.from(
        Snapshot(
            kind = SnapshotKind.GOOD,
            name = "GOOD_real",
            takenAtMillis = 0L,
            metadata = emptyMap(),
            commands = listOf(
                CommandResult("dumpsys telephony.registry", "unavailable", "", "binder buffer full"),
                CommandResult("dumpsys connectivity", "0", connectivity, ""),
            ),
            probe = null,
            probeError = null,
        )
    )

    @Test
    fun cellularPresenceMatchesRealFormat() {
        // 真机写的是 "Transports: CELLULAR"，不是 TRANSPORT_CELLULAR。
        assertEquals("true", summaryOf(realConnectivity).fields["cellular_network_present"])
    }

    @Test
    fun apnComesFromTheInternetBearingPdnNotTheImsOne() {
        // subId=5 的 IMS PDN 排在前面，但 APN 必须取带 INTERNET 能力的 3gnet。
        assertEquals("3gnet", summaryOf(realConnectivity).fields["apn"])
    }

    @Test
    fun dataRatFallsBackToConnectivityWhenRegistryDumpFailed() {
        // telephony.registry 因输出过大采集失败时，仍应从 MOBILE[NR] 得到 RAT。
        val rat = summaryOf(realConnectivity).fields["rat"]!!
        assertTrue(rat.startsWith("NR"), "rat=$rat")
        assertTrue(rat.contains("from connectivity"))
    }

    @Test
    fun nsaSaClueDegradesHonestlyWithoutNrState() {
        val clue = summaryOf(realConnectivity).fields["nsa_sa_clue"]!!
        assertTrue(clue.startsWith("NR_DATA_RAT"), "clue=$clue")
        assertTrue(clue.contains("无 nrState 佐证"))
    }

    @Test
    fun dataNetworkStateIsParsed() {
        assertEquals("CONNECTED", summaryOf(realConnectivity).fields["data_network_state"])
    }
}
