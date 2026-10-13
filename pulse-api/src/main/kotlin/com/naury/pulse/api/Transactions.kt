package com.naury.pulse.api

/** 수집 상태. */
public enum class RecordingState {
    /** [Pulse.initialize] 전. */
    UNINITIALIZED,

    /** 연결된 OkHttpClient의 요청을 기록한다. */
    RECORDING,

    /** 초기화됐지만 기록을 멈췄다. 멈춘 동안의 요청은 저장되지 않는다. */
    PAUSED,

    /** no-op artifact이거나 종료되어 기록할 수 없다. */
    DISABLED,
}

/** 트랜잭션이 어디서 왔는지. */
public enum class CaptureSource { ORIGINAL, REPLAY, MOCK }

/** 하나의 논리 요청(OkHttp `Call`)의 진행 상태. */
public enum class TransactionState {
    /** 요청을 보냈고 응답 본문이 아직 끝나지 않았다. 응답 헤더를 받았다면 [TransactionSummary.statusCode]가 있다. */
    IN_FLIGHT,

    /** HTTP 응답을 받았다. 4xx·5xx도 여기에 속한다. */
    COMPLETED,

    /** I/O 예외로 끝났다. 응답 헤더를 받은 뒤 본문을 읽다 실패하면 상태 코드와 실패가 함께 있다. */
    FAILED,

    /** 호스트가 `Call`을 취소했다. */
    CANCELED,

    /** 앱 프로세스가 끝나 결과를 모른다. 다음 초기화 때 남은 [IN_FLIGHT]를 이 상태로 바꾼다. */
    INTERRUPTED,
}

/** 요청·응답 중 어느 쪽인지. */
public enum class MessageKind { REQUEST, RESPONSE }

/** 본문을 저장했는지, 저장하지 않았다면 이유. */
public enum class BodyState {
    /** 본문이 없거나 길이가 0이다. */
    EMPTY,

    /** 마스킹한 JSON 본문을 저장했다. */
    CAPTURED,

    /** [CaptureMode.METADATA_ONLY]라 저장하지 않았다. */
    OMITTED_BY_POLICY,

    /** JSON이 아니거나(바이너리·form·multipart 등) 압축된 채로 전달되어 저장하지 않았다. */
    UNSUPPORTED_CONTENT,

    /** [CaptureLimits] 상한을 넘었다. 잘린 본문은 마스킹할 수 없어 저장하지 않는다. */
    TOO_LARGE,

    /** 한 번만 쓸 수 있는 요청 본문이다. 다시 읽지 않는다. */
    ONE_SHOT_UNSUPPORTED,

    /** 양방향 스트리밍 본문이다. */
    DUPLEX_UNSUPPORTED,

    /** 호스트가 본문을 끝까지 읽지 않았다. 일부만 읽은 본문은 저장하지 않는다. */
    INCOMPLETE_READ,

    /** JSON 해석에 실패해 마스킹할 수 없어 저장하지 않았다. */
    REDACTION_FAILED,

    /** 문자 인코딩을 해석하지 못했다. */
    DECODE_FAILED,

    /** 수집 큐가 가득 차거나 저장에 실패해 버렸다. */
    DROPPED,

    /** 아직 결과를 모른다(전송·수신 중이거나 요청이 네트워크로 나가지 않았다). */
    PENDING,
}

/** 목록에 보여 줄 트랜잭션 요약. URL과 실패 메시지는 저장 전에 마스킹된 값이다. */
public class TransactionSummary(
    public val id: String,
    public val sessionId: String,
    public val method: String,
    public val url: String,
    public val host: String,
    public val path: String,
    /** 요청 시작 시각(epoch ms). 표시용이며 시간 계산에는 쓰지 않는다. */
    public val startedAtEpochMillis: Long,
    /** 요청 시작부터 응답 본문이 끝나거나 실패할 때까지(ns, 단조 시계). 끝나지 않았으면 null. */
    public val durationNanos: Long?,
    public val statusCode: Int?,
    public val state: TransactionState,
    /** 실패 예외의 클래스 이름. */
    public val failureType: String?,
    /** 호스트가 실제로 보낸 요청 본문 바이트. 모르면 null. */
    public val requestBodyBytes: Long?,
    /** 호스트가 읽은 응답 본문 바이트(압축 해제 후). 끝까지 읽지 않았거나 모르면 null. */
    public val responseBodyBytes: Long?,
    public val source: CaptureSource,
    /** [CaptureSource.REPLAY]이면 재실행한 원본 트랜잭션 ID. */
    public val parentTransactionId: String? = null,
)

