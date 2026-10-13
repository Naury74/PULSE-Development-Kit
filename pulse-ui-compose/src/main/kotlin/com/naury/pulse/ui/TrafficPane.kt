package com.naury.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.StatusFilter
import com.naury.pulse.api.TransactionSummary
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

private const val CHANGE_DEBOUNCE_MILLIS = 300L
private const val NANOS_PER_MILLI = 1_000_000L

@OptIn(FlowPreview::class)
@Composable
internal fun TrafficPane(
    inspector: PulseInspector,
    viewModel: TrafficViewModel,
    selectedId: String?,
    onSelect: (TransactionSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchText by viewModel.searchText.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val health by inspector.captureHealth.collectAsStateWithLifecycle()
    val items = viewModel.transactions.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var hasNewItems by rememberSaveable { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    // 사용자가 맨 위에 있으면 바로 새로고침하고, 아래를 보고 있으면 위치를 빼앗지 않고 알림만 띄운다.
    // 다른 탭에 있는 동안 바뀐 내용도 보이도록 화면에 들어올 때 한 번 새로고침한다.
    LaunchedEffect(items, inspector) {
        inspector.observeChanges().debounce(CHANGE_DEBOUNCE_MILLIS).onStart { emit(Unit) }.collect {
            if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) items.refresh() else hasNewItems = true
        }
    }

    Column(modifier) {
        NotificationPrompt()
        SearchField(searchText, viewModel::search)
        StatusFilters(status, viewModel::filter)
        if (health.droppedEvents > 0 || health.droppedBodies > 0) {
            Box(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
                Notice(
                    stringResource(R.string.pulse_health_warning, health.droppedEvents, health.droppedBodies),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val mocksEnabled by inspector.mocks.enabled.collectAsStateWithLifecycle()
        if (mocksEnabled) {
            Box(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
                Notice(stringResource(R.string.pulse_mocks_active_banner), color = MaterialTheme.colorScheme.error)
            }
        }
        if (hasNewItems) {
            FilledTonalButton(
                onClick = {
                    hasNewItems = false
                    items.refresh()
                    scope.launch { listState.scrollToItem(0) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.l, vertical = Spacing.xs),
            ) { Text(stringResource(R.string.pulse_new_items)) }
        }
        Box(Modifier.weight(1f)) {
            // 진단의 느린 요청 기준을 넘은 요청은 목록에서도 소요 시간을 눈에 띄게 보여 준다.
            val slowNanos = inspector.config.insights.slowRequestMillis * NANOS_PER_MILLI
            TransactionList(
                items,
                listState,
                selectedId,
                onSelect,
                slowNanos,
                isFiltered =
                    searchText.isNotBlank() || status != StatusFilter.ALL,
            )
        }
    }
}

@Composable
private fun SearchField(text: String, onChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.pulse_clear_search))
                }
            }
        },
        placeholder = { Text(stringResource(R.string.pulse_search_hint)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.xs),
    )
}

@Composable
private fun StatusFilters(selected: StatusFilter, onSelect: (StatusFilter) -> Unit) {
    val filters = listOf(
        StatusFilter.ALL to R.string.pulse_filter_all,
        StatusFilter.SUCCESS to R.string.pulse_filter_success,
        StatusFilter.CLIENT_ERROR to R.string.pulse_filter_client_error,
        StatusFilter.SERVER_ERROR to R.string.pulse_filter_server_error,
        StatusFilter.FAILED to R.string.pulse_filter_failed,
        StatusFilter.IN_FLIGHT to R.string.pulse_filter_in_flight,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l, vertical = Spacing.xs),
    ) {
        filters.forEach { (filter, label) ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(label)) },
                shape = MaterialTheme.shapes.small,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = filter == selected,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }
    }
}

@Composable
private fun TransactionList(
    items: LazyPagingItems<TransactionSummary>,
    listState: LazyListState,
    selectedId: String?,
    onSelect: (TransactionSummary) -> Unit,
    slowNanos: Long,
    isFiltered: Boolean,
) {
    val focusManager = LocalFocusManager.current
    val refresh = items.loadState.refresh
    when {
        refresh is LoadState.Error && items.itemCount == 0 -> CenteredMessage(stringResource(R.string.pulse_load_failed)) {
            Button(onClick = items::retry) { Text(stringResource(R.string.pulse_retry)) }
        }

        refresh is LoadState.Loading && items.itemCount == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        items.itemCount == 0 && isFiltered -> CenteredMessage(stringResource(R.string.pulse_no_results))

        items.itemCount == 0 -> CenteredMessage(stringResource(R.string.pulse_empty_title), stringResource(R.string.pulse_empty_message))

        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                items[index]?.let { summary ->
                    TransactionRow(summary, selected = summary.id == selectedId, slowNanos = slowNanos, onClick = {
                        focusManager.clearFocus()
                        onSelect(summary)
                    })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(summary: TransactionSummary, selected: Boolean, slowNanos: Long, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val (_, statusColor) = statusLabelAndColor(summary)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 64.dp)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected },
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(statusColor),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.m, end = Spacing.l, top = Spacing.m, bottom = Spacing.m),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    MethodTag(summary.method)
                    Text(
                        text = summary.path,
                        style = MonospaceStyle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    SourceTag(summary)
                    Text(
                        text = summary.host + " · " + Formatters.clockTime(summary.startedAtEpochMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RowTrailing(summary, slowNanos)
        }
    }
}

/** 행 오른쪽의 결과·소요 시간·크기. 느린 요청은 소요 시간 색을 바꾼다. */
@Composable
private fun RowTrailing(summary: TransactionSummary, slowNanos: Long) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        StatusBadge(summary)
        val duration = summary.durationNanos
        Text(
            text = listOfNotNull(
                duration?.let(Formatters::duration),
                summary.responseBodyBytes?.let(Formatters::bytes),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (duration != null &&
                duration >= slowNanos
            ) {
                LocalStatusColors.current.slow
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun CenteredMessage(title: String, message: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
        action?.invoke()
    }
}
