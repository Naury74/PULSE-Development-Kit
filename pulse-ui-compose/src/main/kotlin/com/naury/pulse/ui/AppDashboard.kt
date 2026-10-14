package com.naury.pulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.AppStatistics
import com.naury.pulse.api.LogLevel
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val NANOS_PER_MILLI = 1_000_000L
private const val MAX_SESSIONS = 10

/** 대시보드의 앱 영역. 실행 시간·크래시·시작 시간·화면 성능·메모리·로그를 기간 기준으로 보여 준다. */
@Composable
internal fun AppDashboard(inspector: PulseInspector, from: Long?, to: Long?, onRangeChange: (TimeRange) -> Unit) {
    val stats = rememberLive(inspector, from to to, events = true) { inspector.loadAppStatistics(from, to) ?: EMPTY }
    val sessions by produceState(emptyList<SessionSummary>(), inspector, from, to) {
        value = inspector.loadSessions(from, to, MAX_SESSIONS)
    }
    when {
        stats == null -> Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) { PulseLoading() }

        stats.sessions == 0 -> Box(Modifier.padding(top = Spacing.m)) { Notice(stringResource(R.string.pulse_app_empty)) }

        else -> Column {
            AppHealth(stats)
            AppKpis(stats)
            UsageByDay(stats)
            ScreenPerformanceCard(stats)
            LogsAndExits(stats)
            SessionsCard(sessions, onRangeChange)
            if (sessions.size >= 2) SessionCompareButton(inspector, sessions)
        }
    }
}

private val EMPTY = AppStatistics(
    0, 0, 0, 0, null, 0, 0, 0, emptyList(),
    com.naury.pulse.api.MillisPercentiles(0, null, null, null), emptyList(), null, null, emptyMap(), emptyList(), 0,
)

private fun millis(value: Long?): String = value?.let { Formatters.duration(it * NANOS_PER_MILLI) } ?: "—"

