package io.github.vvb2060.ims.diagnostics

import android.content.Context
import android.os.Bundle
import android.telephony.CarrierConfigManager
import io.github.vvb2060.ims.ShizukuProvider
import io.github.vvb2060.ims.model.FeatureConfigMapper
import io.github.vvb2060.ims.model.NrMode

/**
 * 采样时刻**实际生效**的 CarrierConfig 指纹。
 *
 * 为什么必须进时间线：一次 6 小时的监测里 NR 模式被改过两次，
 * 而时间线本身没有任何记录 —— 只能靠异常快照的 metadata 反推分段边界，
 * 中间两小时没有快照的区间只能靠猜。要做 A/B 对照，
 * 「这条样本属于哪个配置」必须和样本本身写在一起。
 *
 * 读的是系统里的真实值而不是本应用写过什么：配置可能被另一个应用改掉
 * （实测就发生过），只信自己写过的记录会得到错的分组。
 */
object MonitorConfigTag {

    const val UNKNOWN = "?"

    /**
     * 读取并压成一行。
     *
     * 走 Shizuku instrumentation，开销远大于一次采样，
     * 因此只在启动时与 CarrierConfig 变更广播到达时调用，绝不每次采样都读。
     */
    suspend fun read(context: Context, subId: Int): String {
        if (subId < 0) return UNKNOWN
        val bundle = runCatching {
            ShizukuProvider.readCarrierConfig(context, subId, FeatureConfigMapper.readKeys)
        }.getOrNull() ?: return UNKNOWN
        return format(bundle)
    }

    /** CSV 里不能出现逗号，数组用 `+` 连接。 */
    fun format(bundle: Bundle): String {
        val nr = bundle.getIntArray(CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY)
        val nrText = when {
            nr == null -> UNKNOWN
            nr.isEmpty() -> "off"
            else -> nr.joinToString("+")
        }
        fun flag(key: String): String = when {
            !bundle.containsKey(key) -> UNKNOWN
            bundle.getBoolean(key) -> "1"
            else -> "0"
        }
        val iso = bundle.getString(FeatureConfigMapper.KEY_SIM_COUNTRY_ISO_OVERRIDE)
            ?.ifBlank { null } ?: "-"
        return buildString {
            append("nr=").append(nrText)
            NrMode.fromAvailabilities(nr)?.let { append('(').append(it.name).append(')') }
            append(";vonr=").append(flag(FeatureConfigMapper.KEY_VONR_ENABLED))
            append(";vt=").append(flag(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL))
            append(";ut=").append(flag(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL))
            append(";xsim=").append(
                flag(CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL)
            )
            append(";iso=").append(iso.replace(',', '.'))
        }
    }
}
