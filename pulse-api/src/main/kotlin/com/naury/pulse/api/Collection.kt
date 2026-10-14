package com.naury.pulse.api

/**
 * 어떤 요청을 기록할지 정하는 필터. 걸러진 요청은 원래대로 보내고 아무것도 기록하지 않는다.
 *
 * 호스트 패턴은 정확한 이름(`api.example.com`) 또는 `*.`으로 시작하는 하위 도메인 패턴(`*.example.com`, 자기 자신 포함)이다.
 * 대소문자는 구분하지 않는다.
 *
 * @property onlyHosts 비어 있지 않으면 여기에 맞는 호스트만 기록한다.
 * @property ignoredHosts 맞는 호스트는 기록하지 않는다. [onlyHosts]보다 우선한다.
 * @property ignoredPathPrefixes 경로가 이것으로 시작하면 기록하지 않는다(예: `/analytics`, `/health`). `/`로 시작해야 한다.
 */
public class CaptureFilter @JvmOverloads constructor(
    public val onlyHosts: Set<String> = emptySet(),
    public val ignoredHosts: Set<String> = emptySet(),
    public val ignoredPathPrefixes: Set<String> = emptySet(),
) {
    init {
        (onlyHosts + ignoredHosts).forEach { pattern ->
            require(isValidHostPattern(pattern)) { "호스트 패턴은 이름 또는 *.도메인이어야 합니다: $pattern" }
        }
        ignoredPathPrefixes.forEach { require(it.startsWith("/")) { "경로 접두사는 /로 시작해야 합니다: $it" } }
    }

    /** [host]·[path] 요청을 기록할지. */
    public fun accepts(host: String, path: String): Boolean {
        if (ignoredHosts.any { matches(it, host) }) return false
        if (onlyHosts.isNotEmpty() && onlyHosts.none { matches(it, host) }) return false
        return ignoredPathPrefixes.none { path.startsWith(it) }
    }

    override fun equals(other: Any?): Boolean = other is CaptureFilter &&
        onlyHosts == other.onlyHosts &&
        ignoredHosts == other.ignoredHosts &&
        ignoredPathPrefixes == other.ignoredPathPrefixes

    override fun hashCode(): Int = listOf(onlyHosts, ignoredHosts, ignoredPathPrefixes).hashCode()

    override fun toString(): String = "CaptureFilter(only=$onlyHosts, ignoredHosts=$ignoredHosts, ignoredPaths=$ignoredPathPrefixes)"

    public companion object {
        /** 모든 요청을 기록한다. */
        @JvmField
        public val ALL: CaptureFilter = CaptureFilter()

        private fun isValidHostPattern(pattern: String): Boolean {
            if (pattern.isBlank() || pattern.contains('/')) return false
            if (!pattern.contains('*')) return true
            return pattern.startsWith("*.") && pattern.length > 2 && pattern.lastIndexOf('*') == 0
        }

        internal fun matches(pattern: String, host: String): Boolean {
            val lowerHost = host.lowercase()
            val lowerPattern = pattern.lowercase()
            if (!lowerPattern.startsWith("*.")) return lowerHost == lowerPattern
            val suffix = lowerPattern.removePrefix("*.")
            return lowerHost == suffix || lowerHost.endsWith(".$suffix")
        }
    }
}

/**
 * 요청과 함께 기록할 앱 쪽 맥락. 모두 기기 안에서 바로 아는 값이며 네트워크 요청을 늦추지 않는다.
 *
 * @property screen 요청을 시작할 때 앞에 있던 Activity 이름.
 * @property appState 요청을 시작할 때 앱이 화면에 보였는지(포그라운드) 백그라운드였는지.
 * @property networkType 요청을 시작할 때의 네트워크 종류(Wi-Fi·셀룰러 등). `ACCESS_NETWORK_STATE` 권한을 쓴다.
 */
