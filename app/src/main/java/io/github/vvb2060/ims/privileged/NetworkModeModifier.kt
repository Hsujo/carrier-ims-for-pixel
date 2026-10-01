package io.github.vvb2060.ims.privileged

import android.annotation.SuppressLint
import android.app.Activity
import android.app.IActivityManager
import android.content.Context
import android.os.Bundle
import android.os.ServiceManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import io.github.vvb2060.ims.model.NetworkModeRules
import rikka.shizuku.ShizukuBinderWrapper

/** Read-modify-write and readback share one permission delegation and provider lock. */
class NetworkModeModifier : BackgroundInstrumentation() {
    companion object {
        private const val TAG = "NetworkModeModifier"
        const val SUB_ID = "sub_id"
        const val SLOT_INDEX = "slot_index"
        const val TOGGLE = "toggle"
        const val SUCCESS = "success"
        const val MASK = "mask"
        const val ERROR = "error"
        const val UNSUPPORTED = "unsupported"
    }

    // Phone permissions are delegated from Shizuku shell only within this operation.
    @SuppressLint("MissingPermission")
    override fun execute(arguments: Bundle?) {
        val result = Bundle()
        var am: IActivityManager? = null
        var delegated = false
        try {
            check(isShizukuBinderReady()) { "Shizuku binder is not ready" }
            val subId = arguments?.getInt(SUB_ID, -1) ?: -1
            val slotIndex = arguments?.getInt(SLOT_INDEX, -1) ?: -1
            require(SubscriptionManager.isValidSubscriptionId(subId)) { "Invalid subscription" }
            require(slotIndex >= 0) { "Invalid SIM slot" }
            am = IActivityManager.Stub.asInterface(
                ShizukuBinderWrapper(ServiceManager.getService(Context.ACTIVITY_SERVICE))
            )
            delegated = am.tryStartShellPermissionDelegation(TAG)
            check(delegated) { "Shell permission delegation failed" }
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
            fun checkSubscription() {
                check(subscriptions.getActiveSubscriptionInfo(subId)?.simSlotIndex == slotIndex) {
                    "SIM subscription changed or is inactive"
                }
            }
            checkSubscription()
            val telephony = context.getSystemService(TelephonyManager::class.java)
                .createForSubscriptionId(subId)
            val supported = telephony.supportedRadioAccessFamily
            if (!NetworkModeRules.is5gAllowed(supported)) {
                result.putBoolean(UNSUPPORTED, true)
                error("5G is not supported by this modem")
            }
            val reason = TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER
            val current = telephony.getAllowedNetworkTypesForReason(reason)
            check(current != 0L) { "Empty user network preference" }
            if (arguments?.getBoolean(TOGGLE, false) == true) {
                val target = NetworkModeRules.toggle5g(current)
                checkSubscription()
                telephony.setAllowedNetworkTypesForReason(reason, target)
                val actual = telephony.getAllowedNetworkTypesForReason(reason)
                check(actual == target) { "Network preference readback did not match requested value" }
                result.putLong(MASK, actual)
            } else {
                result.putLong(MASK, current)
            }
            result.putBoolean(SUCCESS, true)
        } catch (t: Throwable) {
            Log.e(TAG, "Network mode operation failed", t)
            result.putBoolean(SUCCESS, false)
            result.putString(ERROR, t.message ?: t.javaClass.simpleName)
        } finally {
            if (delegated) am?.tryStopShellPermissionDelegation(TAG)
        }
        finish(Activity.RESULT_OK, result)
    }
}
