package com.naury.pulse.core

import com.naury.pulse.api.BodyState
import com.naury.pulse.api.CaptureMode
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.ContextCapture
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.RequestContext
import com.naury.pulse.api.ResponseSource
import com.naury.pulse.api.TimingPhase
import com.naury.pulse.api.TransactionState
import com.naury.pulse.security.BodySanitizer
import com.naury.pulse.security.Redactor
import java.util.UUID

/**
 * 네트워크 어댑터가 관찰한 값을 마스킹해 저장 큐에 넣는다.
 *
 * 모든 메서드는 네트워크 스레드에서 불리므로 디스크·JSON 작업을 하지 않고, 실패해도 예외를 밖으로 던지지 않는다(fail open).
 * 헤더·URL·실패 메시지는 여기서 바로 마스킹하고, 본문은 저장 스레드에서 마스킹한다.
 */
public class CaptureRecorder internal constructor(
    config: PulseConfig,
    public val clock: CaptureClock,
    private val queue: CaptureQueue,
    private val redactor: Redactor,
    public val sessionId: String,
    private val contextSource: AppContextSource? = null,
) {
    private val filter = config.filter
    private val contextOptions = config.context
    private val bodySupport = BodySanitizer(redactor, config.bodyFormats)

    public val controller: RecordingController = RecordingController(config.startRecording)

    public val isRecording: Boolean
        get() = controller.isRecording

    public val capturesBodies: Boolean = config.captureMode == CaptureMode.METADATA_AND_SAFE_BODIES

    public val maxRequestBodyBytes: Long = config.limits.maxRequestBodyBytes

    public val maxResponseBodyBytes: Long = config.limits.maxResponseBodyBytes

    public fun newTransactionId(): String = UUID.randomUUID().toString()

    /** [PulseConfig.filter]에 따라 이 요청을 기록할지. */
    public fun accepts(host: String, path: String): Boolean = filter.accepts(host, path)

    /** 이 형식의 본문을 저장할 수 있는지([PulseConfig.bodyFormats]). */
    public fun supportsBody(contentType: String?): Boolean = bodySupport.supports(contentType)

    @Suppress("LongParameterList")
    public fun requestStarted(
        transactionId: String,
        startedAtEpochMillis: Long,
        method: String,
        url: String,
        scheme: String,
        host: String,
        path: String,
        headers: List<Pair<String, String>>,
        contentType: String?,
        declaredContentLength: Long?,
        source: CaptureSource = CaptureSource.ORIGINAL,
        parentTransactionId: String? = null,
        label: String? = null,
    ) {
        val context = snapshotContext(contextSource, contextOptions, label)
        enqueue {
            RequestRecord(
                transactionId = transactionId,
                sessionId = sessionId,
                source = source,
                parentTransactionId = parentTransactionId,
                startedAtEpochMillis = startedAtEpochMillis,
                method = method,
                url = redactor.sanitizeUrl(url),
                scheme = scheme,
                host = host,
                path = path,
                headers = redactor.redactHeaders(headers),
                contentType = contentType,
                declaredContentLength = declaredContentLength,
                context = context,
            )
        }
    }

    @Suppress("LongParameterList")
    public fun responseReceived(
        transactionId: String,
        statusCode: Int,
        protocol: String,
        headers: List<Pair<String, String>>,
        contentType: String?,
        declaredContentLength: Long?,
        responseHeadersNanos: Long,
        mockRuleId: String? = null,
    ) {
        enqueue {
            ResponseRecord(
                transactionId = transactionId,
                statusCode = statusCode,
                protocol = protocol,
                headers = redactor.redactHeaders(headers),
                contentType = contentType,
                declaredContentLength = declaredContentLength,
                responseHeadersNanos = responseHeadersNanos,
                mockRuleId = mockRuleId,
            )
        }
    }

    /** 끝까지 주고받은 본문 원본. 저장 스레드에서 마스킹한 뒤 저장한다. */
    public fun bodyCaptured(transactionId: String, kind: MessageKind, bytes: ByteArray, contentType: String?) {
        enqueue { RawBody(transactionId, kind, bytes, contentType, bytes.size.toLong()) }
    }

    /** 본문을 저장하지 않은 이유. [state]는 [BodyState.CAPTURED]가 아니어야 한다. */
    public fun bodyNotCaptured(transactionId: String, kind: MessageKind, state: BodyState, transferredBytes: Long?) {
        enqueue {
            require(state != BodyState.CAPTURED) { "CAPTURED는 bodyCaptured로 기록합니다." }
            BodyRecord(transactionId, kind, state, text = null, redactedValueCount = 0, transferredBytes = transferredBytes)
        }
    }

    public fun finished(transactionId: String, state: TransactionState, durationNanos: Long, failure: Throwable?) {
        enqueue {
            FinishRecord(
                transactionId = transactionId,
                state = state,
                durationNanos = durationNanos,
                failureType = failure?.javaClass?.name,
                failureMessage = redactor.sanitizeMessage(failure?.message),
            )
        }
    }

    /** 관찰한 단계와 근거. 단계도 근거도 없으면 기록하지 않는다. */
    public fun timings(transactionId: String, phases: List<TimingPhase>, responseSource: ResponseSource?, connectionReused: Boolean?) {
        if (phases.isEmpty() && responseSource == null && connectionReused == null) return
        enqueue { TimingRecord(transactionId, phases, responseSource, connectionReused) }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun enqueue(create: () -> QueuedEvent) {
        try {
            queue.offer(create())
        } catch (_: RuntimeException) {
            // 수집 실패가 호스트 요청을 깨뜨리면 안 된다. 잃은 이벤트로만 센다.
            queue.recordLostEvent()
        }
    }

    internal companion object {
        fun droppedBody(body: RawBody): BodyRecord =
            BodyRecord(body.transactionId, body.kind, BodyState.DROPPED, text = null, redactedValueCount = 0, body.transferredBytes)
    }
}

/** 요청 시작 순간의 맥락. 맥락을 읽다 실패해도 요청 기록은 계속하도록 라벨만 남긴다. */
@Suppress("TooGenericExceptionCaught")
private fun snapshotContext(source: AppContextSource?, options: ContextCapture, label: String?): RequestContext? {
    val context = try {
        RequestContext(
            screen = if (options.screen) source?.screen else null,
            foreground = if (options.appState) source?.foreground else null,
            networkType = if (options.networkType) source?.networkType else null,
            label = label,
        )
    } catch (_: RuntimeException) {
        RequestContext(null, null, null, label)
    }
    return context.takeUnless { it.isEmpty }
}
