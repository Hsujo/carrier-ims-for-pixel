package io.github.vvb2060.ims.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 导出打包期间删掉快照，ZIP 会缺文件却照样报成功；删除必须等打包结束。
 */
class SnapshotStoreTest {

    @Test
    fun `deletion waits until an export in progress has finished`() = runBlocking {
        val dir = Files.createTempDirectory("BAD_5G_test").toFile()
        File(dir, "summary.txt").writeText("probe_verdict=OK")
        val snapshot = SnapshotStore.StoredSnapshot(dir.name, SnapshotKind.BAD, dir, 0L)

        val packing = CompletableDeferred<Unit>()
        val finishPacking = CompletableDeferred<Unit>()
        val export = launch {
            SnapshotStore.withoutRemovals {
                packing.complete(Unit)
                finishPacking.await()
            }
        }
        packing.await()

        val deletion = async { SnapshotStore.delete(snapshot) }
        // 给删除足够的机会执行：打包未结束时它只能等着。
        delay(100)
        assertTrue(dir.exists())
        assertFalse(deletion.isCompleted)

        finishPacking.complete(Unit)
        export.join()
        assertTrue(deletion.await())
        assertFalse(dir.exists())
    }
}
