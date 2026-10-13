package com.naury.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.TransactionState
import com.naury.pulse.api.TransactionSummary

private const val HTTP_REDIRECT_MIN = 300
private const val HTTP_CLIENT_ERROR_MIN = 400
private const val HTTP_SERVER_ERROR_MIN = 500
private const val TINT_ALPHA = 0.14f

/** 요청 결과를 나타내는 글자와 색. 색만으로 상태를 전달하지 않도록 늘 글자와 함께 쓴다. */
@Composable
internal fun statusLabelAndColor(summary: TransactionSummary): Pair<String, Color> {
    val colors = LocalStatusColors.current
    val code = summary.statusCode
    return when {
        summary.state == TransactionState.FAILED && code == null -> stringResource(R.string.pulse_status_failed) to colors.failure
        summary.state == TransactionState.CANCELED -> stringResource(R.string.pulse_status_canceled) to colors.failure
        summary.state == TransactionState.INTERRUPTED -> stringResource(R.string.pulse_status_interrupted) to colors.pending
        code == null -> stringResource(R.string.pulse_status_in_flight) to colors.pending
        else -> code.toString() to statusCodeColor(code)
    }
}

@Composable
internal fun statusCodeColor(code: Int): Color {
    val colors = LocalStatusColors.current
    return when {
        code >= HTTP_SERVER_ERROR_MIN -> colors.serverError
        code >= HTTP_CLIENT_ERROR_MIN -> colors.clientError
        code >= HTTP_REDIRECT_MIN -> colors.redirect
        else -> colors.success
    }
}

/** 상태 코드 또는 실패 종류를 색 바탕의 글자로 보여 준다. */
@Composable
internal fun StatusBadge(summary: TransactionSummary, modifier: Modifier = Modifier) {
    val (label, color) = statusLabelAndColor(summary)
    Pill(label, color, modifier)
}

/** 메서드 이름. 메서드마다 색이 다르지만 이름을 늘 함께 보여 준다. */
@Composable
internal fun MethodTag(method: String, modifier: Modifier = Modifier) {
    Pill(method.uppercase(), LocalAccentColors.current.method(method), modifier)
}

@Composable
internal fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        style = MonospaceStyle.copy(fontSize = 12.sp, lineHeight = 16.sp),
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .background(color.copy(alpha = TINT_ALPHA), PillShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** 큰 숫자와 작은 이름 한 쌍. 상세 머리말과 개요 타일에 쓴다. */
@Composable
internal fun Metric(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** 재실행·모의 응답으로 생긴 기록이면 출처를 표시한다. 원래 요청이면 아무것도 그리지 않는다. */
@Composable
internal fun SourceTag(summary: TransactionSummary) {
    if (summary.source == CaptureSource.ORIGINAL) return
    val label = if (summary.source == CaptureSource.MOCK) R.string.pulse_source_mock else R.string.pulse_source_replay
    Pill(stringResource(label), MaterialTheme.colorScheme.primary)
}
