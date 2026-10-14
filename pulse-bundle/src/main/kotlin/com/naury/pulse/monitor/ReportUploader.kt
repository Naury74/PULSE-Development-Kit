package com.naury.pulse.monitor

import com.naury.pulse.api.PulseIgnore
import com.naury.pulse.api.ReportDestination
import com.naury.pulse.api.ReportEncoding
import com.naury.pulse.api.ReportState
import com.naury.pulse.api.ReportStatus
import com.naury.pulse.api.ReportUpload
import com.naury.pulse.api.Reporting
import com.naury.pulse.api.TransportResult
import com.naury.pulse.storage.PulseStorage
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * 대기 중인 보고를 목적지로 보낸다. 목적지는 코드 설정([Reporting])으로만 정하며, 없으면 보내지 않는다.
 *
 * - 보낼 때마다 [ReportDestination.headerProvider]를 불러 인증 헤더를 받는다(짧게 쓰는 토큰을 저장하지 않는다).
 * - 내장 전송은 PULSE 인터셉터가 없는 별도 클라이언트로 보내고, 리다이렉트는 따르지 않으며, 허용 호스트를 매번 다시 확인한다.
 * - `X-Pulse-Report-Id`·`Idempotency-Key`를 붙여 받는 쪽이 중복을 거를 수 있게 한다. 응답 전에 연결이 끊기면 다시 보낼 수 있다.
 * - 실패하면 15초부터 두 배씩(최대 1시간) 늘려 다시 보내고, [ReportDestination.maxAttempts]를 넘기면 실패로 둔다.
 * - 보내던 중에 프로세스가 끝나면 다음 실행 때 다시 보낸다.
 */
internal class ReportUploader(
    private val reporting: Reporting?,
    private val storage: PulseStorage,
    private val service: ReportService,
    private val progress: MutableStateFlow<Map<String, Float>>,
    private val scope: CoroutineScope,
) {
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val running = ConcurrentHashMap<String, Job>()
    private val clients = ConcurrentHashMap<Long, OkHttpClient>()

    fun start() {
        if (reporting == null) return
        scope.launch {
            storage.requeueInterruptedReports()
            while (isActive) {
                drain()
                select {
                    wakeups.onReceive { }
                    onTimeout(POLL_MILLIS) { }
                }
            }
        }
    }

    fun wake() {
        wakeups.trySend(Unit)
    }

    fun cancel(id: String) {
        running.remove(id)?.cancel()
        scope.launch {
            val state = storage.reportState(id) ?: return@launch
            if (state.status == ReportStatus.QUEUED || state.status == ReportStatus.SENDING) {
                storage.updateReport(id, ReportStatus.CANCELED, state.attempts, 0, null, state.destinationId)
            }
            clearProgress(id)
        }
    }

    private suspend fun drain() {
        while (true) {
            val due = storage.dueReportUploads(System.currentTimeMillis(), BATCH)
            if (due.isEmpty()) return
            due.forEach { state ->
                val job = scope.launch { send(state) }
                running[state.id] = job
                job.join()
                running.remove(state.id)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun send(state: ReportState) {
        val destination = reporting?.destinationFor(state.kind)
        if (destination == null) {
            storage.updateReport(state.id, ReportStatus.FAILED, state.attempts, 0, "보낼 목적지가 없습니다.", null)
            return
        }
        storage.updateReport(state.id, ReportStatus.SENDING, state.attempts, 0, null, destination.id)
        setProgress(state.id, 0f)
        val result = try {
            val upload = service.buildUpload(state.id) ?: return
            val transport = destination.transport
            if (transport != null) transport.send(upload) { setProgress(state.id, it.coerceIn(0f, 1f)) } else post(destination, upload)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            TransportResult.Failed(error.javaClass.simpleName + (error.message?.let { ": $it" } ?: ""), retryable = true)
        } finally {
            clearProgress(state.id)
        }
        val attempts = state.attempts + 1
        when (result) {
            is TransportResult.Sent -> storage.updateReport(state.id, ReportStatus.SENT, attempts, 0, null, destination.id)

            is TransportResult.Failed -> if (result.retryable && attempts < destination.maxAttempts) {
                val backoff = (BASE_BACKOFF_MILLIS shl (attempts - 1).coerceAtMost(MAX_BACKOFF_SHIFT)).coerceAtMost(MAX_BACKOFF_MILLIS)
                storage.updateReport(
                    state.id,
                    ReportStatus.QUEUED,
                    attempts,
                    System.currentTimeMillis() + backoff,
                    result.message,
                    destination.id,
                )
            } else {
                storage.updateReport(state.id, ReportStatus.FAILED, attempts, 0, result.message, destination.id)
            }
        }
    }

    private suspend fun post(destination: ReportDestination, upload: ReportUpload): TransportResult {
        val url = destination.endpoint?.toHttpUrlOrNull()?.takeIf { destination.allowsHost(it.host) }
            ?: return TransportResult.Failed("허용하지 않았거나 해석할 수 없는 주소입니다: ${destination.endpoint}", retryable = false)
        val headers = destination.headerProvider?.headersFor(upload.reportId, upload.kind).orEmpty()
        val request = Request.Builder()
            .url(url)
            .post(body(destination.encoding, upload))
            .header("X-Pulse-Report-Id", upload.reportId)
            .header("Idempotency-Key", upload.reportId)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .tag(PulseIgnore::class.java, PulseIgnore)
            .build()
        return client(destination.timeoutMillis).newCall(request).await().use(::result)
    }

    private fun client(timeoutMillis: Long): OkHttpClient = clients.getOrPut(timeoutMillis) {
        OkHttpClient.Builder()
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun setProgress(id: String, value: Float) = progress.update { it + (id to value) }

    private fun clearProgress(id: String) = progress.update { it - id }

    private companion object {
        const val BATCH = 5
        const val POLL_MILLIS = 60_000L
        const val BASE_BACKOFF_MILLIS = 15_000L
        const val MAX_BACKOFF_SHIFT = 8
        const val MAX_BACKOFF_MILLIS = 60L * 60 * 1000
    }
}

private fun body(encoding: ReportEncoding, upload: ReportUpload) = when (encoding) {
    ReportEncoding.JSON -> upload.json.toRequestBody(JSON)

    ReportEncoding.MULTIPART -> MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("report", null, upload.json.toRequestBody(JSON))
        .apply {
            upload.attachments.forEach { addFormDataPart("attachment", it.name, it.bytes.toRequestBody(it.mimeType.toMediaType())) }
        }
        .build()
}

/** 2xx는 보냄, 408·425·429·5xx는 다시 보낼 실패, 그 밖의 4xx는 다시 보내도 같을 실패다. */
private fun result(response: Response): TransportResult = when {
    response.isSuccessful -> TransportResult.Sent(response.code)

    response.code in RETRYABLE_CODES || response.code >= HTTP_SERVER_ERROR -> TransportResult.Failed(
        "HTTP ${response.code}",
        retryable = true,
    )

    else -> TransportResult.Failed("HTTP ${response.code}", retryable = false)
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(Result.success(response))
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }
        },
    )
}

private val JSON = "application/json; charset=utf-8".toMediaType()
private val RETRYABLE_CODES = setOf(408, 425, 429)
private const val HTTP_SERVER_ERROR = 500
