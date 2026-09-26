package io.github.vvb2060.ims.model

import java.util.Locale

/**
 * SIM 国家码（ISO）解析规则。
 *
 * 这是全应用**唯一**的 MCC -> ISO 映射来源，[io.github.vvb2060.ims.viewmodel.MainViewModel]
 * 直接委托到这里，不另建第二套映射。抽成纯函数对象是为了可单测。
 */
object CarrierIsoRules {
    fun normalizeMcc(raw: String): String = raw.filter { it.isDigit() }.take(3)

    fun normalizeIso(raw: String): String =
        raw.trim().lowercase(Locale.US).filter { it.isLetterOrDigit() }.take(8)

    /**
     * 判断 ISO 是否是 TikTok 修复写入的纯数字伪 ISO（例如 192、705）。
     *
     * 这类值只应由 TikTok 修复主动生成，绝不能作为「当前正常 ISO」被再次写回：
     * framework/cache 可能在修复关闭后仍短暂返回旧的数字 ISO，
     * 若把它当作正常值沿用，用户改动任何其他配置都会把伪 ISO 重新写回系统。
     */
    fun isNumericPseudoIso(iso: String): Boolean = iso.isNotBlank() && iso.all { it.isDigit() }

    /**
     * 由 MCC 推导国家 ISO。
     *
     * MCC 比 framework 返回的 countryIso 更可信：后者可能仍是上一次 TikTok 修复
     * 写入的数字伪 ISO。因此纯数字的 [fallbackIsoRaw] 一律不参与回退。
     *
     * @return 解析出的 ISO；无法确定时返回 null（此时不应写入任何 ISO override）。
     */
    fun resolveIsoByMcc(mccRaw: String, fallbackIsoRaw: String): String? {
        val mcc = normalizeMcc(mccRaw)
        val fallbackIso = normalizeIso(fallbackIsoRaw).takeUnless { isNumericPseudoIso(it) }.orEmpty()
        if (mcc.isBlank()) return fallbackIso.ifBlank { null }
        val mccInt = mcc.toIntOrNull()
        val iso = when {
            mcc == "460" -> "cn"
            mcc == "454" -> "hk"
            mcc == "466" -> "tw"
            mccInt != null && mccInt in 310..316 -> "us"
            mccInt != null && mccInt in 440..441 -> "jp"
            mccInt != null && mccInt in 234..235 -> "gb"
            mcc == "450" -> "kr"
            mcc == "525" -> "sg"
            else -> fallbackIso
        }
        return iso.ifBlank { null }
    }
}
