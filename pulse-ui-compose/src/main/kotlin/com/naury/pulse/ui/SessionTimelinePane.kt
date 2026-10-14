package com.naury.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.TimelineItem
import com.naury.pulse.api.TimelineItemKind
import com.naury.pulse.api.TimelineQuery
import com.naury.pulse.api.TransactionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/** 통합 타임라인에서 고르는 묶음. [requests]가 true면 요청도 함께 보여 준다. */
internal enum class TimelineFilter(val label: Int, val kinds: Set<EventKind>, val requests: Boolean) {
    // 일반 로그는 양이 많아 요청·행동·오류를 덮으므로 "주요"에서 빼고 "로그"에서 본다.
    ALL(R.string.pulse_timeline_all, EventKind.entries.toSet() - EventKind.LOG, true),
    REQUESTS(R.string.pulse_timeline_requests, setOf(EventKind.MARKER), true),
    ISSUES(R.string.pulse_events_issues, EventGroup.ISSUES.kinds, false),
    ACTIONS(R.string.pulse_events_actions, EventGroup.ACTIONS.kinds + EventGroup.MARKERS.kinds, false),
    LOGS(R.string.pulse_events_logs, EventGroup.LOGS.kinds, false),
    PERFORMANCE(R.string.pulse_events_performance, EventGroup.PERFORMANCE.kinds, false),
}

/** 통합 타임라인의 검색어·묶음. 기간은 [range]로 받는다. */
internal class TimelineViewModel(
    private val inspector: PulseInspector,
    private val state: SavedStateHandle,
    private val range: StateFlow<TimeRange>,
) : ViewModel() {
    val text: StateFlow<String> = state.getStateFlow(KEY_TEXT, "")
    val filter: StateFlow<TimelineFilter> = state.getStateFlow(KEY_FILTER, TimelineFilter.ALL)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val items: Flow<PagingData<TimelineItem>> =
        combine(text.debounce(SEARCH_DEBOUNCE_MILLIS), filter, range) { text, filter, range ->
            TimelineQuery(
                sessionId = range.sessionId,
                fromEpochMillis = range.from,
                toEpochMillis = range.to,
                text = text,
                eventKinds = filter.kinds,
                includeRequests = filter.requests,
            )
        }
            .distinctUntilChanged()
            .flatMapLatest { query ->
                Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) { TimelineSource(inspector, query) }.flow
            }
            .cachedIn(viewModelScope)

    fun search(value: String) {
        state[KEY_TEXT] = value
    }

    fun select(value: TimelineFilter) {
        state[KEY_FILTER] = value
    }

    private companion object {
        const val KEY_TEXT = "pulse.timeline.text"
        const val KEY_FILTER = "pulse.timeline.filter"
        const val SEARCH_DEBOUNCE_MILLIS = 250L
        const val PAGE_SIZE = 100
    }
}

private class TimelineSource(private val inspector: PulseInspector, private val query: TimelineQuery) :
    PagingSource<PageCursor, TimelineItem>() {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun load(params: LoadParams<PageCursor>): LoadResult<PageCursor, TimelineItem> = try {
        val page = inspector.loadTimeline(query, params.key, params.loadSize.coerceIn(1, MAX_LOAD))
        LoadResult.Page(data = page.items, prevKey = null, nextKey = page.next)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        LoadResult.Error(error)
    }

    override fun getRefreshKey(state: PagingState<PageCursor, TimelineItem>): PageCursor? = null

    private companion object {
        const val MAX_LOAD = 500
    }
}

/**
 * 한 실행(또는 고른 기간)의 요청·로그·오류·행동·성능을 시간순 한 목록으로 보여 준다.
 * 이벤트를 누르면 상세와 연결된 근거를, 요청을 누르면 요청 상세를 연다.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun SessionTimelinePane(
    inspector: PulseInspector,
    viewModel: TimelineViewModel,
    range: TimeRange,
    onRangeChange: (TimeRange) -> Unit,
    onOpenRequest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    var opened by remember { mutableStateOf<AppEvent?>(null) }
    LaunchedEffect(items, inspector) {
        merge(inspector.observeEvents(), inspector.observeChanges()).debounce(CHANGE_DEBOUNCE_MILLIS).onStart { emit(Unit) }.collect {
            if (listState.firstVisibleItemIndex == 0) items.refresh()
        }
    }
    Column(modifier) {
        TimelineSearch(text, viewModel::search)
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.l, vertical = Spacing.xs),
        ) {
            TimeRangeChip(inspector, range, onRangeChange)
            TimelineFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { viewModel.select(option) },
                    label = { Text(stringResource(option.label)) },
                    shape = MaterialTheme.shapes.small,
                    colors = dashboardChipColors(),
                    border = dashboardChipBorder(filter == option),
                )
            }
        }
        Box(Modifier.weight(1f)) {
            if (items.itemCount == 0) {
                Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.pulse_flow_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(count = items.itemCount, key = items.itemKey { it.kind.name + it.id }) { index ->
                        items[index]?.let { item ->
                            TimelineRow(item) {
                                item.event?.let { opened = it } ?: onOpenRequest(item.id)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
    opened?.let { event ->
        EventDetailSheet(inspector, event, onRangeChange, onDismiss = { opened = null }, onOpenRequest = {
            opened = null
            onOpenRequest(it)
        }, onOpenEvent = { opened = it })
    }
}

@Composable
private fun TimelineSearch(text: String, onChange: (String) -> Unit) {
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
        placeholder = { Text(stringResource(R.string.pulse_timeline_search)) },
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, top = Spacing.s),
    )
}

@Composable
private fun TimelineRow(item: TimelineItem, onClick: () -> Unit) {
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
        Text(
            Formatters.clockTime(item.timestampEpochMillis),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp).padding(top = 2.dp),
        )
        when (item.kind) {
            TimelineItemKind.REQUEST -> RequestLine(item.request!!)
            TimelineItemKind.EVENT -> EventLine(item.event!!)
        }
    }
}

@Composable
private fun RowScope.RequestLine(request: TransactionSummary) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            MethodTag(request.method)
            Text(
                request.path,
                style = MonospaceStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            StatusBadge(request)
        }
        Text(
            listOfNotNull(request.host, request.context?.screen, request.durationNanos?.let(Formatters::duration)).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun RowScope.EventLine(event: AppEvent) {
    Pill(eventBadge(event), eventColor(event), Modifier.padding(top = 1.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(eventTitle(event), style = MonospaceStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(event.tag?.takeIf { event.kind == EventKind.LOG || event.kind == EventKind.ERROR }, event.screen)
        if (meta.isNotEmpty()) {
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val CHANGE_DEBOUNCE_MILLIS = 500L

/** 행동은 속성(`{"scenario":"GET_JSON"}`)을 `scenario=GET_JSON`으로 붙여 무엇을 했는지 한 줄로 보여 준다. */
internal fun eventTitle(event: AppEvent): String {
    val detail = event.detail?.takeIf { event.kind == EventKind.ACTION } ?: return event.message
    val attributes = ATTRIBUTE.findAll(detail).joinToString(" ") { "${it.groupValues[1]}=${it.groupValues[2]}" }
    return if (attributes.isEmpty()) event.message else "${event.message} $attributes"
}

private val ATTRIBUTE = Regex("\"([^\"]+)\":\"([^\"]*)\"")
