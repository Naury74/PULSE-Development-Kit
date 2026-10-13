package com.naury.pulse.ui

import com.naury.pulse.api.BodyState
import com.naury.pulse.api.ByteTotals
import com.naury.pulse.api.CaptureHealth
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.DurationPercentiles
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.HttpMessage
import com.naury.pulse.api.Insight
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.RecordingState
import com.naury.pulse.api.ReplayBlockReason
import com.naury.pulse.api.ReplayDraft
import com.naury.pulse.api.ReplayOutcome
import com.naury.pulse.api.ResponseSource
import com.naury.pulse.api.StatsWindow
import com.naury.pulse.api.StatusCounts
import com.naury.pulse.api.StatusFilter
import com.naury.pulse.api.TimingPhase
import com.naury.pulse.api.TimingPhaseType
import com.naury.pulse.api.TrafficStatistics
import com.naury.pulse.api.TransactionDetail
import com.naury.pulse.api.TransactionPage
import com.naury.pulse.api.TransactionQuery
import com.naury.pulse.api.TransactionState
import com.naury.pulse.api.TransactionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeInspector(var details: List<TransactionDetail> = emptyList()) : PulseInspector {
    override var config: PulseConfig = PulseConfig.debugDefaults()
    var statistics: TrafficStatistics? = null
    var insights: List<Insight> = emptyList()

    override suspend fun loadStatistics(window: StatsWindow): TrafficStatistics = statistics ?: TrafficStatistics(
        window, null, 0, StatusCounts(0, 0, 0, 0, 0, 0), DurationPercentiles(0, null, null, null), ByteTotals(0, 0, 0),
        emptyList(), emptyList(), emptyList(), false,
    )

    override suspend fun loadInsights(window: StatsWindow): List<Insight> = insights

    var replayOutcome: ReplayOutcome = ReplayOutcome.Blocked(ReplayBlockReason.DISABLED)
    val replayed = mutableListOf<Pair<ReplayDraft, Boolean>>()

    override suspend fun replayDraft(transactionId: String): ReplayDraft? = loadTransaction(transactionId)?.let {
        if (config.replay ==
            null
        ) {
            null
        } else {
            ReplayDraft(transactionId, it.summary.method, it.summary.url, emptyList(), null, null, listOf("Authorization"))
        }
    }

    override suspend fun replay(draft: ReplayDraft, confirmed: Boolean): ReplayOutcome {
        replayed += draft to confirmed
        return replayOutcome
    }

    override val recordingState = MutableStateFlow(RecordingState.RECORDING)
    override val captureHealth = MutableStateFlow(CaptureHealth())
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    var cleared = false
    val bodies = mutableMapOf<Pair<String, MessageKind>, String>()

    override suspend fun loadTransactions(query: TransactionQuery, after: PageCursor?, limit: Int): TransactionPage {
        val items = details.map { it.summary }.filter { summary ->
            summary.url.contains(query.text, ignoreCase = true) &&
                when (query.status) {
                    StatusFilter.CLIENT_ERROR -> summary.statusCode in 400..499
                    StatusFilter.SERVER_ERROR -> (summary.statusCode ?: 0) >= 500
                    StatusFilter.ALL -> true
                    else -> false
                }
        }
        return TransactionPage(items, null)
    }

    override fun observeChanges(): Flow<Unit> = changes

    override suspend fun loadTransaction(id: String): TransactionDetail? = details.firstOrNull { it.summary.id == id }

    override suspend fun loadBody(id: String, kind: MessageKind): String? = bodies[id to kind]

    override fun pause() {
        recordingState.value = RecordingState.PAUSED
    }

    override fun resume() {
        recordingState.value = RecordingState.RECORDING
    }

    override fun clear() {
        cleared = true
        details = emptyList()
        changes.tryEmit(Unit)
    }

    companion object {
        fun detail(
            id: String,
            path: String,
            status: Int? = 200,
            state: TransactionState = TransactionState.COMPLETED,
            responseBody: BodyState = BodyState.CAPTURED,
            timings: List<TimingPhase> = listOf(TimingPhase(TimingPhaseType.RESPONSE_HEADERS, 0, 1_000_000, 2_000_000)),
            parentId: String? = null,
        ) = TransactionDetail(
            summary = TransactionSummary(
                id = id,
                sessionId = "s",
                method = "GET",
                url = "https://api.test$path",
                host = "api.test",
                path = path,
                startedAtEpochMillis = 0,
                durationNanos = 5_000_000,
                statusCode = status,
                state = state,
                failureType = if (state == TransactionState.FAILED) "java.net.SocketTimeoutException" else null,
                requestBodyBytes = 0,
                responseBodyBytes = 10,
                source = if (parentId == null) CaptureSource.ORIGINAL else CaptureSource.REPLAY,
                parentTransactionId = parentId,
            ),
            protocol = "h2",
            responseHeadersNanos = 3_000_000,
            failureMessage = null,
            request = HttpMessage(listOf(HeaderField("Authorization", "[REDACTED]")), null, null, BodyState.EMPTY, null, 0),
            response = status?.let {
                HttpMessage(listOf(HeaderField("Content-Type", "application/json")), "application/json", 10, responseBody, 10, 1)
            },
            timings = timings,
            responseSource = ResponseSource.NETWORK,
            connectionReused = true,
        )
    }
}