@Composable
private fun AppHealth(stats: AppStatistics) {
    val colors = LocalStatusColors.current
    val crashed = stats.sessions - stats.crashFreeSessions
    val rate = percentText(stats.crashFreeSessions, stats.sessions)
    Box(Modifier.padding(top = Spacing.m)) {
        PulseCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                DonutChart(
                    segments = listOf(stats.crashFreeSessions to colors.success, crashed to colors.serverError),
                    description = stringResource(R.string.pulse_app_crash_free_description, rate, stats.sessions),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(rate, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.pulse_app_crash_free),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Metric(stringResource(R.string.pulse_app_runs), stats.sessions.toString())
                    Metric(stringResource(R.string.pulse_app_runtime), millis(stats.runtimeMillis))
                    Metric(
                        stringResource(R.string.pulse_app_on_screen),
                        millis(stats.foregroundMillis) + " · " +
                            percentText(
                                (stats.foregroundMillis / MILLIS_PER_SECOND).toInt(),
                                (stats.runtimeMillis / MILLIS_PER_SECOND).toInt(),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppKpis(stats: AppStatistics) {
    val status = LocalStatusColors.current
    val tiles = listOf(
        Triple(stringResource(R.string.pulse_app_average_run), millis(stats.averageSessionMillis), null),
        Triple(
            stringResource(R.string.pulse_app_cold_start),
            millis(stats.startup.p50),
            stringResource(R.string.pulse_app_p95, millis(stats.startup.p95)),
        ),
        Triple(
            stringResource(R.string.pulse_app_crashes),
            stats.crashes.toString(),
            stats.exitReasons.take(2).joinToString {
                "${it.key} ${it.count}"
            },
        ),
        Triple(stringResource(R.string.pulse_app_errors), stats.errors.toString(), null),
        Triple(stringResource(R.string.pulse_app_freezes), stats.mainThreadBlocks.toString(), null),
        Triple(
            stringResource(R.string.pulse_app_memory),
            stats.memoryPeakBytes?.let(Formatters::bytes) ?: "—",
            stats.memoryAverageBytes?.let {
                stringResource(R.string.pulse_app_memory_average, Formatters.bytes(it))
            },
        ),
    )
    Column(Modifier.padding(top = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                row.forEach { (label, value, detail) ->
                    val alert = (label == stringResource(R.string.pulse_app_crashes) && stats.crashes > 0) ||
                        (label == stringResource(R.string.pulse_app_errors) && stats.errors > 0)
                    PulseCard(Modifier.weight(1f)) {
                        Metric(label, value, valueColor = if (alert) status.serverError else androidx.compose.ui.graphics.Color.Unspecified)
                        detail?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 날짜별 실행 시간 막대. 크래시가 있던 날은 막대 위에 빨간 점을 찍는다. */
@Composable
private fun UsageByDay(stats: AppStatistics) {
    if (stats.days.isEmpty()) return
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val crash = LocalStatusColors.current.serverError
    val max = stats.days.maxOf { it.runtimeMillis }.coerceAtLeast(1)
    val format = SimpleDateFormat("MM.dd", currentLocale())
    SectionTitle(stringResource(R.string.pulse_app_usage_by_day))
    PulseCard {
        val summary = stringResource(R.string.pulse_app_usage_summary, stats.days.size, millis(stats.runtimeMillis))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = summary },
        ) {
            val slot = size.width / stats.days.size
            // 날이 적으면 막대가 카드를 다 채우지 않도록 폭을 줄이고 칸 가운데에 둔다.
            val width = minOf(slot - 2.dp.toPx(), MAX_BAR_WIDTH.toPx())
            val inset = (slot - width) / 2
            drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            stats.days.forEachIndexed { index, day ->
                if (day.runtimeMillis == 0L) return@forEachIndexed
                val height = size.height * BAR_HEADROOM * day.runtimeMillis / max
                drawRoundRect(bar, Offset(index * slot + inset, size.height - height), Size(width, height), CornerRadius(3.dp.toPx()))
                if (day.crashes > 0) drawCircle(crash, 3.dp.toPx(), Offset(index * slot + slot / 2, size.height - height - 6.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
            Text(
                format.format(Date(stats.days.first().dayStartEpochMillis)),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            Text(format.format(Date(stats.days.last().dayStartEpochMillis)), style = MaterialTheme.typography.labelSmall)
        }
        stats.days.filter { it.sessions > 0 }.takeLast(MAX_DAY_ROWS).asReversed().forEach { day ->
            Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(format.format(Date(day.dayStartEpochMillis)), style = MonospaceStyle, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.pulse_app_day_row, day.sessions, millis(day.runtimeMillis), day.requests),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (day.crashes > 0) {
                    Text(
                        stringResource(R.string.pulse_app_day_crashes, day.crashes),
                        style = MaterialTheme.typography.bodySmall,
                        color = crash,
                    )
                }
            }
        }
    }
}

private const val MAX_DAY_ROWS = 7
private val MAX_BAR_WIDTH = 28.dp

@Composable
private fun ScreenPerformanceCard(stats: AppStatistics) {
    if (stats.screens.isEmpty()) return
    SectionTitle(stringResource(R.string.pulse_app_screens))
    PulseCard {
        stats.screens.take(MAX_SCREENS).forEachIndexed { index, screen ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(screen.screen, style = MonospaceStyle, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(
                            R.string.pulse_app_screen_detail,
                            screen.loads,
                            millis(screen.loadP50Millis),
                            millis(screen.loadP95Millis),
                            screen.frames,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (screen.frames > 0) {
                    val slow = screen.slowFrames.toFloat() / screen.frames
                    RingGauge(slow, if (slow > SLOW_WARNING) LocalStatusColors.current.slow else MaterialTheme.colorScheme.primary) {
                        Text("${(slow * PERCENT).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text(
            stringResource(R.string.pulse_app_screens_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val MAX_SCREENS = 10
private const val SLOW_WARNING = 0.1f
private const val PERCENT = 100

@Composable
private fun LogsAndExits(stats: AppStatistics) {
    val total = stats.logLevels.values.sum()
    if (total == 0 && stats.exitReasons.isEmpty()) return
    SectionTitle(stringResource(R.string.pulse_app_logs_exits))
    PulseCard {
        LogLevel.entries.reversed().forEach { level ->
            val count = stats.logLevels[level] ?: 0
            if (count > 0) ProportionRow(level.name, count, total, levelColor(level))
        }
        if (stats.exitReasons.isNotEmpty()) {
            Text(
                stringResource(R.string.pulse_app_exit_reasons),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = Spacing.m),
            )
            stats.exitReasons.forEach { Text("${it.key} × ${it.count}", style = MonospaceStyle) }
        }
    }
}

@Composable
private fun SessionsCard(sessions: List<SessionSummary>, onRangeChange: (TimeRange) -> Unit) {
    if (sessions.isEmpty()) return
    val format = SimpleDateFormat("MM.dd HH:mm", currentLocale())
    SectionTitle(stringResource(R.string.pulse_app_recent_runs))
    PulseCard {
        sessions.forEachIndexed { index, session ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val label = format.format(Date(session.startedAtEpochMillis))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onRangeChange(TimeRange.ofSession(session, label)) }
                    .padding(vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text(label, style = MonospaceStyle, modifier = Modifier.weight(1f))
                Text(millis(session.lastSeenEpochMillis - session.startedAtEpochMillis), style = MaterialTheme.typography.bodySmall)
                when {
                    session.current -> Pill(stringResource(R.string.pulse_range_session_current), MaterialTheme.colorScheme.primary)
                    session.crashed -> Pill(session.exitReason ?: "CRASH", LocalStatusColors.current.serverError)
                    else -> session.exitReason?.let { Pill(it, MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Text(
            stringResource(R.string.pulse_app_runs_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val MILLIS_PER_SECOND = 1000L

/** 가장 긴 막대도 위에 표시할 자리를 남기도록 높이의 이 비율까지만 쓴다. */
private const val BAR_HEADROOM = 0.9f
