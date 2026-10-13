package com.naury.pulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.StatsWindow
import com.naury.pulse.api.StatusFilter
import com.naury.pulse.api.TrafficBucket
import com.naury.pulse.api.TrafficStatistics

private const val PERCENT = 100.0

/** 개요 탭. 숫자 하나로 충분한 값은 타일로, 시간에 따른 양만 막대 차트로 보여 준다. */
@Composable
internal fun OverviewPane(
    inspector: PulseInspector,
    window: StatsWindow,
    onWindowChange: (StatsWindow) -> Unit,
    onOpenTraffic: (StatusFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = rememberLive(inspector, window) { inspector.loadStatistics(window) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        WindowSelector(window, onWindowChange)
        when (stats) {
            null -> Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> OverviewContent(stats, onOpenTraffic)
        }
    }
}

@Composable
private fun OverviewContent(stats: TrafficStatistics, onOpenTraffic: (StatusFilter) -> Unit) {
    val counts = stats.counts
    if (counts.total == 0) {
        Notice(stringResource(R.string.pulse_overview_empty))
        return
    }
    if (stats.sampleLimitReached) Notice(stringResource(R.string.pulse_overview_sample_limit, TrafficStatistics.MAX_SAMPLES))
    val errors = counts.clientError + counts.serverError + counts.failed
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.s)) {
        StatTile(stringResource(R.string.pulse_stat_requests), counts.total.toString(), Modifier.weight(1f)) {
            onOpenTraffic(StatusFilter.ALL)
        }
        StatTile(
            stringResource(R.string.pulse_stat_errors),
            String.format(java.util.Locale.ROOT, "%.1f%%", errors * PERCENT / counts.total),
            Modifier.weight(1f),
            detail = stringResource(R.string.pulse_stat_errors_detail, counts.clientError, counts.serverError, counts.failed),
        ) { onOpenTraffic(if (counts.serverError > 0) StatusFilter.SERVER_ERROR else StatusFilter.FAILED) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.s)) {
        val durations = stats.durations
        StatTile(
            stringResource(R.string.pulse_stat_p95),
            durations.p95Nanos?.let(Formatters::duration) ?: "—",
            Modifier.weight(1f),
            detail = durations.p50Nanos?.let {
                stringResource(R.string.pulse_stat_p95_detail, Formatters.duration(it), durations.sampleCount)
            } ?: stringResource(R.string.pulse_stat_no_completed),
        )
        StatTile(
            stringResource(R.string.pulse_stat_bytes),
            Formatters.bytes(stats.bytes.responseBytes),
            Modifier.weight(1f),
            detail = if (stats.bytes.unknownResponseSizeCount > 0) {
                stringResource(R.string.pulse_stat_bytes_unknown, stats.bytes.unknownResponseSizeCount)
            } else {
                null
            },
        )
    }
    SectionTitle(stringResource(R.string.pulse_chart_title))
    RequestsChart(stats.buckets)
    if (stats.slowestEndpoints.isNotEmpty()) {
        SectionTitle(stringResource(R.string.pulse_slowest_title))
        stats.slowestEndpoints.forEach { endpoint ->
            RankRow(
                "${endpoint.method} ${endpoint.path}",
                endpoint.host,
                stringResource(R.string.pulse_slowest_value, Formatters.duration(endpoint.p95Nanos), endpoint.count),
            )
        }
    }
    if (stats.topHosts.isNotEmpty()) {
        SectionTitle(stringResource(R.string.pulse_hosts_title))
        stats.topHosts.forEach { host ->
            RankRow(host.host, null, stringResource(R.string.pulse_hosts_value, host.count, host.errorCount))
        }
    }
}

