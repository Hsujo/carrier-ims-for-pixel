package io.github.vvb2060.ims.model

import android.telephony.CarrierConfigManager

/**
 * `carrier_nr_availabilities_int_array` 的三种取值。
 *
 * 用于定位「信号满格但无数据」类问题：分别锁定 NSA / SA，观察是哪一侧的
 * NR 注册或 PDU session 建立出了问题。
 */
enum class NrMode(val storageKey: String) {
    /** `[1]` —— 只允许 NSA（依赖 LTE 锚点，EN-DC）。 */
    NSA_ONLY("nsa"),

    /** `[1,2]` —— NSA + SA，Android 默认行为。 */
    NSA_AND_SA("nsa_sa"),

    /** `[2]` —— 只允许 SA。实验性：部分网络/设备组合下会完全无 NR 服务。 */
    SA_ONLY("sa");

    fun toAvailabilities(): IntArray = when (this) {
        NSA_ONLY -> intArrayOf(CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA)
        NSA_AND_SA -> intArrayOf(
            CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA,
            CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA,
        )

        SA_ONLY -> intArrayOf(CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA)
    }

    companion object {
        val DEFAULT = NSA_AND_SA

        fun fromStorageKey(key: String?): NrMode? =
            entries.firstOrNull { it.storageKey == key?.trim()?.lowercase() }

        /**
         * 由读回的 `carrier_nr_availabilities_int_array` 反推模式。
         *
         * @return 无法对应到已知模式（数组为空、缺失或含未知值）时返回 null，
         *         由调用方显示为 UNKNOWN，而不是猜一个值。
         */
        fun fromAvailabilities(values: IntArray?): NrMode? {
            if (values == null) return null
            val nsa = values.contains(CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA)
            val sa = values.contains(CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA)
            val known = values.all {
                it == CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA ||
                    it == CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA
            }
            if (!known) return null
            return when {
                nsa && sa -> NSA_AND_SA
                nsa -> NSA_ONLY
                sa -> SA_ONLY
                else -> null
            }
        }

        /** 用于 UI 展示实际读回值，例如 `[1, 2]`。 */
        fun formatAvailabilities(values: IntArray?): String =
            values?.joinToString(prefix = "[", postfix = "]") { it.toString() } ?: "UNKNOWN"
    }
}