public class ContextCapture @JvmOverloads constructor(
    public val screen: Boolean = true,
    public val appState: Boolean = true,
    public val networkType: Boolean = true,
) {
    override fun equals(other: Any?): Boolean =
        other is ContextCapture && screen == other.screen && appState == other.appState && networkType == other.networkType

    override fun hashCode(): Int = listOf(screen, appState, networkType).hashCode()

    override fun toString(): String = "ContextCapture(screen=$screen, appState=$appState, networkType=$networkType)"

    public companion object {
        /** 맥락을 기록하지 않는다. */
        @JvmField
        public val NONE: ContextCapture = ContextCapture(screen = false, appState = false, networkType = false)
    }
}

/**
 * 통계에서 엔드포인트를 묶는 방법. 기록 자체는 바꾸지 않는다.
 *
 * @property autoTemplates 숫자·UUID·긴 16진수 경로 조각을 `{id}`로 묶는다(`/users/42` → `/users/{id}`).
 * @property templates 직접 정한 경로 템플릿. `{이름}` 자리는 한 경로 조각에 맞는다(`/orders/{orderId}/items`).
 *   먼저 맞는 템플릿을 쓰고, 맞는 것이 없으면 [autoTemplates] 규칙을 쓴다.
 */
public class EndpointGrouping @JvmOverloads constructor(
    public val autoTemplates: Boolean = true,
    public val templates: List<String> = emptyList(),
) {
    init {
        templates.forEach { require(it.startsWith("/")) { "템플릿은 /로 시작해야 합니다: $it" } }
    }

    override fun equals(other: Any?): Boolean =
        other is EndpointGrouping && autoTemplates == other.autoTemplates && templates == other.templates

    override fun hashCode(): Int = 31 * autoTemplates.hashCode() + templates.hashCode()

    override fun toString(): String = "EndpointGrouping(auto=$autoTemplates, templates=$templates)"
}

/**
 * [CaptureMode.METADATA_AND_SAFE_BODIES]에서 JSON 말고 더 저장할 본문 형식. 모두 기본 꺼짐이다.
 *
 * @property forms `application/x-www-form-urlencoded`. 민감한 이름의 값을 가리고 나머지 값 안의 URL·`name=value`도 가린다.
 * @property text `text/`로 시작하는 형식과 XML. 구조를 알 수 없어 URL 쿼리와 `name=value` 형태의 비밀만 가린다.
 *   그 밖의 문장 속 비밀은 찾지 못하므로 비밀을 담지 않는 API에만 켠다.
 */
public class BodyFormats @JvmOverloads constructor(public val forms: Boolean = false, public val text: Boolean = false) {
    override fun equals(other: Any?): Boolean = other is BodyFormats && forms == other.forms && text == other.text

    override fun hashCode(): Int = 31 * forms.hashCode() + text.hashCode()

    override fun toString(): String = "BodyFormats(forms=$forms, text=$text)"

    public companion object {
        /** JSON만 저장한다. */
        @JvmField
        public val JSON_ONLY: BodyFormats = BodyFormats()
    }
}

/**
 * 요청에 붙이는 라벨. 기능·화면 단위로 통계를 나눠 보려고 쓴다. no-op에서도 같은 이름으로 있어 출시 코드에 남겨도 된다.
 *
 * ```kotlin
 * Request.Builder().url(url).tag(PulseLabel::class.java, PulseLabel("checkout")).build()
 * ```
 *
 * @property name 1..[MAX_LENGTH]자. 비밀 값을 넣지 않는다(마스킹하지 않는다).
 */
public class PulseLabel(public val name: String) {
    init {
        require(name.isNotBlank() && name.length <= MAX_LENGTH) { "라벨은 1..$MAX_LENGTH 자여야 합니다." }
    }

    override fun equals(other: Any?): Boolean = other is PulseLabel && name == other.name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = "PulseLabel($name)"

    public companion object {
        public const val MAX_LENGTH: Int = 64
    }
}

