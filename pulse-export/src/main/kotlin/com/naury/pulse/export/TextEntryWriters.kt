package com.naury.pulse.export

import java.io.Writer

/** JSON이 아닌 형식의 쓰기. 한 내보내기 동안 같은 [Writer]에 이어 쓴다. */
internal interface TextEntryWriter {
    fun begin(out: Writer, generatedAtEpochMillis: Long, generator: String)

    fun entry(out: Writer, entry: SafeEntry)

    fun end(out: Writer) = Unit
}

/** 요청마다 한 줄. 헤더·본문은 넣지 않는다. 문자열은 RFC 4180대로 따옴표로 감싼다. */
internal object CsvEntryWriter : TextEntryWriter {
    private val columns = listOf(
        "startedAt", "method", "host", "path", "url", "statusCode", "state", "durationMs", "ttfbMs", "requestBytes", "responseBytes",
        "protocol", "source", "screen", "foreground", "network", "label", "failureType", "transactionId",
    )
    private const val NANOS_PER_MILLI = 1_000_000.0

    override fun begin(out: Writer, generatedAtEpochMillis: Long, generator: String) {
        out.write(columns.joinToString(",") + "\r\n")
    }

    override fun entry(out: Writer, entry: SafeEntry) {
        val summary = entry.detail.summary
        val context = summary.context
        val values = listOf(
            isoTime(summary.startedAtEpochMillis),
            summary.method,
            summary.host,
            summary.path,
            entry.url,
            summary.statusCode?.toString(),
            summary.state.name,
            summary.durationNanos?.let { millis(it) },
            entry.detail.responseHeadersNanos?.let { millis(it) },
            summary.requestBodyBytes?.toString(),
            summary.responseBodyBytes?.toString(),
            entry.detail.protocol,
            summary.source.name,
            context?.screen,
            context?.foreground?.toString(),
            context?.networkType?.name,
            context?.label,
            summary.failureType,
            summary.id,
        )
        out.write(values.joinToString(",") { quote(it.orEmpty()) } + "\r\n")
    }

    private fun millis(nanos: Long): String = String.format(java.util.Locale.ROOT, "%.2f", nanos / NANOS_PER_MILLI)

    /** 쉼표·따옴표·줄바꿈이 있으면 따옴표로 감싼다. 스프레드시트가 수식으로 읽지 않도록 `=`·`+`·`-`·`@`로 시작하는 값은 앞에 `'`를 붙인다. */
    fun quote(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@" && value.toDoubleOrNull() == null) "'$value" else value
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}

/** 사람이 읽는 보고서. 본문은 [MAX_BODY_CHARS]자에서 자른다. */
internal object MarkdownEntryWriter : TextEntryWriter {
    const val MAX_BODY_CHARS = 4 * 1024
    private const val NANOS_PER_MILLI = 1_000_000.0

    override fun begin(out: Writer, generatedAtEpochMillis: Long, generator: String) {
        out.write("# PULSE network report\n\n")
        out.write(
            "Generated ${isoTime(generatedAtEpochMillis)} by $generator. Values that look like secrets were replaced with [REDACTED].\n",
        )
    }

    override fun entry(out: Writer, entry: SafeEntry) {
        val summary = entry.detail.summary
        val result = summary.statusCode?.let { "HTTP $it" } ?: summary.failureType ?: summary.state.name
        out.write("\n---\n\n### ${summary.method} ${summary.path} → $result\n\n")
        listOfNotNull(
            "URL: `${entry.url}`",
            "Started: ${isoTime(summary.startedAtEpochMillis)}",
            summary.durationNanos?.let { "Duration: ${ms(it)}" },
            entry.detail.responseHeadersNanos?.let { "Time to response headers: ${ms(it)}" },
            entry.detail.protocol?.let { "Protocol: $it" },
            summary.context?.screen?.let { "Screen: $it" },
            summary.context?.label?.let { "Label: $it" },
            entry.failureMessage?.let { "Failure: ${summary.failureType.orEmpty()} $it" },
        ).forEach { out.write("- $it\n") }
        section(out, "Request", entry.primaryRequestHeaders, entry.requestBody)
        if (entry.detail.response != null) section(out, "Response", entry.primaryResponseHeaders, entry.responseBody)
    }

    private fun section(out: Writer, title: String, headers: List<com.naury.pulse.api.HeaderField>, body: String?) {
        if (headers.isEmpty() && body == null) return
        out.write("\n**$title**\n")
        if (headers.isNotEmpty()) {
            out.write("\n```\n")
            headers.forEach { out.write("${it.name}: ${it.value}\n") }
            out.write("```\n")
        }
        body?.let {
            out.write("\n```\n")
            out.write(it.take(MAX_BODY_CHARS))
            if (it.length > MAX_BODY_CHARS) out.write("\n… (truncated)")
            out.write("\n```\n")
        }
    }

    private fun ms(nanos: Long) = String.format(java.util.Locale.ROOT, "%.1f ms", nanos / NANOS_PER_MILLI)
}
