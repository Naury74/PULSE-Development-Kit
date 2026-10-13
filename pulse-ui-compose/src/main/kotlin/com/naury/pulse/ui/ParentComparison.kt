package com.naury.pulse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naury.pulse.analytics.DiffEntry
import com.naury.pulse.analytics.DiffKind
import com.naury.pulse.analytics.DiffSide
import com.naury.pulse.analytics.TransactionDiff
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.TransactionDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val VALUE_PREVIEW = 120

/** 재실행 결과를 원본과 비교한다. 가려진 값은 비교하지 않는다. */
@Composable
internal fun ParentComparison(inspector: PulseInspector, detail: TransactionDetail, parentId: String, onOpenTransaction: (String) -> Unit) {
    SectionTitle(stringResource(R.string.pulse_diff_title))
    OutlinedButton(onClick = { onOpenTransaction(parentId) }) { Text(stringResource(R.string.pulse_diff_open_original)) }
    val entries by produceState<List<DiffEntry>?>(null, detail, parentId) {
        val parent = inspector.loadTransaction(parentId)
        value = if (parent == null) {
            emptyList()
        } else {
            val left = side(inspector, parent)
            val right = side(inspector, detail)
            withContext(Dispatchers.Default) { TransactionDiff.compare(left, right) }
        }
    }
    when (val current = entries) {
        null -> Text(stringResource(R.string.pulse_body_loading))

        else -> if (current.isEmpty()) {
            Notice(stringResource(R.string.pulse_diff_none))
        } else {
            Notice(stringResource(R.string.pulse_diff_note))
            current.forEach { DiffRow(it) }
        }
    }
}

private suspend fun side(inspector: PulseInspector, detail: TransactionDetail): DiffSide {
    val id = detail.summary.id
    return DiffSide(
        detail,
        if (detail.request.bodyState == BodyState.CAPTURED) inspector.loadBody(id, MessageKind.REQUEST) else null,
        if (detail.response?.bodyState == BodyState.CAPTURED) inspector.loadBody(id, MessageKind.RESPONSE) else null,
    )
}

@Composable
private fun DiffRow(entry: DiffEntry) {
    val kind = stringResource(
        when (entry.kind) {
            DiffKind.ADDED -> R.string.pulse_diff_added
            DiffKind.REMOVED -> R.string.pulse_diff_removed
            DiffKind.CHANGED -> R.string.pulse_diff_changed
            DiffKind.TYPE_CHANGED -> R.string.pulse_diff_type_changed
            DiffKind.REORDERED -> R.string.pulse_diff_reordered
        },
    )
    Column(
        Modifier.padding(vertical = Spacing.xs).semantics(mergeDescendants = true) {
        },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("${entry.area.name.lowercase().replace('_', ' ')} · $kind", style = MaterialTheme.typography.labelMedium)
        Text(entry.path, style = MonospaceStyle)
        if (entry.left != null || entry.right != null) {
            Text(
                "${entry.left?.take(VALUE_PREVIEW) ?: "—"}  →  ${entry.right?.take(VALUE_PREVIEW) ?: "—"}",
                style = MonospaceStyle,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
