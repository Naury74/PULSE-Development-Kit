package com.naury.pulse.analytics

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.LogLevel
import com.naury.pulse.api.SessionSummary
import java.util.TimeZone
import org.junit.Test

class AppStatisticsCalculatorTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 24L * 60 * 60 * 1000

    private fun event(
        kind: EventKind,
        at: Long,
        screen: String? = null,
        duration: Long? = null,
        tag: String? = null,
        detail: String? = null,
        value: Long? = null,
    ) = AppEvent("$kind$at", "s", at, kind, null, tag, kind.name, detail, screen, null, duration, value)

    @Test
    fun `실행 시간·크래시 없는 세션·시작 시간·화면·날짜별 사용을 낸다`() {
        val sessions = listOf(
            SessionSummary(
                "a",
                startedAtEpochMillis = 1_000,
                lastSeenEpochMillis = 61_000,
                foregroundMillis = 30_000,
                exitReason = null,
                crashed = false,
                current = false,
            ),
            SessionSummary(
                "b",
                startedAtEpochMillis = day + 1_000,
                lastSeenEpochMillis = day + 11_000,
                foregroundMillis = 5_000,
                exitReason = "CRASH",
                crashed = true,
                current = false,
            ),
        )
        val events = listOf(
            event(EventKind.STARTUP, 1_100, duration = 400),
            event(EventKind.STARTUP, day + 1_100, duration = 900),
            event(EventKind.SCREEN_LOAD, 1_200, screen = "Home", duration = 120),
            event(EventKind.FRAMES, 30_000, screen = "Home", detail = "total=100;slow=10;frozen=1"),
            event(EventKind.CRASH, day + 10_000),
            event(EventKind.EXIT, day + 10_500, tag = "LOW_MEMORY"),
            event(EventKind.MEMORY, 5_000, value = 100),
            event(EventKind.MEMORY, 6_000, value = 300),
        )
        val input = AppStatisticsInput(sessions, events, mapOf(LogLevel.ERROR to 2), listOf(2_000, day + 2_000, day + 3_000))

        val stats = AppStatisticsCalculator.compute(input, from = 0, to = 2 * day - 1, timeZone = utc)

        assertThat(stats.sessions).isEqualTo(2)
        assertThat(stats.crashFreeSessions).isEqualTo(1)
        assertThat(stats.runtimeMillis).isEqualTo(70_000)
        assertThat(stats.averageSessionMillis).isEqualTo(35_000)
        assertThat(stats.crashes).isEqualTo(1)
        assertThat(stats.startup.p50).isEqualTo(400)
        assertThat(stats.startup.p95).isEqualTo(900)
        val home = stats.screens.single()
        assertThat(home.loads).isEqualTo(1)
        assertThat(home.slowFrames).isEqualTo(10)
        assertThat(stats.memoryPeakBytes).isEqualTo(300)
        assertThat(stats.exitReasons.single().key).isEqualTo("LOW_MEMORY")
        assertThat(stats.days.map { it.sessions }).containsExactly(1, 1).inOrder()
        assertThat(stats.days.map { it.requests }).containsExactly(1, 2).inOrder()
        assertThat(stats.days[1].crashes).isEqualTo(1)
    }
}
