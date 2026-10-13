package com.naury.pulse.api

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.naury.pulse.analytics.InsightEngine
import com.naury.pulse.analytics.StatisticsCalculator
import com.naury.pulse.core.CaptureEngine
import com.naury.pulse.mock.MockInterceptor
import com.naury.pulse.mock.MockRegistry
import com.naury.pulse.okhttp.CallTimingRegistry
import com.naury.pulse.okhttp.PulseEventListenerFactory
import com.naury.pulse.okhttp.PulseInterceptor
import com.naury.pulse.replay.ReplayDrafts
import com.naury.pulse.replay.ReplayEngine
import com.naury.pulse.storage.PulseStorage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * 초기화된 PULSE 인스턴스. OkHttpClient에 붙일 [interceptor]·[eventListenerFactory]를 주고, 저장된 기록을 읽는다.
 * 모든 메서드는 스레드 안전하다.
 */
public class PulseHandle private constructor(
    override val config: PulseConfig,
    private val storage: PulseStorage,
    private val engine: CaptureEngine,
) : PulseInspector {
    // 디스크 정리·삭제 실패는 수집만 못 하게 할 뿐 호스트 앱을 멈추면 안 된다.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val timingRegistry = CallTimingRegistry()
    private val interceptor = PulseInterceptor(engine.recorder, timingRegistry)
    private val insightEngine = InsightEngine(config.insights)
    private val mockRegistry = MockRegistry()
    private val mockInterceptor = MockInterceptor(mockRegistry)

    override val mocks: PulseMocks = mockRegistry

    @Volatile
    private var replayAuthorizer: ReplayAuthorizer? = null

    // 재실행은 호스트 클라이언트와 분리된 클라이언트로 보내며, 같은 수집 파이프라인에 REPLAY로 기록된다.
    private val replayEngine = config.replay?.let { policy ->
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .eventListenerFactory(PulseEventListenerFactory(engine.recorder, timingRegistry))
            .build()
        ReplayEngine(policy, client) { replayAuthorizer }
    }

    override val recordingState: StateFlow<RecordingState> = engine.recorder.controller.recordingState

    override val captureHealth: StateFlow<CaptureHealth> = engine.health

    /**
     * `OkHttpClient.Builder.addInterceptor`에 넣는 application interceptor. 응답 캐시까지 포함한 논리 요청 하나를 한 번 기록한다.
     * 다른 interceptor보다 먼저 넣으면 호스트가 보낸 그대로를, 나중에 넣으면 앞선 interceptor가 바꾼 요청을 기록한다.
     */
    public fun interceptor(): Interceptor = interceptor

    /**
     * 모의 응답 interceptor. 모의 응답을 쓰려면 개발자가 직접 붙여야 하며, [interceptor] **뒤에** 붙여야 모의 응답이 MOCK으로 기록된다.
     * 전체 스위치([PulseMocks.enabled])가 꺼져 있으면(기본) 요청을 그대로 넘긴다.
     */
    public fun mockInterceptor(): Interceptor = mockInterceptor

    /**
     * `OkHttpClient.Builder.eventListenerFactory`에 넣는 팩토리. DNS·연결·TLS·요청·응답 단계 시간을 기록한다.
     * 이미 쓰는 팩토리가 있으면 [delegate]로 넘기면 두 리스너가 모두 이벤트를 받는다.
     */
    @JvmOverloads
    public fun eventListenerFactory(delegate: EventListener.Factory? = null): EventListener.Factory =
        PulseEventListenerFactory(engine.recorder, timingRegistry, delegate)

    /** 인스펙터 화면을 연다. 화면 모듈이 없거나 닫힌 핸들이면 `false`. */
    public fun open(context: Context): Boolean {
        if (recordingState.value == RecordingState.DISABLED) return false
        val component = ComponentName(context.packageName, INSPECTOR_ACTIVITY)
        if (!isDeclared(context, component)) return false
        val intent = Intent().setComponent(component)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    override suspend fun loadTransactions(query: TransactionQuery, after: PageCursor?, limit: Int): TransactionPage =
        storage.loadTransactions(query, after, limit)

    override fun observeChanges(): Flow<Unit> = storage.observeChanges()

    override suspend fun loadTransaction(id: String): TransactionDetail? = storage.loadTransaction(id)

    override suspend fun loadBody(id: String, kind: MessageKind): String? = storage.loadBody(id, kind)

    override suspend fun loadStatistics(window: StatsWindow): TrafficStatistics {
        val now = System.currentTimeMillis()
        val (samples, limited) = storage.loadSamples(window.fromEpochMillis(now), TrafficStatistics.MAX_SAMPLES)
        return withContext(Dispatchers.Default) { StatisticsCalculator.compute(window, samples, now, limited) }
    }

    override suspend fun loadInsights(window: StatsWindow): List<Insight> {
        val (samples, _) = storage.loadSamples(window.fromEpochMillis(System.currentTimeMillis()), TrafficStatistics.MAX_SAMPLES)
        return withContext(Dispatchers.Default) { insightEngine.evaluate(samples) }
    }

    override suspend fun replayDraft(transactionId: String): ReplayDraft? {
        if (replayEngine == null) return null
        val detail = storage.loadTransaction(transactionId) ?: return null
        val body = if (detail.request.bodyState == BodyState.CAPTURED) storage.loadBody(transactionId, MessageKind.REQUEST) else null
        return ReplayDrafts.from(detail, body)
    }

    override suspend fun replay(draft: ReplayDraft, confirmed: Boolean): ReplayOutcome =
        replayEngine?.execute(draft, confirmed) ?: ReplayOutcome.Blocked(ReplayBlockReason.DISABLED)

    /** 재실행에 자격 증명을 붙이는 콜백을 정한다. null이면 자격 증명 없이 보낸다. [PulseConfig.replay]가 없으면 쓰이지 않는다. */
    public fun setReplayAuthorizer(authorizer: ReplayAuthorizer?) {
        replayAuthorizer = authorizer
    }

    override fun pause() {
        engine.recorder.controller.pause()
    }

    override fun resume() {
        engine.recorder.controller.resume()
    }

    override fun clear() {
        scope.launch { storage.clear() }
    }

    /** 기록을 멈추고 남은 기록을 저장한 뒤 자원을 닫는다. 닫은 뒤에는 [Pulse.initialize]로 새 핸들을 만들 수 있다. */
    public fun close() {
        Pulse.release(this)
        engine.close()
        scope.cancel()
        storage.close()
    }

    private fun isDeclared(context: Context, component: ComponentName): Boolean = try {
        context.packageManager.getActivityInfo(component, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun StatsWindow.fromEpochMillis(now: Long): Long = millis?.let { now - it } ?: Long.MIN_VALUE

    internal companion object {
        private const val INSPECTOR_ACTIVITY = "com.naury.pulse.ui.PulseInspectorActivity"

        fun create(context: Context, config: PulseConfig): PulseHandle {
            val storage = PulseStorage.open(context, config.retention)
            val engine = CaptureEngine.start(config, storage.sink)
            return PulseHandle(config, storage, engine).also { handle ->
                handle.scope.launch { storage.startSession(engine.recorder.sessionId, System.currentTimeMillis()) }
            }
        }
    }
}
