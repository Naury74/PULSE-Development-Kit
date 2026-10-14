package com.naury.pulse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.pulse.api.PulseReports
import com.naury.pulse.api.ReportKind
import com.naury.pulse.api.ReportState
import com.naury.pulse.api.ReportStatus

/** 설정 탭의 버그 보고 목록. 눌러서 고치고, 상태에 따라 보내기·다시 보내기·취소·삭제한다. */
@Composable
internal fun ReportsSection(reports: PulseReports) {
    val states by reports.states.collectAsStateWithLifecycle()
    SectionTitle(stringResource(R.string.pulse_bugs_title))
    PulseCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedButton(onClick = { ReportRequests.pending = ComposeRequest() }) { Text(stringResource(R.string.pulse_bug_new)) }
            OpenPackageButton()
        }
        if (states.isEmpty()) {
            Text(
                stringResource(R.string.pulse_bugs_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        states.forEach { state ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ReportRow(state, reports)
        }
    }
}

@Composable
private fun ReportRow(state: ReportState, reports: PulseReports) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { ReportRequests.pending = ComposeRequest(reportId = state.id, kind = state.kind) }
            .padding(vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Pill(stringResource(statusLabel(state.status)), statusColor(state.status))
            Text(
                state.title.ifBlank {
                    "—"
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            listOfNotNull(
                stringResource(severityLabel(state.severity)),
                state.kind.takeIf { it != ReportKind.MANUAL }?.name,
                Formatters.dateTime(state.updatedAtEpochMillis),
                state.attempts.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.pulse_bugs_attempts, it, it) },
                state.progress?.let { "${(it * PERCENT).toInt()}%" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.lastError?.let { Text(it, style = MonospaceStyle, color = LocalStatusColors.current.serverError) }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            when (state.status) {
                ReportStatus.QUEUED, ReportStatus.SENDING -> TextButton(onClick = {
                    reports.cancel(state.id)
                }) { Text(stringResource(R.string.pulse_bugs_cancel)) }

                ReportStatus.FAILED, ReportStatus.CANCELED -> if (reports.canSend(state.kind)) {
                    TextButton(onClick = { reports.retry(state.id) }) { Text(stringResource(R.string.pulse_bugs_retry)) }
                }

                else -> Unit
            }
            TextButton(onClick = { reports.delete(state.id) }) { Text(stringResource(R.string.pulse_bugs_delete)) }
        }
    }
}

private fun statusLabel(status: ReportStatus): Int = when (status) {
    ReportStatus.DRAFT -> R.string.pulse_bug_status_draft
    ReportStatus.QUEUED -> R.string.pulse_bug_status_queued
    ReportStatus.SENDING -> R.string.pulse_bug_status_sending
    ReportStatus.SENT -> R.string.pulse_bug_status_sent
    ReportStatus.FAILED -> R.string.pulse_bug_status_failed
    ReportStatus.CANCELED -> R.string.pulse_bug_status_canceled
}

@Composable
private fun statusColor(status: ReportStatus): Color = when (status) {
    ReportStatus.SENT -> LocalStatusColors.current.success
    ReportStatus.FAILED -> LocalStatusColors.current.serverError
    ReportStatus.QUEUED, ReportStatus.SENDING -> LocalStatusColors.current.redirect
    else -> LocalStatusColors.current.pending
}

private const val PERCENT = 100
