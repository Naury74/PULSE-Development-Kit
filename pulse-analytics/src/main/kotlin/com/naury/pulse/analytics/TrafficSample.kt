package com.naury.pulse.analytics

import com.naury.pulse.api.ResponseSource
import com.naury.pulse.api.TransactionState

/** 집계·진단에 쓰는 트랜잭션 하나. 저장된(마스킹된) 값만 담는다. */
public class TrafficSample(
    public val id: String,
    public val method: String,
    public val url: String,
    public val host: String,
    public val path: String,
    public val startedAtEpochMillis: Long,
    public val durationNanos: Long?,
    public val statusCode: Int?,
    public val state: TransactionState,
    public val requestBodyBytes: Long?,
    public val responseBodyBytes: Long?,
    /** 요청에 `Authorization`·`Cookie` 같은 자격 증명 헤더가 있었는지. 값은 저장하지 않고 이름만 본다. */
    public val hasCredentials: Boolean,
    public val responseSource: ResponseSource?,
) {
    internal val isError: Boolean
        get() = (statusCode != null && statusCode >= HTTP_CLIENT_ERROR) || state in failedStates

    internal val endpoint: String
        get() = "$method $path"

    internal val endEpochMillis: Long?
        get() = durationNanos?.let { startedAtEpochMillis + it / NANOS_PER_MILLI }

    internal companion object {
        const val HTTP_CLIENT_ERROR = 400
        const val HTTP_SERVER_ERROR = 500
        const val NANOS_PER_MILLI = 1_000_000L
        val failedStates = setOf(TransactionState.FAILED, TransactionState.CANCELED, TransactionState.INTERRUPTED)
    }
}
