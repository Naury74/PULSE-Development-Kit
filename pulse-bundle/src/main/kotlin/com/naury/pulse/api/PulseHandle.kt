package com.naury.pulse.api

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.naury.pulse.analytics.AppStatisticsCalculator
import com.naury.pulse.analytics.InsightEngine
import com.naury.pulse.analytics.SessionDiffCalculator
import com.naury.pulse.analytics.SessionMaterial
import com.naury.pulse.analytics.StatisticsCalculator
import com.naury.pulse.core.CaptureEngine
import com.naury.pulse.mock.ChaosRegistry
import com.naury.pulse.mock.MockInterceptor
import com.naury.pulse.mock.MockRegistry
import com.naury.pulse.monitor.AppMonitor
import com.naury.pulse.monitor.EnvironmentRegistry
import com.naury.pulse.monitor.LocalDataReader
import com.naury.pulse.monitor.RemoteReporter
import com.naury.pulse.monitor.ReportService
import com.naury.pulse.okhttp.CallTimingRegistry
import com.naury.pulse.okhttp.PulseEventListenerFactory
import com.naury.pulse.okhttp.PulseInterceptor
import com.naury.pulse.replay.ReplayDrafts
import com.naury.pulse.replay.ReplayEngine
import com.naury.pulse.storage.OutboxFilter
import com.naury.pulse.storage.PulseStorage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * 초기화된 PULSE 인스턴스. OkHttpClient에 붙일 [interceptor]·[eventListenerFactory]를 주고, 저장된 기록을 읽는다.
 * 모든 메서드는 스레드 안전하다.
 */
