package io.github.vvb2060.ims.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vvb2060.ims.R
import io.github.vvb2060.ims.diagnostics.MonitorLog
import io.github.vvb2060.ims.diagnostics.MonitorService
import io.github.vvb2060.ims.diagnostics.SnapshotKind
import io.github.vvb2060.ims.model.SimSelection
import io.github.vvb2060.ims.viewmodel.DiagnosticsViewModel

/**
 * 5G 数据诊断页。
 *
 * 面向现场移动测试：不需要电脑或 ADB，全部经由既有的 Shizuku 架构采集。
 * 所有系统读取都由用户显式点击触发，页面本身不修改任何配置，
 * 尤其不会改动首选网络类型 —— 诊断行为本身不应改变现场。
 */
class DiagnosticsActivity : BaseActivity() {
    private val viewModel: DiagnosticsViewModel by viewModels()

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val subId = intent.getIntExtra(EXTRA_SUB_ID, -1)
        var selectedSim by remember { mutableStateOf<SimSelection?>(null) }

        LaunchedEffect(subId) {
            selectedSim = viewModel.loadSimList().firstOrNull { it.subId == subId }
            viewModel.refreshLiveStatus(subId)
            viewModel.checkShellAvailability()
        }

        LaunchedEffect(state.exportUri) {
            state.exportUri?.let { uri ->
                shareExport(uri)
                viewModel.consumeExportUri()
            }
        }