/** 요청을 시작할 때의 앱 쪽 맥락. 기록하지 않았거나 알 수 없는 값은 null이다. */
public class RequestContext @JvmOverloads constructor(
    /** 앞에 있던 Activity의 짧은 클래스 이름. */
    public val screen: String?,
    /** 앱이 화면에 보였으면 true, 백그라운드였으면 false. */
    public val foreground: Boolean?,
    /** [NetworkType] 이름. */
    public val networkType: NetworkType?,
    /** 요청에 붙은 [PulseLabel] 이름. */
    public val label: String?,
    /** 요청을 시작할 때 열려 있던 가장 최근 구간([Pulse.beginSpan])의 ID. */
    public val spanId: String? = null,
) {
    public val isEmpty: Boolean
        get() = screen == null && foreground == null && networkType == null && label == null && spanId == null
}

/**
 * 요청에 붙이면 PULSE가 기록하지 않는다. 보고를 올리는 전송 구현처럼 기록에 남으면 안 되는 요청에 쓴다.
 *
 * ```
 * Request.Builder().tag(PulseIgnore::class.java, PulseIgnore)
 * ```
 */
public object PulseIgnore

/** 요청을 시작할 때 기본 네트워크의 종류. */
public enum class NetworkType { WIFI, CELLULAR, ETHERNET, VPN, OTHER, NONE }

/**
 * 네트워크 말고 앱 자체에서 모을 것. 모두 기기 안에서만 기록하고 [RedactionPolicy]로 글자 속 비밀을 가린다.
 *
 * @property crashes 처리되지 않은 예외로 죽을 때 스택·스레드·화면을 죽기 직전에 저장한다. 기존 핸들러에 그대로 넘긴다.
 * @property exitReasons 다음 실행 때 지난 실행이 끝난 이유(Android 11+ ApplicationExitInfo: 네이티브 크래시·ANR·메모리 부족 등)를 기록한다.
 * @property logcat 이 앱 프로세스의 logcat을 읽어 로그로 저장한다. 다른 앱의 로그는 읽지 않는다(권한이 필요 없다).
 * @property logcatMinLevel logcat에서 이 수준 이상만 저장한다.
 * @property logcatIgnoredTags 저장하지 않을 logcat 태그(예: 시끄러운 라이브러리).
 * @property mainThreadBlockMillis 메인 스레드가 이보다 오래(ms) 멈추면 그때의 스택과 함께 기록한다. 0이면 끈다. 250 이상.
 * @property performance 콜드 스타트 시간, 화면 로딩 시간, 화면별 프레임 끊김을 기록한다.
 * @property memoryIntervalMillis 앱이 화면에 보이는 동안 이 간격(ms)으로 메모리 사용량을 기록한다. 0이면 끈다. 5,000 이상.
 * @property screenshotOnCrash 크래시 직전에 앞 화면을 캡처해 함께 저장한다. 화면에 개인정보가 보일 수 있어 기본은 끔.
 */
