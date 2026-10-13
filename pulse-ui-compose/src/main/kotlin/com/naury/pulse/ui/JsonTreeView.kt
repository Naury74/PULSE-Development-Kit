package com.naury.pulse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED

private const val MAX_VISIBLE_ROWS = 1_500
private const val VALUE_MAX_LINES = 4

/** 펼친 노드만 그리는 JSON 트리. 한 번에 그리는 줄 수를 제한해 큰 본문에서도 화면이 멈추지 않게 한다. */
@Composable
internal fun JsonTreeView(tree: JsonTree) {
    var expanded by rememberSaveable(tree) { mutableStateOf(setOf(tree.root.path)) }
    val rows = ArrayList<JsonNode>()
    fun collect(node: JsonNode) {
        if (rows.size >= MAX_VISIBLE_ROWS) return
        rows += node
        if (node.isContainer && node.path in expanded) node.children.forEach(::collect)
    }
    collect(tree.root)

    if (tree.truncated) Notice(stringResource(R.string.pulse_tree_truncated, tree.nodeCount))
    Column(Modifier.padding(vertical = Spacing.s)) {
        rows.forEach { node ->
            JsonRow(node, isExpanded = node.path in expanded) {
                expanded = if (node.path in expanded) expanded - node.path else expanded + node.path
            }
        }
        if (rows.size >= MAX_VISIBLE_ROWS) Notice(stringResource(R.string.pulse_tree_rows_limited, MAX_VISIBLE_ROWS))
    }
}

@Composable
private fun JsonRow(node: JsonNode, isExpanded: Boolean, onToggle: () -> Unit) {
    val accents = LocalAccentColors.current
    val redactedColor = LocalStatusColors.current.clientError
    val expandedLabel = stringResource(R.string.pulse_tree_expanded)
    val collapsedLabel = stringResource(R.string.pulse_tree_collapsed)
    val base = Modifier
        .fillMaxWidth()
        .padding(start = (node.depth * 14).dp)
    val modifier = if (node.isContainer) {
        base
            .heightIn(min = 32.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (isExpanded) expandedLabel else collapsedLabel }
    } else {
        base
    }
    Row(modifier, verticalAlignment = Alignment.Top) {
        val marker = when {
            !node.isContainer -> "  "
            isExpanded -> "▾ "
            else -> "▸ "
        }
        Text(marker, style = MonospaceStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        node.label?.let { Text("$it: ", style = MonospaceStyle, color = accents.jsonKey) }
        val valueColor = when (node.kind) {
            JsonNode.Kind.STRING -> if (node.summary == "\"$REDACTED\"") redactedColor else accents.jsonString
            JsonNode.Kind.NUMBER -> accents.jsonNumber
            JsonNode.Kind.BOOLEAN, JsonNode.Kind.NULL -> accents.jsonLiteral
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(node.summary, style = MonospaceStyle, color = valueColor, maxLines = VALUE_MAX_LINES, overflow = TextOverflow.Ellipsis)
    }
}
