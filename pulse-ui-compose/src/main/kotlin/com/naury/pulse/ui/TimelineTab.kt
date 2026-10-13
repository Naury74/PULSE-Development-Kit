package com.naury.pulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.ResponseSource
import com.naury.pulse.api.TimingPhase
import com.naury.pulse.api.TimingPhaseType
import com.naury.pulse.api.TransactionDetail

/** 관찰한 단계만 막대로 그린다. 끝을 관찰하지 못한 단계는 길이를 추정하지 않고 시작점만 표시한다. */
@Composable
internal fun TimelineTab(detail: TransactionDetail) {
    val phases = detail.timings
    SectionTitle(stringResource(R.string.pulse_tab_timeline))
    if (phases.isEmpty()) {
        NetworkEvidence(detail)
        Notice(stringResource(R.string.pulse_timeline_empty))
        return
    }
    val span = maxOf(
        detail.summary.durationNanos ?: 0L,
        phases.maxOf { it.startOffsetNanos + (it.durationNanos ?: 0L) },
        1L,
    )
    PulseCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Formatters.duration(span),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.pulse_timeline_total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val exchanges = phases.groupBy { it.exchangeIndex }
        exchanges.forEach { (exchange, exchangePhases) ->
            if (exchanges.size > 1) {
                Text(
                    stringResource(R.string.pulse_timeline_exchange, exchange + 1),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.m),
                )
            }
            exchangePhases.forEach { phase ->
                if (phase.type == TimingPhaseType.RESPONSE_HEADERS) waitingBefore(phase, exchangePhases)?.let { WaitingRow(it, span) }
                PhaseRow(phase, span)
            }
        }
    }
    ConnectionCard(detail)
}

@Composable
private fun ConnectionCard(detail: TransactionDetail) {
    val phases = detail.timings
    SectionTitle(stringResource(R.string.pulse_timeline_connection))
    PulseCard {
        NetworkEvidence(detail)
        val observed = phases.map { it.type }.toSet()
        val secure = detail.summary.url.startsWith("https://", ignoreCase = true)
        val missing = TimingPhaseType.entries.filter { it !in observed && it.isConnectionPhase(secure) }
        if (missing.isNotEmpty()) {
            Text(
                stringResource(R.string.pulse_timeline_not_observed, missing.map { stringResource(it.label()) }.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        Text(
            stringResource(R.string.pulse_timeline_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
}

/**
 * 요청을 다 보낸 뒤 응답 헤더가 오기 시작할 때까지의 간격. 서버 처리와 네트워크 왕복이 섞인 값이며,
 * 관찰한 두 이벤트 사이의 시간이라 추정이 아니다. 요청 쪽 끝을 관찰하지 못했으면 null.
 */
private fun waitingBefore(responseHeaders: TimingPhase, phases: List<TimingPhase>): TimingPhase? {
    val requestEnd = phases
        .filter { it.type == TimingPhaseType.REQUEST_HEADERS || it.type == TimingPhaseType.REQUEST_BODY }
        .mapNotNull { phase -> phase.durationNanos?.let { phase.startOffsetNanos + it } }
        .maxOrNull() ?: return null
    val gap = responseHeaders.startOffsetNanos - requestEnd
    return if (gap > 0) TimingPhase(TimingPhaseType.RESPONSE_HEADERS, responseHeaders.exchangeIndex, requestEnd, gap) else null
}

@Composable
private fun WaitingRow(waiting: TimingPhase, span: Long) {
    PhaseRow(waiting, span, stringResource(R.string.pulse_phase_waiting), LocalAccentColors.current.waiting)
}

@Composable
private fun PhaseRow(
    phase: TimingPhase,
    span: Long,
    label: String = stringResource(phase.type.label()),
    barColor: Color = LocalAccentColors.current.phases.getValue(phase.type),
) {
    val duration = phase.durationNanos?.let(Formatters::duration) ?: stringResource(R.string.pulse_timeline_open_ended)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.m)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .padding(end = Spacing.s)
                    .size(10.dp)
                    .background(barColor, CircleShape),
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(duration, style = MonospaceStyle, fontWeight = FontWeight.SemiBold)
        }
        Canvas(
            Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(10.dp)
                .clearAndSetSemantics { contentDescription = "$label $duration" },
        ) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(trackColor, cornerRadius = radius)
            val start = size.width * phase.startOffsetNanos / span
            val width = phase.durationNanos?.let { size.width * it / span }?.coerceAtLeast(MIN_BAR_DP.dp.toPx()) ?: MIN_BAR_DP.dp.toPx()
            drawRoundRect(barColor, topLeft = Offset(start, 0f), size = Size(width, size.height), cornerRadius = radius)
        }
    }
}

private const val MIN_BAR_DP = 3

/** 응답 출처와 연결 재사용은 OkHttp가 알린 이벤트가 있을 때만 보여 준다. */
@Composable
private fun NetworkEvidence(detail: TransactionDetail) {
    val source = when (detail.responseSource) {
        ResponseSource.CACHE -> R.string.pulse_evidence_cache
        ResponseSource.CONDITIONAL_CACHE -> R.string.pulse_evidence_conditional_cache
        ResponseSource.NETWORK -> R.string.pulse_evidence_network
        null -> null
    }
    val connection = when (detail.connectionReused) {
        true -> R.string.pulse_evidence_connection_reused
        false -> R.string.pulse_evidence_connection_new
        null -> null
    }
    listOfNotNull(source, connection).forEach { text ->
        Text(
            "• " + stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = Spacing.xs),
        )
    }
}

private fun TimingPhaseType.isConnectionPhase(secure: Boolean) =
    this == TimingPhaseType.DNS || this == TimingPhaseType.CONNECT || (secure && this == TimingPhaseType.TLS)
