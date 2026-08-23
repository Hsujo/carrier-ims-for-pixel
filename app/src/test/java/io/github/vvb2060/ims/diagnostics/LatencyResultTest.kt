package io.github.vvb2060.ims.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抖动是这个故障的指纹，所以「测不到」和「测到很大」绝不能混为一谈。
 *
 * 早先的实现把超时当失败丢掉，链路最差的时刻反而没有样本进入抖动计算，
 * 抖动因此被系统性低估 —— 这几条用例把那个行为钉死。
 */
class LatencyResultTest {

    private fun result(
        samples: List<Long>,
        failures: Int = 0,
        censored: Int = 0,
    ) = NetworkProbe.LatencyResult(
        target = "223.5.5.5",
        samples = samples,
        failures = failures,
        boundToCellular = true,
        censored = censored,
    )

    @Test
    fun `jitter is the spread between best and worst sample`() {
        val r = result(listOf(38L, 41L, 4681L, 40L))
        assertEquals(4643L, r.jitterMs)
        assertEquals(38L, r.minMs)
        assertEquals(4681L, r.maxMs)
    }

    @Test
    fun `a timed-out sample counts as a lower bound, not as a missing sample`() {
        // 6000ms 超时意味着「RTT 至少 6000ms」，这是观测而不是缺失。
        val r = result(listOf(40L, 6000L), censored = 1)
        assertEquals(5960L, r.jitterMs)
        assertTrue(r.jitterIsLowerBound)
        assertTrue(r.verdict.contains(">="))
        assertTrue(r.verdict.startsWith("SEVERE_JITTER"))
    }

    @Test
    fun `without censored samples the verdict states an exact value`() {
        val r = result(listOf(40L, 46L, 52L))
        assertFalse(r.jitterIsLowerBound)
        assertFalse(r.verdict.contains(">="))
        assertTrue(r.verdict.startsWith("STABLE"))
    }

    @Test
    fun `a single sample yields no jitter rather than zero`() {
        // 一个样本算不出极差；报 0 会被读成「非常稳定」，是最坏的一种错。
        assertEquals(null, result(listOf(40L)).jitterMs)
        assertEquals("UNKNOWN", result(listOf(40L)).verdict)
        assertEquals(null, result(emptyList(), failures = 5).jitterMs)
    }

    @Test
    fun `hard failures stay separate from timeouts`() {
        val r = result(listOf(40L, 6000L), failures = 2, censored = 1)
        assertEquals(2, r.failures)
        assertEquals(1, r.censored)
        assertEquals(2, r.samples.size)
    }
}