public class AppMonitoring @JvmOverloads constructor(
    public val crashes: Boolean = true,
    public val exitReasons: Boolean = true,
    public val logcat: Boolean = false,
    public val logcatMinLevel: LogLevel = LogLevel.DEBUG,
    public val logcatIgnoredTags: Set<String> = emptySet(),
    public val mainThreadBlockMillis: Long = DEFAULT_BLOCK_MILLIS,
    public val performance: Boolean = true,
    public val memoryIntervalMillis: Long = DEFAULT_MEMORY_INTERVAL_MILLIS,
    public val screenshotOnCrash: Boolean = false,
) {
    init {
        require(mainThreadBlockMillis == 0L || mainThreadBlockMillis >= MIN_BLOCK_MILLIS) {
            "mainThreadBlockMillis는 0 또는 $MIN_BLOCK_MILLIS 이상이어야 합니다."
        }
        require(memoryIntervalMillis == 0L || memoryIntervalMillis >= MIN_MEMORY_INTERVAL_MILLIS) {
            "memoryIntervalMillis는 0 또는 $MIN_MEMORY_INTERVAL_MILLIS 이상이어야 합니다."
        }
    }

    private fun values(): List<Any> = listOf(
        crashes, exitReasons, logcat, logcatMinLevel, logcatIgnoredTags, mainThreadBlockMillis, performance, memoryIntervalMillis,
        screenshotOnCrash,
    )

    override fun equals(other: Any?): Boolean = other is AppMonitoring && values() == other.values()

    override fun hashCode(): Int = values().hashCode()

    override fun toString(): String = "AppMonitoring(${values().joinToString()})"

    public companion object {
        public const val DEFAULT_BLOCK_MILLIS: Long = 2_000
        public const val MIN_BLOCK_MILLIS: Long = 250
        public const val DEFAULT_MEMORY_INTERVAL_MILLIS: Long = 30_000
        public const val MIN_MEMORY_INTERVAL_MILLIS: Long = 5_000

        /** 앱 쪽 수집을 모두 끈다. */
        @JvmField
        public val OFF: AppMonitoring = AppMonitoring(
            crashes = false,
            exitReasons = false,
            logcat = false,
            mainThreadBlockMillis = 0,
            performance = false,
            memoryIntervalMillis = 0,
        )
    }
}

/**
 * 오류 보고를 원격 서버로 보내는 설정. 보낼 이벤트는 저장할 때 보낼 목록(outbox)에 함께 넣으므로 앱이 죽어도 다음 실행 때 보낸다.
 * 보내는 값은 저장 전에 이미 마스킹한 것이다. 요청은 PULSE가 기록하지 않는 별도 클라이언트로 보낸다.
 *
 * 받는 쪽 형식은 `pulse.remote.v1` JSON이다(docs/remote-reporting.md).
 *
 * @property endpoint 받을 주소. `https`만 받고, [allowCleartext]면 `http`도 받는다.
 * @property headers 요청마다 붙일 헤더(인증 토큰 등). PULSE 저장소·화면·내보내기에 남기지 않는다.
 * @property kinds 보낼 이벤트 종류.
 * @property minLogLevel 이 수준 이상의 로그도 보낸다. null이면 로그는 보내지 않는다.
 * @property networkFailures 5xx 응답과 응답 없이 실패한 요청의 요약(메서드·URL·결과·시간)도 보낸다.
 * @property batchSize 한 번에 보낼 최대 개수, 1..500.
 * @property flushIntervalMillis 보내지 못한 보고를 다시 확인하는 간격(ms), 5,000 이상. 새 보고는 쌓이고 약 1초 안에 보내고,
 *   크래시는 다음 실행 직후에 보낸다.
 * @property wifiOnly 셀룰러 등 요금이 드는 네트워크에서는 보내지 않는다.
 * @property allowCleartext `http` 주소를 허용한다. 개발용 로컬 서버에만 쓴다.
 * @property attributes 모든 보고에 붙일 값(빌드 이름·테스트 실행 ID 등).
 * @property maxPending 보내지 못하고 쌓아 둘 최대 개수. 넘치면 오래된 것부터 버린다.
 */
