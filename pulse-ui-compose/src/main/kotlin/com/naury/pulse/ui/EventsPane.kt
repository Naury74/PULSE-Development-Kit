package com.naury.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.LogLevel
import com.naury.pulse.api.PulseInspector
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart

private const val CHANGE_DEBOUNCE_MILLIS = 500L

/** 로그 탭. 로그·마커·구간·오류·성능 이벤트를 시간순으로 보고, 묶음·수준·글자·기간으로 거르고, 그대로 내보낸다. */
@OptIn(FlowPreview::class)
@Composable
internal fun EventsPane(
    inspector: PulseInspector,
    viewModel: EventsViewModel,
    range: TimeRange,
    onRangeChange: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
    onOpenRequest: ((String) -> Unit)? = null,
) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val group by viewModel.group.collectAsStateWithLifecycle()
    val minLevel by viewModel.minLevel.collectAsStateWithLifecycle()
    val items = viewModel.events.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<AppEvent?>(null) }
    var exporting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(items, inspector) {
        inspector.observeEvents().debounce(CHANGE_DEBOUNCE_MILLIS).onStart { emit(Unit) }.collect {
            if (listState.firstVisibleItemIndex == 0) items.refresh()
        }
    }
    Column(modifier) {
        EventSearch(text, viewModel::search) { exporting = true }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.l, vertical = Spacing.xs),
        ) {
            TimeRangeChip(inspector, range, onRangeChange)
            EventGroup.entries.forEach { option ->
                FilterChip(
                    selected = group == option,
                    onClick = { viewModel.select(option) },
                    label = { Text(stringResource(option.label)) },
                    shape = MaterialTheme.shapes.small,
                    colors = dashboardChipColors(),
                    border = dashboardChipBorder(group == option),
                )
            }
        }
        LevelChips(minLevel, viewModel::level)
        EventList(items, listState, Modifier.weight(1f)) { opened = it }
    }
    opened?.let { event ->
        EventDetailSheet(
            inspector,
            event,
            onRangeChange,
            onDismiss = { opened = null },
            onOpenRequest = onOpenRequest?.let { open ->
                { id: String ->
                    opened = null
                    open(id)
                }
            },
            onOpenEvent = { opened = it },
        )
    }
    if (exporting) EventExportSheet(inspector, viewModel.query(), scope) { exporting = false }
}

@Composable
private fun EventSearch(text: String, onChange: (String) -> Unit, onExport: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.s)) {
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = {
                        onChange("")
                    }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.pulse_clear_search)) }
                }
            },
            placeholder = { Text(stringResource(R.string.pulse_events_search)) },
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onExport) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.pulse_events_export)) }
    }
}

@Composable
private fun LevelChips(selected: LogLevel?, onSelect: (LogLevel?) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.l, vertical = Spacing.xs),
    ) {
        Text(
            stringResource(R.string.pulse_events_min_level),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        (listOf<LogLevel?>(null) + LogLevel.entries.dropLast(1)).forEach { level ->
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
                label = { Text(level?.name?.take(1) ?: stringResource(R.string.pulse_events_level_any)) },
                shape = MaterialTheme.shapes.small,
                colors = dashboardChipColors(),
                border = dashboardChipBorder(selected == level),
            )
        }
    }
}

@Composable
private fun EventRow(event: AppEvent, onClick: () -> Unit) {
    val accent = eventColor(event)
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.Top,
    ) {
        Pill(eventBadge(event), accent, Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                event.tag?.let {
                    Text(
                        it,
                        style = MonospaceStyle,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                Text(
                    Formatters.clockTime(event.timestampEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                event.durationMillis?.let {
                    Text("${it}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(event.message, style = MonospaceStyle, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EventList(items: LazyPagingItems<AppEvent>, listState: LazyListState, modifier: Modifier, onOpen: (AppEvent) -> Unit) {
    Box(modifier) {
        if (items.itemCount == 0) {
            Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.pulse_events_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                    items[index]?.let { event ->
                        EventRow(event) { onOpen(event) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/** 목록·상세에서 이벤트 종류와 수준을 짧게 보여 주는 글자. */
internal fun eventBadge(event: AppEvent): String =
    if (event.kind == EventKind.LOG) event.level?.name?.take(1) ?: "L" else EVENT_BADGES.getValue(event.kind)

private val EVENT_BADGES = mapOf(
    EventKind.LOG to "L",
    EventKind.MARKER to "MARK",
    EventKind.SPAN_START to "SPAN ▶",
    EventKind.SPAN_END to "SPAN ■",
    EventKind.ERROR to "ERROR",
    EventKind.CRASH to "CRASH",
    EventKind.MAIN_THREAD_BLOCKED to "JANK",
    EventKind.EXIT to "EXIT",
    EventKind.STARTUP to "START",
    EventKind.SCREEN_LOAD to "SCREEN",
    EventKind.FRAMES to "FRAMES",
    EventKind.MEMORY to "MEM",
    EventKind.SCREENSHOT to "SHOT",
    EventKind.LIFECYCLE to "APP",
    EventKind.ACTION to "ACT",
    EventKind.NAVIGATION to "NAV",
    EventKind.CONFIG to "FLAG",
)

@Composable
internal fun eventColor(event: AppEvent): Color {
    val status = LocalStatusColors.current
    return when {
        event.kind == EventKind.CRASH || event.kind == EventKind.ERROR -> status.serverError

        event.kind == EventKind.EXIT && event.level == LogLevel.ERROR -> status.serverError

        event.kind == EventKind.MAIN_THREAD_BLOCKED -> status.slow

        event.kind in setOf(
            EventKind.MARKER,
            EventKind.SPAN_START,
            EventKind.SPAN_END,
            EventKind.SCREENSHOT,
        ) -> MaterialTheme.colorScheme.primary

        event.kind != EventKind.LOG -> status.redirect

        else -> levelColor(event.level)
    }
}

@Composable
internal fun levelColor(level: LogLevel?): Color {
    val status = LocalStatusColors.current
    return when (level) {
        LogLevel.ERROR, LogLevel.ASSERT -> status.serverError
        LogLevel.WARN -> status.clientError
        LogLevel.INFO -> status.success
        LogLevel.DEBUG -> status.redirect
        else -> status.pending
    }
}
