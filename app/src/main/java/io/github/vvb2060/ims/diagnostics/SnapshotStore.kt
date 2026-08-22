package io.github.vvb2060.ims.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
                val dir = File(rootDir(context), snapshot.name)
                dir.mkdirs()

                File(dir, "metadata.txt").writeText(buildMetadataText(snapshot))

                // 每条命令一个文件，文件名取自命令本身，便于人工翻阅。
                snapshot.commands.forEach { result ->
                    val fileName = fileNameFor(result.command)
                    runCatching { File(dir, fileName).writeText(result.toReportText()) }
                        .onFailure { Log.w(TAG, "failed to write $fileName", it) }
                }

                File(dir, "probes.txt").writeText(buildProbeText(snapshot))

                cleanupOldSnapshots(context, snapshot.kind)
                StoredSnapshot(snapshot.name, snapshot.kind, dir, snapshot.takenAtMillis)
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

    fun delete(snapshot: StoredSnapshot): Boolean = runCatching {
        snapshot.directory.deleteRecursively()
    }.getOrDefault(false)

    private fun cleanupOldSnapshots(context: Context, kind: SnapshotKind) {
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
