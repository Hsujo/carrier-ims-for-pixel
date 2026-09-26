package io.github.vvb2060.ims.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 快照落盘。
 *
 * 存在应用私有的 external cache 目录下，不需要 MANAGE_EXTERNAL_STORAGE；
 * 保留数量有上限，避免长期使用后无限增长。
 */
object SnapshotStore {
    private const val TAG = "SnapshotStore"
    private const val DIR_NAME = "diagnostics"

    /** 每种类型最多保留的快照数，超出后删除最旧的。 */
    private const val MAX_PER_KIND = 10

    /** 写入中的快照目录前缀；不以任何 [SnapshotKind.prefix] 开头，[list] 不会列出它。 */
    private const val STAGING_PREFIX = ".staging_"

    /** 一次采集最多几十秒，超过一小时仍在的临时目录只可能是残留。 */
    private const val STALE_STAGING_MILLIS = 60 * 60 * 1000L

    data class StoredSnapshot(
        val name: String,
        val kind: SnapshotKind,
        val directory: File,
        val takenAtMillis: Long,
    )

    fun rootDir(context: Context): File {
        val base = context.externalCacheDir ?: context.cacheDir
        return File(base, DIR_NAME).apply { mkdirs() }
    }

    /**
     * 写入一次快照。原始 dump 一律单独成文件，解析器不认识的格式也不会丢失证据。
     */
    suspend fun write(context: Context, snapshot: Snapshot): Result<StoredSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = rootDir(context)
                pruneStaleStaging(root)
                // 先写进 list() 看不到的临时目录，全部写完再改名发布：
                // 导出可能与写入同时进行，否则会把只写了一半的快照打进包里。
                val dir = File(root, "$STAGING_PREFIX${snapshot.name}")
                dir.deleteRecursively()
                dir.mkdirs()

                File(dir, "metadata.txt").writeText(buildMetadataText(snapshot))

                // 每条命令一个文件，文件名取自命令本身，便于人工翻阅。
                snapshot.commands.forEach { result ->
                    val fileName = fileNameFor(result.command)
                    runCatching { File(dir, fileName).writeText(result.toReportText()) }
                        .onFailure { Log.w(TAG, "failed to write $fileName", it) }
                }

                File(dir, "probes.txt").writeText(buildProbeText(snapshot))

                // 摘要只是索引；解析失败的字段为 UNKNOWN，原始 dump 仍然完整保留。
                runCatching {
                    File(dir, "summary.txt").writeText(SnapshotSummary.from(snapshot).toText())
                }.onFailure { Log.w(TAG, "failed to write summary.txt", it) }

                // 同一文件系统内的目录改名是原子的：导出要么看不到它，要么看到完整的一份。
                val published = File(root, snapshot.name)
                if (!dir.renameTo(published)) {
                    dir.deleteRecursively()
                    error("failed to publish snapshot ${snapshot.name}")
                }

