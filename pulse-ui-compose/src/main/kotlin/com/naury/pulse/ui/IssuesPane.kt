package com.naury.pulse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.EventQuery
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.StatsWindow

/** 이슈 탭. 앱 이슈(크래시·오류·멈춤·종료 이유)와 네트워크 진단을 바꿔 본다. */
@Composable
internal fun IssuesPane(
    inspector: PulseInspector,
    window: StatsWindow,
    onWindowChange: (StatsWindow) -> Unit,
    range: TimeRange,
    onRangeChange: (TimeRange) -> Unit,
    onOpenTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier) {
        Box(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
            TwoChoiceRow(stringResource(R.string.pulse_issues_app), stringResource(R.string.pulse_issues_network), mode == 0) {
                mode = if (it) 0 else 1
            }
        }
        if (mode == 0) {
            AppIssues(inspector, range, onRangeChange, onOpenTransaction, Modifier.weight(1f))
        } else {
            InsightsPane(inspector, window, onWindowChange, onOpenTransaction, Modifier.weight(1f))
        }
    }
}

/** 같은 종류·같은 첫 줄 메시지의 이슈 묶음. */
private class IssueGroup(val latest: AppEvent, val count: Int, val firstSeen: Long, val screens: Set<String>)

private val issueKinds = setOf(EventKind.CRASH, EventKind.ERROR, EventKind.MAIN_THREAD_BLOCKED, EventKind.EXIT)

@Composable
private fun AppIssues(
    inspector: PulseInspector,
    range: TimeRange,
    onRangeChange: (TimeRange) -> Unit,
    onOpenTransaction: (String) -> Unit,
    modifier: Modifier,
) {
    val groups = rememberLive(inspector, range, events = true) {
        val events = inspector.loadEvents(
            EventQuery(kinds = issueKinds, fromEpochMillis = range.from, toEpochMillis = range.to, sessionId = range.sessionId),
            null,
            MAX_ISSUES,
        ).items
        events.groupBy { it.kind to signature(it) }
            .map { (_, list) ->
                IssueGroup(list.first(), list.size, list.last().timestampEpochMillis, list.mapNotNull { it.screen }.toSet())
            }
            .sortedWith(compareBy<IssueGroup> { severity(it.latest.kind) }.thenByDescending { it.latest.timestampEpochMillis })
    }
    var opened by remember { mutableStateOf<AppEvent?>(null) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        TimeRangeChip(inspector, range, onRangeChange)
        when {
            groups == null -> Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) { PulseLoading() }
            groups.isEmpty() -> Notice(stringResource(R.string.pulse_issues_empty))
            else -> groups.forEach { group -> IssueCard(group) { opened = group.latest } }
        }
    }
    opened?.let { event ->
        EventDetailSheet(inspector, event, onRangeChange, onDismiss = { opened = null }, onOpenRequest = {
            opened = null
            onOpenTransaction(it)
        }, onOpenEvent = { opened = it })
    }
}

@Composable
private fun IssueCard(group: IssueGroup, onClick: () -> Unit) {
    val event = group.latest
    PulseCard(
        Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Pill(eventBadge(event), eventColor(event))
            event.tag?.let {
                Text(it, style = MonospaceStyle, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            }
            Text(
                stringResource(R.string.pulse_issues_count, group.count),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            event.message,
            style = MonospaceStyle,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.s),
        )
        Text(
            stringResource(
                R.string.pulse_issues_seen,
                Formatters.dateTime(event.timestampEpochMillis).take(DATE_TIME_MINUTES),
                Formatters.dateTime(group.firstSeen).take(DATE_TIME_MINUTES),
            ) + if (group.screens.isNotEmpty()) " · " + group.screens.joinToString() else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/** 이슈 목록의 정렬 순서. 앞에 있을수록 위에 보인다. */
private val SEVERITY_ORDER = listOf(EventKind.CRASH, EventKind.EXIT, EventKind.ERROR)

private fun severity(kind: EventKind): Int = SEVERITY_ORDER.indexOf(kind).let { if (it < 0) SEVERITY_ORDER.size else it }

/** 같은 원인으로 묶는 기준. 메시지 첫 줄에서 숫자(시간·ID·길이)를 지워 "멈춤 2516 ms"와 "멈춤 2169 ms"를 한 묶음으로 본다. */
private fun signature(event: AppEvent): String = event.message.lineSequence().first().take(SIGNATURE_LENGTH).replace(DIGITS, "#")

private val DIGITS = Regex("\\d+")

private const val MAX_ISSUES = 500
private const val SIGNATURE_LENGTH = 160
private const val DATE_TIME_MINUTES = 16