public class RemoteReporting @JvmOverloads constructor(
    public val endpoint: String,
    public val headers: Map<String, String> = emptyMap(),
    public val kinds: Set<EventKind> = DEFAULT_KINDS,
    public val minLogLevel: LogLevel? = null,
    public val networkFailures: Boolean = true,
    public val batchSize: Int = DEFAULT_BATCH_SIZE,
    public val flushIntervalMillis: Long = DEFAULT_FLUSH_INTERVAL_MILLIS,
    public val wifiOnly: Boolean = false,
    public val allowCleartext: Boolean = false,
    public val attributes: Map<String, String> = emptyMap(),
    public val maxPending: Int = DEFAULT_MAX_PENDING,
) {
    init {
        require(isAllowedEndpoint(endpoint, allowCleartext)) { "endpoint는 https 주소여야 합니다(allowCleartext면 http도 가능): $endpoint" }
        require(batchSize in 1..MAX_BATCH_SIZE) { "batchSize는 1..$MAX_BATCH_SIZE 범위여야 합니다." }
        require(flushIntervalMillis >= MIN_FLUSH_INTERVAL_MILLIS) { "flushIntervalMillis는 $MIN_FLUSH_INTERVAL_MILLIS 이상이어야 합니다." }
        require(maxPending >= 1) { "maxPending은 1 이상이어야 합니다." }
    }

    // 헤더 값(토큰)은 같음 비교에는 쓰되 문자열로는 드러내지 않는다.
    private fun values(): List<Any?> = listOf(
        endpoint, headers, kinds, minLogLevel, networkFailures, batchSize, flushIntervalMillis, wifiOnly, allowCleartext, attributes,
        maxPending,
    )

    override fun equals(other: Any?): Boolean = other is RemoteReporting && values() == other.values()

    override fun hashCode(): Int = values().hashCode()

    override fun toString(): String = "RemoteReporting(endpoint=$endpoint, headers=${headers.keys}, kinds=$kinds, minLogLevel=$minLogLevel)"

    public companion object {
        public const val DEFAULT_BATCH_SIZE: Int = 50
        public const val MAX_BATCH_SIZE: Int = 500
        public const val DEFAULT_FLUSH_INTERVAL_MILLIS: Long = 30_000
        public const val MIN_FLUSH_INTERVAL_MILLIS: Long = 5_000
        public const val DEFAULT_MAX_PENDING: Int = 5_000

        @JvmField
        public val DEFAULT_KINDS: Set<EventKind> = setOf(EventKind.CRASH, EventKind.EXIT, EventKind.ERROR, EventKind.MAIN_THREAD_BLOCKED)

        /** `https://` 주소인지(또는 [allowCleartext]면 `http://`). 화면에서 바꾼 주소도 같은 규칙으로 검사한다. */
        @JvmStatic
        public fun isAllowedEndpoint(endpoint: String, allowCleartext: Boolean): Boolean {
            val lower = endpoint.trim().lowercase()
            return lower.startsWith("https://") || (allowCleartext && lower.startsWith("http://"))
        }
    }
}

/**
 * 원격 보고 상태와 실행 중 바꿀 값. 설정 탭에서 쓴다.
 *
 * 실행 중에 바꾼 주소·토큰·켜기는 이 기기에만 저장되어 [PulseConfig.remote]보다 우선한다. 지우면 다시 코드 설정을 쓴다.
 */
public interface PulseRemote {
    public val status: kotlinx.coroutines.flow.StateFlow<RemoteStatus>

    /**
     * 실행 중 덮어쓸 값. null인 항목은 바꾸지 않는다. [bearerToken]은 `Authorization: Bearer` 헤더로 붙인다.
     * 주소가 규칙([RemoteReporting.isAllowedEndpoint])에 맞지 않으면 [IllegalArgumentException].
     */
    public fun setOverride(enabled: Boolean?, endpoint: String?, bearerToken: String?)

    /** 실행 중 바꾼 값을 지우고 코드 설정으로 돌아간다. */
    public fun clearOverride()

    /** 기다리지 않고 지금 보낸다. */
    public fun flushNow()
}

/**
 * @property configured 코드나 실행 중 설정으로 보낼 곳이 정해졌는지.
 * @property overridden 실행 중 바꾼 값이 있는지.
 * @property pending 보내지 못하고 기다리는 개수.
 */
public class RemoteStatus(
    public val configured: Boolean,
    public val enabled: Boolean,
    public val endpoint: String?,
    public val overridden: Boolean,
    public val pending: Int,
    public val sent: Long,
    public val lastSuccessEpochMillis: Long?,
    public val lastError: String?,
) {
    public companion object {
        @JvmField
        public val NONE: RemoteStatus = RemoteStatus(false, false, null, false, 0, 0, null, null)
    }
}
