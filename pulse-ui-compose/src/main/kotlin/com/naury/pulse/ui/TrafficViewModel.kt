package com.naury.pulse.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.StatusFilter
import com.naury.pulse.api.TransactionQuery
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

/** 검색어·상태 필터를 [SavedStateHandle]에 두어 화면 회전과 프로세스 재시작 뒤에도 유지한다. */
internal class TrafficViewModel(private val inspector: PulseInspector, private val state: SavedStateHandle) : ViewModel() {
    val searchText: StateFlow<String> = state.getStateFlow(KEY_TEXT, "")
    val status: StateFlow<StatusFilter> = state.getStateFlow(KEY_STATUS, StatusFilter.ALL)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val transactions: Flow<PagingData<TransactionSummary>> =
        combine(searchText.debounce(SEARCH_DEBOUNCE_MILLIS), status) { text, status -> TransactionQuery(text, status) }
            .distinctUntilChanged()
            .flatMapLatest { query ->
                Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) { TransactionPagingSource(inspector, query) }.flow
            }
            .cachedIn(viewModelScope)

    fun search(text: String) {
        state[KEY_TEXT] = text
    }

    fun filter(status: StatusFilter) {
        state[KEY_STATUS] = status
    }

    private companion object {
        const val KEY_TEXT = "pulse.search"
        const val KEY_STATUS = "pulse.status"
        const val SEARCH_DEBOUNCE_MILLIS = 250L
        const val PAGE_SIZE = 50
    }
}

internal class TransactionPagingSource(private val inspector: PulseInspector, private val query: TransactionQuery) :
    PagingSource<PageCursor, TransactionSummary>() {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun load(params: LoadParams<PageCursor>): LoadResult<PageCursor, TransactionSummary> = try {
        val page = inspector.loadTransactions(query, params.key, params.loadSize.coerceIn(1, MAX_LOAD))
        LoadResult.Page(data = page.items, prevKey = null, nextKey = page.next)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        // DB가 닫혔거나 잠긴 경우 목록에 다시 시도를 보여 준다.
        LoadResult.Error(error)
    }

    // 최신순 목록은 새로고침하면 맨 위부터 다시 읽는다. 사용자가 아래에 있을 때는 화면이 새로고침하지 않고 알림만 띄운다.
    override fun getRefreshKey(state: PagingState<PageCursor, TransactionSummary>): PageCursor? = null

    private companion object {
        const val MAX_LOAD = 500
    }
}
