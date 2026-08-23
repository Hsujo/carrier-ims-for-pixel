package io.github.vvb2060.ims.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台采样的节奏取决于探测强度，因此这些取值是行为的一部分，不是调参。
 */
class ProbeProfileTest {

    private val monitor = NetworkProbe.Profile.MONITOR
    private val full = NetworkProbe.Profile.FULL

    @Test
    fun `monitor profile does not shorten the per-sample timeout`() {
        // 缩短单次超时会把秒级停顿截断掉 —— 那正是要测的东西。
        assertEquals(full.latencyTimeoutMillis, monitor.latencyTimeoutMillis)
    }

    @Test
    fun `monitor profile bounds total probe time instead`() {
        assertTrue(monitor.latencyBudgetMillis < full.latencyBudgetMillis)
        // 预算必须小于常态采样周期，否则探测自身就会把周期撑破。
        assertTrue(monitor.latencyBudgetMillis < MonitorService.SAMPLE_INTERVAL_MILLIS)
    }

    @Test
    fun `monitor profile trims the work that never varied in this fault`() {
        assertFalse(monitor.dnsEnabled)
        assertTrue(full.dnsEnabled)
        assertTrue(monitor.ipTargetCount < full.ipTargetCount)
        assertTrue(monitor.requestNetworkTimeoutMillis < full.requestNetworkTimeoutMillis)
    }

    @Test
    fun `full profile keeps every target so a blocked host is distinguishable`() {
        assertEquals(3, full.ipTargetCount)
        assertEquals(2, full.ipPortCount)
    }
}
