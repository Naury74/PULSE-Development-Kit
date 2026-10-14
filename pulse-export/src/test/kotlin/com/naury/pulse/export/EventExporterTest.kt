package com.naury.pulse.export

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.EventPage
import com.naury.pulse.api.EventQuery
import com.naury.pulse.api.LogLevel
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class EventExporterTest {
    /** 최신순으로 페이지를 넘겨주는 이벤트 저장소. */
    private class EventsInspector(private val newestFirst: List<AppEvent>) : PulseInspector by PagedInspector(emptyList(), emptyMap()) {
        override suspend fun loadEvents(query: EventQuery, after: PageCursor?, limit: Int): EventPage {
            val start = after?.let { cursor -> newestFirst.indexOfFirst { it.id == cursor.id } + 1 } ?: 0
            val items = newestFirst.drop(start).take(limit)
            val next = if (start + limit < newestFirst.size) items.last().let { PageCursor(it.timestampEpochMillis, it.id) } else null
            return EventPage(items, next)
        }
    }

    private fun event(index: Int, message: String = "event $index", detail: String? = null) = AppEvent(
        id = "e$index",
        sessionId = "s1",
        timestampEpochMillis = 1_700_000_000_000L + index * 1_000L,
        kind = if (index % 2 == 0) EventKind.LOG else EventKind.ERROR,
        level = if (index % 2 == 0) LogLevel.INFO else LogLevel.ERROR,
        tag = "Tag",
        message = message,
        detail = detail,
    )

    private fun export(
        events: List<AppEvent>,
        format: EventExportFormat,
        details: Boolean = true,
        maxEvents: Int = 1_000,
    ): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val count = runBlocking {
            EventExporter(
                PulseConfig.debugDefaults().redaction,
                maxEvents,
            ).export(EventsInspector(events.reversed()), EventQuery(), format, out, details)
        }
        return count to out.toString(Charsets.UTF_8.name())
    }

    @Test
    fun `텍스트는 오래된 것부터 쓰고 상세와 비밀을 다시 가린다`() {
        val (count, text) = export(
            listOf(event(1, "login token=abc123"), event(2, detail = "at Foo.bar(Foo.kt:1)")),
            EventExportFormat.TEXT,
        )

        assertThat(count).isEqualTo(2)
        val lines = text.lines()
        assertThat(lines[0]).contains("E/ERROR Tag: login token=$REDACTED")
        assertThat(lines[1]).contains("I/LOG Tag: event 2")
        assertThat(lines[2]).isEqualTo("    at Foo.bar(Foo.kt:1)")
        assertThat(text).doesNotContain("abc123")
    }

    @Test
    fun `상세를 빼면 JSON에 detail이 비어 있다`() {
        val (_, json) = export(listOf(event(2, detail = "stack")), EventExportFormat.JSON, details = false)

        val root = Json.parseToJsonElement(json).jsonObject
        assertThat(root.getValue("schema").jsonPrimitive.content).isEqualTo("pulse.events.v1")
        val first = root.getValue("events").jsonArray[0].jsonObject
        assertThat(first.getValue("kind").jsonPrimitive.content).isEqualTo("LOG")
        assertThat(first["detail"]).isEqualTo(JsonNull)
    }

    @Test
    fun `CSV는 쉼표와 따옴표를 감싸고 한 줄에 하나씩 쓴다`() {
        val (_, csv) = export(listOf(event(2, "a, \"b\"")), EventExportFormat.CSV)

        val rows = csv.trimEnd().split("\r\n")
        assertThat(rows).hasSize(2)
        assertThat(rows[0]).startsWith("time,kind,level,tag,message")
        assertThat(rows[1]).contains("\"a, \"\"b\"\"\"")
    }

    @Test
    fun `최대 개수를 넘으면 가장 최근 것만 쓴다`() {
        val events = (1..1_200).map { event(it) }
        val (count, text) = export(events, EventExportFormat.TEXT, maxEvents = 1_000)

        assertThat(count).isEqualTo(1_000)
        assertThat(text).doesNotContain("event 200\n")
        assertThat(text.lines().first()).contains("event 201")
        assertThat(text.trimEnd().lines().last()).contains("event 1200")
    }
}
