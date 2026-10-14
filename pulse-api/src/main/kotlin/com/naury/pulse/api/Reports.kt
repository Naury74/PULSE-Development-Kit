package com.naury.pulse.api

import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** 보고 종류. 종류마다 다른 목적지([Reporting])로 보낼 수 있다. */
public enum class ReportKind { MANUAL, CRASH, PERFORMANCE }

public enum class Severity { BLOCKER, MAJOR, MINOR, TRIVIAL }

/** 보고 상태. [DRAFT]는 기기에만 있고 보내지 않은 것이다. */
public enum class ReportStatus { DRAFT, QUEUED, SENDING, SENT, FAILED, CANCELED }

/** 보고에 첨부할 근거(요청·이벤트) 하나. [included]가 false면 보낼 때 뺀다. */
public class EvidenceRef @JvmOverloads constructor(
    public val kind: TimelineItemKind,
    public val id: String,
    public val title: String = "",
    public val included: Boolean = true,
) {
    public fun withIncluded(included: Boolean): EvidenceRef = EvidenceRef(kind, id, title, included)

    override fun equals(other: Any?): Boolean =
        other is EvidenceRef && kind == other.kind && id == other.id && title == other.title && included == other.included

    override fun hashCode(): Int = listOf(kind, id, title, included).hashCode()
}

/**
 * 버그 보고 하나. 글자는 저장·내보내기·전송 전에 [RedactionPolicy]로 다시 가린다.
 *
 * @property steps 재현 단계. 한 줄에 하나.
 * @property hasScreenshot 마스킹을 마친 화면 캡처가 있는지. 마스킹 전 이미지는 저장하지 않는다.
 */
public class ReportDraft @JvmOverloads constructor(
    public val id: String,
    public val kind: ReportKind = ReportKind.MANUAL,
    public val title: String = "",
    public val description: String = "",
    public val expected: String = "",
    public val actual: String = "",
    public val steps: List<String> = emptyList(),
    public val severity: Severity = Severity.MAJOR,
    public val sessionId: String? = null,
    public val evidence: List<EvidenceRef> = emptyList(),
    public val hasScreenshot: Boolean = false,
    public val createdAtEpochMillis: Long = 0,
) {
    init {
        require(title.length <= MAX_TITLE_CHARS) { "title은 $MAX_TITLE_CHARS 자 이하여야 합니다." }
        require(evidence.size <= MAX_EVIDENCE) { "근거는 $MAX_EVIDENCE 개 이하여야 합니다." }
    }

    public companion object {
        public const val MAX_TITLE_CHARS: Int = 200
        public const val MAX_TEXT_CHARS: Int = 8_000
        public const val MAX_EVIDENCE: Int = 200
    }
}

/**
 * 저장된 보고의 요약과 전송 상태.
 *
 * @property progress 보내는 중이면 0..1, 아니면 null.
 * @property destinationId 보낼(보낸) 목적지 ID. 목적지가 없으면 null.
 */
public class ReportState(
    public val id: String,
    public val title: String,
    public val kind: ReportKind,
    public val severity: Severity,
    public val status: ReportStatus,
    public val attempts: Int,
    public val lastError: String?,
    public val progress: Float?,
    public val destinationId: String?,
    public val updatedAtEpochMillis: Long,
)

/**
 * 버그 보고 작성·저장·전송. 화면(보고 작성기)이 쓴다. 보고를 지원하지 않는 구현에서는 [PulseInspector.reports]가 null이다.
 */
public interface PulseReports {
    /** 저장된 보고 목록. 최근에 바뀐 순. */
    public val states: StateFlow<List<ReportState>>

    /** 이 종류의 보고를 보낼 목적지가 있는지. 없으면 내보내기만 할 수 있다. */
    public fun canSend(kind: ReportKind): Boolean

    /**
     * 초안을 저장하고 ID를 돌려준다. 같은 ID가 있으면 덮어쓴다.
     * [maskedPng]는 마스킹을 마친 화면 캡처다. null이면 기존 캡처를 그대로 두고, 빈 배열이면 캡처를 지운다.
     */
    public suspend fun save(draft: ReportDraft, maskedPng: ByteArray?): String

    public suspend fun load(id: String): ReportDraft?

