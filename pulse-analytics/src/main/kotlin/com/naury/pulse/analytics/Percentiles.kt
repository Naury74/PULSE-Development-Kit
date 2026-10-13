package com.naury.pulse.analytics

import kotlin.math.ceil

/** nearest-rank 백분위수. [sorted]는 오름차순이어야 하고, 비어 있으면 null이다. */
internal fun percentile(sorted: List<Long>, percent: Int): Long? {
    if (sorted.isEmpty()) return null
    val rank = ceil(percent / PERCENT * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

private const val PERCENT = 100.0
