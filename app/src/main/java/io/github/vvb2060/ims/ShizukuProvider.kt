package io.github.vvb2060.ims

import android.app.IActivityManager
import android.app.IInstrumentationWatcher
import android.app.UiAutomationConnection
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.ServiceManager
import android.telephony.SubscriptionInfo
import android.util.Log
import io.github.vvb2060.ims.model.SimSelection
import io.github.vvb2060.ims.model.ApnDraftConfig
import io.github.vvb2060.ims.privileged.ApnModifier
import io.github.vvb2060.ims.privileged.CaptivePortalFixer
import io.github.vvb2060.ims.privileged.ConfigReader
import io.github.vvb2060.ims.privileged.ImsResetter
import io.github.vvb2060.ims.privileged.ImsStatusReader
import io.github.vvb2060.ims.privileged.ImsModifier
import io.github.vvb2060.ims.privileged.SimReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.LSPass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuProvider

class ShizukuProvider : ShizukuProvider() {
    override fun onCreate(): Boolean {
        LSPass.setHiddenApiExemptions("")
        // 不再自动触发，只在用户手动点击"应用配置"时才执行
        return super.onCreate()
    }

    companion object {
        private const val TAG = "ShizukuProvider"
        private const val INSTRUMENTATION_RESULT_TIMEOUT_MS = 10_000L
        private val instrumentationMutex = Mutex()

        data class CarrierConfigState(
            val config: Bundle?,
            val imsRegistered: Boolean?,
        )

        data class CaptivePortalConfig(
            val httpUrl: String,
            val httpsUrl: String,
            val isCnUrl: Boolean,
            val isOverridden: Boolean,
        )

        /**
         * CarrierConfig 写入结果。
         *
         * @param errorMessage 非 null 表示写入失败。
         * @param cleanupWarning 写入成功、但 shell 权限委托清理失败时的原因。
         *        仅供提示，不代表写入失败。
         */
        data class OverrideResult(
            val errorMessage: String?,
            val cleanupWarning: String? = null,
        ) {
            val isSuccess: Boolean get() = errorMessage == null
        }

        suspend fun overrideImsConfig(context: Context, data: Bundle): OverrideResult {
            val result = startInstrumentation(context, ImsModifier::class.java, Bundle(data), true)
            if (result == null) {
                Log.w(TAG, "overrideImsConfig: failed with empty result")
                return OverrideResult(errorMessage = "failed with empty result")
            }
            if (result.getBoolean(ImsModifier.BUNDLE_RESULT)) {
                val warning = result.getString(ImsModifier.BUNDLE_RESULT_WARNING)
                if (warning != null) {
                    Log.w(TAG, "overrideImsConfig: applied with cleanup warning: $warning")
                }
                return OverrideResult(errorMessage = null, cleanupWarning = warning)
            }
            return OverrideResult(
                errorMessage = result.getString(ImsModifier.BUNDLE_RESULT_MSG) ?: "unknown error"
            )
        }

        suspend fun readSimInfoList(context: Context): List<SimSelection> {
            val result = startInstrumentation(context, SimReader::class.java, null, true)
            if (result == null) {
                Log.w(TAG, "readSimInfoList: failed with empty result")
                return emptyList()
            }
            val subList =
                result.getParcelableArrayList(SimReader.BUNDLE_RESULT, SubscriptionInfo::class.java)
            val resultList = subList?.map {
                SimSelection(
                    it.subscriptionId,
                    it.displayName.toString(),
                    it.carrierName.toString(),
                    it.simSlotIndex,
                    countryIso = it.countryIso ?: "",
                    mcc = it.mccString ?: "",
                    mnc = it.mncString ?: "",
                    iccId = it.iccId ?: "",
                )
            } ?: emptyList()
            return resultList
        }

        suspend fun readCarrierConfig(
            context: Context,
            subId: Int,
            keys: Array<String>,
        ): Bundle? {
            val args = Bundle().apply {
                putInt(ConfigReader.BUNDLE_SELECT_SIM_ID, subId)
                putStringArray(ConfigReader.BUNDLE_KEYS, keys)
            }
            val result = startInstrumentation(context, ConfigReader::class.java, args, true)
            if (result == null) return null
            val value = result.rawValue(ConfigReader.BUNDLE_RESULT)
            if (value == null) return null
            if (value !is Bundle) {
                Log.w(
                    TAG,
                    "readCarrierConfig: unexpected result type ${value.javaClass.name} for subId=$subId"
                )
                return null
            }
            return value
        }

        suspend fun readCarrierConfigWithImsStatus(
            context: Context,
            subId: Int,
            keys: Array<String>,
        ): CarrierConfigState {
            val args = Bundle().apply {
                putInt(ConfigReader.BUNDLE_SELECT_SIM_ID, subId)
                putStringArray(ConfigReader.BUNDLE_KEYS, keys)
                putBoolean(ConfigReader.BUNDLE_WITH_IMS_STATUS, true)
            }
            val result = startInstrumentation(context, ConfigReader::class.java, args, true)
                ?: return CarrierConfigState(null, null)
            return CarrierConfigState(
                config = result.rawValue(ConfigReader.BUNDLE_RESULT) as? Bundle,
                imsRegistered = result.rawValue(ConfigReader.BUNDLE_IMS_REGISTERED) as? Boolean,
            )
        }

        suspend fun dumpCarrierConfig(context: Context, subId: Int): String? {
            val args = Bundle().apply {
                putInt(ConfigReader.BUNDLE_SELECT_SIM_ID, subId)
                putBoolean(ConfigReader.BUNDLE_DUMP, true)
            }
            val result = startInstrumentation(context, ConfigReader::class.java, args, true)
            return result?.getString(ConfigReader.BUNDLE_DUMP_TEXT)
        }

        suspend fun restartImsRegistration(context: Context, subId: Int): String? {
            val args = Bundle().apply {
                putInt(ImsResetter.BUNDLE_SELECT_SIM_ID, subId)
            }
            val result = startInstrumentation(context, ImsResetter::class.java, args, true)
            if (result == null) {
                return "failed with empty result"
            }
            return if (result.getBoolean(ImsResetter.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(ImsResetter.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }

        suspend fun readImsRegistrationStatus(context: Context, subId: Int): Boolean? {
            val args = Bundle().apply {
                putInt(ImsStatusReader.BUNDLE_SELECT_SIM_ID, subId)
            }
            val result = startInstrumentation(context, ImsStatusReader::class.java, args, true)
            if (result == null) return null
            val msg = result.getString(ImsStatusReader.BUNDLE_RESULT_MSG)
            if (msg != null) {
                Log.w(TAG, "readImsRegistrationStatus: failed for subId=$subId: $msg")
                return null
            }
            val value = result.rawValue(ImsStatusReader.BUNDLE_RESULT)
            if (value !is Boolean) {
                Log.w(
                    TAG,
                    "readImsRegistrationStatus: missing or invalid result for subId=$subId"
                )
                return null
            }
            Log.i(TAG, "readImsRegistrationStatus: subId=$subId registered=$value")
            return value
        }

        suspend fun updateCarrierConfigBoolean(
            context: Context,
            subId: Int,
            key: String,
            value: Boolean,
        ): String? {
            val appInfo = context.applicationInfo
            val canUsePersistentOverride =
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val bundle = Bundle().apply {
                putInt(ImsModifier.BUNDLE_SELECT_SIM_ID, subId)
                putBoolean(key, value)
                putBoolean(ImsModifier.BUNDLE_PREFER_PERSISTENT, canUsePersistentOverride)
            }
            return overrideImsConfig(context, bundle).errorMessage
        }

        suspend fun queryCaptivePortalConfig(context: Context): CaptivePortalConfig? {
            val args = Bundle().apply {
                putString(CaptivePortalFixer.BUNDLE_ACTION, CaptivePortalFixer.actionQuery())
            }
            val result = startInstrumentation(context, CaptivePortalFixer::class.java, args, true)
            if (result == null || !result.getBoolean(CaptivePortalFixer.BUNDLE_RESULT, false)) {
                return null
            }
            return CaptivePortalConfig(
                httpUrl = result.getString(CaptivePortalFixer.BUNDLE_HTTP_URL).orEmpty(),
                httpsUrl = result.getString(CaptivePortalFixer.BUNDLE_HTTPS_URL).orEmpty(),
                isCnUrl = result.getBoolean(CaptivePortalFixer.BUNDLE_IS_CN_URL, false),
                isOverridden = result.getBoolean(CaptivePortalFixer.BUNDLE_IS_OVERRIDDEN, false)
            )
        }

        suspend fun applyCaptivePortalCnUrls(context: Context): String? {
            val args = Bundle().apply {
                putString(CaptivePortalFixer.BUNDLE_ACTION, CaptivePortalFixer.actionApplyCn())
            }
            val result = startInstrumentation(context, CaptivePortalFixer::class.java, args, true)
            if (result == null) {
                return "failed with empty result"
            }
            return if (result.getBoolean(CaptivePortalFixer.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(CaptivePortalFixer.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }

        suspend fun restoreCaptivePortalDefaultUrls(context: Context): String? {
            val args = Bundle().apply {
                putString(CaptivePortalFixer.BUNDLE_ACTION, CaptivePortalFixer.actionRestoreDefault())
            }
            val result = startInstrumentation(context, CaptivePortalFixer::class.java, args, true)
            if (result == null) {
                return "failed with empty result"
            }
            return if (result.getBoolean(CaptivePortalFixer.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(CaptivePortalFixer.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }

        suspend fun applyApnConfig(
            context: Context,
            subId: Int,
            config: ApnDraftConfig,
        ): String? {
            val args = Bundle().apply {
                putInt(ApnModifier.BUNDLE_SELECT_SIM_ID, subId)
                putString(ApnModifier.BUNDLE_NAME, config.name)
                putString(ApnModifier.BUNDLE_APN, config.apn)
                putString(ApnModifier.BUNDLE_TYPE, config.type)
                putString(ApnModifier.BUNDLE_MCC, config.mcc)
                putString(ApnModifier.BUNDLE_MNC, config.mnc)
            }
            val result = startInstrumentation(context, ApnModifier::class.java, args, true)
            if (result == null) {
                return "failed with empty result"
            }
            return if (result.getBoolean(ApnModifier.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(ApnModifier.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }

        private suspend fun startInstrumentation(
            context: Context,
            cls: Class<*>,
            args: Bundle?,
            receiveResult: Boolean,
        ): Bundle? = instrumentationMutex.withLock {
            val deferredResult = CompletableDeferred<Bundle?>()
            var watcher: IInstrumentationWatcher.Stub? = null
            if (receiveResult) {
                watcher = object : IInstrumentationWatcher.Stub() {
                    override fun instrumentationStatus(
                        name: ComponentName?,
                        resultCode: Int,
                        results: Bundle?
                    ) {
                    }

                    override fun instrumentationFinished(
                        name: ComponentName?,
                        resultCode: Int,
                        results: Bundle?
                    ) {
                        deferredResult.complete(results)
                    }
                }
            }

            val name = ComponentName(context, cls)
            val flags = 8 // ActivityManager.INSTR_FLAG_NO_RESTART
            var started = false
            try {
                Log.d(TAG, "startInstrumentation: call with component: $name")
                // AMS 调用经 Shizuku 转发，放到 IO 线程，避免阻塞调用方（通常是主线程）；
                // 不可取消，保证「已启动」与持锁等待结果的状态一致
                withContext(Dispatchers.IO + NonCancellable) {
                    val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
                    val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(binder))
                    val connection = UiAutomationConnection()
                    am.startInstrumentation(name, null, flags, args, watcher, connection, 0, null)
                }
                started = true
                Log.i(TAG, "instrumentation started successfully")
                if (receiveResult) {
                    return withTimeoutOrNull(INSTRUMENTATION_RESULT_TIMEOUT_MS) {
                        deferredResult.await()
                    }
                }
                return null
            } catch (e: CancellationException) {
                if (started && receiveResult) {
                    withContext(NonCancellable) {
                        withTimeoutOrNull(INSTRUMENTATION_RESULT_TIMEOUT_MS) {
                            deferredResult.await()
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "failed to start instrumentation", e)
                return null
            }
        }

        @Suppress("DEPRECATION")
        private fun Bundle.rawValue(key: String): Any? = get(key)
    }
}
