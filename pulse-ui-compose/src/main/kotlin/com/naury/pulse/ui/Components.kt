package com.naury.pulse.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.TimingPhaseType
import com.naury.pulse.api.TransactionState
import com.naury.pulse.api.TransactionSummary

private const val HTTP_REDIRECT_MIN = 300
private const val HTTP_CLIENT_ERROR_MIN = 400
private const val HTTP_SERVER_ERROR_MIN = 500

/** 상태 코드 또는 실패 종류를 색과 글자로 함께 보여 준다. */
@Composable
internal fun StatusBadge(summary: TransactionSummary, modifier: Modifier = Modifier) {
    val colors = LocalStatusColors.current
    val code = summary.statusCode
    val (label, color) = when {
        summary.state == TransactionState.FAILED && code == null -> stringResource(R.string.pulse_status_failed) to colors.failure
        summary.state == TransactionState.CANCELED -> stringResource(R.string.pulse_status_canceled) to colors.failure
        summary.state == TransactionState.INTERRUPTED -> stringResource(R.string.pulse_status_interrupted) to colors.pending
        code == null -> stringResource(R.string.pulse_status_in_flight) to colors.pending
        code >= HTTP_SERVER_ERROR_MIN -> code.toString() to colors.serverError
        code >= HTTP_CLIENT_ERROR_MIN -> code.toString() to colors.clientError
        code >= HTTP_REDIRECT_MIN -> code.toString() to colors.redirect
        else -> code.toString() to colors.success
    }
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
    )
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(top = Spacing.l, bottom = Spacing.s)
            .semantics { heading() },
    )
}

@Composable
internal fun Field(label: String, value: String, monospace: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, style = if (monospace) MonospaceStyle else MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun HeaderTable(headers: List<HeaderField>) {
    if (headers.isEmpty()) {
        Text(stringResource(R.string.pulse_headers_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            headers.forEach { header ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = header.name,
                        style = MonospaceStyle,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(160.dp),
                    )
                    Text(header.value, style = MonospaceStyle, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun Notice(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(Spacing.m),
    )
}

@StringRes
internal fun BodyState.explanation(): Int = when (this) {
    BodyState.EMPTY -> R.string.pulse_body_empty
    BodyState.CAPTURED -> R.string.pulse_body_loading
    BodyState.OMITTED_BY_POLICY -> R.string.pulse_body_omitted
    BodyState.UNSUPPORTED_CONTENT -> R.string.pulse_body_unsupported
    BodyState.TOO_LARGE -> R.string.pulse_body_too_large
    BodyState.ONE_SHOT_UNSUPPORTED -> R.string.pulse_body_one_shot
    BodyState.DUPLEX_UNSUPPORTED -> R.string.pulse_body_duplex
    BodyState.INCOMPLETE_READ -> R.string.pulse_body_incomplete
    BodyState.REDACTION_FAILED -> R.string.pulse_body_redaction_failed
    BodyState.DECODE_FAILED -> R.string.pulse_body_decode_failed
    BodyState.DROPPED -> R.string.pulse_body_dropped
    BodyState.PENDING -> R.string.pulse_body_pending
}

@StringRes
internal fun TimingPhaseType.label(): Int = when (this) {
    TimingPhaseType.DNS -> R.string.pulse_phase_dns
    TimingPhaseType.CONNECT -> R.string.pulse_phase_connect
    TimingPhaseType.TLS -> R.string.pulse_phase_tls
    TimingPhaseType.REQUEST_HEADERS -> R.string.pulse_phase_request_headers
    TimingPhaseType.REQUEST_BODY -> R.string.pulse_phase_request_body
    TimingPhaseType.RESPONSE_HEADERS -> R.string.pulse_phase_response_headers
    TimingPhaseType.RESPONSE_BODY -> R.string.pulse_phase_response_body
}