    /** 저장한(마스킹된) 화면 캡처. */
    public suspend fun loadScreenshot(id: String): ByteArray?

    /** [aroundEpochMillis] 앞뒤 [windowMillis] 안에서 보고에 붙일 만한 오류·실패한 요청·느린 요청·경고 로그. */
    public suspend fun recommendEvidence(
        sessionId: String?,
        aroundEpochMillis: Long,
        windowMillis: Long = DEFAULT_EVIDENCE_WINDOW_MILLIS,
    ): List<EvidenceRef>

    /** 목적지로 보낸다. 목적지가 없으면 [IllegalStateException]. 실패하면 간격을 늘려 다시 보낸다. */
    public fun submit(id: String)

    public fun cancel(id: String)

    public fun retry(id: String)

    public fun delete(id: String)

    /** 보고와 근거를 재현 패키지(ZIP)로 쓴다. 쓴 바이트 수를 돌려준다. */
    public suspend fun exportPackage(id: String, out: OutputStream): Long

    public companion object {
        /** [recommendEvidence]의 기본 범위(앞뒤 ms). */
        public const val DEFAULT_EVIDENCE_WINDOW_MILLIS: Long = 60_000
    }
}

/**
 * 화면 캡처 규칙. 앱이 캡처를 막은 화면(FLAG_SECURE)은 항상 찍지 않는다.
 *
 * @property blockedScreens 캡처하지 않을 화면 이름(Activity 짧은 이름 또는 [Pulse.screen]으로 알린 이름).
 */
public class ScreenshotPolicy @JvmOverloads constructor(public val blockedScreens: Set<String> = emptySet()) {
    override fun equals(other: Any?): Boolean = other is ScreenshotPolicy && blockedScreens == other.blockedScreens

    override fun hashCode(): Int = blockedScreens.hashCode()

    override fun toString(): String = "ScreenshotPolicy(blockedScreens=$blockedScreens)"
}

/** 보낼 때마다 인증 헤더를 만들어 주는 콜백. 짧게 쓰는 토큰을 PULSE가 저장하지 않게 한다. I/O 스레드에서 불린다. */
public fun interface ReportHeaderProvider {
    public suspend fun headersFor(reportId: String, kind: ReportKind): Map<String, String>

    public companion object {
        /** Java처럼 suspend 함수를 구현하기 어려운 곳에서 쓴다. */
        @JvmStatic
        public fun blocking(provider: BlockingReportHeaderProvider): ReportHeaderProvider =
            ReportHeaderProvider { id, kind -> withContext(Dispatchers.IO) { provider.headersFor(id, kind) } }
    }
}

/** 막는 방식의 [ReportHeaderProvider]. */
public fun interface BlockingReportHeaderProvider {
    public fun headersFor(reportId: String, kind: ReportKind): Map<String, String>
}

/** 전송할 첨부 하나. */
public class ReportAttachment(public val name: String, public val mimeType: String, public val bytes: ByteArray)

/**
 * 호스트 전송 구현에 넘기는 보고 하나.
 *
 * @property json `pulse.report.v1` JSON(보고·근거 요약·환경). 이미 마스킹한 값이다.
 * @property attachments 마스킹한 화면 캡처와 재현 패키지(ZIP).
 */
public class ReportUpload(
    public val reportId: String,
    public val kind: ReportKind,
    public val json: String,
    public val attachments: List<ReportAttachment>,
)

/** 전송 결과. */
public sealed interface TransportResult {
    public class Sent(public val statusCode: Int?) : TransportResult

    /** [retryable]이 false면 다시 보내지 않고 실패로 둔다. */
    public class Failed(public val message: String, public val retryable: Boolean) : TransportResult
}

/**
 * 호스트 앱의 통신 모듈(Retrofit·사내 클라이언트 등)로 보고를 올린다. I/O 스레드에서 불린다.
 * 그 클라이언트에 PULSE 인터셉터가 붙어 있으면 요청에 [PulseIgnore] 태그를 붙여 업로드가 기록되지 않게 한다.
 */
public fun interface ReportTransport {
    public suspend fun send(upload: ReportUpload, onProgress: (Float) -> Unit): TransportResult
}

public enum class ReportEncoding { JSON, MULTIPART }

