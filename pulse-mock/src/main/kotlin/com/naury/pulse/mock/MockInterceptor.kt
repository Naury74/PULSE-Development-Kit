package com.naury.pulse.mock

import com.naury.pulse.api.ChaosAction
import com.naury.pulse.api.ChaosRule
import com.naury.pulse.api.MockRule
import com.naury.pulse.api.PathMatch
import com.naury.pulse.core.MockMarker
import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 맞는 규칙이 있으면 네트워크 없이 합성 응답을 돌려주는 interceptor. 개발자가 OkHttpClient에 직접 붙여야 하며,
 * PULSE 인터셉터 **뒤에** 붙이면 모의 응답이 MOCK 출처로 기록된다. 전체 스위치가 꺼져 있으면 요청을 그대로 넘긴다.
 * 응답에는 [MockMarker.HEADER] 헤더가 붙어 진짜 서버 응답과 구분된다.
 */
public class MockInterceptor @JvmOverloads constructor(
    private val registry: MockRegistry,
    private val chaos: ChaosRegistry? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val time = now()
        val rule =
            registry.take { matches(it, request, time) }
                ?: return chaos?.pick(request)?.let { applyChaos(chain, it, time) } ?: chain.proceed(request)
        val spec = rule.response
        if (spec.delayMillis > 0) delay(chain, spec.delayMillis)
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(spec.statusCode)
            .message("Mock")
            .sentRequestAtMillis(time)
            .receivedResponseAtMillis(now())
        spec.headers.forEach { builder.addHeader(it.name, it.value) }
        return builder
            .header("Content-Type", spec.contentType)
            .header(MockMarker.HEADER, rule.id)
            .body(spec.body.toResponseBody(spec.contentType.toMediaTypeOrNull()))
            .build()
    }

    /** 장애 모사. 응답을 합성하는 경우는 모의 응답처럼 표시해 MOCK 출처로 기록되게 한다. */
    private fun applyChaos(chain: Interceptor.Chain, rule: ChaosRule, time: Long): Response {
        val request = chain.request()
        val status = when (rule.action) {
            ChaosAction.DELAY -> {
                delay(chain, rule.delayMillis)
                return chain.proceed(request)
            }

            ChaosAction.FAIL_IO -> throw IOException("PULSE chaos: connection failed")

            ChaosAction.TIMEOUT -> {
                delay(chain, rule.delayMillis)
                throw SocketTimeoutException("PULSE chaos: timeout")
            }

            ChaosAction.STATUS_429 -> HTTP_TOO_MANY_REQUESTS

            ChaosAction.STATUS_500 -> HTTP_INTERNAL_ERROR

            ChaosAction.STATUS_503 -> HTTP_UNAVAILABLE
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Chaos")
            .sentRequestAtMillis(time)
            .receivedResponseAtMillis(now())
            .apply { if (status != HTTP_INTERNAL_ERROR) header("Retry-After", RETRY_AFTER_SECONDS) }
            .header("Content-Type", CHAOS_CONTENT_TYPE)
            .header(MockMarker.HEADER, rule.id)
            .header(CHAOS_HEADER, rule.action.name)
            .body("""{"error":"pulse_chaos","action":"${rule.action}"}""".toResponseBody(CHAOS_CONTENT_TYPE.toMediaTypeOrNull()))
            .build()
    }

    /** 지연 중에도 취소를 확인해, 호스트가 취소하면 진짜 네트워크처럼 IOException으로 끝낸다. */
    private fun delay(chain: Interceptor.Chain, millis: Long) {
        val end = System.nanoTime() + millis * NANOS_PER_MILLI
        while (System.nanoTime() < end) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            try {
                Thread.sleep(minOf(DELAY_STEP_MILLIS, (end - System.nanoTime()) / NANOS_PER_MILLI + 1))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted", interrupted)
            }
        }
    }

    internal companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val DELAY_STEP_MILLIS = 50L
        private const val REGEX_BUDGET_NANOS = 50L * NANOS_PER_MILLI
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_INTERNAL_ERROR = 500
        private const val HTTP_UNAVAILABLE = 503
        private const val RETRY_AFTER_SECONDS = "5"
        private const val CHAOS_CONTENT_TYPE = "application/json; charset=utf-8"

        /** 장애 모사 응답에 붙는 헤더. 값은 [ChaosAction] 이름이다. */
        const val CHAOS_HEADER: String = "X-Pulse-Chaos"

        fun matches(rule: MockRule, request: Request, now: Long): Boolean {
            val expires = rule.expiresAtEpochMillis
            val method = rule.method
            val host = rule.host
            val conditions = (expires == null || now < expires) &&
                (method == null || method == request.method) &&
                (host == null || host == request.url.host)
            return conditions && pathMatches(rule, request.url.encodedPath)
        }

        private fun pathMatches(rule: MockRule, path: String): Boolean = when (rule.pathMatch) {
            PathMatch.EXACT -> path == rule.path
            PathMatch.PREFIX -> path.startsWith(rule.path)
            PathMatch.REGEX -> path.length <= MockRule.MAX_PATH_LENGTH && regexMatches(rule.path, path)
        }

        /** 정규식은 시간 예산을 넘으면 맞지 않는 것으로 본다(역추적 폭주 방지). */
        fun regexMatches(pattern: String, input: String): Boolean = try {
            Regex(pattern).matches(DeadlineCharSequence(input, System.nanoTime() + REGEX_BUDGET_NANOS))
        } catch (_: RegexBudgetExceeded) {
            false
        }
    }

    private class RegexBudgetExceeded : RuntimeException()

    private class DeadlineCharSequence(private val inner: CharSequence, private val deadline: Long) : CharSequence {
        override val length: Int
            get() = inner.length

        override fun get(index: Int): Char {
            if (System.nanoTime() > deadline) throw RegexBudgetExceeded()
            return inner[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            DeadlineCharSequence(inner.subSequence(startIndex, endIndex), deadline)

        override fun toString(): String = inner.toString()
    }
}
