package io.github.vvb2060.ims.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间线 CSV 的列必须和表头一一对应。
 *
 * 加列时最容易出的错就是只改了其中一处：文件照样写得出来，
 * 但每一行都错位，而错位要等到事后分析才会暴露。
 */
class MonitorSampleTest {

    private fun sample(note: String = "OK") = MonitorSample(
        atMillis = 1_787_483_839_565L,
        rat = "NR_SA",
        validated = "true",
        ipv4 = "10.1.2.3",
        rttMs = 38,
        jitterMs = 4681,
        probeOk = true,
        rsrp = -88,
        sinr = 21,
        thermal = "MODERATE",
        config = "nr=1+2(NSA_AND_SA);vonr=0;vt=0;ut=0;xsim=0;iso=655",
        note = note,
    )

    @Test
    fun `csv row has exactly as many columns as the header`() {
        assertEquals(
            MonitorSample.CSV_HEADER.split(",").size,
            sample().toCsvRow().split(",").size,
        )
    }

    @Test
    fun `null metrics still keep the column count`() {
        val blank = sample().copy(rttMs = null, jitterMs = null, rsrp = null, sinr = null)
        assertEquals(
            MonitorSample.CSV_HEADER.split(",").size,
            blank.toCsvRow().split(",").size,
        )
    }

    @Test
    fun `note commas and newlines cannot shift later columns`() {
        val row = sample(note = "a,b\nc").toCsvRow()
        assertEquals(MonitorSample.CSV_HEADER.split(",").size, row.split(",").size)
        assertTrue(row.endsWith("a;b c"))
    }

    @Test
    fun `the config fingerprint never introduces a comma`() {
        // 配置指纹里含数组，写成 "[1, 2]" 会把整行后面的列全部顶偏。
        val columns = MonitorSample.CSV_HEADER.split(",")
        val values = sample().toCsvRow().split(",")
        assertEquals(columns.size, values.size)
        assertTrue(values[columns.indexOf("config")].startsWith("nr=1+2"))
    }

    @Test
    fun `thermal is recorded so jitter can be checked against thermal throttling`() {
        val columns = MonitorSample.CSV_HEADER.split(",")
        val values = sample().toCsvRow().split(",")
        assertEquals("MODERATE", values[columns.indexOf("thermal")])
        assertEquals("-88", values[columns.indexOf("rsrp")])
    }

    // 重新开始监测时要把已落盘的时间线装回内存，解析必须与写入严格互逆。
    @Test
    fun `a csv row parses back to the same sample`() {
        assertEquals(sample(), MonitorSample.fromCsvRow(sample().toCsvRow()))
    }

    @Test
    fun `null metrics survive the round trip`() {
        val blank = sample().copy(rttMs = null, jitterMs = null, rsrp = null, sinr = null)
        assertEquals(blank, MonitorSample.fromCsvRow(blank.toCsvRow()))
    }

    @Test
    fun `malformed rows are skipped instead of misaligned`() {
        assertNull(MonitorSample.fromCsvRow(""))
        assertNull(MonitorSample.fromCsvRow(MonitorSample.CSV_HEADER))
        assertNull(MonitorSample.fromCsvRow(sample().toCsvRow() + ",extra"))
    }
}
