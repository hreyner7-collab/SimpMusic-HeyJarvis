package com.maxrave.simpmusic.utils

/**
 * Default 2-core Windows affinity mask (CPU 0 + CPU 1 = 0b11).
 */
const val DEFAULT_TWO_CORE_AFFINITY_MASK: Long = 0x3L

/**
 * Selects up to 2 available logical processor bits from a Windows [systemMask].
 *
 * - If [systemMask] is `0L`, falls back to [DEFAULT_TWO_CORE_AFFINITY_MASK] (`0x3L`).
 * - If [systemMask] has 1 set bit (e.g. single-core VM), returns that 1 bit so
 *   `SetProcessAffinityMask` never fails with `ERROR_INVALID_PARAMETER`.
 * - Otherwise returns the mask containing the lowest 2 set bits of [systemMask]
 *   (which is `0x3L` on standard multi-core Windows systems where CPU 0 & CPU 1 are available).
 * - Handles all 64 bits safely, including bit 63 (`Long.MIN_VALUE`).
 */
fun selectTwoCoreMask(systemMask: Long): Long {
    if (systemMask == 0L) return DEFAULT_TWO_CORE_AFFINITY_MASK
    var remaining = systemMask
    var selected = 0L
    var picked = 0
    while (remaining != 0L && picked < 2) {
        val lowestBit = remaining and -remaining
        selected = selected or lowestBit
        remaining = remaining and (remaining - 1L)
        picked++
    }
    return if (selected != 0L) selected else DEFAULT_TWO_CORE_AFFINITY_MASK
}
