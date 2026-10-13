// no-op은 pulse-bundle과 시그니처를 맞추려고 인자를 쓰지 않고 고정값을 돌려준다.
@file:Suppress("FunctionOnlyReturningConstant", "UnusedParameter")

package com.naury.pulse.api

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.EventListener
import okhttp3.Interceptor

/**
 * 기록하지 않는 핸들. [interceptor]는 요청을 그대로 넘기고, [eventListenerFactory]는 넘겨받은 팩토리를 그대로 돌려준다.
 * 조회 결과는 항상 비어 있고 [recordingState]는 [RecordingState.DISABLED]다.
 */
public class PulseHandle internal constructor(override val config: PulseConfig) : PulseInspector {
    override val recordingState: StateFlow<RecordingState> = MutableStateFlow(RecordingState.DISABLED).asStateFlow()

    override val captureHealth: StateFlow<CaptureHealth> = MutableStateFlow(CaptureHealth()).asStateFlow()

    override val mocks: PulseMocks = NoMocks

    public fun interceptor(): Interceptor = PassThroughInterceptor

    public fun mockInterceptor(): Interceptor = PassThroughInterceptor

    @JvmOverloads
    public fun eventListenerFactory(delegate: EventListener.Factory? = null): EventListener.Factory = delegate ?: NoEvents

    public fun open(context: Context): Boolean = false

    override suspend fun loadTransactions(query: TransactionQuery, after: PageCursor?, limit: Int): TransactionPage =
        TransactionPage(emptyList(), null)

    override fun observeChanges(): Flow<Unit> = emptyFlow()

    override suspend fun loadTransaction(id: String): TransactionDetail? = null

    override suspend fun loadBody(id: String, kind: MessageKind): String? = null

    override suspend fun loadStatistics(window: StatsWindow): TrafficStatistics = TrafficStatistics(
        window = window,
        fromEpochMillis = null,
        toEpochMillis = System.currentTimeMillis(),
        counts = StatusCounts(0, 0, 0, 0, 0, 0),
        durations = DurationPercentiles(0, null, null, null),
        bytes = ByteTotals(0, 0, 0),
        buckets = emptyList(),
        topHosts = emptyList(),
        slowestEndpoints = emptyList(),
        sampleLimitReached = false,
    )

    override suspend fun loadInsights(window: StatsWindow): List<Insight> = emptyList()

    override suspend fun replayDraft(transactionId: String): ReplayDraft? = null

    override suspend fun replay(draft: ReplayDraft, confirmed: Boolean): ReplayOutcome = ReplayOutcome.Blocked(ReplayBlockReason.DISABLED)

    public fun setReplayAuthorizer(authorizer: ReplayAuthorizer?): Unit = Unit

    override fun pause(): Unit = Unit

    override fun resume(): Unit = Unit

    override fun clear(): Unit = Unit

    public fun close(): Unit = Unit

    private object PassThroughInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain) = chain.proceed(chain.request())
    }

    private object NoMocks : PulseMocks {
        override val enabled: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val rules: StateFlow<List<MockRule>> = MutableStateFlow(emptyList<MockRule>()).asStateFlow()

        override fun setEnabled(enabled: Boolean): Unit = Unit

        override fun upsert(rule: MockRule): Unit = Unit

        override fun remove(ruleId: String): Unit = Unit

        override fun disableAll(): Unit = Unit
    }

    private object NoEvents : EventListener.Factory {
        override fun create(call: okhttp3.Call): EventListener = EventListener.NONE
    }
}
