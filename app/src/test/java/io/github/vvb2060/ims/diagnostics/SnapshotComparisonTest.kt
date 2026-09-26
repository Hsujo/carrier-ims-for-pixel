package io.github.vvb2060.ims.diagnostics

import kotlin.test.Test
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
