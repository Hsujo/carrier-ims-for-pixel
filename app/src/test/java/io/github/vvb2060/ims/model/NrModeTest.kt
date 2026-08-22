package io.github.vvb2060.ims.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NrModeTest {
    @Test
    fun modesMapToExpectedAvailabilityArrays() {
        assertContentEquals(intArrayOf(1), NrMode.NSA_ONLY.toAvailabilities())
        assertContentEquals(intArrayOf(1, 2), NrMode.NSA_AND_SA.toAvailabilities())
        assertContentEquals(intArrayOf(2), NrMode.SA_ONLY.toAvailabilities())
    }

    @Test
    fun availabilityArraysMapBackToModes() {
        assertEquals(NrMode.NSA_ONLY, NrMode.fromAvailabilities(intArrayOf(1)))
        assertEquals(NrMode.NSA_AND_SA, NrMode.fromAvailabilities(intArrayOf(1, 2)))
        assertEquals(NrMode.NSA_AND_SA, NrMode.fromAvailabilities(intArrayOf(2, 1)))
        assertEquals(NrMode.SA_ONLY, NrMode.fromAvailabilities(intArrayOf(2)))
    }

    @Test
    fun roundTripIsStableForEveryMode() {
        NrMode.entries.forEach { mode ->
            assertEquals(mode, NrMode.fromAvailabilities(mode.toAvailabilities()))
        }
    }

    @Test
    fun unrecognisedValuesReportUnknownRatherThanGuessing() {
        assertNull(NrMode.fromAvailabilities(null))
        assertNull(NrMode.fromAvailabilities(intArrayOf()))
        // 含未知取值时不猜测，交由 UI 显示 UNKNOWN 并保留原始值。
        assertNull(NrMode.fromAvailabilities(intArrayOf(1, 2, 7)))
        assertNull(NrMode.fromAvailabilities(intArrayOf(9)))
    }

    @Test
    fun storageKeysRoundTrip() {
        NrMode.entries.forEach { mode ->
            assertEquals(mode, NrMode.fromStorageKey(mode.storageKey))
        }
        assertNull(NrMode.fromStorageKey(""))
        assertNull(NrMode.fromStorageKey(null))
        assertNull(NrMode.fromStorageKey("bogus"))
    }

    @Test
    fun formatsActualValueForDisplay() {
        assertEquals("[1, 2]", NrMode.formatAvailabilities(intArrayOf(1, 2)))
        assertEquals("[1]", NrMode.formatAvailabilities(intArrayOf(1)))
        assertEquals("UNKNOWN", NrMode.formatAvailabilities(null))
    }
}
