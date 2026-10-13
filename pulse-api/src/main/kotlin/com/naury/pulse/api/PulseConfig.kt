package com.naury.pulse.api

/**
 * PULSE 수집 설정. [Builder]로 만들며 만든 뒤에는 바뀌지 않는다.
 *
 * 같은 값으로 만든 두 설정은 `equals`가 참이고, [Pulse.initialize]는 이 비교로 재초기화가 같은 설정인지 판단한다.
 *
 * @property captureMode 무엇을 저장할지. 기본은 [CaptureMode.METADATA_ONLY]로 본문을 저장하지 않는다.
 * @property limits 본문 크기 상한.
 * @property retention 저장 건수·기간·본문 용량 상한.
 * @property redaction 저장 전에 가릴 헤더·쿼리·JSON 키.
 * @property queueCapacity 저장을 기다리는 수집 이벤트 최대 개수(16..100,000). 넘치면 이벤트를 버리고 [CaptureHealth]에 센다.
 * @property startRecording `false`면 초기화 후 [RecordingState.PAUSED]로 시작한다.
 */
public class PulseConfig private constructor(
    public val captureMode: CaptureMode,
    public val limits: CaptureLimits,
    public val retention: RetentionPolicy,
    public val redaction: RedactionPolicy,
    public val queueCapacity: Int,
    public val startRecording: Boolean,
) {
    public fun newBuilder(): Builder = Builder(this)

    override fun equals(other: Any?): Boolean = other is PulseConfig &&
        captureMode == other.captureMode &&
        limits == other.limits &&
        retention == other.retention &&
        redaction == other.redaction &&
        queueCapacity == other.queueCapacity &&
        startRecording == other.startRecording

    override fun hashCode(): Int = listOf(captureMode, limits, retention, redaction, queueCapacity, startRecording).hashCode()

    override fun toString(): String = "PulseConfig(captureMode=$captureMode, limits=$limits, retention=$retention, " +
        "redaction=$redaction, queueCapacity=$queueCapacity, startRecording=$startRecording)"

    /** [PulseConfig]를 만든다. 값 검증은 [build]에서 하고, 범위를 벗어나면 [IllegalArgumentException]을 던진다. */
    public class Builder() {
        private var captureMode = CaptureMode.METADATA_ONLY
        private var limits = CaptureLimits()
        private var retention = RetentionPolicy()
        private var redaction = RedactionPolicy.strict()
        private var queueCapacity = DEFAULT_QUEUE_CAPACITY
        private var startRecording = true

        internal constructor(config: PulseConfig) : this() {
            captureMode = config.captureMode
            limits = config.limits
            retention = config.retention
            redaction = config.redaction
            queueCapacity = config.queueCapacity
            startRecording = config.startRecording
        }

        public fun captureMode(mode: CaptureMode): Builder = apply { captureMode = mode }

        public fun limits(limits: CaptureLimits): Builder = apply { this.limits = limits }

        public fun retention(retention: RetentionPolicy): Builder = apply { this.retention = retention }

        public fun redaction(redaction: RedactionPolicy): Builder = apply { this.redaction = redaction }

        public fun queueCapacity(capacity: Int): Builder = apply { queueCapacity = capacity }

        public fun startRecording(start: Boolean): Builder = apply { startRecording = start }

        public fun build(): PulseConfig {
            require(queueCapacity in MIN_QUEUE_CAPACITY..MAX_QUEUE_CAPACITY) {
                "queueCapacity는 $MIN_QUEUE_CAPACITY..$MAX_QUEUE_CAPACITY 범위여야 합니다: $queueCapacity"
            }
            return PulseConfig(captureMode, limits, retention, redaction, queueCapacity, startRecording)
        }
    }

    public companion object {
        public const val DEFAULT_QUEUE_CAPACITY: Int = 2_000
        public const val MIN_QUEUE_CAPACITY: Int = 16
        public const val MAX_QUEUE_CAPACITY: Int = 100_000

        /** 메타데이터만 저장하는 기본 설정. */
        @JvmStatic
        public fun defaults(): PulseConfig = Builder().build()

        /** 개발 빌드용 설정: JSON 본문을 마스킹한 뒤 저장한다. 그 밖의 값은 [defaults]와 같다. */
        @JvmStatic
        public fun debugDefaults(): PulseConfig = Builder().captureMode(CaptureMode.METADATA_AND_SAFE_BODIES).build()
    }
}

