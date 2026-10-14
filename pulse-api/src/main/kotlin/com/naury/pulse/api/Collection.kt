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
public class RequestContext(
    /** 앞에 있던 Activity의 짧은 클래스 이름. */
    public val screen: String?,
    /** 앱이 화면에 보였으면 true, 백그라운드였으면 false. */
    public val foreground: Boolean?,
    /** [NetworkType] 이름. */
    public val networkType: NetworkType?,
    /** 요청에 붙은 [PulseLabel] 이름. */
    public val label: String?,
) {
    public val isEmpty: Boolean
        get() = screen == null && foreground == null && networkType == null && label == null
}

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
