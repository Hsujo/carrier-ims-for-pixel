package io.github.vvb2060.ims.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PerSimOverrideRulesTest {

    @Test
    fun nrArrayOfAFiveGSimIsKeptAsIs() {
        // SA only、以及含厂商取值的数组都要原样保留，不能被全部 SIM 的写入改成 [1,2]。
        assertContentEquals(intArrayOf(2), PerSimOverrideRules.nrArrayToKeep(true, intArrayOf(2)))
        assertContentEquals(intArrayOf(1, 2, 5), PerSimOverrideRules.nrArrayToKeep(true, intArrayOf(1, 2, 5)))
    }

    @Test
    fun requestedNrArrayIsWrittenWhenFiveGWasOff() {
        // 该卡 5G 原本关着：这次是开启操作，照常写请求的数组。
        assertNull(PerSimOverrideRules.nrArrayToKeep(true, intArrayOf()))
        assertNull(PerSimOverrideRules.nrArrayToKeep(true, intArrayOf(0)))
        assertNull(PerSimOverrideRules.nrArrayToKeep(true, null))
        // 本次不写 NR 数组（5G 关闭）时也无从保留。
        assertNull(PerSimOverrideRules.nrArrayToKeep(false, intArrayOf(2)))
    }

    @Test
    fun countryIsoOverrideIsKeptOnlyWhenTheRequestDoesNotSetOne() {
        assertEquals("460", PerSimOverrideRules.countryIsoToKeep(false, "460"))
        assertNull(PerSimOverrideRules.countryIsoToKeep(false, ""))
        assertNull(PerSimOverrideRules.countryIsoToKeep(false, null))
        assertNull(PerSimOverrideRules.countryIsoToKeep(true, "460"))
    }
}
