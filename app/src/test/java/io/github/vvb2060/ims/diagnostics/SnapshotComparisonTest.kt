package io.github.vvb2060.ims.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotComparisonTest {

    private fun summary(kind: SnapshotKind, vararg pairs: Pair<String, String>) =
        SnapshotSummary(
            name = "${kind.prefix}_test",
            kind = kind,
            fields = LinkedHashMap(pairs.toMap()),
        )

    @Test
    fun missingSampleYieldsNoComparison() {
        assertNull(SnapshotComparison.compare(null, summary(SnapshotKind.GOOD)))
        assertNull(SnapshotComparison.compare(summary(SnapshotKind.BAD), null))
    }

    @Test
    fun changedPriorityFieldsAreFlagged() {
        val diffs = SnapshotComparison.compare(
            summary(SnapshotKind.BAD, "nr_state" to "NOT_RESTRICTED", "validated" to "false"),
            summary(SnapshotKind.GOOD, "nr_state" to "CONNECTED", "validated" to "true"),
        )!!
        val nr = diffs.first { it.field == "nr_state" }
        assertTrue(nr.changed)
        assertTrue(nr.priority)
    }

    @Test
    fun unchangedFieldsAreNotFlagged() {
        val diffs = SnapshotComparison.compare(
            summary(SnapshotKind.BAD, "apn" to "3gnet"),
            summary(SnapshotKind.GOOD, "apn" to "3gnet"),
        )!!
        assertTrue(diffs.none { it.changed })
    }

    @Test
    fun badAndGoodArePairedFromTheSameSim() {
        // 从新到旧：最新的 GOOD 属于 subId=2，最新的 BAD 属于 subId=1。
        val newestFirst = listOf(
            Triple("GOOD_sub2", SnapshotKind.GOOD, "2"),
            Triple("BAD_sub1", SnapshotKind.BAD, "1"),
            Triple("GOOD_sub1", SnapshotKind.GOOD, "1"),
        )
        val (bad, good) = SnapshotComparison.pickPair(newestFirst, { it.second }, { it.third })
        assertEquals("BAD_sub1", bad?.first)
        assertEquals("GOOD_sub1", good?.first)

        // 同一张卡没有 GOOD 时宁可缺失，也不拿另一张卡的来比。
        val (onlyBad, noGood) = SnapshotComparison.pickPair(newestFirst.take(2), { it.second }, { it.third })
        assertEquals("BAD_sub1", onlyBad?.first)
        assertNull(noGood)

        // 没有 BAD 时只给出最新的 GOOD。
        val (noBad, latestGood) = SnapshotComparison.pickPair(
            listOf(newestFirst[0], newestFirst[2]), { it.second }, { it.third }
        )
        assertNull(noBad)
        assertEquals("GOOD_sub2", latestGood?.first)
    }

    @Test
    fun textReportCallsOutMissingSamples() {
        val text = SnapshotComparison.toText(null, summary(SnapshotKind.GOOD))
        assertTrue(text.contains("样本不足"))
    }

    @Test
    fun textReportListsChangedPriorityFields() {
        val text = SnapshotComparison.toText(
            summary(SnapshotKind.BAD, "ipv4" to "(none)"),
            summary(SnapshotKind.GOOD, "ipv4" to "10.1.2.3/32"),
        )
        assertTrue(text.contains("ipv4"))
        assertTrue(text.contains("10.1.2.3/32"))
    }
}
