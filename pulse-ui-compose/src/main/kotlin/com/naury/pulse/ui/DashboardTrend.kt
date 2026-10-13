package com.naury.pulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.HistogramBin
import com.naury.pulse.api.StatusFilter
import com.naury.pulse.api.TrafficBucket
import com.naury.pulse.api.TrafficStatistics
import java.util.Locale

private const val CLOCK_MINUTES_LENGTH = 5
private const val SELECTED_ALPHA = 0.12f

/** 시간에 따른 요청 수 또는 응답 시간(p50·p95). */
@Composable
internal fun TrendCard(stats: TrafficStatistics) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    SectionTitle(stringResource(R.string.pulse_chart_title))
    PulseCard {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = Spacing.m)) {
            listOf(R.string.pulse_chart_mode_requests, R.string.pulse_chart_mode_latency).forEachIndexed { index, label ->
                SegmentedButton(
                    selected = mode == index,
                    onClick = { mode = index },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    icon = {},
                ) { Text(stringResource(label)) }
            }
        }
        if (mode == 0) RequestsChart(stats.buckets) else LatencyTrend(stats.buckets)
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
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
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

/** 구간별 p50·p95 선. 끝난 요청이 없는 구간은 점을 찍지 않고 선을 끊는다. */
@Composable
private fun LatencyTrend(buckets: List<TrafficBucket>) {
    val p50Color = MaterialTheme.colorScheme.primary
    val p95Color = LocalStatusColors.current.slow
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = buckets.mapNotNull { it.p95Nanos }.maxOrNull()
    if (max == null) {
        Text(stringResource(R.string.pulse_stat_no_completed), style = MaterialTheme.typography.bodyMedium)
        return
    }
    val summary = stringResource(R.string.pulse_chart_latency_summary, Formatters.duration(max))
    Text(stringResource(R.string.pulse_chart_latency_max, Formatters.duration(max)), style = MaterialTheme.typography.bodyMedium)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(top = Spacing.s)
            .semantics { contentDescription = summary },
    ) {
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        drawSeries(buckets.map { it.p95Nanos }, max, p95Color)
        drawSeries(buckets.map { it.p50Nanos }, max, p50Color)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l), modifier = Modifier.padding(top = Spacing.xs)) {
        LegendItem(p50Color, "p50")
        LegendItem(p95Color, "p95")
    }
}

private fun DrawScope.drawSeries(values: List<Long?>, max: Long, color: Color) {
    val slot = size.width / values.size
    var previous: Offset? = null
    values.forEachIndexed { index, value ->
        if (value == null) {
            previous = null
            return@forEachIndexed
        }
        val point = Offset(slot * index + slot / 2, size.height - size.height * value / max)
        previous?.let { drawLine(color, it, point, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
        drawCircle(color, radius = 4.dp.toPx(), center = point)
        previous = point
    }
}

@Composable
internal fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
