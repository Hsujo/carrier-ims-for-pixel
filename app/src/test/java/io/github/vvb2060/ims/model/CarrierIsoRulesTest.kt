package io.github.vvb2060.ims.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarrierIsoRulesTest {
    @Test
    fun mccMapsToKnownCountryIso() {
        assertEquals("cn", CarrierIsoRules.resolveIsoByMcc("460", ""))
        assertEquals("hk", CarrierIsoRules.resolveIsoByMcc("454", ""))
        assertEquals("tw", CarrierIsoRules.resolveIsoByMcc("466", ""))
    }

    @Test
    fun numericPseudoIsoIsRecognised() {
        assertTrue(CarrierIsoRules.isNumericPseudoIso("192"))
        assertTrue(CarrierIsoRules.isNumericPseudoIso("705"))
        assertFalse(CarrierIsoRules.isNumericPseudoIso("cn"))
        assertFalse(CarrierIsoRules.isNumericPseudoIso(""))
    }

    @Test
    fun mccOutranksStaleNumericIso() {
        // TikTok 修复关闭后 framework 可能仍返回上一次写入的数字 ISO，
        // MCC 必须胜出，否则用户改动其他配置会把 192 重新写回系统。
        assertEquals("cn", CarrierIsoRules.resolveIsoByMcc("460", "192"))
        assertEquals("cn", CarrierIsoRules.resolveIsoByMcc("460", "705"))
        assertEquals("hk", CarrierIsoRules.resolveIsoByMcc("454", "192"))
    }

    @Test
    fun numericIsoIsNeverUsedAsFallback() {
        // MCC 读不到时，宁可不写 ISO，也不能把数字伪 ISO 当成正常国家码沿用。
        assertNull(CarrierIsoRules.resolveIsoByMcc("", "705"))
        assertNull(CarrierIsoRules.resolveIsoByMcc("", ""))
        // 未知 MCC 同理。
        assertNull(CarrierIsoRules.resolveIsoByMcc("999", "192"))
    }

    @Test
    fun realIsoStillFallsBackWhenMccUnknown() {
        assertEquals("de", CarrierIsoRules.resolveIsoByMcc("", "DE"))
        assertEquals("de", CarrierIsoRules.resolveIsoByMcc("999", "de"))
    }
}
