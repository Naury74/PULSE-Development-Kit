package com.naury.pulse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.EvidenceLink
import com.naury.pulse.api.LinkStrength
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.TimelineItemKind
import kotlinx.coroutines.launch

/**
 * [id] 항목과 이어진 요청·이벤트. 연결 등급(기록된 연결·같은 화면·시간상 근접)을 함께 보여 주어
 * 시간상 가까운 것을 원인으로 오해하지 않게 한다. 항목을 누르면 그 요청·이벤트로 간다.
 */
@Composable
internal fun RelatedEvidence(
    inspector: PulseInspector,
    id: String,
    kind: TimelineItemKind,
    onOpenRequest: ((String) -> Unit)?,
    onOpenEvent: ((AppEvent) -> Unit)?,
) {
    val links by produceState<List<EvidenceLink>?>(null, id) { value = inspector.loadRelated(id, kind) }
    val scope = rememberCoroutineScope()
    val current = links ?: return
    SheetLabel(stringResource(R.string.pulse_related_title))
    if (current.isEmpty()) {
        Text(
            stringResource(R.string.pulse_related_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    PulseCard {
        current.forEachIndexed { index, link ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val open: (() -> Unit)? = when (link.targetKind) {
                TimelineItemKind.REQUEST -> onOpenRequest?.let { { it(link.targetId) } }

                TimelineItemKind.EVENT -> onOpenEvent?.let { handler ->
                    { scope.launch { inspector.loadEvent(link.targetId)?.let(handler) } }
                }
            }
            LinkRow(link, open)
        }
        Text(
            stringResource(R.string.pulse_related_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinkRow(link: EvidenceLink, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(stringResource(strengthLabel(link)), strengthColor(link.strength))
        Column(Modifier.weight(1f)) {
            Text(link.title, style = MonospaceStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(offset(link.offsetMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun strengthLabel(link: EvidenceLink): Int = when (link.strength) {
    LinkStrength.EXPLICIT -> when (link.reason) {
        "replay" -> R.string.pulse_related_replay
        "crash-exit" -> R.string.pulse_related_crash_exit
        else -> R.string.pulse_related_span
    }

    LinkStrength.CONTEXTUAL -> R.string.pulse_related_screen

    LinkStrength.TEMPORAL -> R.string.pulse_related_time
}

@Composable
private fun strengthColor(strength: LinkStrength): Color = when (strength) {
    LinkStrength.EXPLICIT -> MaterialTheme.colorScheme.primary
    LinkStrength.CONTEXTUAL -> LocalStatusColors.current.redirect
    LinkStrength.TEMPORAL -> LocalStatusColors.current.pending
}

private fun offset(millis: Long): String {
    val sign = if (millis < 0) "−" else "+"
    val absolute = kotlin.math.abs(millis)
    return if (absolute < MILLIS_PER_SECOND) "$sign${absolute}ms" else "$sign%.1fs".format(absolute / MILLIS_PER_SECOND.toDouble())
}

private const val MILLIS_PER_SECOND = 1000L