@Composable
internal fun WindowSelector(window: StatsWindow, onChange: (StatsWindow) -> Unit) {
    val labels = mapOf(
        StatsWindow.LAST_5_MINUTES to R.string.pulse_window_5m,
        StatsWindow.LAST_HOUR to R.string.pulse_window_1h,
        StatsWindow.LAST_24_HOURS to R.string.pulse_window_24h,
        StatsWindow.ALL to R.string.pulse_window_all,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        StatsWindow.entries.forEach { option ->
            FilterChip(selected = option == window, onClick = {
                onChange(option)
            }, label = { Text(stringResource(labels.getValue(option))) })
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null, onClick: (() -> Unit)? = null) {
    val base = modifier
        .heightIn(min = 96.dp)
        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
    Column(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base)
            .semantics(mergeDescendants = true) {}
            .padding(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * 구간별 요청 수 막대. 오류는 같은 막대 아래쪽에 상태 색으로 쌓고 범례를 함께 둔다.
 * 막대를 누르면 그 구간의 값을 글자로 보여 주고, 스크린 리더에는 전체 요약을 읽어 준다.
 */
@Composable
private fun RequestsChart(buckets: List<TrafficBucket>) {
    if (buckets.isEmpty()) return
    var selected by rememberSaveable(buckets.size) { mutableIntStateOf(buckets.indexOfLast { it.count > 0 }) }
    val requestColor = MaterialTheme.colorScheme.primary
    val errorColor = LocalStatusColors.current.serverError
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val max = buckets.maxOf { it.count }.coerceAtLeast(1)
    val summary = stringResource(
        R.string.pulse_chart_summary,
        buckets.size,
        buckets.sumOf { it.count },
        buckets.maxOf { it.count },
        buckets.sumOf { it.errorCount },
    )
    buckets.getOrNull(selected)?.let { bucket ->
        Text(
            stringResource(
                R.string.pulse_chart_selected,
                Formatters.clockTime(bucket.startEpochMillis).take(CLOCK_MINUTES_LENGTH),
                Formatters.clockTime(bucket.endEpochMillis).take(CLOCK_MINUTES_LENGTH),
                bucket.count,
                bucket.errorCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(top = Spacing.s)
            .semantics { contentDescription = summary }
            .pointerInput(buckets) {
                detectTapGestures { offset -> selected = (offset.x / size.width * buckets.size).toInt().coerceIn(0, buckets.lastIndex) }
            },
    ) {
        drawBuckets(buckets, selected, max, BarColors(requestColor, errorColor, trackColor, surface))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l), modifier = Modifier.padding(top = Spacing.xs)) {
        LegendItem(requestColor, stringResource(R.string.pulse_chart_requests))
        LegendItem(errorColor, stringResource(R.string.pulse_chart_errors))
    }
}

private class BarColors(val request: Color, val error: Color, val track: Color, val surface: Color)

private fun DrawScope.drawBuckets(buckets: List<TrafficBucket>, selected: Int, max: Int, colors: BarColors) {
    val gap = 2.dp.toPx()
    val slot = size.width / buckets.size
    val barWidth = slot - gap
    val radius = CornerRadius(4.dp.toPx())
    drawLine(colors.track, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
    buckets.forEachIndexed { index, bucket ->
        if (bucket.count == 0) return@forEachIndexed
        val left = index * slot
        val height = size.height * bucket.count / max
        drawRoundRect(colors.request, Offset(left, size.height - height), Size(barWidth, height), radius)
        if (bucket.errorCount > 0) {
            val errorHeight = size.height * bucket.errorCount / max
            // 오류 부분과 요청 부분 사이에 2dp 간격을 두어 색이 붙어 보이지 않게 한다.
            drawRect(colors.surface, Offset(left, size.height - errorHeight - gap), Size(barWidth, gap))
            drawRect(colors.error, Offset(left, size.height - errorHeight), Size(barWidth, errorHeight))
        }
        if (index == selected) drawRect(colors.request.copy(alpha = SELECTED_ALPHA), Offset(left, 0f), Size(barWidth, size.height))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RankRow(title: String, subtitle: String?, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MonospaceStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val CLOCK_MINUTES_LENGTH = 5
private const val SELECTED_ALPHA = 0.12f
