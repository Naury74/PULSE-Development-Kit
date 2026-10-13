package com.naury.pulse.okhttp

import com.naury.pulse.api.BodyState
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.TransactionState
import com.naury.pulse.core.CaptureRecorder
import com.naury.pulse.core.CaptureTag
import com.naury.pulse.security.BodySanitizer
import java.io.IOException
import java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import java.net.HttpURLConnection.HTTP_NO_CONTENT
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

/** 한 논리 요청의 수집 상태. 기록은 [CaptureRecorder]가 실패를 삼키므로 여기서 호스트로 예외가 나가지 않는다. */
internal class TransactionCapture(
    private val recorder: CaptureRecorder,
    private val call: Call,
    val transactionId: String,
    private val startNanos: Long,
) {
    private val finished = AtomicBoolean()

    fun recordRequest(request: Request, startedAtEpochMillis: Long, tag: CaptureTag?) {
        val body = request.body
        recorder.requestStarted(
            transactionId = transactionId,
            startedAtEpochMillis = startedAtEpochMillis,
            method = request.method,
            url = request.url.toString(),
            scheme = request.url.scheme,
            host = request.url.host,
            path = request.url.encodedPath,
            headers = request.headers.toPairs(),
            contentType = body?.contentType()?.toString(),
            declaredContentLength = body?.let { safeContentLength { it.contentLength() } },
            source = tag?.source ?: CaptureSource.ORIGINAL,
            parentTransactionId = tag?.parentTransactionId,
        )
    }

    /** 요청 본문을 복사할 수 있으면 복사하는 본문으로 바꾼 요청을, 아니면 이유를 기록하고 원래 요청을 돌려준다. */
    fun teeRequestBody(request: Request): Request {
        val body = request.body
        val contentType = body?.contentType()?.toString()
        val declaredLength = body?.let { safeContentLength { it.contentLength() } }
        val skipReason = requestSkipReason(body, contentType, declaredLength)
        if (skipReason != null) {
            val transferred = if (skipReason == BodyState.EMPTY) 0L else declaredLength
            recorder.bodyNotCaptured(transactionId, MessageKind.REQUEST, skipReason, transferred)
            return request
        }
        val tee = TeeRequestBody(requireNotNull(body), recorder.maxRequestBodyBytes) { copy ->
            val bytes = copy.bytesOrNull()
            when {
                bytes == null -> recorder.bodyNotCaptured(transactionId, MessageKind.REQUEST, BodyState.TOO_LARGE, copy.total)
                bytes.isEmpty() -> recorder.bodyNotCaptured(transactionId, MessageKind.REQUEST, BodyState.EMPTY, 0)
                else -> recorder.bodyCaptured(transactionId, MessageKind.REQUEST, bytes, contentType)
            }
        }
        return request.newBuilder().method(request.method, tee).build()
    }

    private fun requestSkipReason(body: RequestBody?, contentType: String?, declaredLength: Long?): BodyState? = when {
        body == null || declaredLength == 0L -> BodyState.EMPTY
        !recorder.capturesBodies -> BodyState.OMITTED_BY_POLICY
        body.isDuplex() -> BodyState.DUPLEX_UNSUPPORTED
        body.isOneShot() -> BodyState.ONE_SHOT_UNSUPPORTED
        !BodySanitizer.isJson(contentType) -> BodyState.UNSUPPORTED_CONTENT
        declaredLength != null && declaredLength > recorder.maxRequestBodyBytes -> BodyState.TOO_LARGE
        else -> null
    }

    /** 응답 메타데이터를 기록하고, 본문이 있으면 호스트가 읽는 바이트를 관찰하는 본문으로 바꾼 응답을 돌려준다. */
    fun observeResponse(response: Response): Response {
        val headersNanos = recorder.clock.monoNanos() - startNanos
        val body = response.body
        val contentType = body.contentType()?.toString()
        val declaredLength = response.header("Content-Length")?.toLongOrNull()
            ?: safeContentLength { body.contentLength() }
        recorder.responseReceived(
            transactionId = transactionId,
            statusCode = response.code,
            protocol = response.protocol.toString(),
            headers = response.headers.toPairs(),
            contentType = contentType,
            declaredContentLength = declaredLength,
            responseHeadersNanos = headersNanos,
        )
        if (!response.promisesBody() || safeContentLength { body.contentLength() } == 0L) {
            recorder.bodyNotCaptured(transactionId, MessageKind.RESPONSE, BodyState.EMPTY, 0)
            finish(TransactionState.COMPLETED, null)
            return response
        }
        val skipReason = when {
            !recorder.capturesBodies -> BodyState.OMITTED_BY_POLICY
            !BodySanitizer.isJson(contentType) || response.isEncoded() -> BodyState.UNSUPPORTED_CONTENT
            declaredLength != null && declaredLength > recorder.maxResponseBodyBytes -> BodyState.TOO_LARGE
            else -> null
        }
        val limit = if (skipReason == null) recorder.maxResponseBodyBytes else 0L
        val observed = TeeResponseBody(body, limit, ResponseObserver(skipReason, contentType))
        return response.newBuilder().body(observed).build()
    }

    fun requestFailed(error: Throwable) {
        finish(if (call.isCanceled()) TransactionState.CANCELED else TransactionState.FAILED, error)
    }

    private fun finish(state: TransactionState, error: Throwable?) {
        if (!finished.compareAndSet(false, true)) return
        recorder.finished(transactionId, state, recorder.clock.monoNanos() - startNanos, error)
    }

    private inner class ResponseObserver(private val skipReason: BodyState?, private val contentType: String?) : ResponseBodyObserver {
        override fun onComplete(copy: CappedCopy) {
            val bytes = copy.bytesOrNull()
            when {
                copy.total == 0L -> recorder.bodyNotCaptured(transactionId, MessageKind.RESPONSE, BodyState.EMPTY, 0)
                skipReason != null -> recorder.bodyNotCaptured(transactionId, MessageKind.RESPONSE, skipReason, copy.total)
                bytes == null -> recorder.bodyNotCaptured(transactionId, MessageKind.RESPONSE, BodyState.TOO_LARGE, copy.total)
                else -> recorder.bodyCaptured(transactionId, MessageKind.RESPONSE, bytes, contentType)
            }
            finish(TransactionState.COMPLETED, null)
        }

        override fun onClosedEarly(bytesRead: Long) {
            recorder.bodyNotCaptured(transactionId, MessageKind.RESPONSE, BodyState.INCOMPLETE_READ, null)
            finish(TransactionState.COMPLETED, null)
        }

        override fun onReadFailed(bytesRead: Long, error: IOException) {
            recorder.bodyNotCaptured(transactionId, MessageKind.RESPONSE, BodyState.INCOMPLETE_READ, null)
            requestFailed(error)
        }
    }

    private companion object {
        private const val HTTP_CONTINUE_MIN = 100
        private const val HTTP_CONTINUE_MAX = 199

        fun Headers.toPairs(): List<Pair<String, String>> = map { (name, value) -> name to value }

        fun safeContentLength(read: () -> Long): Long? = try {
            read().takeIf { it >= 0 }
        } catch (_: IOException) {
            null
        }

        /** HEAD 응답과 1xx·204·304는 본문이 없다(RFC 9110). */
        fun Response.promisesBody(): Boolean = request.method != "HEAD" &&
            code !in HTTP_CONTINUE_MIN..HTTP_CONTINUE_MAX &&
            code != HTTP_NO_CONTENT &&
            code != HTTP_NOT_MODIFIED

        /** 호스트가 직접 압축을 요청해 압축된 채로 전달되는 본문. OkHttp가 투명하게 푼 본문은 이 헤더가 없다. */
        fun Response.isEncoded(): Boolean = header("Content-Encoding")?.let { !it.equals("identity", ignoreCase = true) } ?: false
    }
}
