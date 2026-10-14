package com.naury.pulse.export

import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.EventQuery
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.RedactionPolicy
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 앱 이벤트(로그·마커·오류 등)를 내보낼 형식. */
public enum class EventExportFormat(public val fileExtension: String, public val mimeType: String) {
    /** logcat과 비슷한 한 줄 형식. 상세(스택)는 들여 써서 다음 줄에 붙인다. */
    TEXT("log", "text/plain"),

    /** 이벤트 배열(`pulse.events.v1`). */
    JSON("json", "application/json"),

    /** 이벤트마다 한 줄인 표(RFC 4180). 상세는 넣지 않는다. */
    CSV("csv", "text/csv"),
}

/**
 * [query]에 맞는 이벤트를 시간순(오래된 것부터)으로 쓴다. 쓰기 전에 현재 정책으로 다시 마스킹한다.
 * 한 페이지씩 읽어 메모리에 모두 모으지 않는다.
 */
public class EventExporter @JvmOverloads constructor(policy: RedactionPolicy, private val maxEvents: Int = DEFAULT_MAX_EVENTS) {
    private val redactor = ExportSanitizer(policy)

    /** 쓴 이벤트 수를 돌려준다. 생성자의 maxEvents를 넘으면 가장 최근 것만 쓴다. */
    public suspend fun export(
        inspector: PulseInspector,
        query: EventQuery,
        format: EventExportFormat,
        out: OutputStream,
        includeDetails: Boolean = true,
    ): Int {
        val newestFirst = ArrayList<AppEvent>()
        var cursor: PageCursor? = null
        do {
            currentCoroutineContext().ensureActive()
            val page = inspector.loadEvents(query, cursor, PAGE_SIZE)
            newestFirst += page.items.take(maxEvents - newestFirst.size)
            cursor = page.next
        } while (cursor != null && newestFirst.size < maxEvents)
        val writer = OutputStreamWriter(out, Charsets.UTF_8).buffered()
        val events = newestFirst.asReversed()
        when (format) {
            EventExportFormat.TEXT -> events.forEach { writeText(writer, it, includeDetails) }
            EventExportFormat.JSON -> writeJson(writer, events, includeDetails)
            EventExportFormat.CSV -> writeCsv(writer, events)
        }
        writer.flush()
        return events.size
    }

    private fun writeText(out: Writer, event: AppEvent, includeDetails: Boolean) {
        val level = event.level?.name?.first() ?: '-'
        out.write("${isoTime(event.timestampEpochMillis)} $level/${event.kind.name}")
        event.tag?.let { out.write(" $it") }
        event.durationMillis?.let { out.write(" (${it}ms)") }
        out.write(": ${redactor.text(event.message)}\n")
        if (includeDetails) event.detail?.let { detail -> redactor.text(detail).lineSequence().forEach { out.write("    $it\n") } }
    }

    private fun writeJson(out: Writer, events: List<AppEvent>, includeDetails: Boolean) {
        val json = JsonStreamWriter(out)
        json.beginObject().name("schema").value(SCHEMA).name("events").beginArray()
        events.forEach { event ->
            json.beginObject()
                .name("id").value(event.id)
                .name("sessionId").value(event.sessionId)
                .name("time").value(isoTime(event.timestampEpochMillis))
                .name("kind").value(event.kind.name)
                .name("level").value(event.level?.name)
                .name("tag").value(event.tag)
                .name("message").value(redactor.text(event.message))
                .name("detail").value(if (includeDetails) event.detail?.let(redactor::text) else null)
                .name("screen").value(event.screen)
                .name("spanId").value(event.spanId)
                .name("durationMillis").value(event.durationMillis)
                .name("value").value(event.value)
                .endObject()
        }
        json.endArray().endObject()
    }

    private fun writeCsv(out: Writer, events: List<AppEvent>) {
        out.write("time,kind,level,tag,message,screen,spanId,durationMillis,value,sessionId\r\n")
        events.forEach { event ->
            val values = listOf(
                isoTime(event.timestampEpochMillis), event.kind.name, event.level?.name, event.tag, redactor.text(event.message),
                event.screen, event.spanId, event.durationMillis?.toString(), event.value?.toString(), event.sessionId,
            )
            out.write(values.joinToString(",") { CsvEntryWriter.quote(it.orEmpty()) } + "\r\n")
        }
    }

    public companion object {
        public const val DEFAULT_MAX_EVENTS: Int = 100_000
        private const val PAGE_SIZE = 500
        private const val SCHEMA = "pulse.events.v1"
    }
}
