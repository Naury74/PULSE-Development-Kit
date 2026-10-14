package com.naury.pulse.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 잡아 둔 화면 구조. 위에는 고른 노드의 영역을 캡처 위에 표시하고, 아래에는 들여쓴 노드 목록과 고른 노드의 속성을 보여 준다. */
@Composable
internal fun HierarchySheet(snapshot: UiSnapshot, onDismiss: () -> Unit) {
    val rows = remember(snapshot) { flatten(snapshot.root, 0) }
    var selected by remember(snapshot) { mutableStateOf(snapshot.root) }
    PulseSheet(
        icon = Icons.Filled.Search,
        title = snapshot.screen,
        subtitle = stringResource(R.string.pulse_layout_subtitle, snapshot.nodeCount),
        onDismiss = onDismiss,
        actions = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pulse_cancel)) } },
    ) {
        Preview(snapshot, selected)
        PulseCard {
            Text(
                "${selected.kind} · ${selected.name}" + (
                    selected.id?.let {
                        " · #$it"
                    } ?: ""
                    ),
                style = MonospaceStyle,
                fontWeight = FontWeight.SemiBold,
            )
            Field(
                "bounds",
                "${selected.bounds.left},${selected.bounds.top} – ${selected.bounds.right},${selected.bounds.bottom}",
                monospace = true,
            )
            selected.properties.forEach { (name, value) -> Field(name, value, monospace = true) }
        }
        if (snapshot.truncated) Notice(stringResource(R.string.pulse_layout_truncated))
        SheetLabel(stringResource(R.string.pulse_layout_nodes))
        rows.forEach { (node, depth) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (node === selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable(role = Role.Button) { selected = node }
                    .padding(start = (depth * INDENT_DP).dp, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (node.kind == "Compose") "◆" else "▪", color = MaterialTheme.colorScheme.primary)
                Text(
                    node.name + (node.id?.let { " #$it" } ?: ""),
                    style = MonospaceStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Preview(snapshot: UiSnapshot, selected: UiNode) {
    val image = remember(snapshot) { snapshot.png?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    if (snapshot.windowWidth <= 0 || snapshot.windowHeight <= 0) return
    val highlight = MaterialTheme.colorScheme.error
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .aspectRatio(snapshot.windowWidth.toFloat() / snapshot.windowHeight, matchHeightConstraintsFirst = true)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            ?: Text(
                stringResource(R.string.pulse_layout_no_image),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(Spacing.s),
            )
        Canvas(Modifier.fillMaxSize()) {
            val scaleX = size.width / snapshot.windowWidth
            val scaleY = size.height / snapshot.windowHeight
            val rect = selected.bounds
            drawRect(
                highlight.copy(alpha = FILL_ALPHA),
                Offset(rect.left * scaleX, rect.top * scaleY),
                Size(
                    rect.width() * scaleX,
                    rect.height() * scaleY,
                ),
            )
            drawRect(
                highlight,
                Offset(rect.left * scaleX, rect.top * scaleY),
                Size(rect.width() * scaleX, rect.height() * scaleY),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

private fun flatten(node: UiNode, depth: Int): List<Pair<UiNode, Int>> =
    listOf(node to depth) + node.children.flatMap { flatten(it, depth + 1) }

private const val INDENT_DP = 10
private const val FILL_ALPHA = 0.18f
