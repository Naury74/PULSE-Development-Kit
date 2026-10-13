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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.StatusFilter
import com.naury.pulse.api.TransactionSummary
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

private const val CHANGE_DEBOUNCE_MILLIS = 300L

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

    // 사용자가 맨 위에 있으면 바로 새로고침하고, 아래를 보고 있으면 위치를 빼앗지 않고 알림만 띄운다.
    LaunchedEffect(items, inspector) {
        inspector.observeChanges().debounce(CHANGE_DEBOUNCE_MILLIS).collect {
            if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) items.refresh() else hasNewItems = true
        }
    }

    Column(modifier) {
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
            TransactionList(items, listState, selectedId, onSelect, isFiltered = searchText.isNotBlank() || status != StatusFilter.ALL)
        }
    }
}

@Composable
private fun SearchField(text: String, onChange: (String) -> Unit) {
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
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
            .padding(horizontal = Spacing.l),
    ) {
        filters.forEach { (filter, label) ->
            FilterChip(selected = filter == selected, onClick = { onSelect(filter) }, label = { Text(stringResource(label)) })
        }
    }
}

@Composable
private fun TransactionList(
    items: LazyPagingItems<TransactionSummary>,
    listState: LazyListState,
    selectedId: String?,
    onSelect: (TransactionSummary) -> Unit,
    isFiltered: Boolean,
) {
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
                    TransactionRow(summary, selected = summary.id == selectedId, onClick = { onSelect(summary) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(summary: TransactionSummary, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        StatusBadge(summary)
        Column(Modifier.weight(1f)) {
            Text(
                text = "${summary.method} ${summary.path}",
                style = MonospaceStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    summary.host,
                    Formatters.clockTime(summary.startedAtEpochMillis),
                    summary.durationNanos?.let(Formatters::duration),
                    summary.responseBodyBytes?.let(Formatters::bytes),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
