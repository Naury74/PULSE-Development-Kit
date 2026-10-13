package com.naury.pulse.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.Insight
import com.naury.pulse.api.InsightCertainty
import com.naury.pulse.api.InsightRule
import com.naury.pulse.api.InsightSeverity
import com.naury.pulse.api.InsightUnit
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.StatsWindow

private const val PREVIEW_TRANSACTIONS = 5
private const val MILLIS_PER_SECOND = 1_000L

/** 진단 탭. 규칙마다 기준값·관찰값·근거 요청을 함께 보여 주고, 추정 규칙은 추정이라고 밝힌다. */
@Composable
internal fun InsightsPane(
    inspector: PulseInspector,
    window: StatsWindow,
    onWindowChange: (StatsWindow) -> Unit,
    onOpenTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val insights = rememberLive(inspector, window) { inspector.loadInsights(window) }
    var dismissed by rememberSaveable(window) { mutableStateOf(emptySet<String>()) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        WindowSelector(window, onWindowChange)
        Notice(stringResource(R.string.pulse_insights_note))
        val visible = insights?.filter { it.key() !in dismissed }
        when {
            visible == null -> Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            visible.isEmpty() -> Text(stringResource(R.string.pulse_insights_empty), style = MaterialTheme.typography.bodyMedium)

            else -> visible.forEach { insight ->
                InsightCard(inspector, insight, onOpenTransaction, onDismiss = { dismissed = dismissed + insight.key() })
            }
        }
    }
}

private fun Insight.key() = "${rule.name}|$host|$endpoint"

@Composable
private fun InsightCard(inspector: PulseInspector, insight: Insight, onOpenTransaction: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalStatusColors.current
    val severityColor = when (insight.severity) {
        InsightSeverity.ERROR -> colors.serverError
        InsightSeverity.WARNING -> colors.clientError
        InsightSeverity.INFO -> colors.redirect
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Pill(stringResource(insight.severity.label()), severityColor)
            Text(
                stringResource(
                    if (insight.certainty ==
                        InsightCertainty.HEURISTIC
                    ) {
                        R.string.pulse_certainty_heuristic
                    } else {
                        R.string.pulse_certainty_observed
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(insight.rule.title()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        listOfNotNull(insight.endpoint, insight.host).firstOrNull()?.let { Text(it, style = MonospaceStyle) }
        Text(evidence(insight), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(insight.rule.caveat()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EvidenceRequests(inspector, insight, onOpenTransaction)
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.pulse_insight_dismiss)) }
    }
}

@Composable
private fun EvidenceRequests(inspector: PulseInspector, insight: Insight, onOpenTransaction: (String) -> Unit) {
    val previews = rememberLive(inspector, insight.transactionIds) {
        insight.transactionIds.take(PREVIEW_TRANSACTIONS).mapNotNull { inspector.loadTransaction(it)?.summary }
    }
    previews?.forEach { summary ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button) { onOpenTransaction(summary.id) }
                .semantics(mergeDescendants = true) {}
                .padding(vertical = Spacing.xs),
        ) {
            MethodTag(summary.method)
            Text(
                summary.path,
                style = MonospaceStyle,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(summary)
            summary.durationNanos?.let {
                Text(
                    Formatters.duration(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (insight.matchCount > PREVIEW_TRANSACTIONS) {
        Text(
            stringResource(R.string.pulse_insight_more, insight.matchCount - PREVIEW_TRANSACTIONS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun evidence(insight: Insight): String {
    fun format(value: Long) = when (insight.unit) {
        InsightUnit.NANOS -> Formatters.duration(value)
        InsightUnit.BYTES -> Formatters.bytes(value)
        InsightUnit.COUNT -> value.toString()
    }
    val threshold = format(insight.threshold)
    val observed = format(insight.observed)
    return insight.windowMillis?.let {
        stringResource(R.string.pulse_insight_evidence_window, threshold, observed, insight.matchCount, it / MILLIS_PER_SECOND)
    } ?: stringResource(R.string.pulse_insight_evidence, threshold, observed, insight.matchCount)
}

@StringRes
private fun InsightSeverity.label(): Int = when (this) {
    InsightSeverity.ERROR -> R.string.pulse_severity_error
    InsightSeverity.WARNING -> R.string.pulse_severity_warning
    InsightSeverity.INFO -> R.string.pulse_severity_info
}

@StringRes
private fun InsightRule.title(): Int = when (this) {
    InsightRule.SLOW_REQUEST -> R.string.pulse_rule_slow
    InsightRule.ERROR_BURST -> R.string.pulse_rule_error_burst
    InsightRule.DUPLICATE_IN_FLIGHT -> R.string.pulse_rule_duplicate
    InsightRule.OVERSIZED_RESPONSE -> R.string.pulse_rule_oversized
    InsightRule.N_PLUS_ONE_SUSPECTED -> R.string.pulse_rule_n_plus_one
    InsightRule.CACHE_OPPORTUNITY -> R.string.pulse_rule_cache
}

@StringRes
private fun InsightRule.caveat(): Int = when (this) {
    InsightRule.SLOW_REQUEST -> R.string.pulse_caveat_slow
    InsightRule.ERROR_BURST -> R.string.pulse_caveat_error_burst
    InsightRule.DUPLICATE_IN_FLIGHT -> R.string.pulse_caveat_duplicate
    InsightRule.OVERSIZED_RESPONSE -> R.string.pulse_caveat_oversized
    InsightRule.N_PLUS_ONE_SUSPECTED -> R.string.pulse_caveat_n_plus_one
    InsightRule.CACHE_OPPORTUNITY -> R.string.pulse_caveat_cache
}
