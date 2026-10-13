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

/** 소요 시간 분포와 백분위수. */
@Composable
internal fun LatencyCard(stats: TrafficStatistics) {
    if (stats.latencyHistogram.isEmpty()) return
    SectionTitle(stringResource(R.string.pulse_latency_title))
    PulseCard {
        val durations = stats.durations
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            listOf("p50" to durations.p50Nanos, "p75" to durations.p75Nanos, "p90" to durations.p90Nanos, "p99" to durations.p99Nanos)
                .forEach { (name, value) -> Metric(name, value?.let(Formatters::duration) ?: "—", Modifier.weight(1f)) }
        }
        val total = stats.latencyHistogram.sumOf { it.count }.coerceAtLeast(1)
        val bars = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary,
            LocalStatusColors.current.slow,
            LocalStatusColors.current.slow,
            LocalStatusColors.current.serverError,
        )
        Column(Modifier.padding(top = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            stats.latencyHistogram.forEachIndexed { index, bin ->
                ProportionRow(
                    binLabel(bin, stats.latencyHistogram.getOrNull(index - 1)),
                    bin.count,
                    total,
                    bars.getOrElse(index) {
                        bars.last()
                    },
                )
            }
        }
    }
}

@Composable
private fun binLabel(bin: HistogramBin, previous: HistogramBin?): String {
    val upper = bin.upperBoundNanos
    return if (upper == null) {
        "> " + Formatters.duration(previous?.upperBoundNanos ?: 0)
    } else {
        "≤ " + Formatters.duration(upper)
    }
}

/** 비율 막대와 개수·비율. 이름은 따로 둔다. */
@Composable
internal fun ProportionBar(count: Int, total: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Bar(if (total == 0) 0f else count.toFloat() / total, color, Modifier.weight(1f))
        Text(
            "$count · ${percentText(count, total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Bar(fraction: Float, color: Color, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.height(10.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (fraction > 0f) drawRoundRect(color, size = Size(size.width * fraction, size.height), cornerRadius = radius)
    }
}

/** 이름, 비율 막대, 개수·비율을 한 줄에 둔다. */
@Composable
internal fun ProportionRow(label: String, count: Int, total: Int, color: Color, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(label, style = MonospaceStyle, modifier = Modifier.width(88.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val fraction = if (total == 0) 0f else count.toFloat() / total
        val track = MaterialTheme.colorScheme.surfaceVariant
        Canvas(
            Modifier
                .weight(1f)
                .height(10.dp),
        ) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            if (fraction > 0f) drawRoundRect(color, size = Size(size.width * fraction, size.height), cornerRadius = radius)
        }
        Text(
            trailing ?: "$count · ${percentText(count, total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 결과 구성(성공·4xx·5xx·실패), 상태 코드, 실패 종류. 구성 줄을 누르면 해당 목록으로 간다. */
@Composable
internal fun StatusCard(stats: TrafficStatistics, onOpenTraffic: (StatusFilter) -> Unit) {
    val counts = stats.counts
    val colors = LocalStatusColors.current
    SectionTitle(stringResource(R.string.pulse_status_title))
    PulseCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            listOf(
                Triple(R.string.pulse_filter_success, counts.success, colors.success) to StatusFilter.SUCCESS,
                Triple(R.string.pulse_filter_client_error, counts.clientError, colors.clientError) to StatusFilter.CLIENT_ERROR,
                Triple(R.string.pulse_filter_server_error, counts.serverError, colors.serverError) to StatusFilter.SERVER_ERROR,
                Triple(R.string.pulse_filter_failed, counts.failed, colors.failure) to StatusFilter.FAILED,
            ).forEach { (row, filter) ->
                ProportionRow(
                    stringResource(row.first),
                    row.second,
                    counts.total,
                    row.third,
                    modifier = Modifier.clickable(role = Role.Button) { onOpenTraffic(filter) },
                )
            }
        }
        if (stats.statusCodes.isNotEmpty()) {
            Text(
                stringResource(R.string.pulse_status_codes),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs),
            )
            FlowPills(stats.statusCodes.map { entry -> "${entry.key} × ${entry.count}" to statusCodeColor(entry.key.toIntOrNull() ?: 0) })
        }
        if (stats.failureTypes.isNotEmpty()) {
            Text(
                stringResource(R.string.pulse_status_failures),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs),
            )
            FlowPills(stats.failureTypes.map { "${it.key} × ${it.count}" to colors.failure })
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowPills(items: List<Pair<String, Color>>) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        items.forEach { (text, color) -> Pill(text, color) }
    }
}