/** 저장 범위. */
public enum class CaptureMode {
    /** method, 마스킹한 URL·헤더, 상태, 오류 종류, 크기와 타이밍만 저장한다. */
    METADATA_ONLY,

    /**
     * 메타데이터와 함께 JSON 본문을 저장한다. 본문은 [CaptureLimits] 안에서 호스트가 실제로 주고받은 바이트만 복사하고,
     * [RedactionPolicy]로 마스킹에 성공한 경우에만 저장한다. JSON이 아니거나 마스킹할 수 없으면 본문 없이 메타데이터만 남는다.
     */
    METADATA_AND_SAFE_BODIES,
}

/**
 * 본문 크기 상한(바이트). 상한을 넘는 본문은 일부만 저장하지 않고 전부 버린다. 잘린 JSON은 안전하게 마스킹할 수 없기 때문이다.
 *
 * @property maxRequestBodyBytes 요청 본문 상한, 1..[MAX_BODY_BYTES].
 * @property maxResponseBodyBytes 응답 본문 상한, 1..[MAX_BODY_BYTES].
 */
public class CaptureLimits @JvmOverloads constructor(
    public val maxRequestBodyBytes: Long = DEFAULT_MAX_REQUEST_BODY_BYTES,
    public val maxResponseBodyBytes: Long = DEFAULT_MAX_RESPONSE_BODY_BYTES,
) {
    init {
        require(maxRequestBodyBytes in 1..MAX_BODY_BYTES) { "maxRequestBodyBytes는 1..$MAX_BODY_BYTES 범위여야 합니다." }
        require(maxResponseBodyBytes in 1..MAX_BODY_BYTES) { "maxResponseBodyBytes는 1..$MAX_BODY_BYTES 범위여야 합니다." }
    }

    override fun equals(other: Any?): Boolean = other is CaptureLimits &&
        maxRequestBodyBytes == other.maxRequestBodyBytes &&
        maxResponseBodyBytes == other.maxResponseBodyBytes

    override fun hashCode(): Int = 31 * maxRequestBodyBytes.hashCode() + maxResponseBodyBytes.hashCode()

    override fun toString(): String = "CaptureLimits(request=$maxRequestBodyBytes, response=$maxResponseBodyBytes)"

    public companion object {
        public const val DEFAULT_MAX_REQUEST_BODY_BYTES: Long = 128L * 1024
        public const val DEFAULT_MAX_RESPONSE_BODY_BYTES: Long = 512L * 1024
        public const val MAX_BODY_BYTES: Long = 4L * 1024 * 1024
    }
}

/**
 * 저장 상한. 세 조건을 모두 지키도록 오래된 트랜잭션부터 지운다.
 *
 * @property maxTransactions 보관할 트랜잭션 수, 1 이상.
 * @property maxAgeMillis 트랜잭션 보관 기간(ms), 1 이상.
 * @property maxBodyBytes 저장한 본문 파일 합계 상한(바이트), 1 이상.
 */
public class RetentionPolicy @JvmOverloads constructor(
    public val maxTransactions: Int = DEFAULT_MAX_TRANSACTIONS,
    public val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    public val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
) {
    init {
        require(maxTransactions >= 1) { "maxTransactions는 1 이상이어야 합니다." }
        require(maxAgeMillis >= 1) { "maxAgeMillis는 1 이상이어야 합니다." }
        require(maxBodyBytes >= 1) { "maxBodyBytes는 1 이상이어야 합니다." }
    }

    override fun equals(other: Any?): Boolean = other is RetentionPolicy &&
        maxTransactions == other.maxTransactions &&
        maxAgeMillis == other.maxAgeMillis &&
        maxBodyBytes == other.maxBodyBytes

    override fun hashCode(): Int = listOf(maxTransactions, maxAgeMillis, maxBodyBytes).hashCode()

    public companion object {
        public const val DEFAULT_MAX_TRANSACTIONS: Int = 10_000
        public const val DEFAULT_MAX_AGE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
        public const val DEFAULT_MAX_BODY_BYTES: Long = 100L * 1024 * 1024
    }

    override fun toString(): String =
        "RetentionPolicy(maxTransactions=$maxTransactions, maxAgeMillis=$maxAgeMillis, maxBodyBytes=$maxBodyBytes)"
}
