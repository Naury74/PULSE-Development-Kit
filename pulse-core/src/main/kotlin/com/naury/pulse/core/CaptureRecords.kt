package com.naury.pulse.core

import com.naury.pulse.api.BodyState
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.ResponseSource
import com.naury.pulse.api.TimingPhase
import com.naury.pulse.api.TransactionState

/** 저장 큐에 들어가는 항목. */
public sealed interface QueuedEvent {
    public val transactionId: String
}

/**
 * 마스킹을 마친 저장 단위. 생성자가 이 모듈 안에만 있어 [CaptureRecorder]를 거치지 않고는 만들 수 없다.
 * 저장소는 이 타입만 받으므로 마스킹되지 않은 값이 저장될 경로가 없다.
 */
public sealed interface CaptureRecord : QueuedEvent

public class RequestRecord internal constructor(
    override val transactionId: String,
    public val sessionId: String,
    public val source: CaptureSource,
    public val startedAtEpochMillis: Long,
    public val method: String,
    public val url: String,
    public val scheme: String,
    public val host: String,
    public val path: String,
    public val headers: List<HeaderField>,
    public val contentType: String?,
    public val declaredContentLength: Long?,
) : CaptureRecord

public class ResponseRecord internal constructor(
    override val transactionId: String,
    public val statusCode: Int,
    public val protocol: String,
    public val headers: List<HeaderField>,
    public val contentType: String?,
    public val declaredContentLength: Long?,
    public val responseHeadersNanos: Long,
) : CaptureRecord

public class BodyRecord internal constructor(
    override val transactionId: String,
    public val kind: MessageKind,
    public val state: BodyState,
    /** 마스킹한 본문. [state]가 [BodyState.CAPTURED]일 때만 있다. */
    public val text: String?,
    public val redactedValueCount: Int,
    /** 호스트가 실제로 주고받은 본문 바이트. 모르면 null. */
    public val transferredBytes: Long?,
) : CaptureRecord

public class FinishRecord internal constructor(
    override val transactionId: String,
    public val state: TransactionState,
    public val durationNanos: Long,
    public val failureType: String?,
    public val failureMessage: String?,
) : CaptureRecord

public class TimingRecord internal constructor(
    override val transactionId: String,
    public val phases: List<TimingPhase>,
    public val responseSource: ResponseSource?,
    public val connectionReused: Boolean?,
) : CaptureRecord

/** 저장 스레드에서 마스킹할 원본 본문. 큐 밖으로 나가기 전에 [BodyRecord]로 바뀐다. */
internal class RawBody(
    override val transactionId: String,
    val kind: MessageKind,
    val bytes: ByteArray,
    val contentType: String?,
    val transferredBytes: Long,
) : QueuedEvent
