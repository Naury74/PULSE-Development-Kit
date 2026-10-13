package com.naury.pulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.TimingPhase
import com.naury.pulse.api.TimingPhaseType
import com.naury.pulse.api.TransactionDetail

/** 관찰한 단계만 막대로 그린다. 끝을 관찰하지 못한 단계는 길이를 추정하지 않고 시작점만 표시한다. */
@Composable
internal fun TimelineTab(detail: TransactionDetail) {
    val phases = detail.timings
    if (phases.isEmpty()) {
        Notice(stringResource(R.string.pulse_timeline_empty))
        return
    }
    Notice(stringResource(R.string.pulse_timeline_note))
    val observed = phases.map { it.type }.toSet()
    val secure = detail.summary.url.startsWith("https://", ignoreCase = true)
    val missing = TimingPhaseType.entries.filter { it !in observed && it.isConnectionPhase(secure) }
    if (missing.isNotEmpty()) {
        Text(
            stringResource(R.string.pulse_timeline_not_observed, missing.map { stringResource(it.label()) }.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
    val span = maxOf(
        detail.summary.durationNanos ?: 0L,
        phases.maxOf { it.startOffsetNanos + (it.durationNanos ?: 0L) },
        1L,
    )
    val exchanges = phases.groupBy { it.exchangeIndex }
    exchanges.forEach { (exchange, exchangePhases) ->
        if (exchanges.size > 1) SectionTitle(stringResource(R.string.pulse_timeline_exchange, exchange + 1))
        exchangePhases.forEach { PhaseRow(it, span) }
    }
}

@Composable
private fun PhaseRow(phase: TimingPhase, span: Long) {
    val label = stringResource(phase.type.label())
    val duration = phase.durationNanos?.let(Formatters::duration) ?: stringResource(R.string.pulse_timeline_open_ended)
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(duration, style = MonospaceStyle)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clearAndSetSemantics { contentDescription = "$label $duration" },
        ) {
            drawRoundRect(trackColor, cornerRadius = CornerRadius(4.dp.toPx()))
            val start = size.width * phase.startOffsetNanos / span
            val width = phase.durationNanos?.let { size.width * it / span }?.coerceAtLeast(2.dp.toPx()) ?: 2.dp.toPx()
            drawRoundRect(barColor, topLeft = Offset(start, 0f), size = Size(width, size.height), cornerRadius = CornerRadius(4.dp.toPx()))
        }
    }
}

private fun TimingPhaseType.isConnectionPhase(secure: Boolean) =
    this == TimingPhaseType.DNS || this == TimingPhaseType.CONNECT || (secure && this == TimingPhaseType.TLS)