                cleanupOldSnapshots(context, snapshot.kind)
                StoredSnapshot(snapshot.name, snapshot.kind, published, snapshot.takenAtMillis)
            }
        }

    fun list(context: Context): List<StoredSnapshot> {
        val root = rootDir(context)
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val kind = SnapshotKind.entries.firstOrNull { dir.name.startsWith(it.prefix) } ?: return@mapNotNull null
            StoredSnapshot(dir.name, kind, dir, dir.lastModified())
        }.sortedByDescending { it.takenAtMillis }
    }

    /**
     * 删除快照（用户手动删、写入后按上限清理）与导出打包互斥：
     * 导出正在读的快照若被删掉一半，ZIP 会缺文件却照样报成功。
     */
    private val removalLock = Mutex()

    /** 在没有快照会被删除的前提下执行 [block]，供导出打包使用。 */
    suspend fun <T> withoutRemovals(block: suspend () -> T): T = removalLock.withLock { block() }

    suspend fun delete(snapshot: StoredSnapshot): Boolean = removalLock.withLock {
        runCatching {
            snapshot.directory.deleteRecursively()
        }.getOrDefault(false)
    }

    /**
     * 清掉进程中途被杀时残留的临时目录。只删足够旧的：另一次采集可能正在写它自己的临时目录。
     */
    private fun pruneStaleStaging(root: File) {
        val cutoff = System.currentTimeMillis() - STALE_STAGING_MILLIS
        root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(STAGING_PREFIX) && it.lastModified() < cutoff }
            ?.forEach {
                Log.i(TAG, "removing stale staging dir ${it.name}")
                it.deleteRecursively()
            }
    }

    private suspend fun cleanupOldSnapshots(context: Context, kind: SnapshotKind) {
        val sameKind = list(context).filter { it.kind == kind }
        if (sameKind.size <= MAX_PER_KIND) return
        sameKind.drop(MAX_PER_KIND).forEach {
            Log.i(TAG, "pruning old snapshot ${it.name}")
            delete(it)
        }
    }

    private fun fileNameFor(command: String): String {
        val base = command
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .take(60)
            .ifBlank { "command" }
        return "$base.txt"
    }

    private fun buildMetadataText(snapshot: Snapshot): String = buildString {
        appendLine("snapshot: ${snapshot.name}")
        appendLine("kind: ${snapshot.kind.name}")
        appendLine()
        snapshot.metadata.forEach { (key, value) -> appendLine("$key=$value") }
    }

    private fun buildProbeText(snapshot: Snapshot): String = buildString {
        val probe = snapshot.probe
        if (probe == null) {
            appendLine("probe unavailable: ${snapshot.probeError ?: "unknown reason"}")
            return@buildString
        }
        appendLine("verdict: ${probe.verdict}")
        appendLine()
        val link = probe.link
        appendLine("target_sub_id=${probe.targetSubId ?: "UNKNOWN"}")
        appendLine("probed_sub_id=${probe.probedSubId ?: "UNKNOWN"}")
        appendLine("sub_id_mismatch=${probe.subIdMismatch}")
        appendLine("link_sub_id=${link.subId ?: "UNKNOWN"}")
        appendLine("link_state_unreadable=${link.unreadable}")
        appendLine("has_cellular_network=${link.hasCellularNetwork}")
        appendLine("validated=${link.validated ?: "UNKNOWN"}")
        appendLine("interface=${link.interfaceName ?: "UNKNOWN"}")
        appendLine("ipv4=${link.ipv4.ifEmpty { listOf("(none)") }.joinToString()}")
        appendLine("ipv6=${link.ipv6.ifEmpty { listOf("(none)") }.joinToString()}")
        appendLine("default_route_v4=${link.hasDefaultRouteV4}")
        appendLine("default_route_v6=${link.hasDefaultRouteV6}")
        appendLine("dns=${link.dnsServers.ifEmpty { listOf("(none)") }.joinToString()}")
        link.error?.let { appendLine("link_error=$it") }
        appendLine()
        appendLine("routes:")
        link.routes.ifEmpty { listOf("(none)") }.forEach { appendLine("  $it") }
        appendLine()
        appendLine("capabilities: ${link.capabilities ?: "UNKNOWN"}")
        appendLine()
        probe.latency?.let { lat ->
            appendLine("latency: target=${lat.target} verdict=${lat.verdict}")
            appendLine("  samples_ms=${lat.samples.joinToString()}")
            appendLine("  min=${lat.minMs} avg=${lat.avgMs} max=${lat.maxMs} jitter=${lat.jitterMs}")
            appendLine("  failures=${lat.failures} bound_to_cellular=${lat.boundToCellular}")
            appendLine()
        }
        listOf(probe.ipReachability, probe.dnsResolution).forEach { p ->
            appendLine(
                "${p.label}: target=${p.target} ${p.successes}/${p.attempts} " +
                    "ok=${p.ok} bound_to_cellular=${p.boundToCellular} unusable=${p.unusable}"
            )
            p.details.forEach { appendLine("  $it") }
            p.lastError?.let { appendLine("  last_error=$it") }
        }
    }
}
