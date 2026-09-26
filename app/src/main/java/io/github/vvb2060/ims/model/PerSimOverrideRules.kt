package io.github.vvb2060.ims.model

import android.telephony.CarrierConfigManager

/**
 * 「应用到全部 SIM」时，每张卡需要原样保留的单卡设置。
 *
 * 全部 SIM 共用同一份配置，写入前又会先清空每张卡的覆盖；而 NR 模式只能逐卡选择，
 * 国家码覆盖（TikTok 修复）也是按卡设置的。不逐卡保留的话，改任意一个开关都会把它们
 * 统一改写：NR 数组变成 [1,2]（连同厂商或新平台的取值一起丢掉），国家码覆盖被清空。
 *
 * 抽成纯函数是为了可单测（与 [ApplyReadbackRules] 同样的做法）。
 */
object PerSimOverrideRules {

    /**
     * 该卡写回的 NR 数组；null 表示照常写入本次请求的数组。
     *
     * 只在本次要写 NR 数组（5G 开启）、且该卡 5G 原本就开着时保留；
     * 该卡 5G 原本关着，说明这次是开启操作，照常写入请求的数组。
     */
    fun nrArrayToKeep(requestWritesNrArray: Boolean, current: IntArray?): IntArray? {
        if (!requestWritesNrArray || current == null) return null
        val hasNr = current.contains(CarrierConfigManager.CARRIER_NR_AVAILABILITY_NSA) ||
            current.contains(CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA)
        return current.takeIf { hasNr }
    }

    /**
     * 该卡写回的国家码覆盖；null 表示不写。
     *
     * 全部 SIM 模式不写国家码：本次请求没带国家码、而该卡原本有覆盖时，原样写回。
     */
    fun countryIsoToKeep(requestWritesCountryIso: Boolean, current: String?): String? =
        current?.takeIf { !requestWritesCountryIso && it.isNotBlank() }
}
