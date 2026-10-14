package com.naury.pulse.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 저장된 트랜잭션을 읽고 수집을 제어하는 계약. UI 모듈은 이 인터페이스만 사용한다.
 *
 * 모든 메서드는 어느 스레드에서 불러도 된다. suspend 함수는 내부에서 I/O 디스패처로 옮겨 실행한다.
 */
@Suppress("TooManyFunctions")
public interface PulseInspector {
    public val recordingState: StateFlow<RecordingState>

    public val captureHealth: StateFlow<CaptureHealth>

    /** 초기화에 쓴 설정. 화면의 설정 탭이 보여 준다. */
    public val config: PulseConfig

    /** 모의 응답 규칙. no-op에서는 항상 꺼져 있고 바꿀 수 없다. */
    public val mocks: PulseMocks

    /**
     * [query]에 맞는 트랜잭션을 최신순으로 [limit]개까지 읽는다.
     *
     * @param after 이전 페이지의 [TransactionPage.next]. null이면 가장 최신부터 읽는다.
     * @param limit 1..500.
     */
    public suspend fun loadTransactions(query: TransactionQuery, after: PageCursor?, limit: Int): TransactionPage

    /** 저장된 트랜잭션이 바뀔 때마다 값을 낸다. 바뀐 내용은 담지 않으므로 필요한 쪽이 다시 읽는다. */
    public fun observeChanges(): Flow<Unit>

    /** 트랜잭션 상세. 없거나 지워졌으면 null. */
    public suspend fun loadTransaction(id: String): TransactionDetail?

    /** 저장한(마스킹된) 본문. [BodyState.CAPTURED]가 아니거나 파일이 지워졌으면 null. */
    public suspend fun loadBody(id: String, kind: MessageKind): String?

    /** [window] 기간의 트래픽 집계. 앱이 실제로 보낸 요청만 센다. */
    public suspend fun loadStatistics(window: StatsWindow): TrafficStatistics

    /** [query] 조건의 트래픽 집계. 기본 구현은 기간만 쓴다. */
    public suspend fun loadStatistics(query: StatsQuery): TrafficStatistics = loadStatistics(query.window)

    /** 원격 보고 상태와 실행 중 설정. 원격 보고를 지원하지 않는 구현은 null. */
    public val remote: PulseRemote?
        get() = null

    /** 네트워크 장애 모사. [PulseConfig.chaos]가 없거나 지원하지 않는 구현은 null. */
    public val chaos: PulseChaos?
        get() = null

    /** 버그 보고 작성·전송. 지원하지 않는 구현은 null. */
    public val reports: PulseReports?
        get() = null

    /** 호스트가 알린 기능 플래그·환경 값([Pulse.setFlag]). */
    public val flags: StateFlow<Map<String, String>>
        get() = EMPTY_FLAGS

    /** 앱·기기 환경과 플래그. 지원하지 않는 구현은 null. */
    public fun environment(): EnvironmentSnapshot? = null

    /** 앱 이벤트·세션이 바뀔 때마다 값을 낸다. 앱 수집이 없는 구현은 아무것도 내지 않는다. */
    public fun observeEvents(): Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()

    /** [query]에 맞는 앱 이벤트를 최신순으로 [limit]개(1..500)까지 읽는다. 앱 수집이 없는 구현은 빈 페이지. */
    public suspend fun loadEvents(query: EventQuery, after: PageCursor?, limit: Int): EventPage = EventPage(emptyList(), null)

    /** [fromEpochMillis]..[toEpochMillis]에 걸친 앱 실행(세션)을 최신순으로 [limit]개까지. null이면 그쪽 끝 제한 없음. */
    public suspend fun loadSessions(fromEpochMillis: Long?, toEpochMillis: Long?, limit: Int): List<SessionSummary> = emptyList()

    /** 앱 실행 시간·크래시·시작 시간·프레임·메모리 집계. 앱 수집이 없는 구현은 null. */
    public suspend fun loadAppStatistics(fromEpochMillis: Long?, toEpochMillis: Long?): AppStatistics? = null

    /** 앱 이벤트 하나. 없거나 지워졌으면 null. */
    public suspend fun loadEvent(id: String): AppEvent? = null

    /** 이벤트 첨부(화면 캡처 PNG). 없거나 지워졌으면 null. */
    public suspend fun loadAttachment(name: String): ByteArray? = null

    /**
     * 요청과 앱 이벤트를 한 목록으로 최신순 [limit]개(1..500)까지 읽는다. 앱 수집이 없는 구현은 빈 페이지.
     *
     * @param after 이전 페이지의 [TimelinePage.next].
     */
    public suspend fun loadTimeline(query: TimelineQuery, after: PageCursor?, limit: Int): TimelinePage = TimelinePage(emptyList(), null)

    /**
     * [id] 항목과 연결된 근거. 같은 구간·재실행 원본처럼 기록에 남은 연결은 [LinkStrength.EXPLICIT],
     * 같은 실행·같은 화면은 [LinkStrength.CONTEXTUAL], 앞뒤 [windowMillis] 안에 일어난 것은 [LinkStrength.TEMPORAL]이다.
     * 연결이 강한 순, 가까운 순으로 최대 50개.
     */
    public suspend fun loadRelated(
        id: String,
        kind: TimelineItemKind,
        windowMillis: Long = DEFAULT_RELATED_WINDOW_MILLIS,
    ): List<EvidenceLink> = emptyList()

