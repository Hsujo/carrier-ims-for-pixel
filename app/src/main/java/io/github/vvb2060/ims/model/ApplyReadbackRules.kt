package io.github.vvb2060.ims.model

import android.os.Build

/**
 * 写入后读回的校验规则。
 *
 * 从 MainViewModel 抽成纯函数对象是为了可单测（与 [CarrierIsoRules] 同样的做法）。
 * 「可校验项」必须与 `ImsModifier.buildBundle` 实际写入的内容一致：没写入的项读回的是
 * 运营商默认值，拿它来判断只会得到假的「不一致」，还会让写入后的等待白白拉长。
 */
object ApplyReadbackRules {

    /**
     * 读回是否已经体现了本次写入的全部可校验项。
     *
     * 没有任何可校验项时返回 false，调用方应先用 [hasVerifiableTarget] 区分这种情况。
     */
    fun confirms(
        readback: Map<Feature, FeatureValue>,
        requested: Map<Feature, FeatureValue>,
        resolvedCountryIso: String?,
        sdkInt: Int,
    ): Boolean {
        var verifiable = 0
        for ((feature, target) in requested) {
            if (!isVerifiableSwitch(feature, target, requested, sdkInt)) continue
            verifiable++
            if ((readback[feature]?.data as? Boolean) != true) return false
        }
        if (isNrModeVerifiable(requested)) {
            val requestedMode =
                NrMode.fromStorageKey(requested[Feature.NR_MODE]?.data as? String) ?: NrMode.DEFAULT
            val actualMode = NrMode.fromStorageKey(readback[Feature.NR_MODE]?.data as? String)
            verifiable++
            if (actualMode != requestedMode) return false
        }
        if (isIsoVerifiable(resolvedCountryIso, sdkInt)) {
            verifiable++
            val actualIso = (readback[Feature.COUNTRY_ISO]?.data as? String).orEmpty()
            if (!actualIso.equals(resolvedCountryIso, ignoreCase = true)) return false
        }
        // 没有任何可校验项时不能凭空判定成功。
        return verifiable > 0
    }

    /**
     * 本次写入里是否存在可校验项。没有时读回无从确认，也就不必等待重读。
     */
    fun hasVerifiableTarget(
        requested: Map<Feature, FeatureValue>,
        resolvedCountryIso: String?,
        sdkInt: Int,
    ): Boolean =
        requested.any { (feature, target) -> isVerifiableSwitch(feature, target, requested, sdkInt) } ||
            isNrModeVerifiable(requested) ||
            isIsoVerifiable(resolvedCountryIso, sdkInt)

    private fun isVerifiableSwitch(
        feature: Feature,
        target: FeatureValue,
        requested: Map<Feature, FeatureValue>,
        sdkInt: Int,
    ): Boolean {
        // 运营商名称当前不参与写入。
        if (feature == Feature.CARRIER_NAME) return false
        // ISO 与 TikTok 修复由 resolvedCountryIso 统一校验。
        if (feature == Feature.COUNTRY_ISO || feature == Feature.TIKTOK_NETWORK_FIX) return false
        // NR 模式单独校验（见下），且仅在 5G NR 启用时才会被写入。
        if (feature == Feature.NR_MODE) return false
        // 5G 信号阈值与 5G+ 图标写在 buildBundle 的 5G NR 分支里，5G NR 关闭时不会写入。
        if ((feature == Feature.FIVE_G_THRESHOLDS || feature == Feature.FIVE_G_PLUS_ICON) &&
            !isEnabled(requested, Feature.FIVE_G_NR)
        ) {
            return false
        }
        // VoNR 只在 Android 14+ 写入，更低版本读回的是运营商默认值。
        if (feature == Feature.VONR && sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return target.data as? Boolean ?: false
    }

    // 请求里的模式读不出（空串）时，写入的是系统原数组或默认模式，无从比对具体模式；
    // 此时只由 FIVE_G_NR 开关本身校验 5G 是否开着。
    private fun isNrModeVerifiable(requested: Map<Feature, FeatureValue>): Boolean =
        isEnabled(requested, Feature.FIVE_G_NR) &&
            NrMode.fromStorageKey(requested[Feature.NR_MODE]?.data as? String) != null

    // 国家码 override 同样只在 Android 14+ 写入。
    private fun isIsoVerifiable(resolvedCountryIso: String?, sdkInt: Int): Boolean =
        !resolvedCountryIso.isNullOrBlank() && sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private fun isEnabled(map: Map<Feature, FeatureValue>, feature: Feature): Boolean =
        (map[feature]?.data as? Boolean) == true
}
