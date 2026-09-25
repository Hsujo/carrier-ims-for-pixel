package io.github.vvb2060.ims.ui

import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vvb2060.ims.R
import io.github.vvb2060.ims.model.Feature
import io.github.vvb2060.ims.model.FeatureValue
import io.github.vvb2060.ims.model.FeatureValueType
import io.github.vvb2060.ims.model.ApnDraftConfig
import io.github.vvb2060.ims.model.ConfigBackupSnapshot
import io.github.vvb2060.ims.model.NetworkExitStatus
import io.github.vvb2060.ims.model.ShizukuStatus
import io.github.vvb2060.ims.model.SimSelection
import io.github.vvb2060.ims.model.ToolRules
import io.github.vvb2060.ims.model.SystemInfo
import io.github.vvb2060.ims.privileged.ImsModifier
import io.github.vvb2060.ims.tiles.SIM1IMSStatusTileService
import io.github.vvb2060.ims.tiles.SIM1VoLTETileService
import io.github.vvb2060.ims.tiles.SIM2IMSStatusTileService
import io.github.vvb2060.ims.tiles.SIM2VoLTETileService
import io.github.vvb2060.ims.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val REPO_URL = "https://github.com/Hsujo/carrier-ims-for-pixel"
private const val REPO_ISSUE_URL = "https://github.com/Hsujo/carrier-ims-for-pixel/issues/new"
private val VERSION_DISPLAY_WITH_REV_REGEX = Regex("""\d+\.\d+\.\d+\.[rd]\d+""")
private val VERSION_DISPLAY_REGEX = Regex("""\d+\.\d+\.\d+""")

private enum class CaptivePortalAction {
    FIX,
    RESTORE,
    NONE,
}

private enum class MainTab(
    val labelRes: Int,
) {
    IMS(R.string.tab_ims),
    EXTRA(R.string.tab_extra),
    ABOUT(R.string.tab_about),
}

private val fiveGFeatureSet = setOf(
    Feature.FIVE_G_NR,
    Feature.FIVE_G_THRESHOLDS,
    Feature.FIVE_G_PLUS_ICON,
    Feature.VONR,
)

private val fourGFeatureSet = setOf(
    Feature.VOLTE,
    Feature.SHOW_4G_FOR_LTE,
)

private fun switchFeatureCategoryOrder(feature: Feature): Int {
    return when {
        fiveGFeatureSet.contains(feature) -> 0
        fourGFeatureSet.contains(feature) -> 1
        else -> 2
    }
}

private fun isChinaDomesticSim(sim: SimSelection?): Boolean {
    if (sim == null || sim.subId < 0) return false
    val iccId = sim.iccId.trim()
    if (iccId.startsWith("8986")) return true
    val mcc = sim.mcc.filter { it.isDigit() }.take(3)
    return mcc == "460"
}

private fun displayCountryIso(sim: SimSelection): String {
    val rawIso = sim.countryIso.trim()
    if (rawIso.length == 2 && rawIso.all { it.isLetter() }) {
        return rawIso.lowercase(Locale.US)
    }
    val mcc = sim.mcc.filter { it.isDigit() }.take(3)
    val mccInt = mcc.toIntOrNull()
    val derivedIso = when {
        mcc == "460" -> "cn"
        mcc == "454" -> "hk"
        mcc == "466" -> "tw"
        mccInt != null && mccInt in 310..316 -> "us"
        mccInt != null && mccInt in 440..441 -> "jp"
        mccInt != null && mccInt in 234..235 -> "gb"
        mcc == "450" -> "kr"
        mcc == "525" -> "sg"
        else -> null
    }
    return derivedIso ?: rawIso.ifBlank { "-" }
}

private fun toDisplayVersion(rawVersion: String?): String {
    val text = rawVersion?.trim().orEmpty()
    if (text.isBlank()) return ""
    val normalized = text.removePrefix("v")
    return VERSION_DISPLAY_WITH_REV_REGEX.find(normalized)?.value
        ?: VERSION_DISPLAY_REGEX.find(normalized)?.value
        ?: normalized
}

private fun defaultFeatureValue(feature: Feature): FeatureValue {
    return FeatureValue(feature.defaultValue, feature.valueType)
}

private fun buildCompleteFeatureMap(source: Map<Feature, FeatureValue>): LinkedHashMap<Feature, FeatureValue> {
    val completed = linkedMapOf<Feature, FeatureValue>()
    Feature.entries.forEach { feature ->
        completed[feature] = source[feature] ?: defaultFeatureValue(feature)
    }
    return completed
}

private fun openApnSettings(context: Context, selectedSim: SimSelection?) {
    val subId = selectedSim?.subId ?: -1
    if (subId < 0) {
        Toast.makeText(context, R.string.select_single_sim, Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Settings.ACTION_APN_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(Settings.EXTRA_SUB_ID, subId)
        .putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId)
        .putExtra("sub_id", subId)
        .putExtra("subscription", subId)
    if (intent.resolveActivity(context.packageManager) == null) {
        Toast.makeText(context, R.string.apn_settings_open_failed, Toast.LENGTH_LONG).show()
        return
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, R.string.apn_settings_open_failed, Toast.LENGTH_LONG).show()
    }
}

private fun requestAddQuickSettingsTile(
    context: Context,
    tileClass: Class<*>,
    label: String,
) {
    val statusBarManager = context.getSystemService(StatusBarManager::class.java)
    if (statusBarManager == null) {
        Toast.makeText(context, R.string.qs_tile_add_failed, Toast.LENGTH_LONG).show()
        return
    }
    runCatching {
        statusBarManager.requestAddTileService(
            ComponentName(context, tileClass),
            label,
            AndroidIcon.createWithResource(context, R.mipmap.ic_launcher),
            context.mainExecutor,
        ) { resultCode ->
            val messageRes = when (resultCode) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.qs_tile_added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.qs_tile_already_added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> R.string.qs_tile_not_added
                else -> R.string.qs_tile_add_failed
            }
            Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
        }
    }.onFailure {
        Toast.makeText(context, R.string.qs_tile_add_failed, Toast.LENGTH_LONG).show()
    }
}

private fun syncFeatureState(
    target: MutableMap<Feature, FeatureValue>,
    source: Map<Feature, FeatureValue>,
) {
    target.clear()
    target.putAll(buildCompleteFeatureMap(source))
}

private fun buildIssueBody(
    context: Context,
    systemInfo: SystemInfo,
    shizukuStatus: ShizukuStatus,
    issueFailureLogs: String,
): String {
    val shizukuStatusText = when (shizukuStatus) {
        ShizukuStatus.CHECKING -> context.getString(R.string.shizuku_checking)
        ShizukuStatus.NOT_RUNNING -> context.getString(R.string.shizuku_not_running)
        ShizukuStatus.NO_PERMISSION -> context.getString(R.string.shizuku_no_permission)
        ShizukuStatus.READY -> context.getString(R.string.shizuku_ready)
        else -> "UNKNOWN"
    }
    return buildString {
        appendLine("App Version: ${systemInfo.appVersionName}")
        appendLine("Device Model: ${systemInfo.deviceModel}")
        appendLine("Android Version: ${systemInfo.androidVersion}")
        appendLine("Patch Date: ${systemInfo.securityPatchVersion}")
        appendLine("Shizuku Status: $shizukuStatusText")
        if (issueFailureLogs.isNotBlank()) {
            appendLine()
            appendLine("Switch Failure Logs:")
            append(issueFailureLogs)
        }
    }.trim()
}

private fun dumpValueText(value: Any?): String {
    return when (value) {
        null -> "null"
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is LongArray -> value.joinToString(prefix = "[", postfix = "]")
        is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
        is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]")
        else -> value.toString()
    }
}

