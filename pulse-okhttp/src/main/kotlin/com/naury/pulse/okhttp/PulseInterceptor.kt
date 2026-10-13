package com.naury.pulse.okhttp

import com.naury.pulse.core.CaptureRecorder
import com.naury.pulse.core.CaptureTag
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 논리 요청 하나를 기록하는 application interceptor. `proceed`는 정확히 한 번 부르고, 호스트의 예외·취소는 그대로 전달한다.
 * 수집 준비나 기록에 실패하면 수집만 포기하고 원래 요청·응답을 그대로 쓴다(fail open).
 */
public class PulseInterceptor(private val recorder: CaptureRecorder, private val timings: CallTimingRegistry) : Interceptor {
    // 실패를 기록한 뒤 같은 예외를 다시 던지므로 모든 종류를 잡는다.
    @Suppress("TooGenericExceptionCaught")
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!recorder.isRecording) return chain.proceed(request)
        val tag = request.tag(CaptureTag::class.java)
        // 재실행·모의 응답처럼 PULSE가 직접 만든 요청은 필터와 상관없이 기록한다.
        if (tag == null && !accepts(request)) return chain.proceed(request)

        val capture = runCatchingCapture {
            val startNanos = recorder.clock.monoNanos()
            TransactionCapture(recorder, chain.call(), tag?.transactionId ?: recorder.newTransactionId(), startNanos).also {
                it.recordRequest(request, recorder.clock.wallMillis(), tag)
                timings.attach(chain.call(), it.transactionId, startNanos)
            }
        }
        val outgoing = capture?.let { runCatchingCapture { it.teeRequestBody(request) } } ?: request

        val response = try {
            chain.proceed(outgoing)
        } catch (error: Throwable) {
            capture?.let { runCatchingCapture { it.requestFailed(error) } }
            throw error
        }
        return capture?.let { runCatchingCapture { it.observeResponse(response) } } ?: response
    }

    private fun accepts(request: okhttp3.Request): Boolean =
        runCatchingCapture { recorder.accepts(request.url.host, request.url.encodedPath) } ?: true

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> runCatchingCapture(block: () -> T): T? = try {
        block()
    } catch (_: RuntimeException) {
        null
    }
}
