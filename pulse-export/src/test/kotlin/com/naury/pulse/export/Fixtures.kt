package com.naury.pulse.export

import com.naury.pulse.api.BodyState
import com.naury.pulse.api.CaptureHealth
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.HttpMessage
import com.naury.pulse.api.Insight
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.MockRule
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.PulseMocks
import com.naury.pulse.api.RecordingState
import com.naury.pulse.api.ReplayBlockReason
import com.naury.pulse.api.ReplayDraft
import com.naury.pulse.api.ReplayOutcome
import com.naury.pulse.api.StatsWindow
import com.naury.pulse.api.TimingPhase
import com.naury.pulse.api.TrafficStatistics
import com.naury.pulse.api.TransactionDetail
import com.naury.pulse.api.TransactionPage
import com.naury.pulse.api.TransactionQuery
import com.naury.pulse.api.TransactionState
import com.naury.pulse.api.TransactionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

internal fun detail(
    id: String,
    method: String = "GET",
    url: String = "https://api.test/items?page=1",
    requestHeaders: List<HeaderField> = listOf(HeaderField("Accept", "application/json")),
    requestBodyState: BodyState = BodyState.EMPTY,
    timings: List<TimingPhase> = emptyList(),
    startedAt: Long = 0,
) = TransactionDetail(
    summary = TransactionSummary(
        id, "s", method, url, "api.test", "/items", startedAt, 120_000_000, 200, TransactionState.COMPLETED, null, 0, 7,
        CaptureSource.ORIGINAL,
    ),
    protocol = "h2",
    responseHeadersNanos = 80_000_000,
    failureMessage = null,
    request = HttpMessage(requestHeaders, "application/json", null, requestBodyState, null, 0),
    response = HttpMessage(listOf(HeaderField("Content-Type", "application/json")), "application/json", 7, BodyState.CAPTURED, 7, 0),
    timings = timings,
)

/** 페이지 단위로 넘겨주는 메모리 인스펙터. */
internal class PagedInspector(private val details: List<TransactionDetail>, private val bodies: Map<String, String>) : PulseInspector {
    var pagesRead = 0
    override val recordingState = MutableStateFlow(RecordingState.RECORDING)
    override val captureHealth = MutableStateFlow(CaptureHealth())
    override val config: PulseConfig = PulseConfig.debugDefaults()
    override val mocks: PulseMocks = FakeMocks()

    override suspend fun loadTransactions(query: TransactionQuery, after: PageCursor?, limit: Int): TransactionPage {
        pagesRead++
        val start = after?.let { cursor -> details.indexOfFirst { it.summary.id == cursor.id } + 1 } ?: 0
        val items = details.drop(start).take(limit).map { it.summary }
        val next = if (start + limit < details.size) items.last().let { PageCursor(it.startedAtEpochMillis, it.id) } else null
        return TransactionPage(items, next)
    }

    override fun observeChanges(): Flow<Unit> = emptyFlow()

    override suspend fun loadTransaction(id: String) = details.firstOrNull { it.summary.id == id }

    override suspend fun loadBody(id: String, kind: MessageKind): String? = bodies["$id/${kind.name}"]

    override suspend fun loadStatistics(window: StatsWindow): TrafficStatistics = error("사용하지 않음")

    override suspend fun loadInsights(window: StatsWindow): List<Insight> = emptyList()

    override suspend fun replayDraft(transactionId: String) = null

    override suspend fun replay(draft: ReplayDraft, confirmed: Boolean): ReplayOutcome = ReplayOutcome.Blocked(ReplayBlockReason.DISABLED)

    override fun pause() = Unit

    override fun resume() = Unit

    override fun clear() = Unit
}

class FakeMocks : PulseMocks {
    override val enabled = MutableStateFlow(false)
    override val rules = MutableStateFlow<List<MockRule>>(emptyList())

    override fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override fun upsert(rule: MockRule) {
        rules.value = rules.value.filterNot { it.id == rule.id } + rule
    }

    override fun remove(ruleId: String) {
        rules.value = rules.value.filterNot { it.id == ruleId }
    }

    override fun disableAll() {
        enabled.value = false
    }
}