private fun buildEditableConfigSnapshotText(
    selectedSim: SimSelection,
    featureMap: Map<Feature, FeatureValue>,
    resolvedCountryIsoForApply: String?,
    bundleForApply: Bundle,
    captivePortalState: MainViewModel.CaptivePortalFixState?,
): String {
    val sortedBundleKeys = bundleForApply.keySet().sorted()
    return buildString {
        appendLine("[selected_sim]")
        appendLine("sim.show_title=${selectedSim.showTitle}")
        appendLine("sim.sub_id=${selectedSim.subId}")
        appendLine("sim.slot_index=${selectedSim.simSlotIndex}")
        appendLine("sim.current_mcc=${selectedSim.mcc}")
        appendLine("sim.current_mnc=${selectedSim.mnc}")
        appendLine("sim.current_iso=${selectedSim.countryIso}")
        appendLine()

        appendLine("[feature_inputs]")
        Feature.entries.forEach { feature ->
            val value = featureMap[feature]?.data ?: feature.defaultValue
            appendLine("feature.${feature.name.lowercase(Locale.US)}=${dumpValueText(value)}")
        }
        appendLine("apply.country_iso_resolved=${resolvedCountryIsoForApply ?: ""}")
        appendLine()

        appendLine("[carrier_config_bundle_for_apply]")
        if (sortedBundleKeys.isEmpty()) {
            appendLine("(empty)")
        } else {
            sortedBundleKeys.forEach { key ->
                appendLine("$key=${dumpValueText(bundleForApply.get(key))}")
            }
        }
        appendLine()

        appendLine("[network_verification]")
        if (captivePortalState == null) {
            appendLine("captive_portal.mode=unknown")
            appendLine("captive_portal.http_url=")
            appendLine("captive_portal.https_url=")
        } else {
            appendLine("captive_portal.mode=${captivePortalState.mode}")
            appendLine("captive_portal.http_url=${captivePortalState.httpUrl}")
            appendLine("captive_portal.https_url=${captivePortalState.httpsUrl}")
        }
    }.trim()
}

