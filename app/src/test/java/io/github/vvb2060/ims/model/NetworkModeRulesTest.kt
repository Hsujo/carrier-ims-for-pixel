package io.github.vvb2060.ims.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkModeRulesTest {
    @Test
    fun disableAndRestorePreserveAllOtherNetworksIncludingUnknownBits() {
        val otherNetworks = (1L shl 12) or (1L shl 1) or (1L shl 45)
        val original = otherNetworks or NetworkModeRules.NR_BIT
        val disabled = NetworkModeRules.toggle5g(original)
        assertEquals(otherNetworks, disabled)
        assertFalse(NetworkModeRules.is5gAllowed(disabled))
        assertEquals(original, NetworkModeRules.toggle5g(disabled))
        assertTrue(NetworkModeRules.is5gAllowed(original))
    }

    @Test
    fun invalidOrNrOnlyPreferenceCannotDisableAllNetworks() {
        assertFailsWith<IllegalArgumentException> { NetworkModeRules.toggle5g(0L) }
        assertFailsWith<IllegalArgumentException> { NetworkModeRules.toggle5g(NetworkModeRules.NR_BIT) }
    }
}
