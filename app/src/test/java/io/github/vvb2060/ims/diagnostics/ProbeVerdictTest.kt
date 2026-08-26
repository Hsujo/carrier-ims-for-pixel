package io.github.vvb2060.ims.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「没测」不等于「测了没通」。
 *
 * MONITOR 剖面关掉 DNS 探测后返回 0/0，而 `!ok` 恒为真 ——
 * 实测 1223 条采样里 1217 条被判成 DNS_FAILED，判定列整列作废。
 */
class ProbeVerdictTest {

    private fun probe(attempts: Int, successes: Int) = NetworkProbe.ProbeResult(
        label = "dns",
        target = "www.baidu.com",
        attempts = attempts,
        successes = successes,
        lastError = null,
        boundToCellular = true,
        details = emptyList(),
    )

    @Test
    fun `a skipped probe is neither reachable nor a failure`() {
        val skipped = probe(attempts = 0, successes = 0)
        assertTrue(skipped.skipped)
        assertFalse(skipped.ok)
    }

    @Test
    fun `a probe that ran and failed is not marked skipped`() {
        val failed = probe(attempts = 2, successes = 0)
        assertFalse(failed.skipped)
        assertFalse(failed.ok)
    }

    @Test
    fun `a probe that ran and succeeded is neither`() {
        val okProbe = probe(attempts = 2, successes = 2)
        assertFalse(okProbe.skipped)
        assertTrue(okProbe.ok)
    }
}
