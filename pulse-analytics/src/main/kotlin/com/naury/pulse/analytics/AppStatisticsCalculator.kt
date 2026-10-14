package com.naury.pulse.analytics

import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.AppStatistics
import com.naury.pulse.api.CountEntry
import com.naury.pulse.api.DayUsage
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.LogLevel
import com.naury.pulse.api.MillisPercentiles
import com.naury.pulse.api.ScreenPerformance
import com.naury.pulse.api.SessionSummary
import java.util.Calendar
import java.util.TimeZone

/** 앱 상태 집계 재료. 저장소가 채운다. */
public class AppStatisticsInput(
    public val sessions: List<SessionSummary>,
    public val events: List<AppEvent>,
    public val logLevels: Map<LogLevel, Int>,
    public val requestTimes: List<Long>,
)

/**
 * 세션·이벤트로 [AppStatistics]를 만든다. 상태가 없어 여러 스레드에서 함께 써도 된다.
 *
 * 실행 시간은 세션마다 시작부터 마지막으로 살아 있던 시각까지이며, 기간 밖으로 나간 부분은 잘라 낸다.
 * 날짜는 [timeZone] 기준 0시로 나눈다.
 */
public object AppStatisticsCalculator {
    private const val P50 = 50
    private const val P95 = 95
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val MAX_DAYS = 120
    private val crashReasons = setOf("CRASH", "CRASH_NATIVE", "ANR")

    @JvmStatic
    public fun compute(input: AppStatisticsInput, from: Long?, to: Long, timeZone: TimeZone = TimeZone.getDefault()): AppStatistics {
        val sessions = input.sessions
        val events = input.events
        val runtimes = sessions.map { clippedRuntime(it, from, to) }
        val startups = events.filter { it.kind == EventKind.STARTUP }.mapNotNull { it.durationMillis }.sorted()
        val memory = events.filter { it.kind == EventKind.MEMORY }.mapNotNull { it.value }
        return AppStatistics(
            sessions = sessions.size,
            crashFreeSessions = sessions.count { !it.crashed && it.exitReason !in crashReasons },
            runtimeMillis = runtimes.sum(),
            foregroundMillis = sessions.sumOf { it.foregroundMillis },
            averageSessionMillis = if (sessions.isEmpty()) null else runtimes.sum() / sessions.size,
            crashes = events.count(::isCrash),
            errors = events.count { it.kind == EventKind.ERROR },
            mainThreadBlocks = events.count { it.kind == EventKind.MAIN_THREAD_BLOCKED },
            exitReasons = events.filter { it.kind == EventKind.EXIT }.mapNotNull { it.tag }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { CountEntry(it.key, it.value) },
            startup = MillisPercentiles(startups.size, percentile(startups, P50), percentile(startups, P95), startups.lastOrNull()),
            screens = screens(events),
            memoryPeakBytes = memory.maxOrNull(),
            memoryAverageBytes = if (memory.isEmpty()) null else memory.sum() / memory.size,
            logLevels = input.logLevels,
            days = days(sessions, events, input.requestTimes, from, to, timeZone),
            markers = events.count { it.kind == EventKind.MARKER },
        )
    }

    private fun clippedRuntime(session: SessionSummary, from: Long?, to: Long): Long {
        val start = maxOf(session.startedAtEpochMillis, from ?: Long.MIN_VALUE)
        val end = minOf(session.lastSeenEpochMillis, to)
        return (end - start).coerceAtLeast(0)
    }

    private fun screens(events: List<AppEvent>): List<ScreenPerformance> {
        val loads = events.filter { it.kind == EventKind.SCREEN_LOAD && it.screen != null }.groupBy { it.screen!! }
        val frames = events.filter { it.kind == EventKind.FRAMES && it.screen != null }.groupBy { it.screen!! }
        return (loads.keys + frames.keys).map { screen ->
            val durations = loads[screen].orEmpty().mapNotNull { it.durationMillis }.sorted()
            val counts = frames[screen].orEmpty().map { FrameCounts.parse(it.detail) }
            ScreenPerformance(
                screen = screen,
                loads = durations.size,
                loadP50Millis = percentile(durations, P50),
                loadP95Millis = percentile(durations, P95),
                frames = counts.sumOf { it.total },
                slowFrames = counts.sumOf { it.slow },
                frozenFrames = counts.sumOf { it.frozen },
            )
        }.sortedByDescending { it.loads + it.frames }
    }

    @Suppress("LongParameterList")
    private fun days(
        sessions: List<SessionSummary>,
        events: List<AppEvent>,
        requests: List<Long>,
        from: Long?,
        to: Long,
        zone: TimeZone,
    ): List<DayUsage> {
        val first = from ?: sessions.minOfOrNull { it.startedAtEpochMillis } ?: return emptyList()
        val days = generateSequence(startOfDay(first, zone)) { it + DAY_MILLIS }.takeWhile { it <= to }.take(MAX_DAYS).toList()
        return days.map { day ->
            val end = day + DAY_MILLIS
            val inDay = sessions.filter { it.startedAtEpochMillis < end && it.lastSeenEpochMillis >= day }
            DayUsage(
                dayStartEpochMillis = day,
                sessions = inDay.size,
                runtimeMillis = inDay.sumOf { clippedRuntime(it, day, end) },
                foregroundMillis = inDay.filter { it.startedAtEpochMillis >= day }.sumOf { it.foregroundMillis },
                crashes = events.count { isCrash(it) && it.timestampEpochMillis in day until end },
                requests = requests.count { it in day until end },
            )
        }
    }

    private fun isCrash(event: AppEvent): Boolean =
        event.kind == EventKind.CRASH || (event.kind == EventKind.EXIT && event.tag in crashReasons)

    private fun startOfDay(epochMillis: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        timeInMillis = epochMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/** [EventKind.FRAMES] 이벤트의 상세(`total=..;slow=..;frozen=..`). 읽을 수 없는 값은 0. */
public class FrameCounts(public val total: Long, public val slow: Long, public val frozen: Long) {
    public fun format(): String = "total=$total;slow=$slow;frozen=$frozen"

    public companion object {
        @JvmStatic
        public fun parse(detail: String?): FrameCounts {
            val values = detail.orEmpty().split(';').associate { it.substringBefore('=') to (it.substringAfter('=').toLongOrNull() ?: 0L) }
            return FrameCounts(values["total"] ?: 0, values["slow"] ?: 0, values["frozen"] ?: 0)
        }
    }
}