/** 상세 화면용 트랜잭션. */
public class TransactionDetail(
    public val summary: TransactionSummary,
    /** HTTP 프로토콜(`http/1.1`, `h2` 등). 응답을 받지 못했으면 null. */
    public val protocol: String?,
    /** 응답 헤더를 받기까지(ns). 받지 못했으면 null. */
    public val responseHeadersNanos: Long?,
    /** 마스킹한 실패 메시지. */
    public val failureMessage: String?,
    public val request: HttpMessage,
    /** 응답 헤더를 받지 못했으면 null. */
    public val response: HttpMessage?,
    /** `EventListener`가 관찰한 단계만 담는다. 관찰하지 못한 단계는 목록에 없다. */
    public val timings: List<TimingPhase>,
    /** OkHttp 캐시 이벤트로 판단한 응답 출처. 캐시를 쓰지 않는 클라이언트이거나 관찰하지 못했으면 null. */
    public val responseSource: ResponseSource? = null,
    /** 연결을 새로 맺지 않고 재사용했는지. 연결을 얻는 이벤트를 관찰하지 못했으면(캐시 응답 등) null. */
    public val connectionReused: Boolean? = null,
)

/** 응답이 어디서 왔는지. OkHttp `Cache`가 알린 이벤트만 근거로 쓴다. */
public enum class ResponseSource {
    /** 캐시에 없어 네트워크에서 받았다. */
    NETWORK,

    /** 네트워크 없이 캐시에서 받았다. */
    CACHE,

    /** 서버에 조건부 요청을 보내 캐시가 유효함(304)을 확인했다. */
    CONDITIONAL_CACHE,
}

/** 요청 또는 응답 메시지. 헤더 값은 저장 전에 마스킹된 값이다. */
public class HttpMessage(
    public val headers: List<HeaderField>,
    public val contentType: String?,
    /** `Content-Length` 헤더나 본문이 알린 길이. 실제로 주고받은 바이트와 다를 수 있다. */
    public val declaredContentLength: Long?,
    public val bodyState: BodyState,
    /** 저장한 본문 크기(마스킹 후 UTF-8 바이트). */
    public val storedBodyBytes: Long?,
    /** 마스킹으로 가린 값의 개수(헤더·URL은 제외, 본문만). */
    public val redactedValueCount: Int,
)

public class HeaderField(public val name: String, public val value: String) {
    override fun equals(other: Any?): Boolean = other is HeaderField && name == other.name && value == other.value

    override fun hashCode(): Int = 31 * name.hashCode() + value.hashCode()

    override fun toString(): String = "$name: $value"
}

/** OkHttp `EventListener`로 관찰한 네트워크 단계. */
public enum class TimingPhaseType { DNS, CONNECT, TLS, REQUEST_HEADERS, REQUEST_BODY, RESPONSE_HEADERS, RESPONSE_BODY }

/**
 * 관찰한 한 단계.
 *
 * @property exchangeIndex 같은 `Call` 안에서 몇 번째 요청 교환인지(0부터). 리다이렉트·재시도마다 하나씩 늘어난다.
 * @property startOffsetNanos `callStart`부터 단계 시작까지(ns).
 * @property durationNanos 단계 길이(ns). 시작만 관찰하고 끝을 관찰하지 못했으면 null이며 0으로 추정하지 않는다.
 */
public class TimingPhase(
    public val type: TimingPhaseType,
    public val exchangeIndex: Int,
    public val startOffsetNanos: Long,
    public val durationNanos: Long?,
)

/** 수집 파이프라인 상태. 값은 초기화 이후 누적이다. */
public class CaptureHealth(
    /** 큐가 가득 차 버린 이벤트 수. */
    public val droppedEvents: Long = 0,
    /** 메모리 상한 때문에 본문만 버린 횟수. */
    public val droppedBodies: Long = 0,
    /** 큐에 한 번에 쌓였던 최대 이벤트 수. */
    public val queueHighWater: Int = 0,
    /** 저장에 실패한 묶음 수. */
    public val writeFailures: Long = 0,
) {
    override fun equals(other: Any?): Boolean = other is CaptureHealth &&
        droppedEvents == other.droppedEvents &&
        droppedBodies == other.droppedBodies &&
        queueHighWater == other.queueHighWater &&
        writeFailures == other.writeFailures

    override fun hashCode(): Int = listOf(droppedEvents, droppedBodies, queueHighWater, writeFailures).hashCode()

    override fun toString(): String = "CaptureHealth(droppedEvents=$droppedEvents, droppedBodies=$droppedBodies, " +
        "queueHighWater=$queueHighWater, writeFailures=$writeFailures)"
}
