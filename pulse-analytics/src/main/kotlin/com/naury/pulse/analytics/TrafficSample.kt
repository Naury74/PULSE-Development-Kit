package com.naury.pulse.analytics

import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.RequestContext
import com.naury.pulse.api.ResponseSource
import com.naury.pulse.api.TransactionState

/** 집계·진단에 쓰는 트랜잭션 하나. 저장된(마스킹된) 값만 담는다. */
@Suppress("LongParameterList")
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
    public val source: CaptureSource = CaptureSource.ORIGINAL,
    /** `h2`, `http/1.1` 등. 응답을 받지 못했으면 null. */
    public val protocol: String? = null,
    /** 요청 시작부터 응답 헤더까지(ns). */
    public val responseHeadersNanos: Long? = null,
    /** 실패 예외의 클래스 이름. */
    public val failureType: String? = null,
    public val connectionReused: Boolean? = null,
    public val context: RequestContext? = null,
    /** 관찰한 DNS·연결·TLS 단계 시간 합(ns). 관찰하지 못했으면 null. */
    public val dnsNanos: Long? = null,
    public val connectNanos: Long? = null,
    public val tlsNanos: Long? = null,
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
