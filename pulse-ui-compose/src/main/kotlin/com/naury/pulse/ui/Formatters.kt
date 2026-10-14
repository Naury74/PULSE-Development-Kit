package com.naury.pulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

internal object Formatters {
    private const val NANOS_PER_MILLI = 1_000_000.0
    private const val MILLIS_PER_SECOND = 1_000.0
    private const val MILLIS_PER_MINUTE = 60_000.0
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val BYTES_PER_KILOBYTE = 1_024.0
    private const val SMALL_VALUE_LIMIT = 10.0

    fun duration(nanos: Long): String {
        val millis = nanos / NANOS_PER_MILLI
        return when {
            millis < 1 -> String.format(Locale.ROOT, "%.2f ms", millis)
            millis < MILLIS_PER_SECOND -> "${millis.roundToLong()} ms"
            millis < MILLIS_PER_MINUTE -> String.format(Locale.ROOT, "%.2f s", millis / MILLIS_PER_SECOND)
            else -> clock((millis / MILLIS_PER_SECOND).roundToLong())
        }
    }

    /** 1분 이상은 `7m 15s`, 1시간 이상은 `1h 05m`으로 줄여 쓴다. */
    private fun clock(seconds: Long): String = if (seconds < SECONDS_PER_HOUR) {
        "${seconds / SECONDS_PER_MINUTE}m ${seconds % SECONDS_PER_MINUTE}s"
    } else {
        "${seconds / SECONDS_PER_HOUR}h " + "%02dm".format(Locale.ROOT, seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE)
    }

    fun bytes(count: Long): String {
        if (count < BYTES_PER_KILOBYTE) return "$count B"
        var value = count / BYTES_PER_KILOBYTE
        val units = listOf("KB", "MB", "GB")
        var unit = 0
        while (value >= BYTES_PER_KILOBYTE && unit < units.lastIndex) {
            value /= BYTES_PER_KILOBYTE
            unit++
        }
        val pattern = if (value < SMALL_VALUE_LIMIT) "%.1f %s" else "%.0f %s"
        return String.format(Locale.ROOT, pattern, value, units[unit])
    }

    fun clockTime(epochMillis: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date(epochMillis))

    fun dateTime(epochMillis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(epochMillis))
}

/** 화면 설정이 바뀌면 다시 그리도록 Compose 설정에서 읽는 현재 로케일. */
@Composable
@ReadOnlyComposable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
