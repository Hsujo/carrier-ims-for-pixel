package io.github.vvb2060.ims.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采样节奏的约束。
 *
 * 实测导出里「15 秒间隔」的真实周期中位数是 27.8 秒、最长 76.6 秒，
 * 因为旧写法是「跑完再等固定时长」，探测耗时全部叠加在周期上，
 * 链路越差探测越慢、采样越稀。这里把定频调度该满足的性质固定下来。
 */
class MonitorCadenceTest {

    /** 复刻服务里的调度算式：定频 = 周期 - 本次耗时，且不低于硬下限。 */
    private fun gap(periodMillis: Long, elapsedMillis: Long): Long =
        (periodMillis - elapsedMillis).coerceAtLeast(MonitorService.MIN_GAP_MILLIS)

    @Test
    fun `a slow probe shortens the wait instead of stretching the period`() {
        val period = MonitorService.SAMPLE_INTERVAL_MILLIS
        assertEquals(period - 13_000, gap(period, 13_000))
        // 旧写法在这里会得到 period + 13000，也就是周期被探测耗时拖长。
        assertTrue(gap(period, 13_000) + 13_000 <= period)
    }

    @Test
    fun `an over-budget probe still leaves a floor between samples`() {
        // 满功率发射时背靠背探测会明显加剧发热，负数间隔必须被夹住。
        assertEquals(MonitorService.MIN_GAP_MILLIS, gap(MonitorService.SAMPLE_INTERVAL_MILLIS, 60_000))
        assertTrue(gap(MonitorService.FAST_INTERVAL_MILLIS, 9_000) > 0)
    }

    @Test
    fun `the fast burst is denser than the normal cadence and finite`() {
        assertTrue(MonitorService.FAST_INTERVAL_MILLIS < MonitorService.SAMPLE_INTERVAL_MILLIS)
        assertTrue(MonitorService.FAST_BURST_SAMPLES > 0)
    }

    @Test
    fun `the burst covers about a minute, long enough to shape an episode`() {
        val coverage = MonitorService.FAST_INTERVAL_MILLIS * MonitorService.FAST_BURST_SAMPLES
        assertTrue(coverage in 30_000..180_000)
    }

    @Test
    fun `a burst cannot outlast the capture cooldown`() {
        // 否则一段劣化会在加密采样期间反复触发完整快照，把存储塞满。
        val coverage = MonitorService.FAST_INTERVAL_MILLIS * MonitorService.FAST_BURST_SAMPLES
        assertTrue(coverage < MonitorService.CAPTURE_COOLDOWN_MILLIS)
    }
}