        LaunchedEffect(state.message) {
            state.message?.let {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                viewModel.consumeMessage()
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(title = { Text(stringResource(R.string.diagnostics_5g_title)) })
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TargetCard(selectedSim)
                ShellStatusCard(state.shellAvailable)
                LiveStatusCard(
                    state = state,
                    onRefresh = { viewModel.refreshLiveStatus(subId) },
                )
                MonitorCard(
                    subId = subId,
                    onChanged = { viewModel.refreshSnapshots() },
                )
                CaptureCard(
                    capturing = state.capturing,
                    progress = state.progress,
                    onRecordBad = { viewModel.capture(SnapshotKind.BAD, selectedSim) },
                    onRecordGood = { viewModel.capture(SnapshotKind.GOOD, selectedSim) },
                )
                SnapshotListCard(
                    snapshots = state.snapshots,
                    exporting = state.exporting,
                    onDelete = { viewModel.deleteSnapshot(it) },
                    onExport = { viewModel.export(selectedSim?.let { "${it.mcc}${it.mnc}" }) },
                )
            }
        }
    }

    @Composable
    private fun TargetCard(sim: SimSelection?) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.diagnostics_target),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (sim == null) {
                    Text(
                        stringResource(R.string.diagnostics_no_sim),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        "${sim.showTitle} · subId=${sim.subId} · slot=${sim.simSlotIndex} · ${sim.mcc}/${sim.mnc}",
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }

    @Composable
    private fun ShellStatusCard(available: Boolean?) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.diagnostics_shell_channel),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val text = when (available) {
                    null -> stringResource(R.string.diagnostics_shell_checking)
                    true -> stringResource(R.string.diagnostics_shell_ok)
                    false -> stringResource(R.string.diagnostics_shell_unavailable)
                }
                Text(
                    text,
                    fontSize = 12.sp,
                    color = if (available == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
    }

    @Composable
    private fun LiveStatusCard(
        state: DiagnosticsViewModel.UiState,
        onRefresh: () -> Unit,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.diagnostics_live_status),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val probe = state.liveStatus
                if (state.liveStatusError != null) {
                    Text(state.liveStatusError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                if (probe == null) {
                    Text(
                        stringResource(R.string.diagnostics_live_unknown),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Text(probe.verdict, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    val link = probe.link
                    KeyValueRow(
                        "测到的 SIM",
                        buildString {
                            append(probe.probedSubId?.let { "subId=$it" } ?: "未知")
                            if (probe.subIdMismatch) append(" ≠ 目标 subId=${probe.targetSubId}")
                        }
                    )
                    KeyValueRow("VALIDATED", link.validated?.toString() ?: "UNKNOWN")
                    KeyValueRow("IPv4", link.ipv4.ifEmpty { listOf("(none)") }.joinToString())
                    KeyValueRow("IPv6", link.ipv6.ifEmpty { listOf("(none)") }.joinToString())
                    KeyValueRow(
                        "默认路由",
                        "v4=${link.hasDefaultRouteV4} v6=${link.hasDefaultRouteV6}"
                    )
                    KeyValueRow("DNS", link.dnsServers.ifEmpty { listOf("(none)") }.joinToString())
                    probe.latency?.let { lat ->
                        KeyValueRow(
                            "RTT / 抖动",
                            "${lat.minMs ?: "-"}~${lat.maxMs ?: "-"}ms / ${lat.jitterMs ?: "-"}ms"
                        )
                    }
                    val boundNote = if (probe.ipReachability.boundToCellular) "" else " (默认路由)"
                    KeyValueRow(
                        "IP 可达",
                        "${probe.ipReachability.successes}/${probe.ipReachability.attempts}$boundNote"
                    )
                    KeyValueRow(
                        "域名可达",
                        "${probe.dnsResolution.successes}/${probe.dnsResolution.attempts}$boundNote"
                    )
                    link.error?.let {
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                    // 逐目标结果：用来分辨「全都不通」与「个别目标被拦」。
                    (probe.ipReachability.details + probe.dnsResolution.details).forEach {
                        Text(
                            it,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                if (state.refreshingLive) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Button(
                    onClick = onRefresh,
                    enabled = !state.refreshingLive,
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(stringResource(R.string.diagnostics_refresh_live))
                }
            }
        }
    }


    /**
     * 后台持续监测入口。
     *
     * 人工点击会错过窗口，因此这里提供自动采样与异常自动抓取；
     * 但必须由用户显式启动、可随时停止，且明确告知存储与耗电代价。
     */
    @Composable
    private fun MonitorCard(subId: Int, onChanged: () -> Unit) {
        val context = LocalContext.current
        val running by MonitorService.running.collectAsStateWithLifecycle()
        val samples by MonitorService.sampleCount.collectAsStateWithLifecycle()
        val captures by MonitorService.autoCaptures.collectAsStateWithLifecycle()
        val trigger by MonitorService.lastTrigger.collectAsStateWithLifecycle()

        LaunchedEffect(captures) { if (captures > 0) onChanged() }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.monitor_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.monitor_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    stringResource(R.string.monitor_state, samples, captures),
                    fontSize = 12.sp,
                )
                trigger?.let {
                    Text(
                        stringResource(R.string.monitor_last_trigger, it),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = {
                        if (running) {
                            MonitorService.stop(context)
                        } else if (subId >= 0) {
                            MonitorService.start(context, subId)
                        } else {
                            Toast.makeText(context, R.string.select_single_sim, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(
                        stringResource(
                            if (running) R.string.monitor_stop_action else R.string.monitor_start
                        )
                    )
                }
                Text(
                    stringResource(R.string.monitor_bounded, MonitorLog.MAX_SAMPLES),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }

    @Composable
    private fun CaptureCard(
        capturing: Boolean,
        progress: String,
        onRecordBad: () -> Unit,
        onRecordGood: () -> Unit,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.diagnostics_capture),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.diagnostics_capture_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRecordBad,
                        enabled = !capturing,
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text(stringResource(R.string.diagnostics_record_bad))
                    }
                    OutlinedButton(
                        onClick = onRecordGood,
                        enabled = !capturing,
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text(stringResource(R.string.diagnostics_record_good))
                    }
                }
                if (capturing) {
                    Text(progress, fontSize = 12.sp)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    @Composable
    private fun SnapshotListCard(
        snapshots: List<io.github.vvb2060.ims.diagnostics.SnapshotStore.StoredSnapshot>,
        exporting: Boolean,
        onDelete: (io.github.vvb2060.ims.diagnostics.SnapshotStore.StoredSnapshot) -> Unit,
        onExport: () -> Unit,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.diagnostics_saved, snapshots.size),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (snapshots.isEmpty()) {
                    Text(
                        stringResource(R.string.diagnostics_saved_empty),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                snapshots.forEach { snapshot ->
                    HorizontalDivider(thickness = 0.5.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(snapshot.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            Text(
                                "${snapshot.directory.listFiles()?.size ?: 0} files",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        OutlinedButton(
                            onClick = { onDelete(snapshot) },
                            modifier = Modifier.height(36.dp),
                        ) {
                            Text(stringResource(R.string.diagnostics_delete), fontSize = 12.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onExport,
                    enabled = !exporting,
                    modifier = Modifier.height(40.dp),
                ) {
                    Text(stringResource(R.string.diagnostics_export))
                }
                if (exporting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                // 文档要求：raw dump 含身份标识时导出页必须给出明确提示。
                Text(
                    stringResource(R.string.diagnostics_export_privacy),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    @Composable
    private fun KeyValueRow(key: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(key, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            Text(
                value,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    /** 用系统分享面板发出 ZIP；授予临时读权限，不需要任何存储权限。 */
    private fun shareExport(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.diagnostics_export)))
        }.onFailure {
            Toast.makeText(this, it.message ?: "share failed", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_SUB_ID = "sub_id"

        fun intent(context: android.content.Context, subId: Int): Intent =
            Intent(context, DiagnosticsActivity::class.java).putExtra(EXTRA_SUB_ID, subId)
    }
}
