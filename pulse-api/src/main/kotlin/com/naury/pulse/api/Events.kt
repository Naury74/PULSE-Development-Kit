package com.naury.pulse.api

/** 로그 수준. 순서대로 무겁다. */
public enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR, ASSERT }

/** 네트워크 요청 말고 앱에서 일어난 일의 종류. */
public enum class EventKind {
    /** 앱 로그. [Pulse.log]로 남기거나, 설정하면 이 프로세스의 logcat에서 가져온다. */
    LOG,

    /** 한 시점에 찍은 표시. */
    MARKER,

    /** 구간 시작. 같은 [AppEvent.spanId]의 [SPAN_END]와 짝을 이룬다. */
    SPAN_START,

    /** 구간 끝. [AppEvent.durationMillis]에 구간 길이가 있다. */
    SPAN_END,

    /** 앱이 잡아서 처리한 예외([Pulse.recordError]). */
    ERROR,

    /** 처리되지 않은 예외로 앱이 죽었다. 죽기 직전에 기록한다. */
    CRASH,

    /** 메인 스레드가 기준 시간보다 오래 멈췄다. [AppEvent.detail]에 그때의 메인 스레드 스택이 있다. */
    MAIN_THREAD_BLOCKED,

    /** 지난 실행이 끝난 이유(Android 11 이상의 ApplicationExitInfo). 네이티브 크래시·ANR·메모리 부족 종료 등을 다음 실행 때 기록한다. */
    EXIT,

    /** 프로세스 시작부터 첫 화면이 그려질 때까지. */
    STARTUP,

    /** 화면(Activity)이 만들어져 처음 그려질 때까지. */
    SCREEN_LOAD,

    /** 한 화면에 머무는 동안의 프레임 요약. [AppEvent.detail]에 전체·느린·멈춘 프레임 수가 있다. */
    FRAMES,

    /** 메모리 사용량 표본. [AppEvent.value]는 바이트. */
    MEMORY,

    /** 화면 캡처. [AppEvent.attachment]로 이미지를 읽는다. */
    SCREENSHOT,

    /** 앱이 포그라운드·백그라운드로 바뀌었다. */
    LIFECYCLE,

    /** 사용자 행동([Pulse.action]). [AppEvent.message]는 `checkout.submit` 같은 행동 ID, [AppEvent.detail]은 속성 JSON이다. */
    ACTION,

    /** 화면 이동. Activity 전환은 자동으로, Compose 경로 등은 [Pulse.screen]으로 남는다. [AppEvent.message]는 `이전 → 새 화면`. */
    NAVIGATION,

    /** 환경 값·기능 플래그가 바뀌었다([Pulse.setFlag]). [AppEvent.tag]는 이름, [AppEvent.message]는 `이름 = 새 값`. */
    CONFIG,
}

/**
 * 앱에서 일어난 일 하나. 문자열 값은 저장 전에 [RedactionPolicy]로 URL 쿼리와 `name=value` 형태의 비밀을 가린 것이다.
 *
 * @property timestampEpochMillis 일어난 시각(epoch ms).
 * @property level [EventKind.LOG]·[EventKind.ERROR]·[EventKind.CRASH]의 수준. 그 밖에는 null.
 * @property detail 스택 트레이스 등 긴 내용. 최대 [MAX_DETAIL_CHARS]자.
 * @property screen 그때 앞에 있던 Activity 이름.
 * @property spanId 구간에 속하면 구간 ID.
 * @property durationMillis 길이가 있는 일(구간·시작·화면 로딩·멈춤)의 길이(ms).
 * @property value 수치 하나(메모리 바이트 등).
 * @property attachment 첨부 파일 이름(화면 캡처). [PulseInspector.loadAttachment]로 읽는다.
 */
@Suppress("LongParameterList")
public class AppEvent(
    public val id: String,
    public val sessionId: String,
    public val timestampEpochMillis: Long,
    public val kind: EventKind,
    public val level: LogLevel?,
    public val tag: String?,
    public val message: String,
    public val detail: String? = null,
    public val screen: String? = null,
    public val spanId: String? = null,
    public val durationMillis: Long? = null,
    public val value: Long? = null,
    public val attachment: String? = null,
) {
    public companion object {
        public const val MAX_MESSAGE_CHARS: Int = 4_000
        public const val MAX_DETAIL_CHARS: Int = 64_000
    }
}