@Suppress("TooManyFunctions")
public class PulseHandle private constructor(
    override val config: PulseConfig,
    private val storage: PulseStorage,
    private val engine: CaptureEngine,
    private val appContext: AndroidAppContext,
    context: Context,
    private val reporter: RemoteReporter,
) : PulseInspector {
    override val remote: PulseRemote = reporter

    // 디스크 정리·삭제 실패는 수집만 못 하게 할 뿐 호스트 앱을 멈추면 안 된다.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val timingRegistry = CallTimingRegistry()
    private val interceptor = PulseInterceptor(engine.recorder, timingRegistry)
    private val events = engine.events
    private val monitor = AppMonitor(context, config.monitoring, events, storage, scope)
    private val insightEngine = InsightEngine(config.insights)
    private val mockRegistry = MockRegistry()
    private val chaosRegistry = config.chaos?.let(::ChaosRegistry)
    private val mockInterceptor = MockInterceptor(mockRegistry, chaosRegistry)

    override val chaos: PulseChaos? = chaosRegistry

    override val localData: PulseLocalData? =
        config.dataInspection?.let { options ->
            LocalDataReader(context.applicationContext, options, config.redaction) { name, key, removed ->
                // 값은 남기지 않는다. 무엇을 바꿨는지만 타임라인에 보인다.
                events.record(EventKind.CONFIG, "$name.$key ${if (removed) "(removed)" else "(edited)"}", tag = "pulse.data")
            }
        }
    private val environment = EnvironmentRegistry(context.applicationContext, events)
    private val reportService = ReportService(config, storage, { this }, environment::snapshot, scope)

    override val reports: PulseReports = reportService

    override val mocks: PulseMocks = mockRegistry

    @Volatile
    private var replayAuthorizer: ReplayAuthorizer? = null

    // 재실행은 호스트 클라이언트와 분리된 클라이언트로 보내며, 같은 수집 파이프라인에 REPLAY로 기록된다.
    // TLS 설정을 만드는 비용이 있어 첫 재실행 때(I/O 스레드) 만든다.
    private val replayEngine by lazy {
        config.replay?.let { policy ->
            val client = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .eventListenerFactory(PulseEventListenerFactory(engine.recorder, timingRegistry))
                .build()
            ReplayEngine(policy, client) { replayAuthorizer }
        }
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

    override fun observeEvents(): Flow<Unit> = storage.observeEventChanges()

    override suspend fun loadEvents(query: EventQuery, after: PageCursor?, limit: Int): EventPage = storage.loadEvents(query, after, limit)

    override suspend fun loadSessions(fromEpochMillis: Long?, toEpochMillis: Long?, limit: Int): List<SessionSummary> =
        storage.loadSessions(fromEpochMillis, toEpochMillis, limit, events.sessionId)

    override suspend fun loadAppStatistics(fromEpochMillis: Long?, toEpochMillis: Long?): AppStatistics {
        val to = toEpochMillis ?: System.currentTimeMillis()
        val input = storage.loadAppStatisticsInput(fromEpochMillis, to, events.sessionId, MAX_STATISTICS_EVENTS)
        return withContext(Dispatchers.Default) { AppStatisticsCalculator.compute(input, fromEpochMillis, to) }
    }

    override suspend fun loadAttachment(name: String): ByteArray? = storage.loadAttachment(name)

    override suspend fun loadEvent(id: String): AppEvent? = storage.loadEvent(id)

    override suspend fun loadTimeline(query: TimelineQuery, after: PageCursor?, limit: Int): TimelinePage =
        storage.loadTimeline(query, after, limit)

    override suspend fun loadRelated(id: String, kind: TimelineItemKind, windowMillis: Long): List<EvidenceLink> =
        storage.loadRelated(id, kind, windowMillis)

    override val flags: StateFlow<Map<String, String>> = environment.flags

    override suspend fun compareSessions(baseSessionId: String, targetSessionId: String): SessionDiff? {
        val sessions = storage.loadSessions(null, null, MAX_SESSIONS_FOR_DIFF, events.sessionId).associateBy { it.id }
        val base = sessions[baseSessionId]?.let { material(it) } ?: return null
        val target = sessions[targetSessionId]?.let { material(it) } ?: return null
        return withContext(Dispatchers.Default) { SessionDiffCalculator.compute(base, target, config.endpoints) }
    }

    private suspend fun material(session: SessionSummary): SessionMaterial {
        val requests = ArrayList<TransactionSummary>()
        var cursor: PageCursor? = null
        do {
            val page = storage.loadTransactions(TransactionQuery(sessionId = session.id), cursor, DIFF_PAGE)
            requests += page.items
            cursor = page.next
        } while (cursor != null && requests.size < MAX_DIFF_ITEMS)
        val events = ArrayList<AppEvent>()
        var eventCursor: PageCursor? = null
        do {
            val page = storage.loadEvents(EventQuery(sessionId = session.id, kinds = DIFF_KINDS), eventCursor, DIFF_PAGE)
            events += page.items
            eventCursor = page.next
        } while (eventCursor != null && events.size < MAX_DIFF_ITEMS)
        return SessionMaterial(session, requests, events, cursor != null || eventCursor != null)
    }

    override fun environment(): EnvironmentSnapshot = environment.snapshot()

    /**
     * 사용자 행동을 남긴다. [id]는 `checkout.submit`처럼 의미가 드러나는 이름(64자까지)을 쓰고, 입력값 원문은 넣지 않는다.
     * [attributes]는 32개, 값은 256자까지 남기며 비밀로 보이는 값은 가린다.
     */
    @JvmOverloads
    public fun action(id: String, attributes: Map<String, String> = emptyMap()) {
        events.action(id, attributes)
    }

    /** 지금 화면 이름을 알린다(Compose 경로 등). 요청·이벤트의 화면으로 쓰이고, 바뀌면 화면 이동으로 남는다. */
    public fun screen(name: String) {
        appContext.screen = name
        events.navigate(name)
    }

    /**
     * 기능 플래그·환경 값(서버 환경, 실험 그룹 등)을 알린다. null이면 지운다. 값이 바뀔 때만 이력을 남긴다.
     * 값은 마스킹하지 않으므로 비밀을 넣지 않는다. 이름 64자·값 256자·200개까지.
     */
    public fun setFlag(name: String, value: String?) {
        environment.setFlag(name, value)
    }

    /**
     * 로그를 남긴다. [throwable]이 있으면 스택을 함께 저장한다. 메시지 속 URL 쿼리와 `name=value` 형태의 비밀은 저장 전에 가린다.
     * 어느 스레드에서 불러도 되고 디스크 작업은 저장 스레드에서 한다.
     */
    @JvmOverloads
    public fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        events.record(EventKind.LOG, message, level = level, tag = tag, detail = throwable?.stackTraceToString())
    }

    /** 앱이 잡아서 처리한 예외를 오류로 남긴다. */
    @JvmOverloads
    public fun recordError(throwable: Throwable, message: String? = null) {
        events.record(
            EventKind.ERROR,
            message ?: "${throwable.javaClass.name}: ${throwable.message.orEmpty()}",
            level = LogLevel.ERROR,
            tag = throwable.javaClass.simpleName,
            detail = throwable.stackTraceToString(),
        )
    }

    /** 지금 시각에 [name] 마커를 남긴다. 기록 화면에서 이 시점 앞뒤를 따로 볼 수 있다. */
    override fun mark(name: String) {
        events.mark(name)
    }

    /** [name] 구간을 시작하고 ID를 돌려준다. [endSpan]으로 끝낸다. 기록 화면에서 이 구간만 따로 볼 수 있다. */
    override fun beginSpan(name: String): String = events.beginSpan(name)

    override fun endSpan(spanId: String) {
        events.endSpan(spanId)
    }

    override fun recordScreenshot(png: ByteArray, note: String?) {
        events.record(EventKind.SCREENSHOT, note ?: "Screenshot", attachmentPng = png)
    }

    override suspend fun loadTransaction(id: String): TransactionDetail? = storage.loadTransaction(id)

    override suspend fun loadBody(id: String, kind: MessageKind): String? = storage.loadBody(id, kind)

    override suspend fun loadStatistics(window: StatsWindow): TrafficStatistics = loadStatistics(StatsQuery(window))

    override suspend fun loadStatistics(query: StatsQuery): TrafficStatistics {
        val now = System.currentTimeMillis()
        val from = query.fromEpochMillis ?: query.window.fromEpochMillis(now)
        val to = query.toEpochMillis ?: now
        val (samples, limited) = storage.loadSamples(from, TrafficStatistics.MAX_SAMPLES, query.includeSynthetic, to)
        return withContext(Dispatchers.Default) { StatisticsCalculator.compute(query, samples, to, limited, config.endpoints) }
    }

    override suspend fun loadInsights(window: StatsWindow): List<Insight> {
        val (samples, _) = storage.loadSamples(window.fromEpochMillis(System.currentTimeMillis()), TrafficStatistics.MAX_SAMPLES)
        return withContext(Dispatchers.Default) { insightEngine.evaluate(samples) }
    }

    override suspend fun replayDraft(transactionId: String): ReplayDraft? {
        if (config.replay == null) return null
        val detail = storage.loadTransaction(transactionId) ?: return null
        val body = if (detail.request.bodyState == BodyState.CAPTURED) storage.loadBody(transactionId, MessageKind.REQUEST) else null
        return ReplayDrafts.from(detail, body)
    }

    override suspend fun replay(draft: ReplayDraft, confirmed: Boolean): ReplayOutcome = withContext(Dispatchers.IO) {
        replayEngine?.execute(draft, confirmed) ?: ReplayOutcome.Blocked(ReplayBlockReason.DISABLED)
    }

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

    @Volatile
    private var startup: Job? = null

    /** 기록을 멈추고 남은 기록을 저장한 뒤 자원을 닫는다. 닫은 뒤에는 [Pulse.initialize]로 새 핸들을 만들 수 있다. */
    public fun close() {
        Pulse.release(this)
        monitor.close()
        reporter.close()
        engine.close()
        appContext.close()
        // 시작 정리(세션 기록·오래된 기록 삭제)가 DB를 쓰는 중에 닫으면, 바로 다시 연 핸들의 쓰기가 잠금에 막힐 수 있다.
        startup?.let { job -> runBlocking { withTimeoutOrNull(STARTUP_JOIN_MILLIS) { job.join() } } }
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
        private const val MAX_STATISTICS_EVENTS = 50_000
        private const val STARTUP_JOIN_MILLIS = 2_000L
        private const val MAX_SESSIONS_FOR_DIFF = 5_000
        private const val MAX_DIFF_ITEMS = 20_000
        private const val DIFF_PAGE = 500
        private val DIFF_KINDS = EventKind.entries.toSet() - EventKind.LOG

        fun create(context: Context, config: PulseConfig): PulseHandle {
            // 보낼 목록에 넣을지는 저장 스레드에서 정하므로, 실행 중에 원격 보고를 켜고 끈 값이 바로 반영되게 늦게 묶는다.
            val reporterHolder = arrayOfNulls<RemoteReporter>(1)
            val remote = config.remote
            val filter = OutboxFilter(
                kinds = remote?.kinds ?: RemoteReporting.DEFAULT_KINDS,
                minLogLevel = remote?.minLogLevel,
                networkFailures = remote?.networkFailures ?: true,
                maxPending = remote?.maxPending ?: RemoteReporting.DEFAULT_MAX_PENDING,
                enabled = { reporterHolder[0]?.isActive() == true },
            )
            val storage = PulseStorage.open(context, config.retention, filter)
            val appContext = AndroidAppContext(context, config.context)
            val engine = CaptureEngine.start(config, storage.sink, contextSource = appContext)
            appContext.spanSource = { engine.events.currentSpanId }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
            val reporter = RemoteReporter(context.applicationContext, remote, { storage }, scope).also { reporterHolder[0] = it }
            return PulseHandle(config, storage, engine, appContext, context, reporter).also { handle ->
                handle.monitor.start()
                reporter.start()
                handle.reportService.uploader.start()
                handle.startup = handle.scope.launch {
                    storage.startSession(engine.recorder.sessionId, System.currentTimeMillis())
                    handle.monitor.recordExitReasons()
                }
            }
        }
    }
}
