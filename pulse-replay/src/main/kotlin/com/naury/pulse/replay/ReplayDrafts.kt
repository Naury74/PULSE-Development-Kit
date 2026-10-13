package com.naury.pulse.replay

import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import com.naury.pulse.api.ReplayDraft
import com.naury.pulse.api.TransactionDetail

/** 저장된 요청으로 재실행 초안을 만든다. */
public object ReplayDrafts {
    /** OkHttp가 다시 붙이거나 다시 보내면 안 되는 헤더. */
    private val transportHeaders = setOf("host", "content-length", "connection", "transfer-encoding", "accept-encoding", "te", "upgrade")

    /** 가려진 헤더·전송 헤더를 빼고, 값이 가려진 쿼리 파라미터를 URL에서 지운다. */
    @JvmStatic
    public fun from(detail: TransactionDetail, requestBody: String?): ReplayDraft {
        val (redacted, kept) = detail.request.headers.partition { it.value == REDACTED }
        return ReplayDraft(
            sourceTransactionId = detail.summary.id,
            method = detail.summary.method,
            url = withoutRedactedQuery(detail.summary.url),
            headers = kept.filter { it.name.lowercase() !in transportHeaders },
            body = requestBody,
            contentType = detail.request.contentType,
            removedHeaders = redacted.map(HeaderField::name),
        )
    }

    private fun withoutRedactedQuery(url: String): String {
        val base = url.substringBefore('?')
        val query = url.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return url
        val kept = query.split('&').filterNot { it.substringAfter('=', missingDelimiterValue = "") == REDACTED }
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }
}
