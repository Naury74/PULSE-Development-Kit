package com.naury.pulse.replay

import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.ReplayAuthorizer
import com.naury.pulse.api.ReplayBlockReason
import com.naury.pulse.api.ReplayDraft
import com.naury.pulse.api.ReplayOutcome
import com.naury.pulse.api.ReplayPolicy
import com.naury.pulse.core.CaptureTag
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * [ReplayPolicy]를 지키며 초안을 보낸다. 동시에 하나만 보내고, 리다이렉트는 직접 따라가며 단계마다 호스트·scheme을 다시 검사한다.
 * 자격 증명은 [authorizer]가 준 헤더만 원래 호스트에 붙인다.
 *
 * @param client 호스트 앱과 분리된 클라이언트. PULSE 인터셉터가 붙어 있으면 결과가 [CaptureSource.REPLAY]로 기록된다.
 */
public class ReplayEngine(private val policy: ReplayPolicy, client: OkHttpClient, private val authorizer: () -> ReplayAuthorizer?) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(policy.timeoutMillis, TimeUnit.MILLISECONDS)
        .build()
    private val running = Mutex()

    public suspend fun execute(draft: ReplayDraft, confirmed: Boolean): ReplayOutcome {
        val method = draft.method.trim().uppercase()
        val url = draft.url.trim().toHttpUrlOrNull()
        val auth = authorizer()
        val blocked = validate(draft, method, url, confirmed || (!draft.mutatesState && auth == null))
        if (blocked != null || url == null) return blocked ?: ReplayOutcome.Blocked(ReplayBlockReason.INVALID_REQUEST)
        if (!running.tryLock()) return ReplayOutcome.Blocked(ReplayBlockReason.BUSY)
        return try {
            val credentials = auth?.headersFor(draft).orEmpty()
            follow(draft, Hop(method, url, draft.body?.toByteArray(Charsets.UTF_8)), credentials, originHost = url.host)
        } finally {
            running.unlock()
        }
    }

    private fun validate(draft: ReplayDraft, method: String, url: HttpUrl?, confirmed: Boolean): ReplayOutcome.Blocked? {
        val bodySize = draft.body?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val reason = when {
            url == null || !methodToken.matches(method) -> ReplayBlockReason.INVALID_REQUEST
            checkTarget(url) != null -> checkTarget(url)
            bodySize > policy.maxRequestBodyBytes -> ReplayBlockReason.BODY_TOO_LARGE
            !confirmed -> ReplayBlockReason.CONFIRMATION_REQUIRED
            else -> null
        }
        return reason?.let { ReplayOutcome.Blocked(it, if (it == ReplayBlockReason.HOST_NOT_ALLOWED) url?.host else null) }
    }

    private suspend fun follow(draft: ReplayDraft, first: Hop, credentials: List<HeaderField>, originHost: String): ReplayOutcome {
        var hop = first
        var redirects = 0
        var outcome: ReplayOutcome? = null
        while (outcome == null) {
            val id = UUID.randomUUID().toString()
            val request = build(draft, hop, if (hop.url.host == originHost) credentials else emptyList(), id)
            val response = try {
                client.newCall(request).await()
            } catch (error: IOException) {
                return ReplayOutcome.Failed(id, error.javaClass.name, error.message)
            }
            val location = response.use { consume(it) }
            val target = location?.takeIf { response.isRedirect }?.let { hop.url.resolve(it) }
            val blocked = target?.let(::checkTarget)
            outcome = when {
                target == null -> ReplayOutcome.Completed(id, response.code, redirects)

                blocked != null -> ReplayOutcome.Blocked(ReplayBlockReason.REDIRECT_NOT_ALLOWED, target.host)

                ++redirects > policy.maxRedirects -> ReplayOutcome.Blocked(ReplayBlockReason.TOO_MANY_REDIRECTS)

                else -> {
                    hop = hop.redirectTo(target, response.code)
                    null
                }
            }
        }
        return outcome
    }

    private fun build(draft: ReplayDraft, hop: Hop, credentials: List<HeaderField>, id: String): Request {
        val builder = Request.Builder().url(
            hop.url,
        ).tag(CaptureTag::class.java, CaptureTag(id, CaptureSource.REPLAY, draft.sourceTransactionId))
        (draft.headers + credentials).forEach { builder.addHeader(it.name, it.value) }
        val body = hop.body?.toRequestBody(draft.contentType?.toMediaTypeOrNull())
        builder.method(hop.method, body ?: if (requiresBody(hop.method)) ByteArray(0).toRequestBody() else null)
        return builder.build()
    }

    /** 본문을 끝까지(상한까지) 읽어 기록이 완료되게 하고, 리다이렉트 위치를 돌려준다. */
    private fun consume(response: Response): String? {
        val source = response.body.source()
        val sink = Buffer()
        var total = 0L
        while (total < MAX_RESPONSE_BYTES) {
            val read = source.read(sink, READ_CHUNK)
            if (read == -1L) break
            total += read
            sink.clear()
        }
        return response.header("Location")
    }

    private fun checkTarget(url: HttpUrl): ReplayBlockReason? = when {
        url.scheme == "http" && !policy.allowCleartext -> ReplayBlockReason.SCHEME_NOT_ALLOWED
        !policy.allowsHost(url.host) -> ReplayBlockReason.HOST_NOT_ALLOWED
        else -> null
    }

    private class Hop(val method: String, val url: HttpUrl, val body: ByteArray?) {
        /** 303은 GET으로, 301·302는 POST만 GET으로 바꾸고, 307·308은 메서드와 본문을 그대로 둔다. */
        fun redirectTo(target: HttpUrl, code: Int): Hop {
            val keep = code == HTTP_TEMPORARY_REDIRECT || code == HTTP_PERMANENT_REDIRECT
            val toGet = code == HTTP_SEE_OTHER || (!keep && method == "POST")
            return if (toGet) Hop("GET", target, null) else Hop(method, target, body)
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            },
        )
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        const val READ_CHUNK = 8_192L
        const val HTTP_SEE_OTHER = 303
        const val HTTP_TEMPORARY_REDIRECT = 307
        const val HTTP_PERMANENT_REDIRECT = 308
        val methodToken = Regex("^[A-Z]{1,16}$")

        fun requiresBody(method: String) = method in setOf("POST", "PUT", "PATCH")
    }
}
