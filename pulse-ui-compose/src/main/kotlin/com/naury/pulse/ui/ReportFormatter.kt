package com.naury.pulse.ui

import com.naury.pulse.api.BodyState
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.HttpMessage
import com.naury.pulse.api.TransactionDetail

/**
 * 요청 하나를 이슈 트래커나 메신저에 붙여 넣을 수 있는 글로 만든다. Markdown으로 읽어도, 그냥 읽어도 알아볼 수 있게 쓴다.
 * 모든 값은 저장 전에 이미 가린 것이며, 본문은 [MAX_BODY_CHARS]자에서 자른다.
 */
internal object ReportFormatter {
    const val MAX_BODY_CHARS = 8 * 1024

    fun format(detail: TransactionDetail, requestBody: String?, responseBody: String?, curl: String?): String = buildString {
        val summary = detail.summary
        val result = summary.statusCode?.let { "HTTP $it" } ?: summary.failureType ?: summary.state.name
        appendLine("### ${summary.method} ${summary.path} → $result")
        appendLine()
        appendLine("- URL: `${summary.url}`")
        appendLine("- Started: ${Formatters.dateTime(summary.startedAtEpochMillis)}")
        summary.durationNanos?.let { appendLine("- Duration: ${Formatters.duration(it)}") }
        detail.responseHeadersNanos?.let { appendLine("- Time to response headers: ${Formatters.duration(it)}") }
        detail.protocol?.let { appendLine("- Protocol: $it") }
        summary.responseBodyBytes?.let { appendLine("- Response size: ${Formatters.bytes(it)}") }
        summary.failureType?.let { type -> appendLine("- Failure: $type${detail.failureMessage?.let { ": $it" }.orEmpty()}") }
        if (summary.source.name != "ORIGINAL") appendLine("- Source: ${summary.source.name}")
        appendMessage("Request", detail.request, requestBody)
        detail.response?.let { appendMessage("Response", it, responseBody) }
        curl?.let {
            appendLine()
            appendLine("#### cURL")
            appendLine("```")
            appendLine(it)
            appendLine("```")
        }
        appendLine()
        append("_Recorded with PULSE. Values that look like secrets were replaced with [REDACTED] before storage._")
    }

    private fun StringBuilder.appendMessage(title: String, message: HttpMessage, body: String?) {
        appendLine()
        appendLine("#### $title")
        appendHeaders(message.headers)
        when {
            body != null -> {
                appendLine("```json")
                appendLine(JsonPrettyPrinter.format(body.take(MAX_BODY_CHARS)))
                if (body.length > MAX_BODY_CHARS) appendLine("… (truncated)")
                appendLine("```")
            }

            message.bodyState != BodyState.EMPTY -> appendLine("Body not stored (${message.bodyState.name}).")
        }
    }

    private fun StringBuilder.appendHeaders(headers: List<HeaderField>) {
        if (headers.isEmpty()) return
        appendLine("```")
        headers.forEach { appendLine("${it.name}: ${it.value}") }
        appendLine("```")
    }
}
