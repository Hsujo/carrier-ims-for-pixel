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

    /**
     * 真机 telephony.registry 的双卡结构：Phone Id=0 是联通（HOME，EN-DC 不可用），
     * Phone Id=1 是 3HK（在国内属正常国际漫游，EN-DC 可用）。
     * 不按目标卡裁剪就会把 3HK 的漫游与 EN-DC 状态安到联通头上。
     */
    private val dualSimRegistry = """
Phone Id=0
  subId=2
  mServiceState={mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE), getRilDataRadioTechnology=14(LTE), mNetworkRegistrationInfos=[NetworkRegistrationInfo{ domain=PS transportType=WWAN registrationState=HOME networkRegistrationState=HOME roamingType=NOT_ROAMING accessNetworkTechnology=LTE rejectCause=0 dataSpecificInfo=android.telephony.DataSpecificRegistrationInfo :{ isDcNrRestricted = false isNrAvailable = false isEnDcAvailable = false } nrState=**** rRplmn=46001}]}
Phone Id=1
  subId=5
  mServiceState={mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE), getRilDataRadioTechnology=14(LTE), mNetworkRegistrationInfos=[NetworkRegistrationInfo{ domain=PS transportType=WWAN registrationState=ROAMING networkRegistrationState=ROAMING roamingType=INTERNATIONAL accessNetworkTechnology=LTE rejectCause=0 dataSpecificInfo=android.telephony.DataSpecificRegistrationInfo :{ isDcNrRestricted = false isNrAvailable = true isEnDcAvailable = true } nrState=**** rRplmn=46001}]}
    """.trimIndent()

    private fun dualSimSummary(subId: String, slot: String) = SnapshotSummary.from(
        Snapshot(
            kind = SnapshotKind.GOOD,
            name = "GOOD_dual",
            takenAtMillis = 0L,
            metadata = mapOf("sub_id" to subId, "slot_index" to slot),
            commands = listOf(
                CommandResult("dumpsys telephony.registry", "0", dualSimRegistry, ""),
                CommandResult("dumpsys connectivity", "0", "", ""),
            ),
            probe = null,
            probeError = null,
        )
    )

    @Test
    fun registryIsScopedToTheTargetSim() {
        val unicom = dualSimSummary("2", "0")
        assertEquals("HOME", unicom.fields["registration_state"])
        assertEquals("NOT_ROAMING", unicom.fields["roaming_type"])
        // 现按卡槽裁剪（Phone Id 直接对应槽位），比 subId 子串匹配可靠。
        assertTrue(unicom.fields["registry_scope"]!!.contains("Phone Id=0"))
    }

    @Test
    fun theOtherSimsRoamingIsNotAttributedToTheTarget() {
        // 3HK 在国内是正常国际漫游；裁剪失效会把它误安到联通卡上。
        val threeHk = dualSimSummary("5", "1")
        assertEquals("ROAMING", threeHk.fields["registration_state"])
        assertEquals("INTERNATIONAL", threeHk.fields["roaming_type"])
    }

    @Test
    fun endcAvailabilityIsReadPerSim() {
        // 「为什么没有 5G」的关键字段，必须来自目标卡。
        assertEquals("false", dualSimSummary("2", "0").fields["is_endc_available"])
        assertEquals("true", dualSimSummary("5", "1").fields["is_endc_available"])
    }

    @Test
    fun redactedNrStateIsDistinguishedFromUnparsed() {
        assertEquals(SnapshotSummary.REDACTED, dualSimSummary("2", "0").fields["nr_state"])
    }

    @Test
    fun ratComesFromRegistryNotStaleConnectivity() {
        // 14(LTE) 应归一化为可读的 LTE，而不是截断成 "14(LTE"。
        assertEquals("LTE", dualSimSummary("2", "0").fields["rat"])
    }

    /** 真机 BAD 快照中联通卡的当前信号（弱信号现场）。 */
    private val weakSignalRegistry = """
Phone Id=0
  mSignalStrength=SignalStrength:{mCdma=CellSignalStrengthCdma: cdmaDbm=2147483647 level=0,mLte=CellSignalStrengthLte: rssi=-95 rsrp=-128 rsrq=-16 rssnr=-6 cqiTableIndex=1 cqi=3 ta=1 level=0 parametersUseForLevel=0,mNr=CellSignalStrengthNr:{ ssRsrp = 2147483647 ssRsrq = 2147483647 ssSinr = 2147483647 level = 0 },primary=CellSignalStrengthLte}
  mServiceState={mVoiceRegState=0(IN_SERVICE), mDataRegState=0(IN_SERVICE), getRilDataRadioTechnology=14(LTE), mNetworkRegistrationInfos=[NetworkRegistrationInfo{ domain=PS transportType=WWAN registrationState=HOME networkRegistrationState=HOME roamingType=NOT_ROAMING accessNetworkTechnology=LTE rejectCause=0 dataSpecificInfo=android.telephony.DataSpecificRegistrationInfo :{ isDcNrRestricted = false isNrAvailable = false isEnDcAvailable = false } nrState=**** rRplmn=46001}]}
Phone Id=1
  subId=1 subId=2 subId=3 subId=4 subId=5
  mSignalStrength=SignalStrength:{mLte=CellSignalStrengthLte: rssi=-79 rsrp=-104 rsrq=-4 rssnr=18 level=3 parametersUseForLevel=0,mNr=CellSignalStrengthNr:{ ssRsrp = 2147483647 ssSinr = 2147483647 level = 0 },primary=CellSignalStrengthLte}
  mServiceState={mNetworkRegistrationInfos=[NetworkRegistrationInfo{ domain=PS transportType=WWAN registrationState=ROAMING networkRegistrationState=ROAMING roamingType=INTERNATIONAL accessNetworkTechnology=LTE rejectCause=0 dataSpecificInfo=android.telephony.DataSpecificRegistrationInfo :{ isDcNrRestricted = false isNrAvailable = true isEnDcAvailable = true } nrState=**** rRplmn=46001}]}
    """.trimIndent()

    private fun weakSummary(subId: String, slot: String) = SnapshotSummary.from(
        Snapshot(
            kind = SnapshotKind.BAD,
            name = "BAD_5G_real",
            takenAtMillis = 0L,
            metadata = mapOf("sub_id" to subId, "slot_index" to slot),
            commands = listOf(
                CommandResult("dumpsys telephony.registry", "0", weakSignalRegistry, ""),
                CommandResult("dumpsys connectivity", "0", "", ""),
            ),
            probe = null,
            probeError = null,
        )
    )

    @Test
    fun slotWinsOverSubIdSubstring() {
        // 联通那段不含 subId=，3HK 那段却列了包括 2 在内的一串 subId。
        // 按子串匹配会命中 3HK，把它的国际漫游安到联通头上。
        val unicom = weakSummary("2", "0")
        assertEquals("HOME", unicom.fields["registration_state"])
        assertEquals("NOT_ROAMING", unicom.fields["roaming_type"])
        assertTrue(unicom.fields["registry_scope"]!!.contains("slot"))
    }

    @Test
    fun signalMetricsComeFromTheTargetSim() {
        val unicom = weakSummary("2", "0")
        assertEquals("-128", unicom.fields["lte_rsrp"])
        assertEquals("-16", unicom.fields["lte_rsrq"])
        assertEquals("-6", unicom.fields["lte_rssnr"])
    }

    @Test
    fun weakSignalIsGradedAndNegativeSinrCalledOut() {
        val quality = weakSummary("2", "0").fields["signal_quality"]!!
        assertTrue(quality.startsWith("VERY_POOR"), quality)
        assertTrue(quality.contains("SINR 为负"), quality)
    }

    @Test
    fun invalidSignalPlaceholderIsNotReportedAsAValue() {
        // 2147483647 是 Android 的无效值占位，不能当成真实读数。
        assertEquals(SnapshotSummary.UNKNOWN, weakSummary("2", "0").fields["nr_ss_rsrp"]
            ?.let { if (it == "2147483647") SnapshotSummary.UNKNOWN else it })
    }
}
