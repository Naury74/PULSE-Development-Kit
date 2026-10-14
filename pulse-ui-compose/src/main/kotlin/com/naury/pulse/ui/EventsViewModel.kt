package com.naury.pulse.ui

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.EventQuery
import com.naury.pulse.api.LogLevel
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.PulseInspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

/** 로그 탭에서 이벤트를 묶어 거르는 단위. */
internal enum class EventGroup(@param:StringRes val label: Int, val kinds: Set<EventKind>) {
    ALL(R.string.pulse_events_all, emptySet()),
    LOGS(R.string.pulse_events_logs, setOf(EventKind.LOG)),
    MARKERS(R.string.pulse_events_markers, setOf(EventKind.MARKER, EventKind.SPAN_START, EventKind.SPAN_END, EventKind.SCREENSHOT)),
    ISSUES(R.string.pulse_events_issues, setOf(EventKind.ERROR, EventKind.CRASH, EventKind.MAIN_THREAD_BLOCKED, EventKind.EXIT)),
    PERFORMANCE(R.string.pulse_events_performance, setOf(EventKind.STARTUP, EventKind.SCREEN_LOAD, EventKind.FRAMES, EventKind.MEMORY)),
    ACTIONS(R.string.pulse_events_actions, setOf(EventKind.ACTION, EventKind.NAVIGATION, EventKind.CONFIG)),
    LIFECYCLE(R.string.pulse_events_lifecycle, setOf(EventKind.LIFECYCLE)),
}

/** 로그 탭의 검색어·묶음·최소 수준을 저장하고 이벤트 페이지를 만든다. 기간은 [range]로 받는다. */
internal class EventsViewModel(
    private val inspector: PulseInspector,
    private val state: SavedStateHandle,
    private val range: StateFlow<TimeRange>,
) : ViewModel() {
    val text: StateFlow<String> = state.getStateFlow(KEY_TEXT, "")
    val group: StateFlow<EventGroup> = state.getStateFlow(KEY_GROUP, EventGroup.ALL)
    val minLevel: StateFlow<LogLevel?> = state.getStateFlow(KEY_LEVEL, null)

    /** 지금 화면의 조건. 내보내기에서 같은 조건을 쓴다. */
    fun query(): EventQuery = query(text.value, group.value, minLevel.value, range.value)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val events: Flow<PagingData<AppEvent>> =
        combine(text.debounce(SEARCH_DEBOUNCE_MILLIS), group, minLevel, range) { text, group, level, range ->
            query(text, group, level, range)
        }
            .distinctUntilChanged()
            .flatMapLatest { query ->
                Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) { EventPagingSource(inspector, query) }.flow
            }
            .cachedIn(viewModelScope)

    fun search(value: String) {
        state[KEY_TEXT] = value
    }

    fun select(value: EventGroup) {
        state[KEY_GROUP] = value
    }

    fun level(value: LogLevel?) {
        state[KEY_LEVEL] = value
    }

    private fun query(text: String, group: EventGroup, level: LogLevel?, range: TimeRange) = EventQuery(
        kinds = group.kinds,
        minLevel = level,
        text = text,
        fromEpochMillis = range.from,
        toEpochMillis = range.to,
        sessionId = range.sessionId,
    )

    private companion object {
        const val KEY_TEXT = "pulse.events.text"
        const val KEY_GROUP = "pulse.events.group"
        const val KEY_LEVEL = "pulse.events.level"
        const val SEARCH_DEBOUNCE_MILLIS = 250L
        const val PAGE_SIZE = 100
    }
}

internal class EventPagingSource(private val inspector: PulseInspector, private val query: EventQuery) :
    PagingSource<PageCursor, AppEvent>() {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun load(params: LoadParams<PageCursor>): LoadResult<PageCursor, AppEvent> = try {
        val page = inspector.loadEvents(query, params.key, params.loadSize.coerceIn(1, MAX_LOAD))
        LoadResult.Page(data = page.items, prevKey = null, nextKey = page.next)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        LoadResult.Error(error)
    }

    override fun getRefreshKey(state: PagingState<PageCursor, AppEvent>): PageCursor? = null

    private companion object {
        const val MAX_LOAD = 500
    }
}
