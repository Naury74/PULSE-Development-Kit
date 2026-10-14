package com.naury.pulse.api

/** 통합 타임라인 항목의 출처. */
public enum class TimelineItemKind { EVENT, REQUEST }

/**
 * 두 항목이 얼마나 확실하게 이어져 있는지.
 *
 * 시간상 가깝다는 것([TEMPORAL])은 원인과 결과라는 뜻이 아니다. 화면에서도 등급을 구분해 보여 준다.
 */
public enum class LinkStrength {
    /** 기록에 연결이 남아 있다(같은 구간, 재실행 원본, 같은 실행의 크래시와 종료 이유). */
    EXPLICIT,

    /** 같은 실행·같은 화면에서 일어났다. */
    CONTEXTUAL,

    /** 앞뒤 몇 초 안에 일어났을 뿐이다. */
    TEMPORAL,
}

/**
 * 연결된 근거 하나.
 *
 * @property reason 연결 근거: `span`, `replay`, `crash-exit`, `screen`, `time`.
 * @property offsetMillis 기준 항목보다 얼마나 뒤(양수)·앞(음수)에 일어났는지(ms).
 */
public class EvidenceLink(
    public val targetId: String,
    public val targetKind: TimelineItemKind,
    public val strength: LinkStrength,
    public val reason: String,
    public val offsetMillis: Long,
    public val title: String,
)

/** 타임라인 한 줄. [kind]에 따라 [event]나 [request] 중 하나만 있다. */
public class TimelineItem(
    public val id: String,
    public val timestampEpochMillis: Long,
    public val kind: TimelineItemKind,
    public val event: AppEvent?,
    public val request: TransactionSummary?,
)

/**
 * 타임라인 조건.
 *
 * @property eventKinds 이 종류의 이벤트만. 비어 있으면 전체.
 * @property includeRequests 요청도 함께 보여 줄지.
 * @property text 이벤트 메시지·태그와 요청 URL에서 찾을 글자.
 */
public class TimelineQuery @JvmOverloads constructor(
    public val sessionId: String? = null,
    public val fromEpochMillis: Long? = null,
    public val toEpochMillis: Long? = null,
    public val text: String = "",
    public val eventKinds: Set<EventKind> = emptySet(),
    public val includeRequests: Boolean = true,
    public val spanId: String? = null,
) {
    private fun values(): List<Any?> = listOf(sessionId, fromEpochMillis, toEpochMillis, text, eventKinds, includeRequests, spanId)

    override fun equals(other: Any?): Boolean = other is TimelineQuery && values() == other.values()

    override fun hashCode(): Int = values().hashCode()

    override fun toString(): String = "TimelineQuery(${values().joinToString()})"
}

public class TimelinePage(public val items: List<TimelineItem>, public val next: PageCursor?)

/**
 * 앱·기기 환경과 호스트가 알린 기능 플래그. 보고와 재현 패키지에 함께 들어간다.
 * 플래그 값은 마스킹하지 않으므로 비밀을 넣지 않는다.
 */
public class EnvironmentSnapshot(
    public val app: Map<String, String>,
    public val device: Map<String, String>,
    public val flags: Map<String, String>,
    public val capturedAtEpochMillis: Long,
)