    /** 두 실행(세션)을 비교한다. 한쪽 실행이 없거나 지원하지 않는 구현은 null. 실행마다 요청·이벤트를 최대 20,000개까지 본다. */
    public suspend fun compareSessions(baseSessionId: String, targetSessionId: String): SessionDiff? = null

    /** 지금 시각에 마커를 남긴다. 화면(플로팅 버튼 등)에서 쓴다. */
    public fun mark(name: String) {}

    /** 구간을 시작하고 구간 ID를 돌려준다. 기록하지 않는 구현은 null. */
    public fun beginSpan(name: String): String? = null

    /** [spanId] 구간을 끝낸다. 없는 ID면 아무것도 하지 않는다. */
    public fun endSpan(spanId: String) {}

    /** 화면 캡처(PNG)를 이벤트로 남긴다. */
    public fun recordScreenshot(png: ByteArray, note: String?) {}

    /** [window] 기간 기록에 진단 규칙을 적용한 결과. 심각도가 높은 순, 같은 심각도는 근거가 많은 순이다. */
    public suspend fun loadInsights(window: StatsWindow): List<Insight>

    /** 저장된 요청으로 재실행 초안을 만든다. 재실행이 꺼져 있거나 요청이 없으면 null. */
    public suspend fun replayDraft(transactionId: String): ReplayDraft?

    /**
     * [draft]를 다시 보낸다. 정책([PulseConfig.replay])을 먼저 검사하고, 상태를 바꿀 수 있는 요청이나 자격 증명을 붙이는 요청은
     * [confirmed]가 `true`여야 보낸다. 결과는 [CaptureSource.REPLAY]로 따로 기록된다.
     */
    public suspend fun replay(draft: ReplayDraft, confirmed: Boolean): ReplayOutcome

    /** 기록을 멈춘다. 이미 진행 중인 요청은 끝까지 기록한다. */
    public fun pause()

    /** 기록을 다시 시작한다. */
    public fun resume()

    /** 저장된 트랜잭션과 본문을 모두 지운다. 지우는 작업은 백그라운드에서 진행한다. */
    public fun clear()

    public companion object {
        /** [loadRelated]가 시간상 가깝다고 보는 기본 범위(앞뒤 ms). */
        public const val DEFAULT_RELATED_WINDOW_MILLIS: Long = 5_000

        private val EMPTY_FLAGS: StateFlow<Map<String, String>> = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    }
}

/** 목록 조회 조건. */
public class TransactionQuery @JvmOverloads constructor(
    /** 마스킹한 URL에서 찾을 문자열. 빈 문자열이면 조건 없음. 대소문자를 구분하지 않는다. */
    public val text: String = "",
    public val status: StatusFilter = StatusFilter.ALL,
    /** 이 시각(epoch ms) 이후에 시작한 요청만. null이면 제한 없음. */
    public val fromEpochMillis: Long? = null,
    /** 이 시각(epoch ms) 이전에 시작한 요청만. null이면 제한 없음. */
    public val toEpochMillis: Long? = null,
    /** 이 앱 실행(세션)의 요청만. null이면 제한 없음. */
    public val sessionId: String? = null,
    /** 이 구간 안에서 시작한 요청만([RequestContext.spanId]). null이면 제한 없음. */
    public val spanId: String? = null,
) {
    private fun values(): List<Any?> = listOf(text, status, fromEpochMillis, toEpochMillis, sessionId, spanId)

    override fun equals(other: Any?): Boolean = other is TransactionQuery && values() == other.values()

    override fun hashCode(): Int = values().hashCode()

    override fun toString(): String =
        "TransactionQuery(text=$text, status=$status, from=$fromEpochMillis, to=$toEpochMillis, session=$sessionId, span=$spanId)"
}

/** 상태 필터. */
public enum class StatusFilter {
    ALL,

    /** 응답 코드 1xx·2xx·3xx. */
    SUCCESS,

    /** 응답 코드 4xx. */
    CLIENT_ERROR,

    /** 응답 코드 5xx. */
    SERVER_ERROR,

    /** 응답 없이 실패·취소·중단된 요청. */
    FAILED,

    /** 아직 끝나지 않은 요청. */
    IN_FLIGHT,
}

/** 다음 페이지 위치. 내용은 구현에 속하므로 값을 만들거나 해석하지 않는다. */
public class PageCursor(public val startedAtEpochMillis: Long, public val id: String) {
    override fun equals(other: Any?): Boolean = other is PageCursor && startedAtEpochMillis == other.startedAtEpochMillis && id == other.id

    override fun hashCode(): Int = 31 * startedAtEpochMillis.hashCode() + id.hashCode()
}

/** 한 페이지. [next]가 null이면 마지막 페이지다. */
public class TransactionPage(public val items: List<TransactionSummary>, public val next: PageCursor?)

/** 설정이 맞지 않거나 잘못된 상태에서 API를 부르면 던진다. 네트워크 호출 경로에서는 던지지 않는다. */
public class PulseConfigurationException(message: String) : IllegalStateException(message)
