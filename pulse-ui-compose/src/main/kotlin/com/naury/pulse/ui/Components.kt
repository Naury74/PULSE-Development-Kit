package com.naury.pulse.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import com.naury.pulse.api.TimingPhaseType
import com.naury.pulse.api.TransactionState
import com.naury.pulse.api.TransactionSummary

/** 테두리가 있는 흰 카드. 화면의 묶음 단위다. */
@Composable
internal fun PulseCard(modifier: Modifier = Modifier, padding: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(if (padding) Modifier.padding(Spacing.l) else Modifier, content = content)
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.l, bottom = Spacing.s),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing?.invoke()
    }
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

/** 이름·값 두 칸 표. 가린 값은 다른 색으로 보여 준다. */
@Composable
internal fun HeaderTable(headers: List<HeaderField>) {
    if (headers.isEmpty()) {
        Text(
            stringResource(R.string.pulse_headers_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val redactedColor = LocalStatusColors.current.clientError
    SelectionContainer {
        Column {
            headers.forEachIndexed { index, header ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = Spacing.s)) {
                    Text(
                        text = header.name,
                        style = MonospaceStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(HEADER_NAME_WEIGHT)
                            .padding(end = Spacing.m),
                    )
                    Text(
                        text = header.value,
                        style = MonospaceStyle,
                        fontWeight = if (header.value == REDACTED) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (header.value == REDACTED) redactedColor else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f - HEADER_NAME_WEIGHT),
                    )
                }
            }
        }
    }
}

private const val HEADER_NAME_WEIGHT = 0.42f

/** 안내 상자. [color]가 오류 색이면 오류 바탕으로 보여 준다. */
@Composable
internal fun Notice(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val isError = color == MaterialTheme.colorScheme.error
    val background = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (isError) MaterialTheme.colorScheme.onErrorContainer else color
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = foreground,
        modifier = Modifier
            .fillMaxWidth()
            .background(background, MaterialTheme.shapes.small)
            .padding(horizontal = Spacing.m, vertical = Spacing.s + 2.dp),
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
