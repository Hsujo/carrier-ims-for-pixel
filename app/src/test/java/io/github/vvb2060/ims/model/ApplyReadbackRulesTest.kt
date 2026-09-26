package io.github.vvb2060.ims.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplyReadbackRulesTest {
    private val api34 = 34
    private val api33 = 33

    private fun on() = FeatureValue(true, FeatureValueType.BOOLEAN)
    private fun off() = FeatureValue(false, FeatureValueType.BOOLEAN)
    private fun text(value: String) = FeatureValue(value, FeatureValueType.STRING)

    /** 所有开关都关、不写 ISO：本次写入没有任何可校验项。 */
    private fun allOff(): MutableMap<Feature, FeatureValue> = Feature.entries.associateWith { feature ->
        if (feature.valueType == FeatureValueType.BOOLEAN) off() else text("")
    }.toMutableMap()

    @Test
    fun onlyEnabledSwitchesAreVerified() {
        val requested = allOff().apply { put(Feature.VOLTE, on()) }
        assertTrue(ApplyReadbackRules.hasVerifiableTarget(requested, null, api34))

        // 关闭的开关读回为开（运营商默认值）不算不一致。
        val readback = allOff().apply {
            put(Feature.VOLTE, on())
            put(Feature.VOWIFI, on())
        }
        assertTrue(ApplyReadbackRules.confirms(readback, requested, null, api34))
        // 开启的开关读回为关：写入尚未生效。
        assertFalse(ApplyReadbackRules.confirms(allOff(), requested, null, api34))
    }

    @Test
    fun nothingToVerifyIsNeverReportedAsConfirmed() {
        val requested = allOff()
        assertFalse(ApplyReadbackRules.hasVerifiableTarget(requested, null, api34))
        assertFalse(ApplyReadbackRules.confirms(allOff(), requested, null, api34))
    }

    @Test
    fun fiveGSubFeaturesAreSkippedWhenFiveGNrIsOff() {
        // 阈值与 5G+ 图标默认开启，但 5G NR 关闭时 buildBundle 根本不写它们。
        val requested = allOff().apply {
            put(Feature.FIVE_G_THRESHOLDS, on())
            put(Feature.FIVE_G_PLUS_ICON, on())
        }
        assertFalse(ApplyReadbackRules.hasVerifiableTarget(requested, null, api34))

        requested[Feature.FIVE_G_NR] = on()
        requested[Feature.NR_MODE] = text(NrMode.NSA_AND_SA.storageKey)
        assertTrue(ApplyReadbackRules.hasVerifiableTarget(requested, null, api34))
    }

    @Test
    fun voNrAndCountryIsoAreOnlyVerifiedFromApi34() {
        val requested = allOff().apply { put(Feature.VONR, on()) }
        assertFalse(ApplyReadbackRules.hasVerifiableTarget(requested, "cn", api33))
        assertTrue(ApplyReadbackRules.hasVerifiableTarget(requested, "cn", api34))

        // API 33 上 VoNR 与 ISO 都不写入，只剩 VoLTE 可校验。
        requested[Feature.VOLTE] = on()
        val readback = allOff().apply { put(Feature.VOLTE, on()) }
        assertTrue(ApplyReadbackRules.confirms(readback, requested, "cn", api33))
        assertFalse(ApplyReadbackRules.confirms(readback, requested, "cn", api34))
    }

    @Test
    fun nrModeMismatchIsNotConfirmed() {
        val requested = allOff().apply {
            put(Feature.FIVE_G_NR, on())
            put(Feature.NR_MODE, text(NrMode.SA_ONLY.storageKey))
        }
        val stale = allOff().apply {
            put(Feature.FIVE_G_NR, on())
            put(Feature.NR_MODE, text(NrMode.NSA_AND_SA.storageKey))
        }
        assertFalse(ApplyReadbackRules.confirms(stale, requested, null, api34))

        val applied = stale.apply { put(Feature.NR_MODE, text(NrMode.SA_ONLY.storageKey)) }
        assertTrue(ApplyReadbackRules.confirms(applied, requested, null, api34))
    }

    @Test
    fun unknownRequestedNrModeIsNotComparedAgainstTheDefault() {
        // 系统数组含无法识别的取值时读回的模式为空串，写入会原样保留该数组，
        // 不能拿默认模式 [1,2] 去比，否则每次写入都会白等重读。
        val requested = allOff().apply {
            put(Feature.FIVE_G_NR, on())
            put(Feature.NR_MODE, text(""))
        }
        val readback = allOff().apply {
            put(Feature.FIVE_G_NR, on())
            put(Feature.NR_MODE, text(""))
        }
        assertTrue(ApplyReadbackRules.hasVerifiableTarget(requested, null, api34))
        assertTrue(ApplyReadbackRules.confirms(readback, requested, null, api34))
        // 5G 开关本身仍然校验。
        assertFalse(ApplyReadbackRules.confirms(allOff(), requested, null, api34))
    }

    @Test
    fun disabledSwitchStillReadingOnIsNotSettled() {
        // 关掉 VoWiFi、VoLTE 仍开：刚写入时 VoWiFi 读回仍为开，多半是旧值。
        val requested = allOff().apply { put(Feature.VOLTE, on()) }
        val stale = allOff().apply {
            put(Feature.VOLTE, on())
            put(Feature.VOWIFI, on())
        }
        // confirms 不看关闭项（关闭态读回的是运营商默认值），但读回还不算稳定。
        assertTrue(ApplyReadbackRules.confirms(stale, requested, null, api34))
        assertFalse(ApplyReadbackRules.isSettled(stale, requested, null, api34))

        val applied = allOff().apply { put(Feature.VOLTE, on()) }
        assertTrue(ApplyReadbackRules.isSettled(applied, requested, null, api34))
    }

    @Test
    fun settledRequiresTheEnabledTargetsToo() {
        val requested = allOff().apply { put(Feature.VOLTE, on()) }
        assertFalse(ApplyReadbackRules.isSettled(allOff(), requested, null, api34))
        // 没有可校验项、也没有待刷新的关闭项：读回即可采用。
        assertTrue(ApplyReadbackRules.isSettled(allOff(), allOff(), null, api34))
    }

    @Test
    fun switchesNeverWrittenOnThisSdkDoNotDelaySettling() {
        // Android 13 从不写 VoNR 与国家码，读回的运营商默认值再等也不会变。
        val readback = allOff().apply {
            put(Feature.VONR, on())
            put(Feature.TIKTOK_NETWORK_FIX, on())
        }
        assertTrue(ApplyReadbackRules.isSettled(readback, allOff(), null, api33))
        assertFalse(ApplyReadbackRules.isSettled(readback, allOff(), null, api34))
    }

    @Test
    fun countryIsoIsComparedIgnoringCase() {
        val requested = allOff()
        val readback = allOff().apply { put(Feature.COUNTRY_ISO, text("CN")) }
        assertTrue(ApplyReadbackRules.confirms(readback, requested, "cn", api34))
        assertFalse(ApplyReadbackRules.confirms(allOff(), requested, "cn", api34))
    }
}