class MainActivity : BaseActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Composable
    override fun Content() {
        val context = LocalContext.current

        val systemInfo by viewModel.systemInfo.collectAsStateWithLifecycle()
        val shizukuStatus by viewModel.shizukuStatus.collectAsStateWithLifecycle()
        val allSimList by viewModel.allSimList.collectAsStateWithLifecycle()
        val issueFailureLogs by viewModel.issueFailureLogs.collectAsStateWithLifecycle()
        val uriHandler = LocalUriHandler.current
        val clipboardManager = context.getSystemService(ClipboardManager::class.java)

        val scope = rememberCoroutineScope()
        var selectedTab by remember { mutableStateOf(MainTab.IMS) }
        var selectedSim by remember { mutableStateOf<SimSelection?>(null) }
        var showShizukuUpdateDialog by remember { mutableStateOf(false) }
        var pendingAutoSelectSimAfterReady by remember { mutableStateOf(false) }
        val imsRegistrationStatusMap = remember { mutableStateMapOf<Int, Boolean?>() }
        val imsRegistrationLoadingMap = remember { mutableStateMapOf<Int, Boolean>() }
        var applyingConfiguration by remember { mutableStateOf(false) }
        var fixingCaptivePortal by remember { mutableStateOf(false) }
        var checkingCaptivePortalStatus by remember { mutableStateOf(false) }
        var captivePortalFixState by remember { mutableStateOf<MainViewModel.CaptivePortalFixState?>(null) }
        var showDiagnosticsDialog by remember { mutableStateOf(false) }
        var diagnosticsRunning by remember { mutableStateOf(false) }
        var diagnosticsJob by remember { mutableStateOf<Job?>(null) }
        var networkExitChecking by remember { mutableStateOf(false) }
        var networkExitStatus by remember { mutableStateOf<NetworkExitStatus?>(null) }
        var networkExitError by remember { mutableStateOf<String?>(null) }
        var apnDraft by remember { mutableStateOf<ApnDraftConfig?>(null) }
        var apnDraftSim by remember { mutableStateOf<SimSelection?>(null) }
        var applyingApn by remember { mutableStateOf(false) }
        var configBackups by remember { mutableStateOf<List<ConfigBackupSnapshot>>(emptyList()) }
        var pendingBackupRestore by remember { mutableStateOf<ConfigBackupSnapshot?>(null) }
        var pendingBackupRestoreSim by remember { mutableStateOf<SimSelection?>(null) }
        val diagnosticsLines = remember { mutableStateListOf<String>() }
        val featureSwitches = remember { mutableStateMapOf<Feature, FeatureValue>() }
        val committedFeatureSwitches = remember { mutableStateMapOf<Feature, FeatureValue>() }
        val submitIssueAction: () -> Unit = {
            val issueBody = buildIssueBody(
                context = context,
                systemInfo = systemInfo,
                shizukuStatus = shizukuStatus,
                issueFailureLogs = issueFailureLogs
            )
            clipboardManager?.setPrimaryClip(
                ClipData.newPlainText(
                    "turboims_issue_info",
                    issueBody
                )
            )
            Toast.makeText(context, R.string.issue_info_copied, Toast.LENGTH_SHORT).show()
            uriHandler.openUri(REPO_ISSUE_URL)
        }

        LaunchedEffect(shizukuStatus) {
            if (shizukuStatus == ShizukuStatus.NEED_UPDATE) {
                showShizukuUpdateDialog = true
            }
            pendingAutoSelectSimAfterReady = shizukuStatus == ShizukuStatus.READY
            if (shizukuStatus == ShizukuStatus.READY) {
                checkingCaptivePortalStatus = true
                captivePortalFixState = viewModel.queryCaptivePortalFixState()
                checkingCaptivePortalStatus = false
            } else {
                checkingCaptivePortalStatus = false
                captivePortalFixState = null
            }
        }
        LaunchedEffect(Unit) {
            configBackups = viewModel.loadConfigBackups()
        }
        LaunchedEffect(allSimList) {
            val validSubIds = allSimList.filter { it.subId >= 0 }.map { it.subId }.toSet()
            imsRegistrationStatusMap.keys.toList()
                .filterNot { validSubIds.contains(it) }
                .forEach { imsRegistrationStatusMap.remove(it) }
            imsRegistrationLoadingMap.keys.toList()
                .filterNot { validSubIds.contains(it) }
                .forEach { imsRegistrationLoadingMap.remove(it) }
            val currentSelected = selectedSim
            if (currentSelected == null) {
                selectedSim = allSimList.firstOrNull()
                return@LaunchedEffect
            }
            val stillExists = allSimList.any {
                it.subId == currentSelected.subId && it.simSlotIndex == currentSelected.simSlotIndex
            }
            if (!stillExists) {
                selectedSim = allSimList.firstOrNull()
            }
        }
        LaunchedEffect(allSimList, pendingAutoSelectSimAfterReady) {
            if (!pendingAutoSelectSimAfterReady) return@LaunchedEffect
            val firstSingleSim = allSimList.firstOrNull { it.subId >= 0 } ?: return@LaunchedEffect
            if (selectedSim == null || selectedSim?.subId == -1) {
                selectedSim = firstSingleSim
            }
            pendingAutoSelectSimAfterReady = false
        }
        LaunchedEffect(selectedSim, shizukuStatus, allSimList) {
            val currentSelected = selectedSim ?: return@LaunchedEffect
            committedFeatureSwitches.clear()
            val currentConfig = if (shizukuStatus == ShizukuStatus.READY && currentSelected.subId >= 0) {
                viewModel.loadCurrentConfiguration(currentSelected.subId)
            } else {
                null
            }
            if (currentConfig != null) {
                committedFeatureSwitches.putAll(currentConfig)
            } else {
                val savedConfig = viewModel.loadConfiguration(currentSelected.subId)
                if (savedConfig != null) {
                    committedFeatureSwitches.putAll(savedConfig)
                } else {
                    committedFeatureSwitches.putAll(viewModel.loadDefaultPreferences())
                }
            }
            syncFeatureState(featureSwitches, committedFeatureSwitches)
            if (currentSelected.subId >= 0) {
                imsRegistrationStatusMap[currentSelected.subId] =
                    if (shizukuStatus == ShizukuStatus.READY) {
                        viewModel.readImsRegistrationStatus(currentSelected.subId)
                    } else {
                        null
                    }
            } else {
                allSimList.filter { it.subId >= 0 }.forEach { sim ->
                    imsRegistrationStatusMap[sim.subId] =
                        if (shizukuStatus == ShizukuStatus.READY) {
                            viewModel.readImsRegistrationStatus(sim.subId)
                        } else {
                            null
                        }
                }
            }
        }

        val handleFeatureSwitchChange: (SimSelection?, Feature, FeatureValue) -> Unit =
            handleFeatureSwitchChange@{ targetSim, feature, value ->
            when (feature.valueType) {
                FeatureValueType.STRING -> {
                    featureSwitches[feature] = value
                }

                FeatureValueType.BOOLEAN -> {
                    val sim = targetSim
                    val previousUiValue = featureSwitches[feature] ?: defaultFeatureValue(feature)
                    val previousCommittedValue =
                        committedFeatureSwitches[feature] ?: defaultFeatureValue(feature)
                    featureSwitches[feature] = value
                    committedFeatureSwitches[feature] = value
                    if (applyingConfiguration) {
                        featureSwitches[feature] = previousUiValue
                        committedFeatureSwitches[feature] = previousCommittedValue
                        return@handleFeatureSwitchChange
                    }
                    if (sim == null || shizukuStatus != ShizukuStatus.READY) {
                        featureSwitches[feature] = previousUiValue
                        committedFeatureSwitches[feature] = previousCommittedValue
                        Toast.makeText(
                            context,
                            R.string.shizuku_not_running_msg,
                            Toast.LENGTH_LONG
                        ).show()
                        return@handleFeatureSwitchChange
                    }
                    scope.launch {
                        applyingConfiguration = true
                        try {
                            val resultMsg = viewModel.onApplyConfiguration(
                                sim,
                                buildCompleteFeatureMap(committedFeatureSwitches),
                            )
                            if (resultMsg != null) {
                                if ((value.data as? Boolean) == true) {
                                    viewModel.appendSwitchFailureLog(
                                        action = feature.name,
                                        subId = sim.subId.takeIf { it >= 0 },
                                        stage = "apply_switch_enable",
                                        backendMessage = resultMsg
                                    )
                                }
                                featureSwitches[feature] = previousUiValue
                                committedFeatureSwitches[feature] = previousCommittedValue
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.config_failed, resultMsg),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } finally {
                            applyingConfiguration = false
                        }
                    }
                }
            }
        }

        val refreshSimListAction: () -> Unit = {
            scope.launch {
                val hasValidSim = viewModel.refreshSimListNow()
                val messageRes = if (hasValidSim) {
                    R.string.sim_list_refresh
                } else {
                    R.string.sim_list_refresh_failed_restart_shizuku
                }
                val duration = if (hasValidSim) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                Toast.makeText(context, messageRes, duration).show()
            }
        }

        val fixCaptivePortalAction = fixCaptivePortal@{
            if (fixingCaptivePortal) return@fixCaptivePortal
            if (shizukuStatus != ShizukuStatus.READY) {
                Toast.makeText(context, R.string.shizuku_not_running_msg, Toast.LENGTH_LONG).show()
                return@fixCaptivePortal
            }
            scope.launch {
                val action = when (captivePortalFixState?.mode) {
                    MainViewModel.CaptivePortalFixMode.CAN_RESTORE -> CaptivePortalAction.RESTORE
                    MainViewModel.CaptivePortalFixMode.NORMAL -> CaptivePortalAction.NONE
                    else -> CaptivePortalAction.FIX
                }
                if (action == CaptivePortalAction.NONE) return@launch
                fixingCaptivePortal = true
                val resultMsg = when (action) {
                    CaptivePortalAction.FIX -> viewModel.applyCaptivePortalCnUrls()
                    CaptivePortalAction.RESTORE -> viewModel.restoreCaptivePortalDefaultUrls()
                    CaptivePortalAction.NONE -> null
                }
                fixingCaptivePortal = false
                if (resultMsg == null) {
                    Toast.makeText(
                        context,
                        if (action == CaptivePortalAction.RESTORE) {
                            R.string.captive_portal_restore_success
                        } else {
                            R.string.captive_portal_fix_success
                        },
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        context.getString(
                            if (action == CaptivePortalAction.RESTORE) {
                                R.string.captive_portal_restore_failed
                            } else {
                                R.string.captive_portal_fix_failed
                            },
                            resultMsg
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                checkingCaptivePortalStatus = true
                captivePortalFixState = viewModel.queryCaptivePortalFixState()
                checkingCaptivePortalStatus = false
            }
        }

        val restoreBackupAction = restoreBackup@{ sim: SimSelection?, backup: ConfigBackupSnapshot, allowMismatch: Boolean ->
            if (sim == null || sim.subId < 0) {
                Toast.makeText(context, R.string.select_single_sim, Toast.LENGTH_SHORT).show()
                return@restoreBackup
            }
            if (shizukuStatus != ShizukuStatus.READY) {
                Toast.makeText(context, R.string.shizuku_not_running_msg, Toast.LENGTH_LONG).show()
                return@restoreBackup
            }
            if (!allowMismatch &&
                ToolRules.requiresBackupMismatchConfirmation(backup, sim.mcc, sim.mnc)
            ) {
                pendingBackupRestore = backup
                pendingBackupRestoreSim = sim
                return@restoreBackup
            }
            scope.launch {
                applyingConfiguration = true
                try {
                    val resultMsg = viewModel.onApplyConfiguration(
                        sim,
                        buildCompleteFeatureMap(backup.featureValues),
                    )
                    if (resultMsg == null) {
                        syncFeatureState(committedFeatureSwitches, backup.featureValues)
                        syncFeatureState(featureSwitches, backup.featureValues)
                        Toast.makeText(context, R.string.config_backup_restored, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.config_failed, resultMsg),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } finally {
                    applyingConfiguration = false
                }
            }
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize(),
            contentWindowInsets = WindowInsets(0.dp),
            bottomBar = {
                NavigationBar {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = {
                                Text(
                                    text = when (tab) {
                                        MainTab.IMS -> "IMS"
                                        MainTab.EXTRA -> "+"
                                        MainTab.ABOUT -> "i"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            label = { Text(stringResource(tab.labelRes), fontSize = 11.sp) }
                        )
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
            ) {
                if (selectedTab == MainTab.IMS) {
                    HomeStatusCard(
                        shizukuStatus = shizukuStatus,
                        onRefresh = {
                            viewModel.updateShizukuStatus()
                            if (shizukuStatus == ShizukuStatus.READY) {
                                scope.launch {
                                    val hasValidSim = viewModel.refreshSimListNow()
                                    val messageRes = if (hasValidSim) {
                                        R.string.sim_list_refresh
                                    } else {
                                        R.string.sim_list_refresh_failed_restart_shizuku
                                    }
                                    val duration = if (hasValidSim) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                                    Toast.makeText(context, messageRes, duration).show()
                                }
                            }
                        },
                        onRequestShizukuPermission = {
                            viewModel.requestShizukuPermission(0)
                        },
                    )
                }
                if (selectedTab == MainTab.ABOUT) {
                    SystemInfoCard(
                        systemInfo,
                        shizukuStatus,
                        onRefresh = {
                            viewModel.updateShizukuStatus()
                            if (shizukuStatus == ShizukuStatus.READY) {
                                scope.launch {
                                    val hasValidSim = viewModel.refreshSimListNow()
                                    val messageRes = if (hasValidSim) {
                                        R.string.sim_list_refresh
                                    } else {
                                        R.string.sim_list_refresh_failed_restart_shizuku
                                    }
                                    val duration = if (hasValidSim) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                                    Toast.makeText(context, messageRes, duration).show()
                                }
                            }
                        },
                        onRequestShizukuPermission = {
                            viewModel.requestShizukuPermission(0)
                        },
                        onLogcatClick = {
                            startActivity(
                                Intent(
                                    this@MainActivity,
                                    LogcatActivity::class.java
                                )
                            )
                        },
                        onIssueClick = submitIssueAction,
                    )
                }
                if (selectedTab == MainTab.IMS && shizukuStatus == ShizukuStatus.READY) {
                    SimCardSelectionCard(selectedSim, allSimList, onSelectSim = {
                        selectedSim = it
                    }, onRefreshSimList = refreshSimListAction)
                    FeaturesCard(
                        isSelectAllSim = selectedSim?.subId == -1,
                        allSimList = allSimList,
                        selectedSim = selectedSim,
                        imsRegistrationStatusBySubId = imsRegistrationStatusMap,
                        imsRegistrationLoadingBySubId = imsRegistrationLoadingMap,
                        featureSwitchesEnabled = !applyingConfiguration,
                        onImsRegistrationToggle = { subId, targetChecked ->
                            if (!targetChecked) return@FeaturesCard
                            if (applyingConfiguration) return@FeaturesCard
                            if (shizukuStatus != ShizukuStatus.READY) {
                                Toast.makeText(context, R.string.shizuku_not_running_msg, Toast.LENGTH_LONG).show()
                                return@FeaturesCard
                            }
                            val sim = allSimList.firstOrNull { it.subId == subId }
                            if (sim == null) {
                                Toast.makeText(context, R.string.select_single_sim, Toast.LENGTH_SHORT).show()
                                return@FeaturesCard
                            }
                            scope.launch {
                                applyingConfiguration = true
                                imsRegistrationLoadingMap[subId] = true
                                val oldVolteUi =
                                    featureSwitches[Feature.VOLTE] ?: defaultFeatureValue(Feature.VOLTE)
                                val oldVowifiUi =
                                    featureSwitches[Feature.VOWIFI] ?: defaultFeatureValue(Feature.VOWIFI)
                                val oldVolteCommitted =
                                    committedFeatureSwitches[Feature.VOLTE] ?: defaultFeatureValue(Feature.VOLTE)
                                val oldVowifiCommitted =
                                    committedFeatureSwitches[Feature.VOWIFI] ?: defaultFeatureValue(Feature.VOWIFI)
                                try {
                                    val enabledValue = FeatureValue(true, FeatureValueType.BOOLEAN)
                                    featureSwitches[Feature.VOLTE] = enabledValue
                                    featureSwitches[Feature.VOWIFI] = enabledValue
                                    committedFeatureSwitches[Feature.VOLTE] = enabledValue
                                    committedFeatureSwitches[Feature.VOWIFI] = enabledValue
                                    Toast.makeText(
                                        context,
                                        R.string.ims_register_apply_then_register,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                        val applyResultMsg = viewModel.onApplyConfiguration(
                                            sim,
                                            buildCompleteFeatureMap(committedFeatureSwitches),
                                        )
                                    if (applyResultMsg != null) {
                                        viewModel.appendSwitchFailureLog(
                                            action = "IMS_REGISTER",
                                            subId = sim.subId,
                                            stage = "apply_before_register",
                                            backendMessage = applyResultMsg
                                        )
                                        featureSwitches[Feature.VOLTE] = oldVolteUi
                                        featureSwitches[Feature.VOWIFI] = oldVowifiUi
                                        committedFeatureSwitches[Feature.VOLTE] = oldVolteCommitted
                                        committedFeatureSwitches[Feature.VOWIFI] = oldVowifiCommitted
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.ims_register_apply_failed, applyResultMsg),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@launch
                                    }
                                    val registerResult = viewModel.registerIms(sim.subId)
                                    imsRegistrationStatusMap[subId] = registerResult.registered
                                    if (registerResult.backendErrorMessage != null) {
                                        viewModel.appendSwitchFailureLog(
                                            action = "IMS_REGISTER",
                                            subId = sim.subId,
                                            stage = "restart_ims",
                                            backendMessage = registerResult.backendErrorMessage
                                        )
                                    }
                                } finally {
                                    imsRegistrationLoadingMap[subId] = false
                                    applyingConfiguration = false
                                }
                            }
                        },
                        featureSwitches,
                        onFeatureSwitchChange = { feature, value ->
                            handleFeatureSwitchChange(selectedSim, feature, value)
                        },
                        showTikTokFix = false,
                        resetFeatures = {
                            val sim = selectedSim
                            if (sim == null || sim.subId < 0) {
                                Toast.makeText(context, R.string.select_single_sim, Toast.LENGTH_SHORT).show()
                                return@FeaturesCard
                            }
                            if (shizukuStatus != ShizukuStatus.READY) {
                                Toast.makeText(context, R.string.shizuku_not_running_msg, Toast.LENGTH_LONG).show()
                                return@FeaturesCard
                            }
                            if (applyingConfiguration) {
                                return@FeaturesCard
                            }
                            scope.launch {
                                applyingConfiguration = true
                                try {
                                    val success = viewModel.onResetConfiguration(sim)
                                    if (!success) return@launch
                                    val currentConfig = viewModel.loadCurrentConfiguration(sim.subId)
                                    if (currentConfig != null) {
                                        syncFeatureState(committedFeatureSwitches, currentConfig)
                                        syncFeatureState(featureSwitches, committedFeatureSwitches)
                                        imsRegistrationStatusMap[sim.subId] =
                                            viewModel.readImsRegistrationStatus(sim.subId)
                                    } else {
                                        syncFeatureState(committedFeatureSwitches, viewModel.loadDefaultPreferences())
                                        syncFeatureState(featureSwitches, committedFeatureSwitches)
                                    }
                                } finally {
                                    applyingConfiguration = false
                                }
                            }
                        },
                        onDumpConfig = {
                            val sim = selectedSim
                            if (sim == null || sim.subId < 0) {
                                Toast.makeText(context, R.string.select_single_sim, Toast.LENGTH_SHORT).show()
                                return@FeaturesCard
                            }
                            scope.launch {
                                val mapToDump = buildCompleteFeatureMap(featureSwitches)
                                val resolvedCountryIso = viewModel.resolveCountryIsoOverridePreview(sim, mapToDump)
                                val bundleForApply = ImsModifier.buildBundle(
                                    carrierName = null,
                                    countryISO = resolvedCountryIso,
                                    enableVoLTE = (mapToDump[Feature.VOLTE]?.data ?: true) as Boolean,
                                    enableVoWiFi = (mapToDump[Feature.VOWIFI]?.data ?: true) as Boolean,
                                    enableVT = (mapToDump[Feature.VT]?.data ?: true) as Boolean,
                                    enableVoNR = (mapToDump[Feature.VONR]?.data ?: true) as Boolean,
                                    enableCrossSIM = (mapToDump[Feature.CROSS_SIM]?.data ?: true) as Boolean,
                                    enableUT = (mapToDump[Feature.UT]?.data ?: true) as Boolean,
                                    enable5GNR = (mapToDump[Feature.FIVE_G_NR]?.data ?: true) as Boolean,
                                    enable5GThreshold = (mapToDump[Feature.FIVE_G_THRESHOLDS]?.data ?: true) as Boolean,
                                    enable5GPlusIcon = (mapToDump[Feature.FIVE_G_PLUS_ICON]?.data ?: true) as Boolean,
                                    enableShow4GForLTE = (mapToDump[Feature.SHOW_4G_FOR_LTE]?.data ?: false) as Boolean,
                                )
                                val snapshotText = buildEditableConfigSnapshotText(
                                    selectedSim = sim,
                                    featureMap = mapToDump,
                                    resolvedCountryIsoForApply = resolvedCountryIso,
                                    bundleForApply = bundleForApply,
                                    captivePortalState = captivePortalFixState ?: viewModel.queryCaptivePortalFixState(),
                                )
                                startActivity(
                                    Intent(
                                        this@MainActivity,
                                        DumpActivity::class.java
                                    )
                                        .putExtra(DumpActivity.EXTRA_SUB_ID, sim.subId)
                                        .putExtra(DumpActivity.EXTRA_PRESET_TEXT, snapshotText)
                                )
                            }
                        },
                        onRunDiagnostics = {
                            val sim = selectedSim
                            if (sim == null || sim.subId < 0) {
                                Toast.makeText(context, R.string.select_single_sim, Toast.LENGTH_SHORT).show()
                                return@FeaturesCard
                            }
                            if (shizukuStatus != ShizukuStatus.READY) {
                                Toast.makeText(context, R.string.shizuku_not_running_msg, Toast.LENGTH_LONG).show()
                                return@FeaturesCard
                            }
                            diagnosticsJob?.cancel()
                            diagnosticsLines.clear()
                            showDiagnosticsDialog = true
                            diagnosticsRunning = true
                            diagnosticsJob = scope.launch {
                                try {
                                    val appMapSnapshot = buildCompleteFeatureMap(featureSwitches)
                                    viewModel.runShizukuDiagnostics(
                                        selectedSim = sim,
                                        visibleSimList = allSimList,
                                        appFeatureMap = appMapSnapshot
                                    ).collect { line ->
                                        diagnosticsLines.add(line)
                                    }
                                } catch (t: Throwable) {
                                    diagnosticsLines.add("❌ 诊断异常：${t.javaClass.simpleName} (${t.message ?: "unknown"})")
                                    Toast.makeText(
                                        context,
                                        R.string.diagnostics_failed,
                                        Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    diagnosticsRunning = false
                                }
                            }
                        },
                    )
                }
                if (selectedTab == MainTab.EXTRA) {
                    val extraSimList = allSimList.filter { it.subId >= 0 }
                    val extraSelectedSim = selectedSim?.takeIf { it.subId >= 0 } ?: extraSimList.firstOrNull()
                    ExtraToolsPage(
                        shizukuStatus = shizukuStatus,
                        selectedSim = extraSelectedSim,
                        allSimList = extraSimList,
                        tiktokEnabled = (featureSwitches[Feature.TIKTOK_NETWORK_FIX]?.data as? Boolean) == true,
                        featureSwitchesEnabled = !applyingConfiguration,
                        checkingCaptivePortalStatus = checkingCaptivePortalStatus,
                        fixingCaptivePortal = fixingCaptivePortal,
                        captivePortalFixState = captivePortalFixState,
                        networkExitChecking = networkExitChecking,
                        networkExitStatus = networkExitStatus,
                        networkExitError = networkExitError,
                        configBackups = configBackups,
                        onSelectSim = { selectedSim = it },
                        onRefreshSimList = refreshSimListAction,
                        onFixCaptivePortal = fixCaptivePortalAction,
                        onTikTokFixChange = { enabled ->
                            handleFeatureSwitchChange(
                                extraSelectedSim,
                                Feature.TIKTOK_NETWORK_FIX,
                                FeatureValue(enabled, FeatureValueType.BOOLEAN)
                            )
                        },
                        onCheckNetworkExit = {
                            if (networkExitChecking) return@ExtraToolsPage
                            scope.launch {
                                networkExitChecking = true
                                networkExitError = null
                                val result = viewModel.checkNetworkExit()
                                networkExitStatus = result.getOrNull()
                                networkExitError = result.exceptionOrNull()?.message
                                networkExitChecking = false
                            }
                        },
                        onOpenApnSettings = {
                            openApnSettings(context, extraSelectedSim)
                        },
                        onPrepareApn = {
                            val sim = extraSelectedSim
                            if (sim == null || sim.subId < 0) {
                                Toast.makeText(context, R.string.select_single_sim, Toast.LENGTH_SHORT).show()
                                return@ExtraToolsPage
                            }
                            val draft = viewModel.buildSuggestedApnConfig(sim)
                            val validation = ToolRules.validateApnDraft(draft)
                            if (validation != null) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.apn_draft_invalid, validation),
                                    Toast.LENGTH_LONG
                                ).show()
                                return@ExtraToolsPage
                            }
                            apnDraftSim = sim
                            apnDraft = draft
                        },
                        onAddQuickTile = { tileClass, labelRes ->
                            requestAddQuickSettingsTile(
                                context = context,
                                tileClass = tileClass,
                                label = context.getString(labelRes),
                            )
                        },
                        onBackupConfig = {
                            val sim = extraSelectedSim
                            if (sim == null || sim.subId < 0) {
                                Toast.makeText(context, R.string.select_single_sim, Toast.LENGTH_SHORT).show()
                                return@ExtraToolsPage
                            }
                            viewModel.saveConfigBackup(
                                selectedSim = sim,
                                featureMap = buildCompleteFeatureMap(featureSwitches),
                                name = sim.showTitle,
                            )
                            configBackups = viewModel.loadConfigBackups()
                            Toast.makeText(context, R.string.config_backup_saved, Toast.LENGTH_SHORT).show()
                        },
                        onRestoreBackup = { backup -> restoreBackupAction(extraSelectedSim, backup, false) },
                        onDeleteBackup = { backup ->
                            viewModel.deleteConfigBackup(backup.id)
                            configBackups = viewModel.loadConfigBackups()
                        },
                    )
                }
                if (selectedTab == MainTab.ABOUT && issueFailureLogs.isNotBlank()) {
                    IssueReportHintCard(
                        onSubmitIssue = submitIssueAction
                    )
                }
                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))

                apnDraft?.let { draft ->
                    ApnConfirmDialog(
                        draft = draft,
                        applying = applyingApn,
                        onDismiss = {
                            if (!applyingApn) {
                                apnDraft = null
                                apnDraftSim = null
                            }
                        },
                        onConfirm = {
                            val sim = apnDraftSim
                            if (sim == null || sim.subId < 0) {
                                Toast.makeText(context, R.string.select_single_sim, Toast.LENGTH_SHORT).show()
                                apnDraft = null
                                apnDraftSim = null
                                return@ApnConfirmDialog
                            }
                            if (shizukuStatus != ShizukuStatus.READY) {
                                Toast.makeText(context, R.string.shizuku_not_running_msg, Toast.LENGTH_LONG).show()
                                return@ApnConfirmDialog
                            }
                            scope.launch {
                                applyingApn = true
                                val resultMsg = viewModel.applyApnConfig(sim, draft)
                                applyingApn = false
                                apnDraft = null
                                apnDraftSim = null
                                Toast.makeText(
                                    context,
                                    if (resultMsg == null) {
                                        context.getString(R.string.apn_apply_success)
                                    } else {
                                        context.getString(R.string.apn_apply_failed, resultMsg)
                                    },
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
                }
                pendingBackupRestore?.let { backup ->
                    val restoreSim = pendingBackupRestoreSim
                    AlertDialog(
                        onDismissRequest = {
                            pendingBackupRestore = null
                            pendingBackupRestoreSim = null
                        },
                        title = { Text(stringResource(R.string.config_backup_mismatch_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.config_backup_mismatch_message,
                                    "${backup.mcc.ifBlank { "-" }}/${backup.mnc.ifBlank { "-" }}",
                                    "${restoreSim?.mcc.orEmpty().ifBlank { "-" }}/${restoreSim?.mnc.orEmpty().ifBlank { "-" }}"
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pendingBackupRestore = null
                                    pendingBackupRestoreSim = null
                                    restoreBackupAction(restoreSim, backup, true)
                                }
                            ) {
                                Text(stringResource(R.string.config_backup_restore_anyway))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                pendingBackupRestore = null
                                pendingBackupRestoreSim = null
                            }) {
                                Text(stringResource(id = android.R.string.cancel))
                            }
                        }
                    )
                }
                if (showShizukuUpdateDialog) {
                    ShizukuUpdateDialog {
                        showShizukuUpdateDialog = false
                    }
                }
                if (showDiagnosticsDialog) {
                    AlertDialog(
                        modifier = Modifier.fillMaxWidth(0.96f),
                        properties = DialogProperties(usePlatformDefaultWidth = false),
                        onDismissRequest = {
                            diagnosticsJob?.cancel()
                            diagnosticsRunning = false
                            showDiagnosticsDialog = false
                        },
                        title = {
                            Text(stringResource(R.string.diagnostics_menu))
                        },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(
                                        if (diagnosticsRunning) {
                                            R.string.diagnostics_running
                                        } else {
                                            R.string.diagnostics_finished
                                        }
                                    ),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                if (diagnosticsRunning) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                val logText = diagnosticsLines.joinToString("\n")
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 220.dp, max = 480.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                        .padding(10.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = logText.ifBlank { stringResource(R.string.diagnostics_empty) },
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = diagnosticsLines.isNotEmpty(),
                                onClick = {
                                    val content = diagnosticsLines.joinToString("\n")
                                    clipboardManager?.setPrimaryClip(
                                        ClipData.newPlainText("carrier_ims_diagnostics", content)
                                    )
                                    Toast.makeText(context, R.string.dump_copy_success, Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text(stringResource(R.string.dump_copy))
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    diagnosticsJob?.cancel()
                                    diagnosticsRunning = false
                                    showDiagnosticsDialog = false
                                }
                            ) {
                                Text(stringResource(id = android.R.string.cancel))
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateShizukuStatus()
    }
}

@Composable
private fun HomeStatusCard(
    shizukuStatus: ShizukuStatus,
    onRefresh: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
) {
    val shizukuStatusText = when (shizukuStatus) {
        ShizukuStatus.CHECKING -> stringResource(R.string.shizuku_checking)
        ShizukuStatus.NOT_RUNNING -> stringResource(R.string.shizuku_not_running)
        ShizukuStatus.NO_PERMISSION -> stringResource(R.string.shizuku_no_permission)
        ShizukuStatus.READY -> stringResource(R.string.shizuku_ready)
        ShizukuStatus.NEED_UPDATE -> stringResource(R.string.update_shizuku)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.shizuku_status, shizukuStatusText),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                FeatureActionChip(
                    icon = Icons.Rounded.Cached,
                    label = stringResource(R.string.refresh_permission),
                    onClick = onRefresh,
                )
            }
            if (shizukuStatus == ShizukuStatus.NO_PERMISSION) {
                Button(onClick = onRequestShizukuPermission) {
                    Text(stringResource(R.string.request_permission))
                }
            }
        }
    }
}

@Composable
private fun ExtraToolsPage(
    shizukuStatus: ShizukuStatus,
    selectedSim: SimSelection?,
    allSimList: List<SimSelection>,
    tiktokEnabled: Boolean,
    featureSwitchesEnabled: Boolean,
    checkingCaptivePortalStatus: Boolean,
    fixingCaptivePortal: Boolean,
    captivePortalFixState: MainViewModel.CaptivePortalFixState?,
    networkExitChecking: Boolean,
    networkExitStatus: NetworkExitStatus?,
    networkExitError: String?,
    configBackups: List<ConfigBackupSnapshot>,
    onSelectSim: (SimSelection) -> Unit,
    onRefreshSimList: () -> Unit,
    onFixCaptivePortal: () -> Unit,
    onTikTokFixChange: (Boolean) -> Unit,
    onCheckNetworkExit: () -> Unit,
    onOpenApnSettings: () -> Unit,
    onPrepareApn: () -> Unit,
    onAddQuickTile: (Class<*>, Int) -> Unit,
    onBackupConfig: () -> Unit,
    onRestoreBackup: (ConfigBackupSnapshot) -> Unit,
    onDeleteBackup: (ConfigBackupSnapshot) -> Unit,
) {
    val isDomestic = isChinaDomesticSim(selectedSim)
    if (shizukuStatus != ShizukuStatus.READY) {
        SimpleMessageCard(
            title = stringResource(R.string.extra_requires_shizuku_title),
            body = stringResource(R.string.captive_portal_fix_requires_shizuku)
        )
    }
    SimCardSelectionCard(
        selectedSim = selectedSim,
        allSimList = allSimList,
        onSelectSim = onSelectSim,
        onRefreshSimList = onRefreshSimList,
    )
    Spacer(modifier = Modifier.height(16.dp))
    RegionCompatibilityCard(
        selectedSim = selectedSim,
        isDomestic = isDomestic,
    )
    ApnSimInfoCard(
        selectedSim = selectedSim,
        onOpenApnSettings = onOpenApnSettings,
        onPrepareApn = onPrepareApn,
    )
    CaptivePortalFixCard(
        shizukuStatus = shizukuStatus,
        checkingCaptivePortalStatus = checkingCaptivePortalStatus,
        fixingCaptivePortal = fixingCaptivePortal,
        state = captivePortalFixState,
        onFixCaptivePortal = onFixCaptivePortal,
    )
    NetworkExitCard(
        checking = networkExitChecking,
        status = networkExitStatus,
        error = networkExitError,
        onCheck = onCheckNetworkExit,
    )
    TiktokFixCard(
        enabled = tiktokEnabled,
        available = isDomestic,
        switchEnabled = featureSwitchesEnabled && (selectedSim?.subId ?: -1) >= 0,
        onCheckedChange = onTikTokFixChange,
    )
    QuickSettingsGuideCard(onAddQuickTile = onAddQuickTile)
    ConfigBackupCard(
        selectedSim = selectedSim,
        backups = configBackups,
        onBackupConfig = onBackupConfig,
        onRestoreBackup = onRestoreBackup,
        onDeleteBackup = onDeleteBackup,
    )
}

@Composable
private fun RegionCompatibilityCard(
    selectedSim: SimSelection?,
    isDomestic: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.region_compat_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val sim = selectedSim
            if (sim == null || sim.subId < 0) {
                Text(
                    text = stringResource(R.string.select_single_sim),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                return@Column
            }
            KeyValueRow("MCC/MNC", "${sim.mcc.ifBlank { "-" }}/${sim.mnc.ifBlank { "-" }}")
            KeyValueRow("ISO", displayCountryIso(sim))
            KeyValueRow(
                stringResource(R.string.region_mainland_sim),
                stringResource(if (isDomestic) R.string.region_status_yes else R.string.region_status_no),
            )
            KeyValueRow(
                stringResource(R.string.region_tiktok_applicable),
                stringResource(
                    if (isDomestic) {
                        R.string.region_status_applicable
                    } else {
                        R.string.region_status_not_applicable
                    }
                ),
            )
        }
    }
}

@Composable
private fun TiktokFixCard(
    enabled: Boolean,
    available: Boolean,
    switchEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            BooleanFeatureItem(
                title = stringResource(R.string.tiktok_network_fix),
                description = if (available) {
                    stringResource(R.string.tiktok_network_fix_desc)
                } else {
                    stringResource(R.string.tiktok_network_fix_unavailable)
                },
                checked = enabled && available,
                enabled = switchEnabled && available,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun NetworkExitCard(
    checking: Boolean,
    status: NetworkExitStatus?,
    error: String?,
    onCheck: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.network_exit_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.network_exit_desc),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Button(
                onClick = onCheck,
                enabled = !checking,
                modifier = Modifier.height(40.dp)
            ) {
                Text(stringResource(if (checking) R.string.network_exit_checking else R.string.network_exit_action))
            }
            if (checking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (error != null) {
                Text(text = stringResource(R.string.network_exit_failed, error), color = MaterialTheme.colorScheme.error)
            }
            status?.let {
                KeyValueRow(stringResource(R.string.network_exit_ip), "${it.ip} · ${it.ipVersion}")
                KeyValueRow(stringResource(R.string.network_exit_region), listOf(it.country, it.region, it.city).filter { text -> text.isNotBlank() }.joinToString(" / "))
                KeyValueRow(stringResource(R.string.network_exit_org), it.org)
                KeyValueRow(stringResource(R.string.network_exit_risk), it.risk)
                KeyValueRow(stringResource(R.string.network_exit_services), serviceSummary(it))
            }
        }
    }
}

@Composable
private fun ApnSimInfoCard(
    selectedSim: SimSelection?,
    onOpenApnSettings: () -> Unit,
    onPrepareApn: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.apn_sim_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val sim = selectedSim
            if (sim == null || sim.subId < 0) {
                Text(
                    text = stringResource(R.string.select_single_sim),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                KeyValueRow(stringResource(R.string.sim_card), sim.showTitle)
                KeyValueRow("MCC/MNC", "${sim.mcc.ifBlank { "-" }}/${sim.mnc.ifBlank { "-" }}")
                KeyValueRow("ISO", displayCountryIso(sim))
                KeyValueRow("ICCID", sim.iccId.takeLast(4).ifBlank { "----" })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenApnSettings, modifier = Modifier.height(40.dp)) {
                    Text(stringResource(R.string.open_apn_settings))
                }
                Button(
                    onClick = onPrepareApn,
                    enabled = selectedSim != null && selectedSim.subId >= 0,
                    modifier = Modifier.height(40.dp)
                ) {
                    Text(stringResource(R.string.apn_auto_apply))
                }
            }
        }
    }
}

@Composable
private fun QuickSettingsGuideCard(
    onAddQuickTile: (Class<*>, Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.qs_guide_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.qs_guide_desc),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTileButton(
                    label = stringResource(R.string.qs_add_volte_sim_1),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onAddQuickTile(
                            SIM1VoLTETileService::class.java,
                            R.string.qs_toggle_tile_title_sim_1
                        )
                    },
                )
                QuickTileButton(
                    label = stringResource(R.string.qs_add_ims_sim_1),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onAddQuickTile(
                            SIM1IMSStatusTileService::class.java,
                            R.string.qs_status_tile_title_sim_1
                        )
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTileButton(
                    label = stringResource(R.string.qs_add_volte_sim_2),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onAddQuickTile(
                            SIM2VoLTETileService::class.java,
                            R.string.qs_toggle_tile_title_sim_2
                        )
                    },
                )
                QuickTileButton(
                    label = stringResource(R.string.qs_add_ims_sim_2),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onAddQuickTile(
                            SIM2IMSStatusTileService::class.java,
                            R.string.qs_status_tile_title_sim_2
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickTileButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConfigBackupCard(
    selectedSim: SimSelection?,
    backups: List<ConfigBackupSnapshot>,
    onBackupConfig: () -> Unit,
    onRestoreBackup: (ConfigBackupSnapshot) -> Unit,
    onDeleteBackup: (ConfigBackupSnapshot) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.config_backup_title),
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(onClick = onBackupConfig, modifier = Modifier.height(40.dp)) {
                    Text(stringResource(R.string.config_backup_action))
                }
            }
            Text(
                text = if (selectedSim != null && selectedSim.subId >= 0) {
                    stringResource(R.string.config_backup_desc, selectedSim.showTitle)
                } else {
                    stringResource(R.string.select_single_sim)
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            if (backups.isEmpty()) {
                Text(
                    text = stringResource(R.string.config_backup_empty),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                backups.forEach { backup ->
                    HorizontalDivider(thickness = 0.5.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(backup.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                text = backupSubtitle(backup),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        TextButton(onClick = { onRestoreBackup(backup) }) {
                            Text(stringResource(R.string.config_backup_restore))
                        }
                        TextButton(onClick = { onDeleteBackup(backup) }) {
                            Text(stringResource(R.string.config_backup_delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApnConfirmDialog(
    draft: ApnDraftConfig,
    applying: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apn_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyValueRow(stringResource(R.string.apn_name), draft.name)
                KeyValueRow("APN", draft.apn)
                KeyValueRow(stringResource(R.string.apn_type), draft.type)
                KeyValueRow("MCC/MNC", "${draft.mcc}/${draft.mnc}")
                if (applying) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !applying) {
                Text(stringResource(R.string.apn_confirm_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !applying) {
                Text(stringResource(id = android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun SimpleMessageCard(
    title: String,
    body: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun KeyValueRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(label, modifier = Modifier.width(82.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        Text(value.ifBlank { "-" }, modifier = Modifier.weight(1f), fontSize = 12.sp)
    }
}

private fun serviceSummary(status: NetworkExitStatus): String {
    fun flag(value: Boolean?): String = when (value) {
        true -> "OK"
        false -> "FAIL"
        null -> "N/A"
    }
    return "Google ${flag(status.googleReachable)} · TikTok ${flag(status.tiktokReachable)} · 验证 ${flag(status.captivePortalReachable)}"
}

private fun backupSubtitle(backup: ConfigBackupSnapshot): String {
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(backup.createdAtMillis))
    return "$time · ${backup.simTitle.ifBlank { backup.name }} · MCC/MNC ${backup.mcc.ifBlank { "-" }}/${backup.mnc.ifBlank { "-" }}"
}

/**
 *系统信息卡片
 * 显示软件版本、Android 版本、Shizuku 状态等。
 */
@Composable
fun SystemInfoCard(
    systemInfo: SystemInfo,
    shizukuStatus: ShizukuStatus,
    onRefresh: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onLogcatClick: () -> Unit,
    onIssueClick: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val shizukuStatusText = when (shizukuStatus) {
        ShizukuStatus.CHECKING -> stringResource(R.string.shizuku_checking)
        ShizukuStatus.NOT_RUNNING -> stringResource(R.string.shizuku_not_running)
        ShizukuStatus.NO_PERMISSION -> stringResource(R.string.shizuku_no_permission)
        ShizukuStatus.READY -> stringResource(R.string.shizuku_ready)
        else -> ""
    }
    val shizukuStatusColor = when (shizukuStatus) {
        ShizukuStatus.NOT_RUNNING -> MaterialTheme.colorScheme.error
        ShizukuStatus.NO_PERMISSION -> MaterialTheme.colorScheme.tertiary
        else -> Color(0xFF16A34A)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            BrandHeader()
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(id = R.string.system_info),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.weight(1F))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    HeaderActionChip(
                        icon = painterResource(R.drawable.ic_github),
                        label = stringResource(R.string.action_repo),
                        onClick = {
                            uriHandler.openUri(REPO_URL)
                        },
                    )
                    HeaderActionChip(
                        icon = painterResource(R.drawable.ic_issue),
                        label = stringResource(R.string.action_issue),
                        onClick = onIssueClick,
                    )
                    HeaderActionChip(
                        icon = painterResource(R.drawable.ic_log),
                        label = stringResource(R.string.action_logcat),
                        onClick = onLogcatClick,
                    )
                }
            }
            Text(
                text = stringResource(R.string.current_version, toDisplayVersion(systemInfo.appVersionName)),
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.device_model, systemInfo.deviceModel),
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.android_version, systemInfo.androidVersion),
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.security_patch_version, systemInfo.securityPatchVersion),
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.shizuku_status, shizukuStatusText),
                    fontSize = 14.sp,
                    color = shizukuStatusColor
                )
                FeatureActionChip(
                    icon = Icons.Rounded.Cached,
                    label = stringResource(id = R.string.refresh_permission),
                    onClick = onRefresh,
                )
            }
            if (shizukuStatus == ShizukuStatus.NO_PERMISSION) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onRequestShizukuPermission) {
                    Text(text = stringResource(id = R.string.request_permission))
                }
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            contentScale = ContentScale.Fit
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.brand_name),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.brand_subtitle),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun CaptivePortalFixCard(
    shizukuStatus: ShizukuStatus,
    checkingCaptivePortalStatus: Boolean,
    fixingCaptivePortal: Boolean,
    state: MainViewModel.CaptivePortalFixState?,
    onFixCaptivePortal: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.captive_portal_fix_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.captive_portal_fix_desc),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(modifier = Modifier.height(10.dp))
            val isReady = shizukuStatus == ShizukuStatus.READY
            val currentMode = state?.mode ?: MainViewModel.CaptivePortalFixMode.NEED_FIX
            val buttonEnabled = isReady &&
                !fixingCaptivePortal &&
                !checkingCaptivePortalStatus &&
                currentMode != MainViewModel.CaptivePortalFixMode.NORMAL
            val statusTextRes = when {
                checkingCaptivePortalStatus -> R.string.captive_portal_fix_status_checking
                currentMode == MainViewModel.CaptivePortalFixMode.CAN_RESTORE -> R.string.captive_portal_fix_status_restorable
                currentMode == MainViewModel.CaptivePortalFixMode.NORMAL -> R.string.captive_portal_fix_status_normal
                else -> R.string.captive_portal_fix_status_need_fix
            }
            val actionTextRes = when {
                fixingCaptivePortal && currentMode == MainViewModel.CaptivePortalFixMode.CAN_RESTORE ->
                    R.string.captive_portal_restore_running
                fixingCaptivePortal -> R.string.captive_portal_fix_running
                checkingCaptivePortalStatus -> R.string.captive_portal_fix_checking
                currentMode == MainViewModel.CaptivePortalFixMode.CAN_RESTORE ->
                    R.string.captive_portal_fix_restore_action
                currentMode == MainViewModel.CaptivePortalFixMode.NORMAL ->
                    R.string.captive_portal_fix_normal_action
                else -> R.string.captive_portal_fix_action
            }
            Button(
                onClick = onFixCaptivePortal,
                enabled = buttonEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text(
                    text = stringResource(actionTextRes)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(statusTextRes),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
            if (!isReady) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.captive_portal_fix_requires_shizuku),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun HeaderActionChip(
    icon: Painter,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Column(
        modifier = Modifier
            .width(42.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = icon,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            tint = contentColor,
        )
        Text(
            text = label,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            color = contentColor,
        )
    }
}

/**
 * SIM 卡选择卡片
 * 列出所有可用的 SIM 卡供用户选择。
 */
@Composable
fun SimCardSelectionCard(
    selectedSim: SimSelection?,
    allSimList: List<SimSelection>,
    onSelectSim: (SimSelection) -> Unit,
    onRefreshSimList: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(id = R.string.sim_card),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.weight(1F))
                FeatureActionChip(
                    icon = Icons.Rounded.Cached,
                    label = stringResource(R.string.refresh_short),
                    onClick = onRefreshSimList,
                )
            }
            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                Column {
                    allSimList.forEach { sim ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 36.dp)
                                .selectable(
                                    selected = (selectedSim == sim),
                                    onClick = { onSelectSim(sim) }),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                modifier = Modifier.size(20.dp),
                                selected = (selectedSim == sim),
                                onClick = { onSelectSim(sim) })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(sim.showTitle)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 此时功能配置卡片
 * 动态加载并显示所支持的 IMS 功能开关。
 */
@Composable
fun FeaturesCard(
    isSelectAllSim: Boolean,
    allSimList: List<SimSelection>,
    selectedSim: SimSelection?,
    imsRegistrationStatusBySubId: Map<Int, Boolean?>,
    imsRegistrationLoadingBySubId: Map<Int, Boolean>,
    featureSwitchesEnabled: Boolean = true,
    onImsRegistrationToggle: (Int, Boolean) -> Unit,
    featureSwitches: Map<Feature, FeatureValue>,
    onFeatureSwitchChange: (Feature, FeatureValue) -> Unit,
    resetFeatures: () -> Unit,
    onDumpConfig: () -> Unit,
    onRunDiagnostics: () -> Unit,
    showTikTokFix: Boolean = true,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            var featureMenuExpanded by remember(isSelectAllSim, selectedSim?.subId) { mutableStateOf(false) }
            var showRestoreConfirmDialog by remember(isSelectAllSim, selectedSim?.subId) {
                mutableStateOf(false)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.features_config),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (!isSelectAllSim) {
                    Box {
                        IconButton(
                            onClick = { featureMenuExpanded = true },
                            enabled = featureSwitchesEnabled,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.tools)
                            )
                        }
                        DropdownMenu(
                            expanded = featureMenuExpanded,
                            onDismissRequest = { featureMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reset_config)) },
                                onClick = {
                                    featureMenuExpanded = false
                                    showRestoreConfirmDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dump_config)) },
                                onClick = {
                                    featureMenuExpanded = false
                                    onDumpConfig()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diagnostics_menu)) },
                                onClick = {
                                    featureMenuExpanded = false
                                    onRunDiagnostics()
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (showRestoreConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showRestoreConfirmDialog = false },
                    title = { Text(stringResource(R.string.restore_confirm_title)) },
                    text = { Text(stringResource(R.string.restore_confirm_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showRestoreConfirmDialog = false
                                resetFeatures()
                            }
                        ) {
                            Text(stringResource(R.string.restore_confirm_action))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRestoreConfirmDialog = false }) {
                            Text(stringResource(id = android.R.string.cancel))
                        }
                    }
                )
            }

            val showFeatures = Feature.entries.toMutableList().apply {
                removeAll { it.valueType == FeatureValueType.STRING }
                if (!showTikTokFix || isSelectAllSim || !isChinaDomesticSim(selectedSim)) {
                    remove(Feature.TIKTOK_NETWORK_FIX)
                }
            }
            val orderedFeatures = showFeatures.sortedWith(
                compareBy<Feature>(
                    { if (it.valueType == FeatureValueType.BOOLEAN) 0 else 1 },
                    { switchFeatureCategoryOrder(it) },
                    { Feature.entries.indexOf(it) },
                )
            )
            if (!isSelectAllSim) {
                val selectedSubId = selectedSim?.subId
                val status = selectedSubId?.let { imsRegistrationStatusBySubId[it] }
                val checked = status == true
                val loading = selectedSubId != null && imsRegistrationLoadingBySubId[selectedSubId] == true
                val description = when {
                    loading -> stringResource(R.string.ims_registration_status_registering_desc)
                    status == true -> stringResource(R.string.ims_registration_status_registered_desc)
                    status == null -> stringResource(R.string.ims_registration_status_unknown_desc)
                    else -> stringResource(R.string.ims_registration_status_desc)
                }
                BooleanFeatureItem(
                    title = stringResource(R.string.ims_registration_status),
                    description = description,
                    checked = checked,
                    enabled = !loading && featureSwitchesEnabled,
                    trailingContent = if (loading) {
                        {
                            FeatureStatusPill(
                                label = stringResource(R.string.ims_registration_status_registering)
                            )
                        }
                    } else {
                        null
                    },
                    onCheckedChange = { targetChecked ->
                        if (selectedSubId == null || selectedSubId < 0) return@BooleanFeatureItem
                        onImsRegistrationToggle(selectedSubId, targetChecked)
                    },
                )
                if (orderedFeatures.isNotEmpty()) {
                    HorizontalDivider(thickness = 0.5.dp)
                }
            } else {
                val realSims = allSimList.filter { it.subId >= 0 }
                realSims.forEachIndexed { index, sim ->
                    val status = imsRegistrationStatusBySubId[sim.subId]
                    val checked = status == true
                    val loading = imsRegistrationLoadingBySubId[sim.subId] == true
                    val description = when {
                        loading -> stringResource(R.string.ims_registration_status_registering_desc)
                        status == true -> stringResource(R.string.ims_registration_status_registered_desc)
                        status == null -> stringResource(R.string.ims_registration_status_unknown_desc)
                        else -> stringResource(R.string.ims_registration_status_desc)
                    }
                    BooleanFeatureItem(
                        title = "${stringResource(R.string.ims_registration_status)} · ${sim.showTitle}",
                        description = description,
                        checked = checked,
                        enabled = !loading && featureSwitchesEnabled,
                        trailingContent = if (loading) {
                            {
                                FeatureStatusPill(
                                    label = stringResource(R.string.ims_registration_status_registering)
                                )
                            }
                        } else {
                            null
                        },
                        onCheckedChange = { targetChecked ->
                            onImsRegistrationToggle(sim.subId, targetChecked)
                        },
                    )
                    val hasMoreRows = index < realSims.lastIndex
                    if (hasMoreRows || orderedFeatures.isNotEmpty()) {
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
            orderedFeatures.forEachIndexed { index, feature ->
                val title = stringResource(feature.showTitleRes)
                val description = stringResource(feature.showDescriptionRes)
                BooleanFeatureItem(
                    title = title,
                    description = description,
                    checked = (featureSwitches[feature]?.data ?: feature.defaultValue) as Boolean,
                    enabled = featureSwitchesEnabled,
                    onCheckedChange = {
                        onFeatureSwitchChange(
                            feature,
                            FeatureValue(it, feature.valueType)
                        )
                    }
                )
                if (index < orderedFeatures.lastIndex) {
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun FeatureActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                label,
                fontSize = 12.sp,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(16.dp)
            )
        }
    )
}

@Composable
fun BooleanFeatureItem(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
        }
        if (trailingContent != null) {
            trailingContent()
        } else {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun FeatureStatusPill(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun IssueReportHintCard(
    onSubmitIssue: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.issue_failure_hint_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.issue_failure_hint_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Button(
                onClick = onSubmitIssue,
                modifier = Modifier.height(40.dp),
            ) {
                Text(text = stringResource(R.string.issue_failure_submit))
            }
        }
    }
}

@Composable
fun ShizukuUpdateDialog(dismissDialog: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismissDialog,
        title = { Text("Shizuku") },
        text = { Text(stringResource(id = R.string.update_shizuku)) },
        confirmButton = {
            TextButton(onClick = dismissDialog) {
                Text(stringResource(id = android.R.string.ok))
            }
        }
    )
}
