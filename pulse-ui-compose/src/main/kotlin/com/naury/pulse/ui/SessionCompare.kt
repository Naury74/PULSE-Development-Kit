package com.naury.pulse.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.naury.pulse.api.EndpointDiff
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.SessionDiff
import com.naury.pulse.api.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date

/** 두 실행 비교를 여는 버튼. 기준은 크래시 없이 끝난 가장 최근 실행, 비교는 가장 최근 실행으로 시작한다. */
@Composable
internal fun SessionCompareButton(inspector: PulseInspector, sessions: List<SessionSummary>) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = {
        open = true
    }, modifier = Modifier.padding(top = Spacing.s)) { Text(stringResource(R.string.pulse_runs_open)) }
    if (open) SessionCompareSheet(inspector, sessions) { open = false }
}

@Composable
private fun SessionCompareSheet(inspector: PulseInspector, sessions: List<SessionSummary>, onDismiss: () -> Unit) {
    var target by rememberSaveable { mutableStateOf(sessions.first().id) }
    var base by rememberSaveable { mutableStateOf((sessions.drop(1).firstOrNull { !it.crashed } ?: sessions[1]).id) }
    val diff by produceState<SessionDiff?>(null, base, target) {
        value = null
        value = inspector.compareSessions(base, target)
    }
    PulseSheet(
        icon = Icons.Filled.Refresh,
        title = stringResource(R.string.pulse_runs_title),
        subtitle = stringResource(R.string.pulse_runs_subtitle),
        onDismiss = onDismiss,
        actions = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pulse_cancel)) } },
    ) {
        SheetLabel(stringResource(R.string.pulse_runs_base))
        SessionChips(sessions, base) { base = it }
        SheetLabel(stringResource(R.string.pulse_runs_target))
        SessionChips(sessions, target) { target = it }
        when {
            base == target -> Notice(stringResource(R.string.pulse_runs_same))
            diff == null -> PulseLoading()
            else -> DiffResult(diff!!)
        }
    }
}

@Composable
private fun SessionChips(sessions: List<SessionSummary>, selected: String, onSelect: (String) -> Unit) {
    val format = SimpleDateFormat("MM.dd HH:mm", currentLocale())
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        sessions.take(MAX_CHOICES).forEach { session ->
            val mark = when {
                session.current -> " ●"
                session.crashed -> " ✕"
                else -> ""
            }
            FilterChip(
                selected = selected == session.id,
                onClick = { onSelect(session.id) },
                label = { Text(format.format(Date(session.startedAtEpochMillis)) + mark) },
                shape = MaterialTheme.shapes.small,
                colors = dashboardChipColors(),
                border = dashboardChipBorder(selected == session.id),
            )
        }
    }
}

@Composable
private fun DiffResult(diff: SessionDiff) {
    if (diff.newIssues.isNotEmpty()) {
        SheetLabel(stringResource(R.string.pulse_runs_new_issues))
        PulseCard {
            diff.newIssues.take(MAX_ROWS).forEach { Text(it, style = MonospaceStyle, color = LocalStatusColors.current.serverError) }
        }
    }
    if (diff.flags.isNotEmpty()) {
        SheetLabel(stringResource(R.string.pulse_runs_flags))
        PulseCard {
            diff.flags.forEach { (name, values) -> Field(name, "${values.first ?: "—"} → ${values.second ?: "—"}", monospace = true) }
        }
    }
    if (diff.screensOnlyInBase.isNotEmpty() || diff.screensOnlyInTarget.isNotEmpty()) {
        SheetLabel(stringResource(R.string.pulse_runs_screens))
        PulseCard {
            if (diff.screensOnlyInBase.isNotEmpty()) {
                Field(
                    stringResource(R.string.pulse_runs_only_base),
                    diff.screensOnlyInBase.joinToString(),
                    monospace = true,
                )
            }
            if (diff.screensOnlyInTarget.isNotEmpty()) {
                Field(
                    stringResource(R.string.pulse_runs_only_target),
                    diff.screensOnlyInTarget.joinToString(),
                    monospace = true,
                )
            }
        }
    }
    SheetLabel(stringResource(R.string.pulse_runs_endpoints))
    PulseCard {
        Text(
            stringResource(R.string.pulse_runs_endpoint_header),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        diff.endpoints.take(MAX_ROWS).forEachIndexed { index, endpoint ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            EndpointRow(endpoint)
        }
        if (diff.truncated) {
            Text(
                stringResource(R.string.pulse_runs_truncated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SheetLabel(stringResource(R.string.pulse_runs_events))
    PulseCard {
        diff.eventCounts.entries.sortedByDescending { kotlin.math.abs(it.value.second - it.value.first) }.forEach { (kind, counts) ->
            Field(kind.name, "${counts.first} → ${counts.second}")
        }
    }
}

@Composable
private fun EndpointRow(endpoint: EndpointDiff) {
    val worse = endpoint.targetErrors > endpoint.baseErrors
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Text(
            endpoint.endpoint,
            style = MonospaceStyle,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (worse) LocalStatusColors.current.serverError else MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${endpoint.baseCount} → ${endpoint.targetCount} · ${endpoint.baseErrors} → ${endpoint.targetErrors} · " +
                    "${endpoint.baseMedianNanos?.let(
                        Formatters::duration,
                    ) ?: "—"} → ${endpoint.targetMedianNanos?.let(Formatters::duration) ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val MAX_CHOICES = 20
private const val MAX_ROWS = 30
