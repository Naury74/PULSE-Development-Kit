package com.naury.pulse.export

import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.HttpMessage
import com.naury.pulse.api.TimingPhase
import com.naury.pulse.api.TimingPhaseType
import com.naury.pulse.api.TransactionDetail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 다시 마스킹한 트랜잭션 하나. 쓰는 쪽은 이 값만 본다. */
internal class SafeEntry(
    val detail: TransactionDetail,
    val url: String,
    val requestHeaders: List<HeaderField>,
    val responseHeaders: List<HeaderField>,
    val requestBody: String?,
    val responseBody: String?,
    val failureMessage: String?,
)

internal interface EntryWriter {
    fun begin(json: JsonStreamWriter, generatedAtEpochMillis: Long, generator: String)

    fun entry(json: JsonStreamWriter, entry: SafeEntry)

    fun end(json: JsonStreamWriter)
}

internal fun isoTime(epochMillis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(Date(epochMillis))

private const val NANOS_PER_MILLI = 1_000_000.0
private const val UNKNOWN = -1L

private fun JsonStreamWriter.headers(name: String, headers: List<HeaderField>) {
    name(name).beginArray()
    headers.forEach { beginObject().name("name").value(it.name).name("value").value(it.value).endObject() }
    endArray()
}

/** PULSE 자체 형식 `pulse.capture.v1`. */
internal object PulseJsonEntryWriter : EntryWriter {
    const val SCHEMA = "pulse.capture.v1"

    override fun begin(json: JsonStreamWriter, generatedAtEpochMillis: Long, generator: String) {
        json.beginObject()
            .name("schema").value(SCHEMA)
            .name("generatedAt").value(isoTime(generatedAtEpochMillis))
            .name("generator").value(generator)
            .name("redaction").value("Values were redacted before storage and again at export. Originals are not recoverable.")
            .name("transactions").beginArray()
    }

    override fun entry(json: JsonStreamWriter, entry: SafeEntry) {
        val summary = entry.detail.summary
        json.beginObject()
            .name("id").value(summary.id)
            .name("source").value(summary.source.name)
            .name("startedAt").value(isoTime(summary.startedAtEpochMillis))
            .name("durationNanos").value(summary.durationNanos)
            .name("state").value(summary.state.name)
            .name("method").value(summary.method)
            .name("url").value(entry.url)
            .name("statusCode").value(summary.statusCode?.toLong())
            .name("protocol").value(entry.detail.protocol)
            .name("failureType").value(summary.failureType)
            .name("failureMessage").value(entry.failureMessage)
        message(json, "request", entry.detail.request, entry.requestHeaders, entry.requestBody)
        entry.detail.response?.let { message(json, "response", it, entry.responseHeaders, entry.responseBody) }
        json.name("timings").beginArray()
        entry.detail.timings.forEach { phase ->
            json.beginObject()
                .name("phase").value(phase.type.name)
                .name("exchange").value(phase.exchangeIndex.toLong())
                .name("startOffsetNanos").value(phase.startOffsetNanos)
                .name("durationNanos").value(phase.durationNanos)
                .endObject()
        }
        json.endArray()
            .name("responseSource").value(entry.detail.responseSource?.name)
            .name("connectionReused").value(entry.detail.connectionReused)
            .endObject()
    }

    private fun message(json: JsonStreamWriter, name: String, message: HttpMessage, headers: List<HeaderField>, body: String?) {
        json.name(name).beginObject()
        json.headers("headers", headers)
        json.name("contentType").value(message.contentType)
            .name("declaredContentLength").value(message.declaredContentLength)
            .name("bodyState").value(
                if (body == null &&
                    message.bodyState.name == "CAPTURED"
                ) {
                    "REDACTION_FAILED"
                } else {
                    message.bodyState.name
                },
            )
            .name("redactedValueCount").value(message.redactedValueCount.toLong())
            .name("body").value(body)
            .endObject()
    }

    override fun end(json: JsonStreamWriter) {
        json.endArray().endObject()
    }
}

/**
 * HAR 1.2. 마지막 교환의 관찰한 단계로 `timings`를 채우고, 관찰하지 못한 값은 -1로 둔다(추정하지 않는다).
 * 전송 바이트(`bodySize`, `headersSize`)는 알 수 없어 -1이다.
 */
internal object HarEntryWriter : EntryWriter {
    override fun begin(json: JsonStreamWriter, generatedAtEpochMillis: Long, generator: String) {
        json.beginObject().name("log").beginObject()
            .name("version").value("1.2")
            .name("creator").beginObject().name("name").value("PULSE").name("version").value(generator).endObject()
            .name("entries").beginArray()
    }

    override fun entry(json: JsonStreamWriter, entry: SafeEntry) {
        val detail = entry.detail
        val summary = detail.summary
        val timings = HarTimings.of(detail.timings)
        json.beginObject()
            .name("startedDateTime").value(isoTime(summary.startedAtEpochMillis))
            .name("time").value(summary.durationNanos?.let { millis(it) } ?: UNKNOWN)
        request(json, entry)
        response(json, entry)
        json.name("cache").beginObject().endObject()
        json.name("timings").beginObject()
            .name("blocked").value(UNKNOWN)
            .name("dns").value(timings.dns)
            .name("connect").value(timings.connect)
            .name("ssl").value(timings.ssl)
            .name("send").value(timings.send)
            .name("wait").value(timings.wait)
            .name("receive").value(timings.receive)
            .endObject()
        json.name("_pulse").beginObject()
            .name("transactionId").value(summary.id)
            .name("state").value(summary.state.name)
            .name("failureType").value(summary.failureType)
            .name("failureMessage").value(entry.failureMessage)
            .name("requestBodyState").value(detail.request.bodyState.name)
            .name("responseBodyState").value(detail.response?.bodyState?.name)
            .name("responseSource").value(detail.responseSource?.name)
            .endObject()
        json.endObject()
    }

    override fun end(json: JsonStreamWriter) {
        json.endArray().endObject().endObject()
    }

    private fun request(json: JsonStreamWriter, entry: SafeEntry) {
        val detail = entry.detail
        val summary = detail.summary
        json.name("request").beginObject()
            .name("method").value(summary.method)
            .name("url").value(entry.url)
            .name("httpVersion").value(httpVersion(detail.protocol))
            .name("cookies").beginArray().endArray()
        json.headers("headers", entry.requestHeaders)
        json.name("queryString").beginArray()
        queryParameters(entry.url).forEach { (name, value) ->
            json.beginObject().name("name").value(name).name("value").value(value).endObject()
        }
        json.endArray()
        entry.requestBody?.let { body ->
            json.name(
                "postData",
            ).beginObject().name("mimeType").value(detail.request.contentType.orEmpty()).name("text").value(body).endObject()
        }
        json.name("headersSize").value(UNKNOWN).name("bodySize").value(summary.requestBodyBytes ?: UNKNOWN).endObject()
    }

    private fun response(json: JsonStreamWriter, entry: SafeEntry) {
        val detail = entry.detail
        val summary = detail.summary
        json.name("response").beginObject()
            .name("status").value(summary.statusCode?.toLong() ?: 0L)
            .name("statusText").value("")
            .name("httpVersion").value(httpVersion(detail.protocol))
            .name("cookies").beginArray().endArray()
        json.headers("headers", entry.responseHeaders)
        json.name("content").beginObject()
            .name("size").value(summary.responseBodyBytes ?: UNKNOWN)
            .name("mimeType").value(detail.response?.contentType.orEmpty())
        entry.responseBody?.let { json.name("text").value(it) }
        json.endObject()
            .name("redirectURL").value("")
            .name("headersSize").value(UNKNOWN)
            .name("bodySize").value(UNKNOWN)
            .endObject()
    }

    private fun httpVersion(protocol: String?): String = when (protocol?.lowercase()) {
        "http/1.0" -> "HTTP/1.0"
        "http/1.1" -> "HTTP/1.1"
        "h2", "h2_prior_knowledge" -> "HTTP/2"
        "quic", "h3" -> "HTTP/3"
        else -> protocol.orEmpty()
    }

    private fun queryParameters(url: String): List<Pair<String, String>> {
        val query = url.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        if (query.isEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map {
            it.substringBefore('=') to
                it.substringAfter('=', missingDelimiterValue = "")
        }
    }
}

/** 마지막 교환의 관찰한 단계로 계산한 HAR 타이밍(ms). 관찰하지 못한 값은 -1. */
internal class HarTimings(val dns: Long, val connect: Long, val ssl: Long, val send: Long, val wait: Long, val receive: Long) {
    companion object {
        fun of(phases: List<TimingPhase>): HarTimings {
            val last = phases.maxOfOrNull { it.exchangeIndex } ?: return HarTimings(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN)
            val exchange = phases.filter { it.exchangeIndex == last }
            fun total(vararg types: TimingPhaseType): Long {
                val matching = exchange.filter { it.type in types }
                if (matching.isEmpty() || matching.any { it.durationNanos == null }) return UNKNOWN
                return millis(matching.sumOf { it.durationNanos ?: 0 })
            }
            val requestEnd = exchange.filter { it.type == TimingPhaseType.REQUEST_HEADERS || it.type == TimingPhaseType.REQUEST_BODY }
                .mapNotNull { phase -> phase.durationNanos?.let { phase.startOffsetNanos + it } }
                .maxOrNull()
            val headers = exchange.lastOrNull { it.type == TimingPhaseType.RESPONSE_HEADERS }
            val headersEnd = headers?.durationNanos?.let { headers.startOffsetNanos + it }
            val wait = if (requestEnd != null && headersEnd != null &&
                headersEnd >= requestEnd
            ) {
                millis(headersEnd - requestEnd)
            } else {
                UNKNOWN
            }
            // OkHttp의 connect 구간은 TLS 핸드셰이크를 포함하고, HAR의 connect도 ssl을 포함하므로 그대로 쓴다.
            return HarTimings(
                dns = total(TimingPhaseType.DNS),
                connect = total(TimingPhaseType.CONNECT),
                ssl = total(TimingPhaseType.TLS),
                send = total(TimingPhaseType.REQUEST_HEADERS, TimingPhaseType.REQUEST_BODY),
                wait = wait,
                receive = total(TimingPhaseType.RESPONSE_BODY),
            )
        }
    }
}

private fun millis(nanos: Long): Long = Math.round(nanos / NANOS_PER_MILLI)
