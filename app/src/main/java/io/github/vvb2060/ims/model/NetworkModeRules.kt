package io.github.vvb2060.ims.model

/** User network preferences only; carrier/power restrictions remain managed by Android. */
object NetworkModeRules {
    // TelephonyManager.NETWORK_TYPE_BITMASK_NR (NETWORK_TYPE_NR = 20).
    const val NR_BIT: Long = 1L shl 19

    fun is5gAllowed(mask: Long): Boolean = mask and NR_BIT != 0L

    fun toggle5g(mask: Long): Long {
        require(mask != 0L) { "Empty user network preference" }
        val target = mask xor NR_BIT
        require(target != 0L) { "Cannot disable the only allowed network type" }
        return target
    }
}
