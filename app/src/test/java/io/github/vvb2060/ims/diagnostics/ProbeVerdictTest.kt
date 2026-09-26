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

    private fun result(
        onCellular: Boolean,
        targetSubId: Int? = 1,
        probedSubId: Int? = 1,
        targetUnverified: Boolean = false,
    ) = NetworkProbe.Result(
        link = NetworkProbe.LinkSnapshot(
            unreadable = false,
            hasCellularNetwork = onCellular,
            validated = null,
            interfaceName = null,
            ipv4 = emptyList(),
            ipv6 = emptyList(),
            hasDefaultRouteV4 = false,
            hasDefaultRouteV6 = false,
            routes = emptyList(),
            dnsServers = emptyList(),
            capabilities = null,
            subId = probedSubId,
            hasInternetCapability = onCellular,
            error = null,
        ),
        ipReachability = probe(attempts = 2, successes = 2),
        dnsResolution = probe(attempts = 0, successes = 0),
        latency = null,
        verdict = "",
        targetSubId = targetSubId,
        probedSubId = probedSubId,
        targetUnverified = targetUnverified,
        onCellular = onCellular,
    )

    @Test
    fun `a probe that never got a cellular network does not measure the target`() {
        // 蜂窝数据完全中断时探测走的是默认路由（常是 Wi-Fi），通了也不能记在目标卡名下。
        assertFalse(result(onCellular = false, probedSubId = null).measuresTarget)
        assertTrue(result(onCellular = true).measuresTarget)
    }

    @Test
    fun `a probe on another or unproven sim does not measure the target`() {
        assertFalse(result(onCellular = true, probedSubId = 2).measuresTarget)
        assertFalse(result(onCellular = true, probedSubId = null, targetUnverified = true).measuresTarget)
    }
}