/**
 * 보고를 보낼 곳. [endpoint]와 [transport] 중 하나가 있어야 한다.
 *
 * @property allowedHosts [endpoint]의 호스트는 이 목록에 있어야 한다(`*.example.com` 허용). 리다이렉트는 따르지 않는다.
 * @property encoding [ReportEncoding.MULTIPART]면 JSON과 첨부를 함께, [ReportEncoding.JSON]이면 JSON만 보낸다.
 * @property timeoutMillis 요청 하나의 제한 시간(ms).
 * @property maxAttempts 이만큼 실패하면 다시 보내지 않고 실패로 둔다. 1..50.
 * @property headerProvider 보낼 때마다 부르는 인증 헤더 콜백.
 * @property transport 있으면 [endpoint] 대신 이것으로 보낸다.
 */
public class ReportDestination @JvmOverloads constructor(
    public val id: String,
    public val endpoint: String? = null,
    allowedHosts: Set<String> = emptySet(),
    public val encoding: ReportEncoding = ReportEncoding.MULTIPART,
    public val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    public val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    public val headerProvider: ReportHeaderProvider? = null,
    public val transport: ReportTransport? = null,
    public val allowCleartext: Boolean = false,
) {
    public val allowedHosts: Set<String> = allowedHosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    init {
        require(id.isNotBlank()) { "id가 비어 있습니다." }
        require(endpoint != null || transport != null) { "endpoint나 transport 중 하나가 필요합니다." }
        require(timeoutMillis >= 1) { "timeoutMillis는 1 이상이어야 합니다." }
        require(maxAttempts in 1..MAX_ATTEMPTS_LIMIT) { "maxAttempts는 1..$MAX_ATTEMPTS_LIMIT 범위여야 합니다." }
        endpoint?.let { url ->
            require(RemoteReporting.isAllowedEndpoint(url, allowCleartext)) { "endpoint는 https 주소여야 합니다(allowCleartext면 http도 가능): $url" }
            val host = hostOf(url)
            require(host != null && allowsHost(host)) { "endpoint 호스트가 allowedHosts에 없습니다: $host" }
        }
    }

    /** [host]가 허용 목록에 있는지. `*.example.com`은 하위 도메인만 허용한다. */
    public fun allowsHost(host: String): Boolean {
        val target = host.lowercase()
        return allowedHosts.any { pattern ->
            if (pattern.startsWith("*.")) target.endsWith(pattern.substring(1)) && target.length > pattern.length - 1 else target == pattern
        }
    }

    // 콜백은 같음 비교에서 뺀다. 같은 설정으로 다시 초기화할 때 람다가 달라도 같은 설정으로 본다.
    private fun values(): List<Any?> = listOf(id, endpoint, allowedHosts, encoding, timeoutMillis, maxAttempts, allowCleartext)

    override fun equals(other: Any?): Boolean = other is ReportDestination && values() == other.values()

    override fun hashCode(): Int = values().hashCode()

    override fun toString(): String = "ReportDestination(id=$id, endpoint=$endpoint, allowedHosts=$allowedHosts, encoding=$encoding)"

    public companion object {
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000
        public const val DEFAULT_MAX_ATTEMPTS: Int = 10
        public const val MAX_ATTEMPTS_LIMIT: Int = 50

        private fun hostOf(url: String): String? =
            url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').substringBefore(':').lowercase().ifEmpty { null }
    }
}

/**
 * 보고 종류별 목적지. 없는 종류는 기기에 저장하고 내보내기만 한다. 아무 서버로도 임의로 보내지 않는다.
 */
public class Reporting @JvmOverloads constructor(
    public val manual: ReportDestination? = null,
    public val crash: ReportDestination? = null,
    public val performance: ReportDestination? = null,
) {
    public fun destinationFor(kind: ReportKind): ReportDestination? = when (kind) {
        ReportKind.MANUAL -> manual
        ReportKind.CRASH -> crash
        ReportKind.PERFORMANCE -> performance
    }

    override fun equals(other: Any?): Boolean =
        other is Reporting && manual == other.manual && crash == other.crash && performance == other.performance

    override fun hashCode(): Int = listOf(manual, crash, performance).hashCode()

    override fun toString(): String = "Reporting(manual=$manual, crash=$crash, performance=$performance)"
}