/**
 * 이벤트 질의. 모든 조건을 함께 만족하는 것만 돌려준다.
 *
 * @property kinds 이 종류만. 비어 있으면 전체.
 * @property minLevel 수준이 있는 이벤트는 이 수준 이상만. 수준이 없는 이벤트(마커 등)는 거르지 않는다.
 * @property text 메시지·태그·상세에 들어 있는 글자(대소문자 무시).
 * @property fromEpochMillis 이 시각 이후(포함).
 * @property toEpochMillis 이 시각 이전(포함).
 */
public class EventQuery @JvmOverloads constructor(
    public val kinds: Set<EventKind> = emptySet(),
    public val minLevel: LogLevel? = null,
    public val text: String = "",
    public val tag: String? = null,
    public val fromEpochMillis: Long? = null,
    public val toEpochMillis: Long? = null,
    public val sessionId: String? = null,
    /** 이 구간의 이벤트만. */
    public val spanId: String? = null,
) {
    private fun values(): List<Any?> = listOf(kinds, minLevel, text, tag, fromEpochMillis, toEpochMillis, sessionId, spanId)

    override fun equals(other: Any?): Boolean = other is EventQuery && values() == other.values()

    override fun hashCode(): Int = values().hashCode()

    override fun toString(): String =
        "EventQuery(kinds=$kinds, minLevel=$minLevel, text=$text, tag=$tag, from=$fromEpochMillis, to=$toEpochMillis, " +
            "session=$sessionId, span=$spanId)"
}

public class EventPage(public val items: List<AppEvent>, public val next: PageCursor?)

/**
 * 앱 실행(프로세스) 하나.
 *
 * @property lastSeenEpochMillis 마지막으로 살아 있던 것을 확인한 시각. 지금 실행 중이면 계속 늘어난다.
 * @property foregroundMillis 화면에 보인 시간 합(ms).
 * @property exitReason 끝난 이유. 지금 실행 중이거나 알 수 없으면 null.
 * @property crashed 처리되지 않은 예외나 네이티브 크래시·ANR로 끝났는지.
 */
public class SessionSummary(
    public val id: String,
    public val startedAtEpochMillis: Long,
    public val lastSeenEpochMillis: Long,
    public val foregroundMillis: Long,
    public val exitReason: String?,
    public val crashed: Boolean,
    public val current: Boolean,
)

/** 밀리초 값의 백분위수(nearest-rank). 값이 없으면 [count]가 0이고 나머지는 null. */
public class MillisPercentiles(public val count: Int, public val p50: Long?, public val p95: Long?, public val max: Long?)

/**
 * 화면 하나의 성능.
 *
 * @property loads 화면이 만들어져 처음 그려진 횟수.
 * @property frames 관찰한 전체 프레임 수.
 * @property slowFrames 16ms를 넘긴 프레임 수.
 * @property frozenFrames 700ms를 넘긴 프레임 수.
 */
@Suppress("LongParameterList")
public class ScreenPerformance(
    public val screen: String,
    public val loads: Int,
    public val loadP50Millis: Long?,
    public val loadP95Millis: Long?,
    public val frames: Long,
    public val slowFrames: Long,
    public val frozenFrames: Long,
)

/** 하루(기기 시간대 기준 0시부터) 동안의 앱 사용. */
public class DayUsage(
    public val dayStartEpochMillis: Long,
    public val sessions: Int,
    public val runtimeMillis: Long,
    public val foregroundMillis: Long,
    public val crashes: Int,
    public val requests: Int,
)

/**
 * 기간 안의 앱 상태 집계. 실행 시간은 세션의 시작부터 마지막으로 살아 있던 시각까지다.
 *
 * @property crashFreeSessions 크래시·ANR·네이티브 크래시 없이 끝났거나 실행 중인 세션 수.
 * @property exitReasons 지난 실행이 끝난 이유별 수.
 * @property logLevels 로그 수준별 수.
 * @property memoryPeakBytes 기록한 메모리 표본 중 가장 큰 값.
 */
@Suppress("LongParameterList")
public class AppStatistics(
    public val sessions: Int,
    public val crashFreeSessions: Int,
    public val runtimeMillis: Long,
    public val foregroundMillis: Long,
    public val averageSessionMillis: Long?,
    public val crashes: Int,
    public val errors: Int,
    public val mainThreadBlocks: Int,
    public val exitReasons: List<CountEntry>,
    public val startup: MillisPercentiles,
    public val screens: List<ScreenPerformance>,
    public val memoryPeakBytes: Long?,
    public val memoryAverageBytes: Long?,
    public val logLevels: Map<LogLevel, Int>,
    public val days: List<DayUsage>,
    public val markers: Int,
)
