package io.github.vvb2060.ims.privileged

import android.app.Activity
import android.app.IActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.ServiceManager
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.android.internal.telephony.ITelephony
import io.github.vvb2060.ims.LogcatRepository
import io.github.vvb2060.ims.model.FeatureConfigMapper
import io.github.vvb2060.ims.model.NrMode
import io.github.vvb2060.ims.model.PerSimOverrideRules
import rikka.shizuku.ShizukuBinderWrapper

class ImsModifier : BackgroundInstrumentation() {
    companion object Companion {
        private const val TAG = "ImsModifier"
        private const val KEY_NR_ADVANCED_THRESHOLD_BANDWIDTH_KHZ = "nr_advanced_threshold_bandwidth_khz_int"
        private const val KEY_ADDITIONAL_NR_ADVANCED_BANDS = "additional_nr_advanced_bands_int_array"
        private const val KEY_5G_ICON_CONFIGURATION = "5g_icon_configuration_string"
        private const val KEY_NR_ADVANCED_CAPABLE_PCO_ID = "nr_advanced_capable_pco_id_int"
        private const val KEY_INCLUDE_LTE_FOR_NR_ADVANCED_THRESHOLD_BANDWIDTH =
            "include_lte_for_nr_advanced_threshold_bandwidth_bool"
        private const val NR_ADVANCED_THRESHOLD_KHZ_FOR_5GA =
            FeatureConfigMapper.NR_ADVANCED_THRESHOLD_KHZ_FOR_5GA
        private const val NR_ICON_CONFIGURATION_5GA = FeatureConfigMapper.NR_ICON_CONFIGURATION_5GA
        private val NR_ADVANCED_BANDS_FOR_CHINA = intArrayOf(
            1, 3, 8, 28, 41, 78, 79
        )
        const val BUNDLE_SELECT_SIM_ID = "select_sim_id"
        const val BUNDLE_RESET = "reset"

        // overrideConfig 是 putAll 合并语义：只写「开启」的 key 无法撤销旧覆盖。
        // 置为 true 时先清空该 SIM 的覆盖再写入完整配置，关闭的开关才会回到运营商默认值。
        const val BUNDLE_REPLACE = "replace"
        const val BUNDLE_PREFER_PERSISTENT = "prefer_persistent"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_RESULT_MSG = "result_msg"

        /**
         * 主操作成功、但 shell 权限委托清理失败时携带的警告原因。
         * 出现该键时 [BUNDLE_RESULT] 仍为 true，调用方不得据此判定失败。
         */
        const val BUNDLE_RESULT_WARNING = "result_warning"

        /**
         * 应用到全部 SIM 时置为 true：逐卡保留 NR 数组与国家码覆盖，见 [PerSimOverrideRules]。
         */
        const val BUNDLE_KEEP_PER_SIM = "keep_per_sim"

        fun buildResetBundle(): Bundle = Bundle().apply {
            putBoolean(BUNDLE_RESET, true)
        }

        fun buildBundle(
            carrierName: String?,
            countryISO: String?,
            enableVoLTE: Boolean,
            enableVoWiFi: Boolean,
            enableVT: Boolean,
            enableVoNR: Boolean,
            enableCrossSIM: Boolean,
            enableUT: Boolean,
            enable5GNR: Boolean,
            enable5GThreshold: Boolean,
            enable5GPlusIcon: Boolean,
            enableShow4GForLTE: Boolean,
            // 不给默认值：漏传会静默写出与实际不符的数组，必须由调用方显式决定。
            nrMode: NrMode,
            // 非 null 时原样写入这个数组而不是 nrMode 对应的数组：
            // 用于保留系统里含无法识别取值的 NR 数组，避免被改写成默认模式。
            nrAvailabilitiesOverride: IntArray? = null,
        ): Bundle {
            val bundle = Bundle()
            // 运营商名称
            if (carrierName?.isNotBlank() == true) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true)
                bundle.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, carrierName)
                bundle.putString(CarrierConfigManager.KEY_CARRIER_CONFIG_VERSION_STRING, ":3")
            }
            // 运营商国家码
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (countryISO?.isNotBlank() == true) {
                    bundle.putString(
                        CarrierConfigManager.KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING,
                        countryISO
                    )
                }
            }
            // VoLTE 配置
            if (enableVoLTE) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, true)
                bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true)
                bundle.putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, false)
                bundle.putBoolean(CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL, false)
            }

            // LTE 显示为 4G
            if (enableShow4GForLTE) {
                bundle.putBoolean("show_4g_for_lte_data_icon_bool", true)
            }

            // VT (视频通话) 配置
            if (enableVT) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, true)
            }

            // UT 补充服务配置
            if (enableUT) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL, true)
            }

            // 跨 SIM 通话配置
            if (enableCrossSIM) {
                bundle.putBoolean(
                    CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL,
                    true
                )
                bundle.putBoolean(
                    CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL,
                    true
                )
            }

            // VoWiFi 配置
            if (enableVoWiFi) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, true)
                bundle.putBoolean(
                    CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL,
                    true
                )
                bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, true)
                bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL, true)
                // KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL
                bundle.putBoolean("show_wifi_calling_icon_in_status_bar_bool", true)
                // KEY_WFC_SPN_FORMAT_IDX_INT
                bundle.putInt("wfc_spn_format_idx_int", 6)
            }

            // VoNR (5G 语音) 配置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (enableVoNR) {
                    bundle.putBoolean(CarrierConfigManager.KEY_VONR_ENABLED_BOOL, true)
                    bundle.putBoolean(CarrierConfigManager.KEY_VONR_SETTING_VISIBILITY_BOOL, true)
                }
            }

            // 5G NR 配置
            if (enable5GNR) {
                bundle.putIntArray(
                    CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
                    nrAvailabilitiesOverride ?: nrMode.toAvailabilities()
                )
                if (enable5GPlusIcon) {
                    // 5GA / 5G+ 图标判定逻辑：
                    // 1) 只有达到较高 NR 聚合带宽（这里使用 110MHz）才进入 NR Advanced；
                    // 2) 将 NR Advanced 对应状态映射到 5G_Plus 图标；
                    // 3) 补充常见国内 NR 频段，避免 Sub-6 场景因非毫米波而无法进入高级图标状态。
                    bundle.putInt(KEY_NR_ADVANCED_THRESHOLD_BANDWIDTH_KHZ, NR_ADVANCED_THRESHOLD_KHZ_FOR_5GA)
                    bundle.putBoolean(
                        KEY_INCLUDE_LTE_FOR_NR_ADVANCED_THRESHOLD_BANDWIDTH,
                        false
                    )
                    bundle.putIntArray(KEY_ADDITIONAL_NR_ADVANCED_BANDS, NR_ADVANCED_BANDS_FOR_CHINA)
                    bundle.putString(KEY_5G_ICON_CONFIGURATION, NR_ICON_CONFIGURATION_5GA)
                    // 将 PCO 约束置零，避免被运营商 PCO gate 阻断 NR Advanced 图标显示。
                    bundle.putInt(KEY_NR_ADVANCED_CAPABLE_PCO_ID, 0)
                }
                if (enable5GThreshold) {
                    bundle.putIntArray(
                        CarrierConfigManager.KEY_5G_NR_SSRSRP_THRESHOLDS_INT_ARRAY,  // Boundaries: [-140 dBm, -44 dBm]
                        intArrayOf(
                            -128,  /* SIGNAL_STRENGTH_POOR */
                            -118,  /* SIGNAL_STRENGTH_MODERATE */
                            -108,  /* SIGNAL_STRENGTH_GOOD */
                            -98,  /* SIGNAL_STRENGTH_GREAT */
                        )
                    )
                }
            }
            return bundle
        }
    }

    override fun execute(arguments: Bundle?) {
        // 检查 Shizuku binder 是否就绪（与 App 同进程，binder 已收到时无需等待）
        val results = Bundle()
        if (!isShizukuBinderReady()) {
            results.putBoolean(BUNDLE_RESULT, false)
            results.putString(BUNDLE_RESULT_MSG, "shizuku binder is not ready")
            finish(Activity.RESULT_OK, results)
            return
        }
        Log.i(TAG, "shizuku binder is ready")

        try {
            val cleanupWarning = overrideConfig(arguments ?: Bundle())
            if (LogcatRepository.isCapturing()) {
                Log.i(TAG, "overrideConfig success")
            }
            results.putBoolean(BUNDLE_RESULT, true)
            // 主操作已成功。权限委托清理失败只作为警告上报，绝不能翻转成失败。
            if (cleanupWarning != null) {
                Log.w(TAG, "overrideConfig succeeded with cleanup compatibility warning")
                results.putString(
                    BUNDLE_RESULT_WARNING,
                    cleanupWarning.message ?: cleanupWarning.javaClass.simpleName
                )
            }
        } catch (t: Throwable) {
            if (LogcatRepository.isCapturing()) {
                Log.i(TAG, "overrideConfig failed")
            }
            Log.e(TAG, "failed to override config", t)
            results.putBoolean(BUNDLE_RESULT, false)
            results.putString(BUNDLE_RESULT_MSG, t.message ?: t.javaClass.simpleName)
        }
        finish(Activity.RESULT_OK, results)
    }

    /**
     * 执行 CarrierConfig 写入。
     *
     * @return 主操作成功、但权限委托清理失败时返回该失败原因；全部成功返回 null。
     *         主操作失败时抛出异常。
     */
    @Throws(Exception::class)
    private fun overrideConfig(arguments: Bundle): Throwable? {
        val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
        val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(binder))
        Log.i(TAG, "starting shell permission delegation")
        var startFailure: Throwable? = null
        // 委托是特权写入的前置条件：开启失败就直接判定主操作失败，
        // 而不是继续执行并收到一个更难解释的 SecurityException。
        if (!am.tryStartShellPermissionDelegation(TAG) { startFailure = it }) {
            throw IllegalStateException(
                "failed to start shell permission delegation: " +
                    (startFailure?.message ?: startFailure?.javaClass?.simpleName ?: "unknown"),
                startFailure
            )
        }
        try {
            val cm = context.getSystemService(CarrierConfigManager::class.java)
            val sm = context.getSystemService(SubscriptionManager::class.java)

            val selectedSubId = arguments.getInt(BUNDLE_SELECT_SIM_ID, -1)
            arguments.remove(BUNDLE_SELECT_SIM_ID)

            val subIds: IntArray = if (selectedSubId == -1) {
                // 应用到所有 SIM 卡
                sm.javaClass.getMethod("getActiveSubscriptionIdList").invoke(sm) as IntArray
            } else {
                // 只应用到选中的 SIM 卡
                intArrayOf(selectedSubId)
            }
            val reset = arguments.getBoolean(BUNDLE_RESET, false)
            arguments.remove(BUNDLE_RESET)
            val preferPersistent = arguments.getBoolean(BUNDLE_PREFER_PERSISTENT, false)
            arguments.remove(BUNDLE_PREFER_PERSISTENT)
            val replace = arguments.getBoolean(BUNDLE_REPLACE, false)
            arguments.remove(BUNDLE_REPLACE)
            val keepPerSim = arguments.getBoolean(BUNDLE_KEEP_PER_SIM, false)
            arguments.remove(BUNDLE_KEEP_PER_SIM)
            val baseValues = if (reset) null else arguments.toPersistableBundle()
            // 逐卡保留要在任何写入之前全部读完：清空之后读到的只是运营商默认值；
            // 中途读取失败就整次放弃，不会只改了一部分卡。
            val valuesBySubId = subIds.associateWith { subId ->
                baseValues?.let { PersistableBundle(it) }?.also { values ->
                    if (keepPerSim) keepPerSimSettings(cm, subId, values)
                }
            }
            for (subId in subIds) {
                val values = valuesBySubId[subId]
                if (replace && values != null) {
                    Log.i(TAG, "clear existing overrides before replace for subId $subId")
                    applyOverrideConfig(
                        cm,
                        subId,
                        null,
                        preferPersistent = preferPersistent
                    )
                }
                Log.i(TAG, "overrideConfig for subId $subId with values $values")
                applyOverrideConfig(
                    cm,
                    subId,
                    values,
                    preferPersistent = preferPersistent
                )
                if (reset) {
                    clearCarrierTestOverride(subId)
                }
            }
        } catch (t: Throwable) {
            // 主操作失败：先尽力清理，再把原始失败原因抛给调用方。
            // 清理结果在这条路径上不重要，绝不能遮蔽真正的失败。
            am.tryStopShellPermissionDelegation(TAG)
            throw t
        }
        // 主操作已成功。清理失败只作为返回值上报，不抛出，
        // 避免 finally 中的 NoSuchMethodError 覆盖掉一次成功的写入。
        return am.tryStopShellPermissionDelegation(TAG)
    }

    /**
     * 把该卡当前的单卡设置写回 [values]，见 [PerSimOverrideRules]。
     *
     * 读不到该卡当前的配置就抛出、放弃整次写入：宁可不写，也不把各卡的设置统一改写。
     */
    private fun keepPerSimSettings(cm: CarrierConfigManager, subId: Int, values: PersistableBundle) {
        // 与 ConfigReader 一致用整份读取：按键读取的重载要 API 34，而 minSdk 是 33。
        @Suppress("DEPRECATION")
        val current = cm.getConfigForSubId(subId)
            ?: throw IllegalStateException("cannot read current carrier config for subId $subId")
        PerSimOverrideRules.nrArrayToKeep(
            values.containsKey(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY),
            current.getIntArray(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY),
        )?.let {
            Log.i(TAG, "keeping NR array ${NrMode.formatAvailabilities(it)} for subId $subId")
            values.putIntArray(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY, it)
        }
        PerSimOverrideRules.countryIsoToKeep(
            values.containsKey(FeatureConfigMapper.KEY_SIM_COUNTRY_ISO_OVERRIDE),
            current.getString(FeatureConfigMapper.KEY_SIM_COUNTRY_ISO_OVERRIDE),
        )?.let {
            Log.i(TAG, "keeping country ISO override $it for subId $subId")
            values.putString(FeatureConfigMapper.KEY_SIM_COUNTRY_ISO_OVERRIDE, it)
        }
    }

    @Throws(Exception::class)
    private fun applyOverrideConfig(
        cm: CarrierConfigManager,
        subId: Int,
        values: PersistableBundle?,
        preferPersistent: Boolean,
    ) {
        if (!preferPersistent) {
            invokeOverrideConfig(cm, subId, values, persistent = false)
            return
        }
        try {
            invokeOverrideConfig(cm, subId, values, persistent = true)
            Log.i(TAG, "overrideConfig persistent success for subId $subId")
        } catch (persistentError: Throwable) {
            Log.w(
                TAG,
                "overrideConfig persistent failed for subId $subId, fallback to non-persistent",
                persistentError
            )
            try {
                invokeOverrideConfig(cm, subId, values, persistent = false)
                Log.i(TAG, "overrideConfig fallback non-persistent success for subId $subId")
            } catch (fallbackError: Throwable) {
                fallbackError.addSuppressed(persistentError)
                throw fallbackError
            }
        }
    }

    @Throws(Exception::class)
    private fun invokeOverrideConfig(
        cm: CarrierConfigManager,
        subId: Int,
        values: PersistableBundle?,
        persistent: Boolean,
    ) {
        // 使用反射调用 overrideConfig
        try {
            cm.javaClass.getMethod(
                "overrideConfig",
                Int::class.javaPrimitiveType,
                PersistableBundle::class.java,
                Boolean::class.javaPrimitiveType
            ).invoke(cm, subId, values, persistent)
        } catch (_: NoSuchMethodException) {
            cm.javaClass.getMethod(
                "overrideConfig",
                Int::class.javaPrimitiveType,
                PersistableBundle::class.java
            ).invoke(cm, subId, values)
        }
    }

    @Throws(Exception::class)
    private fun clearCarrierTestOverride(subId: Int) {
        val binder = ServiceManager.getService(Context.TELEPHONY_SERVICE)
            ?: throw IllegalStateException("phone service unavailable")
        val telephony = ITelephony.Stub.asInterface(ShizukuBinderWrapper(binder))
            ?: throw IllegalStateException("ITelephony unavailable")

        val clearMethod = runCatching {
            telephony.javaClass.getMethod("clearCarrierTestOverride", Int::class.javaPrimitiveType)
        }.getOrNull()

        if (clearMethod != null) {
            Log.i(TAG, "clearCarrierTestOverride for subId=$subId")
            clearMethod.invoke(telephony, subId)
            return
        }

        val currentMccMnc = resolveActiveSubscriptionMccMnc(subId)
        if (currentMccMnc.isNullOrBlank()) {
            Log.w(
                TAG,
                "clearCarrierTestOverride unavailable and unable to resolve MCCMNC for subId=$subId; skip fallback to avoid empty operator override"
            )
            return
        }

        Log.i(
            TAG,
            "clearCarrierTestOverride unavailable, fallback setCarrierTestOverride(current=$currentMccMnc) for subId=$subId"
        )
        // 其余字段传 null 表示不覆盖；传 "" 会把 IMSI/ICCID/GID/SPN 覆盖成空值
        telephony.setCarrierTestOverride(
            subId,
            currentMccMnc,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        )
    }

    private fun resolveActiveSubscriptionMccMnc(subId: Int): String? {
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
        val info = sm.activeSubscriptionInfoList?.firstOrNull { it.subscriptionId == subId } ?: return null

        val mcc = info.mccString
            ?.filter { it.isDigit() }
            ?.takeIf { it.length == 3 }
            ?: info.mcc.takeIf { it in 0..999 }?.toString()?.padStart(3, '0')

        val mnc = info.mncString
            ?.filter { it.isDigit() }
            ?.takeIf { it.length in 2..3 }
            ?: info.mnc.takeIf { it in 0..999 }?.toString()?.padStart(2, '0')

        return if (mcc != null && mnc != null) mcc + mnc else null
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    fun Bundle.toPersistableBundle(): PersistableBundle {
        val pb = PersistableBundle()

        // 遍历 Bundle 的所有 Key
        for (key in this.keySet()) {
            val value = this.get(key)

            when (value) {
                is Int -> pb.putInt(key, value)
                is Long -> pb.putLong(key, value)
                is Double -> pb.putDouble(key, value)
                is String -> pb.putString(key, value)
                is Boolean -> pb.putBoolean(key, value)
                is IntArray -> pb.putIntArray(key, value)
                is LongArray -> pb.putLongArray(key, value)
                is DoubleArray -> pb.putDoubleArray(key, value)
                is BooleanArray -> pb.putBooleanArray(key, value)
                else -> {
                    if (value is Array<*> && value.isArrayOf<String>()) {
                        pb.putStringArray(key, value as Array<String>)
                    } else {
                        Log.i(TAG, "toPersistableBundle: unsupported type for key $key")
                    }
                }
            }
        }
        return pb
    }
}
