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
 * @property insights 진단 규칙의 임계값.
 * @property replay 재실행 정책. null(기본)이면 재실행할 수 없다.
 * @property filter 기록할 요청. 기본은 모두 기록한다.
 * @property context 요청과 함께 기록할 화면·앱 상태·네트워크 종류.
 * @property endpoints 통계에서 엔드포인트를 묶는 방법.
 * @property bodyFormats JSON 말고 더 저장할 본문 형식. 기본은 JSON만.
 * @property monitoring 앱 로그·크래시·종료 이유·성능 수집.
 * @property remote 오류 보고를 원격으로 보내는 설정. null(기본)이면 보내지 않는다.
 * @property reporting 버그 보고를 보낼 목적지. null(기본)이면 기기에 저장하고 내보내기만 한다.
 * @property screenshots 화면 캡처 규칙.
 * @property chaos 네트워크 장애 모사를 허용할 호스트. null(기본)이면 장애 모사를 쓸 수 없다.
 * @property dataInspection 인스펙터에서 읽기 전용으로 볼 앱 저장소. null(기본)이면 보이지 않는다.
 */
// 생성자는 Builder만 부르며, 인자는 설정 항목과 일대일이다.
@Suppress("LongParameterList")
public class PulseConfig private constructor(
    public val captureMode: CaptureMode,
    public val limits: CaptureLimits,
    public val retention: RetentionPolicy,
    public val redaction: RedactionPolicy,
    public val queueCapacity: Int,
    public val startRecording: Boolean,
    public val insights: InsightThresholds,
    public val replay: ReplayPolicy?,
    public val filter: CaptureFilter,
    public val context: ContextCapture,
    public val endpoints: EndpointGrouping,
    public val bodyFormats: BodyFormats,
    public val monitoring: AppMonitoring,
    public val remote: RemoteReporting?,
    public val reporting: Reporting?,
    public val screenshots: ScreenshotPolicy,
    public val chaos: ChaosPolicy?,
    public val dataInspection: DataInspection?,
) {
    public fun newBuilder(): Builder = Builder(this)

    private fun values(): List<Any?> = listOf(
        captureMode, limits, retention, redaction, queueCapacity, startRecording, insights, replay, filter, context, endpoints, bodyFormats,
        monitoring, remote, reporting, screenshots, chaos, dataInspection,
    )

    override fun equals(other: Any?): Boolean = other is PulseConfig && values() == other.values()

    override fun hashCode(): Int = values().hashCode()

    override fun toString(): String = "PulseConfig(captureMode=$captureMode, limits=$limits, retention=$retention, " +
        "redaction=$redaction, queueCapacity=$queueCapacity, startRecording=$startRecording, insights=$insights, replay=$replay, " +
        "filter=$filter, context=$context, endpoints=$endpoints, bodyFormats=$bodyFormats, monitoring=$monitoring, remote=$remote, " +
        "reporting=$reporting, screenshots=$screenshots, chaos=$chaos, " +
        "dataInspection=$dataInspection)"

    /** [PulseConfig]를 만든다. 값 검증은 [build]에서 하고, 범위를 벗어나면 [IllegalArgumentException]을 던진다. */
    @Suppress("TooManyFunctions")
    public class Builder() {
        private var captureMode = CaptureMode.METADATA_ONLY
        private var limits = CaptureLimits()
        private var retention = RetentionPolicy()
        private var redaction = RedactionPolicy.strict()
        private var queueCapacity = DEFAULT_QUEUE_CAPACITY
        private var startRecording = true
        private var insights = InsightThresholds()
        private var replay: ReplayPolicy? = null
        private var filter = CaptureFilter.ALL
        private var context = ContextCapture()
        private var endpoints = EndpointGrouping()
        private var bodyFormats = BodyFormats.JSON_ONLY
        private var monitoring = AppMonitoring()
        private var remote: RemoteReporting? = null
        private var reporting: Reporting? = null
        private var screenshots = ScreenshotPolicy()
        private var chaos: ChaosPolicy? = null
        private var dataInspection: DataInspection? = null

        internal constructor(config: PulseConfig) : this() {
            captureMode = config.captureMode
            limits = config.limits
            retention = config.retention
            redaction = config.redaction
            queueCapacity = config.queueCapacity
            startRecording = config.startRecording
            insights = config.insights
            replay = config.replay
            filter = config.filter
            context = config.context
            endpoints = config.endpoints
            bodyFormats = config.bodyFormats
            monitoring = config.monitoring
            remote = config.remote
            reporting = config.reporting
            screenshots = config.screenshots
            chaos = config.chaos
            dataInspection = config.dataInspection
        }

        public fun captureMode(mode: CaptureMode): Builder = apply { captureMode = mode }

        public fun limits(limits: CaptureLimits): Builder = apply { this.limits = limits }

        public fun retention(retention: RetentionPolicy): Builder = apply { this.retention = retention }

        public fun redaction(redaction: RedactionPolicy): Builder = apply { this.redaction = redaction }

        public fun queueCapacity(capacity: Int): Builder = apply { queueCapacity = capacity }

        public fun startRecording(start: Boolean): Builder = apply { startRecording = start }

        public fun insights(thresholds: InsightThresholds): Builder = apply { insights = thresholds }

        /** 재실행을 켠다. null이면 끈다. */
        public fun replay(policy: ReplayPolicy?): Builder = apply { replay = policy }

        public fun filter(filter: CaptureFilter): Builder = apply { this.filter = filter }

        public fun context(context: ContextCapture): Builder = apply { this.context = context }

        public fun endpoints(grouping: EndpointGrouping): Builder = apply { endpoints = grouping }

        public fun bodyFormats(formats: BodyFormats): Builder = apply { bodyFormats = formats }

        public fun monitoring(monitoring: AppMonitoring): Builder = apply { this.monitoring = monitoring }

        /** 원격 보고를 켠다. null이면 끈다. */
        public fun remote(remote: RemoteReporting?): Builder = apply { this.remote = remote }

        /** 버그 보고 목적지. null이면 보내지 않고 내보내기만 한다. */
        public fun reporting(reporting: Reporting?): Builder = apply { this.reporting = reporting }

        public fun screenshots(policy: ScreenshotPolicy): Builder = apply { screenshots = policy }

        /** 네트워크 장애 모사를 이 호스트들에만 허용한다. null이면 끈다. */
        public fun chaos(policy: ChaosPolicy?): Builder = apply { chaos = policy }

        /** 인스펙터에서 읽기 전용으로 볼 저장소. null이면 숨긴다. */
        public fun dataInspection(inspection: DataInspection?): Builder = apply { dataInspection = inspection }

        public fun build(): PulseConfig {
            require(queueCapacity in MIN_QUEUE_CAPACITY..MAX_QUEUE_CAPACITY) {
                "queueCapacity는 $MIN_QUEUE_CAPACITY..$MAX_QUEUE_CAPACITY 범위여야 합니다: $queueCapacity"
            }
            return PulseConfig(
                captureMode, limits, retention, redaction, queueCapacity, startRecording, insights, replay, filter, context, endpoints,
                bodyFormats, monitoring, remote, reporting, screenshots, chaos, dataInspection,
            )
        }
    }

    public companion object {
        public const val DEFAULT_QUEUE_CAPACITY: Int = 2_000
        public const val MIN_QUEUE_CAPACITY: Int = 16
        public const val MAX_QUEUE_CAPACITY: Int = 100_000

        /** 메타데이터만 저장하는 기본 설정. */
        @JvmStatic
        public fun defaults(): PulseConfig = Builder().build()

        /** 개발 빌드용 설정: JSON 본문을 마스킹한 뒤 저장하고, 이 앱의 logcat과 탭·뒤로 키도 기록한다. 그 밖의 값은 [defaults]와 같다. */
        @JvmStatic
        public fun debugDefaults(): PulseConfig = Builder()
            .captureMode(CaptureMode.METADATA_AND_SAFE_BODIES)
            .monitoring(AppMonitoring(logcat = true, interactions = true))
            .build()
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
 * @property maxEvents 보관할 앱 이벤트(로그·마커·오류 등) 수, 1 이상. 화면 캡처 파일도 함께 지운다.
 */
public class RetentionPolicy @JvmOverloads constructor(
    public val maxTransactions: Int = DEFAULT_MAX_TRANSACTIONS,
    public val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    public val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    public val maxEvents: Int = DEFAULT_MAX_EVENTS,
) {
    init {
        require(maxEvents >= 1) { "maxEvents는 1 이상이어야 합니다." }
        require(maxTransactions >= 1) { "maxTransactions는 1 이상이어야 합니다." }
        require(maxAgeMillis >= 1) { "maxAgeMillis는 1 이상이어야 합니다." }
        require(maxBodyBytes >= 1) { "maxBodyBytes는 1 이상이어야 합니다." }
    }

    override fun equals(other: Any?): Boolean = other is RetentionPolicy &&
        maxTransactions == other.maxTransactions &&
        maxAgeMillis == other.maxAgeMillis &&
        maxBodyBytes == other.maxBodyBytes &&
        maxEvents == other.maxEvents

    override fun hashCode(): Int = listOf(maxTransactions, maxAgeMillis, maxBodyBytes, maxEvents).hashCode()

    public companion object {
        public const val DEFAULT_MAX_TRANSACTIONS: Int = 10_000
        public const val DEFAULT_MAX_AGE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
        public const val DEFAULT_MAX_BODY_BYTES: Long = 100L * 1024 * 1024
        public const val DEFAULT_MAX_EVENTS: Int = 50_000
    }

    override fun toString(): String =
        "RetentionPolicy(maxTransactions=$maxTransactions, maxAgeMillis=$maxAgeMillis, maxBodyBytes=$maxBodyBytes, maxEvents=$maxEvents)"
}
