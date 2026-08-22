package io.github.vvb2060.ims.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.vvb2060.ims.ShizukuProvider
import android.net.Uri
import io.github.vvb2060.ims.diagnostics.DiagnosticsExporter
import io.github.vvb2060.ims.diagnostics.NetworkProbe
import io.github.vvb2060.ims.diagnostics.SnapshotCollector
import io.github.vvb2060.ims.diagnostics.SnapshotKind
import io.github.vvb2060.ims.diagnostics.SnapshotStore
import io.github.vvb2060.ims.model.SimSelection
import io.github.vvb2060.ims.shell.ShellRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val capturing: Boolean = false,
        val progress: String = "",
        val snapshots: List<SnapshotStore.StoredSnapshot> = emptyList(),
        val liveStatus: NetworkProbe.Result? = null,
        val liveStatusError: String? = null,
        val refreshingLive: Boolean = false,
        val shellAvailable: Boolean? = null,
        val message: String? = null,
        val exporting: Boolean = false,
        /** 导出成功后待分享的 ZIP，消费后清空。 */
        val exportUri: Uri? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refreshSnapshots()
    }

    fun refreshSnapshots() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                snapshots = runCatching { SnapshotStore.list(getApplication()) }.getOrDefault(emptyList())
            )
        }
    }

    /**
     * 刷新实时链路状态。不做任何写入，纯读取。
     */
    fun refreshLiveStatus(targetSubId: Int? = null) {
        if (_uiState.value.refreshingLive) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(refreshingLive = true, liveStatusError = null)
            val result = runCatching { NetworkProbe.run(getApplication(), targetSubId) }
            _uiState.value = _uiState.value.copy(
                refreshingLive = false,
                liveStatus = result.getOrNull(),
                liveStatusError = result.exceptionOrNull()
                    ?.let { "${it.javaClass.simpleName}: ${it.message ?: "no message"}" },
            )
        }
    }

    /**
     * 探测 shell 用户服务是否可用。radio 日志与 dumpsys 都依赖它，
     * 提前告知用户，免得采集完才发现关键证据缺失。
     */
    fun checkShellAvailability() {
        viewModelScope.launch {
            val result = ShellRunner.exec(getApplication(), listOf("id"), timeoutMillis = 8_000, maxOutputBytes = 4096)
            _uiState.value = _uiState.value.copy(
                shellAvailable = result.isSuccess,
                message = if (result.isSuccess) {
                    "shell 通道可用：${result.stdout.trim().take(120)}"
                } else {
                    "shell 通道不可用：${result.stderr.ifBlank { result.exitCode }}"
                },
            )
        }
    }

    /**
     * 采集一次快照。所有采集失败都会记录进快照本身，不会中断流程。
     */
    fun capture(kind: SnapshotKind, selectedSim: SimSelection?) {
        if (_uiState.value.capturing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(capturing = true, progress = "准备中", message = null)
            try {
                val snapshot = SnapshotCollector.collect(
                    context = getApplication(),
                    kind = kind,
                    selectedSim = selectedSim,
                ) { step ->
                    _uiState.value = _uiState.value.copy(progress = step)
                }
                _uiState.value = _uiState.value.copy(progress = "写入文件")
                val stored = SnapshotStore.write(getApplication(), snapshot)
                val failedCommands = snapshot.commands.count { !it.isSuccess }
                _uiState.value = _uiState.value.copy(
                    message = stored.fold(
                        onSuccess = {
                            buildString {
                                append("已记录 ${it.name}")
                                if (failedCommands > 0) append("（${failedCommands} 条命令失败，详情已保留在包内）")
                            }
                        },
                        onFailure = { "写入失败：${it.javaClass.simpleName}: ${it.message ?: ""}" },
                    )
                )
            } catch (t: Throwable) {
                Log.w(TAG, "capture failed", t)
                _uiState.value = _uiState.value.copy(
                    message = "采集失败：${t.javaClass.simpleName}: ${t.message ?: "no message"}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(capturing = false, progress = "")
                refreshSnapshots()
            }
        }
    }

    fun deleteSnapshot(snapshot: SnapshotStore.StoredSnapshot) {
        viewModelScope.launch {
            SnapshotStore.delete(snapshot)
            refreshSnapshots()
        }
    }

    /**
     * 打包全部快照。失败不抛出，原因通过 message 呈现。
     */
    fun export(mccMnc: String?) {
        if (_uiState.value.exporting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(exporting = true, message = null)
            val result = DiagnosticsExporter.export(getApplication(), mccMnc)
            _uiState.value = _uiState.value.copy(
                exporting = false,
                exportUri = result.getOrNull()?.uri,
                message = result.fold(
                    onSuccess = { "已生成 ${it.file.name}（${it.snapshotCount} 份快照）" },
                    onFailure = { "导出失败：${it.message ?: it.javaClass.simpleName}" },
                ),
            )
        }
    }

    fun consumeExportUri() {
        _uiState.value = _uiState.value.copy(exportUri = null)
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    suspend fun loadSimList(): List<SimSelection> =
        runCatching { ShizukuProvider.readSimInfoList(getApplication()) }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "DiagnosticsViewModel"
    }
}
