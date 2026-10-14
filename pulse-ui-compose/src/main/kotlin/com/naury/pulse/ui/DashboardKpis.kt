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

private const val PERCENT = 100.0

internal fun percentText(part: Int, total: Int): String =
    if (total == 0) "—" else String.format(Locale.ROOT, "%.1f%%", part * PERCENT / total)

/** 핵심 지표 타일. 오류 타일은 누르면 오류 요청 목록으로 간다. */
@Composable
internal fun KpiGrid(stats: TrafficStatistics, columns: Int, onOpenTraffic: (StatusFilter) -> Unit) {
    val tiles = kpis(stats, onOpenTraffic)
    Column(Modifier.padding(top = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                row.forEach { KpiTile(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun kpis(stats: TrafficStatistics, onOpenTraffic: (StatusFilter) -> Unit): List<Kpi> =
    trafficKpis(stats, onOpenTraffic) + resourceKpis(stats)

@Composable
private fun trafficKpis(stats: TrafficStatistics, onOpenTraffic: (StatusFilter) -> Unit): List<Kpi> {
    val counts = stats.counts
    val errors = counts.clientError + counts.serverError + counts.failed
    val status = LocalStatusColors.current
    return listOf(
        Kpi(
            stringResource(R.string.pulse_stat_requests),
            counts.total.toString(),
            stats.requestsPerMinute?.let { stringResource(R.string.pulse_kpi_per_minute, String.format(Locale.ROOT, "%.1f", it)) },
        ) { onOpenTraffic(StatusFilter.ALL) },
        Kpi(
            stringResource(R.string.pulse_stat_errors),
            percentText(errors, counts.total),
            stringResource(R.string.pulse_stat_errors_detail, counts.clientError, counts.serverError, counts.failed),
            if (errors > 0) status.serverError else Color.Unspecified,
            ring = fraction(errors, counts.total),
            ringColor = status.serverError,
        ) { onOpenTraffic(if (counts.serverError > 0) StatusFilter.SERVER_ERROR else StatusFilter.FAILED) },
        Kpi(
            stringResource(R.string.pulse_stat_p95),
            stats.durations.p95Nanos?.let(Formatters::duration) ?: "—",
            stats.durations.p50Nanos?.let {
                stringResource(
                    R.string.pulse_kpi_p50_p99,
                    Formatters.duration(it),
                    stats.durations.p99Nanos?.let(Formatters::duration) ?: "—",
                )
            } ?: stringResource(R.string.pulse_stat_no_completed),
        ),
        Kpi(
            stringResource(R.string.pulse_kpi_ttfb),
            stats.timeToFirstByte.p95Nanos?.let(Formatters::duration) ?: "—",
            stats.timeToFirstByte.p50Nanos?.let { stringResource(R.string.pulse_kpi_p50, Formatters.duration(it)) },
        ),
    )
}

@Composable
private fun resourceKpis(stats: TrafficStatistics): List<Kpi> {
    val counts = stats.counts
    val status = LocalStatusColors.current
    val connections = stats.connections
    val connectionTotal = connections.reused + connections.newConnections
    return listOf(
        Kpi(
            stringResource(R.string.pulse_stat_bytes),
            Formatters.bytes(stats.bytes.responseBytes),
            stringResource(R.string.pulse_kpi_sent, Formatters.bytes(stats.bytes.requestBytes)),
        ),
        Kpi(
            stringResource(R.string.pulse_kpi_reuse),
            percentText(connections.reused, connectionTotal),
            stringResource(R.string.pulse_kpi_reuse_detail, connections.reused, connectionTotal),
            ring = fraction(connections.reused, connectionTotal),
            ringColor = MaterialTheme.colorScheme.primary,
        ),
        Kpi(
            stringResource(R.string.pulse_kpi_cache),
            (connections.cacheHits + connections.conditionalHits).toString(),
            stringResource(R.string.pulse_kpi_cache_detail, connections.cacheHits, connections.conditionalHits),
            ring = fraction(
                connections.cacheHits + connections.conditionalHits,
                connections.cacheHits + connections.conditionalHits + connections.network,
            ),
            ringColor = status.success,
        ),
        Kpi(
            stringResource(R.string.pulse_kpi_in_flight),
            counts.inFlight.toString(),
            stats.durations.maxNanos?.let { stringResource(R.string.pulse_kpi_max, Formatters.duration(it)) },
        ),
    )
}

private class Kpi(
    val label: String,
    val value: String,
    val detail: String? = null,
    val valueColor: Color = Color.Unspecified,
    /** 0..1이면 오른쪽에 고리로 보여 준다. 모수가 0이면 null. */
    val ring: Float? = null,
    val ringColor: Color = Color.Unspecified,
    val onClick: (() -> Unit)? = null,
)

private fun fraction(part: Int, total: Int): Float? = if (total == 0) null else part.toFloat() / total

@Composable
private fun KpiTile(kpi: Kpi, modifier: Modifier) {
    val base = modifier
        .heightIn(min = 96.dp)
        .clip(CardShape)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
    Row(
        (kpi.onClick?.let { base.clickable(role = Role.Button, onClick = it) } ?: base)
            .semantics(mergeDescendants = true) {}
            .padding(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KpiText(kpi, Modifier.weight(1f))
        kpi.ring?.let { RingGauge(it, kpi.ringColor, Modifier.padding(start = Spacing.s)) }
    }
}

@Composable
private fun KpiText(kpi: Kpi, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(kpi.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(
            kpi.value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = kpi.valueColor,
            maxLines = 1,
        )
        kpi.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
