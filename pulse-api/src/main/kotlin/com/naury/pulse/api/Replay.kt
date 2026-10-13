package com.naury.pulse.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 재실행 정책. [PulseConfig.replay]에 넣어야 재실행할 수 있으며 기본은 꺼져 있다.
 *
 * @property allowedHosts 재실행·리다이렉트를 허용할 호스트. 정확한 이름 또는 `*.example.com`(하위 도메인만) 형식이며 대소문자를 구분하지 않는다.
 *   운영 서버는 넣지 말고 개발·로컬 서버만 넣기를 권한다.
 * @property allowCleartext `true`면 `http://`도 허용한다. 기본은 `https://`만.
 * @property maxRequestBodyBytes 보낼 수 있는 본문 크기(바이트), 0 이상.
 * @property timeoutMillis 요청 하나의 전체 제한 시간(ms), 1 이상.
 * @property maxRedirects 따라갈 리다이렉트 수, 0..20. 매 단계에서 호스트·scheme을 다시 검사한다.
 */
public class ReplayPolicy @JvmOverloads constructor(
    allowedHosts: Set<String>,
    public val allowCleartext: Boolean = false,
    public val maxRequestBodyBytes: Long = DEFAULT_MAX_REQUEST_BODY_BYTES,
    public val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    public val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
) {
    public val allowedHosts: Set<String> = allowedHosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    init {
        require(maxRequestBodyBytes >= 0) { "maxRequestBodyBytes는 0 이상이어야 합니다." }
        require(timeoutMillis >= 1) { "timeoutMillis는 1 이상이어야 합니다." }
        require(maxRedirects in 0..MAX_REDIRECTS_LIMIT) { "maxRedirects는 0..$MAX_REDIRECTS_LIMIT 범위여야 합니다." }
    }

    /** [host]가 허용 목록에 있는지. `*.example.com`은 `api.example.com`을 허용하고 `example.com` 자체는 허용하지 않는다. */
    public fun allowsHost(host: String): Boolean {
        val target = host.lowercase()
        return allowedHosts.any { pattern ->
            if (pattern.startsWith("*.")) target.endsWith(pattern.substring(1)) && target.length > pattern.length - 1 else target == pattern
        }
    }

    private fun values() = listOf(allowedHosts, allowCleartext, maxRequestBodyBytes, timeoutMillis, maxRedirects)

    override fun equals(other: Any?): Boolean = other is ReplayPolicy && values() == other.values()

    override fun hashCode(): Int = values().hashCode()

    override fun toString(): String = "ReplayPolicy(${values().joinToString()})"

    public companion object {
        public const val DEFAULT_MAX_REQUEST_BODY_BYTES: Long = 256L * 1024
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000
        public const val DEFAULT_MAX_REDIRECTS: Int = 5
        public const val MAX_REDIRECTS_LIMIT: Int = 20
    }
}

/**
 * 재실행할 요청. 저장된 요청에서 만들며, 가려진 헤더와 가려진 쿼리 값은 빼고 시작한다. 화면에서 고친 뒤 보낸다.
 *
 * @property removedHeaders 값이 가려져 뺀 헤더 이름. 다시 넣으려면 [ReplayAuthorizer]를 쓴다.
 */
public class ReplayDraft(
    public val sourceTransactionId: String,
    public val method: String,
    public val url: String,
    public val headers: List<HeaderField>,
    public val body: String?,
    public val contentType: String?,
    public val removedHeaders: List<String> = emptyList(),
) {
    /** 상태를 바꿀 수 있는 메서드(GET·HEAD·OPTIONS가 아님)인지. */
    public val mutatesState: Boolean
        get() = method.uppercase() !in setOf("GET", "HEAD", "OPTIONS")
}

/** 재실행 결과. */
public sealed interface ReplayOutcome {
    /** 응답을 받았다. [transactionId]는 마지막 단계의 기록이고 [redirects]는 따라간 리다이렉트 수다. */
    public class Completed(public val transactionId: String, public val statusCode: Int, public val redirects: Int) : ReplayOutcome

    /** 정책 때문에 보내지 않았다(리다이렉트 단계에서 막혔으면 앞 단계는 이미 보냈다). */
    public class Blocked(public val reason: ReplayBlockReason, public val detail: String? = null) : ReplayOutcome

    /** 보냈지만 I/O 오류·시간 초과·취소로 끝났다. */
    public class Failed(public val transactionId: String, public val errorType: String, public val message: String?) : ReplayOutcome
}

public enum class ReplayBlockReason {
    /** [PulseConfig.replay]가 없거나 no-op artifact다. */
    DISABLED,

    /** 메서드·URL을 해석할 수 없다. */
    INVALID_REQUEST,

    /** 허용하지 않은 scheme(기본은 https만). */
    SCHEME_NOT_ALLOWED,

    /** 허용 목록에 없는 호스트. */
    HOST_NOT_ALLOWED,

    /** 상태를 바꿀 수 있는 요청이거나 자격 증명을 붙이는데 사용자 확인이 없다. */
    CONFIRMATION_REQUIRED,

    /** 본문이 [ReplayPolicy.maxRequestBodyBytes]보다 크다. */
    BODY_TOO_LARGE,

    /** 리다이렉트가 허용하지 않은 호스트·scheme으로 향한다. */
    REDIRECT_NOT_ALLOWED,

    /** 리다이렉트가 [ReplayPolicy.maxRedirects]를 넘었다. */
    TOO_MANY_REDIRECTS,

    /** 다른 재실행이 진행 중이다. 동시에 하나만 보낸다. */
    BUSY,
}

/**
 * 호스트 앱이 재실행에 자격 증명을 붙이는 콜백. PULSE는 원래 요청의 인증 값을 보관하지 않으므로, 인증이 필요하면 앱이 직접 만들어 준다.
 * 돌려준 헤더는 원래 호스트로 가는 요청에만 붙고, 다른 호스트로의 리다이렉트에는 붙지 않는다. 기록될 때는 다른 헤더처럼 마스킹된다.
 */
public fun interface ReplayAuthorizer {
    public suspend fun headersFor(draft: ReplayDraft): List<HeaderField>

    public companion object {
        /** Java처럼 suspend 함수를 구현하기 어려운 곳에서 쓴다. [authorizer]는 I/O 스레드에서 불리므로 막아도 된다. */
        @JvmStatic
        public fun blocking(authorizer: BlockingReplayAuthorizer): ReplayAuthorizer =
            ReplayAuthorizer { draft -> withContext(Dispatchers.IO) { authorizer.headersFor(draft) } }
    }
}

/** 막는 방식으로 자격 증명 헤더를 돌려주는 [ReplayAuthorizer]. [ReplayAuthorizer.blocking]으로 감싸 쓴다. */
public fun interface BlockingReplayAuthorizer {
    public fun headersFor(draft: ReplayDraft): List<HeaderField>
}
