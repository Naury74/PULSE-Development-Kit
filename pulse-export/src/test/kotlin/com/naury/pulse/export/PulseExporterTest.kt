package com.naury.pulse.export

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.RedactionPolicy
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import com.naury.pulse.api.TimingPhase
import com.naury.pulse.api.TimingPhaseType
import com.naury.pulse.api.TransactionQuery
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

class PulseExporterTest {
    private val exporter = PulseExporter(RedactionPolicy.strict())

    private fun export(format: ExportFormat, vararg entries: ExportEntry): JsonObject {
        val out = ByteArrayOutputStream()
        exporter.write(entries.asSequence(), format, out)
        return Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
    }

    private fun JsonObject.obj(name: String) = getValue(name).jsonObject

    @Test
    fun `HAR는 관찰하지 못한 단계를 -1로 두고 관찰한 단계만 ms로 쓴다`() {
        val reused = listOf(
            TimingPhase(TimingPhaseType.REQUEST_HEADERS, 0, 1_000_000, 2_000_000),
            TimingPhase(TimingPhaseType.RESPONSE_HEADERS, 0, 3_000_000, 50_000_000),
            TimingPhase(TimingPhaseType.RESPONSE_BODY, 0, 60_000_000, null),
        )

        val har = export(ExportFormat.HAR, ExportEntry(detail("a", timings = reused), null, """{"a":1}"""))

        val log = har.obj("log")
        assertThat(log.getValue("version").jsonPrimitive.content).isEqualTo("1.2")
        val entry = log.getValue("entries").jsonArray.single().jsonObject
        val timings = entry.obj("timings")
        assertThat(timings.getValue("dns").jsonPrimitive.long).isEqualTo(-1)
        assertThat(timings.getValue("connect").jsonPrimitive.long).isEqualTo(-1)
        assertThat(timings.getValue("ssl").jsonPrimitive.long).isEqualTo(-1)
        assertThat(timings.getValue("send").jsonPrimitive.long).isEqualTo(2)
        assertThat(timings.getValue("wait").jsonPrimitive.long).isEqualTo(50)
        assertThat(timings.getValue("receive").jsonPrimitive.long).isEqualTo(-1)
        assertThat(entry.getValue("time").jsonPrimitive.long).isEqualTo(120)
        assertThat(entry.obj("request").getValue("httpVersion").jsonPrimitive.content).isEqualTo("HTTP/2")
        assertThat(entry.obj("request").getValue("queryString").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
            .isEqualTo("page")
        assertThat(entry.obj("response").obj("content").getValue("text").jsonPrimitive.content).isEqualTo("""{"a":1}""")
        assertThat(entry.obj("_pulse").getValue("transactionId").jsonPrimitive.content).isEqualTo("a")
    }

    @Test
    fun `내보낼 때 현재 정책으로 다시 마스킹한다`() {
        val stricter = PulseExporter(RedactionPolicy.strict().withHeaders("X-Tenant").withSensitiveNames("email"))
        val entry = ExportEntry(
            detail("a", url = "https://api.test/x?email=kim@test", requestHeaders = listOf(HeaderField("X-Tenant", "acme"))),
            null,
            """{"email":"kim@test","id":1}""",
        )
        val out = ByteArrayOutputStream()

        stricter.write(sequenceOf(entry), ExportFormat.PULSE_JSON, out)
        val text = out.toString(Charsets.UTF_8)

        assertThat(text).doesNotContain("kim@test")
        assertThat(text).doesNotContain("acme")
        val json = Json.parseToJsonElement(text).jsonObject
        assertThat(json.getValue("schema").jsonPrimitive.content).isEqualTo("pulse.capture.v1")
        val transaction = json.getValue("transactions").jsonArray.single().jsonObject
        assertThat(transaction.getValue("url").jsonPrimitive.content).isEqualTo("https://api.test/x?email=$REDACTED")
    }

    @Test
    fun `다시 마스킹할 수 없는 본문은 빼고 센다`() {
        val out = ByteArrayOutputStream()

        val result = exporter.write(sequenceOf(ExportEntry(detail("a"), null, "{broken")), ExportFormat.PULSE_JSON, out)

        assertThat(result.droppedBodyCount).isEqualTo(1)
        assertThat(out.toString(Charsets.UTF_8)).doesNotContain("broken")
    }

    @Test
    fun `인스펙터에서 페이지 단위로 읽어 상한까지 쓴다`() = runTest {
        val details = (1..250).map { detail("t$it", startedAt = 1_000L - it) }
        val inspector = PagedInspector(details, mapOf("t1/RESPONSE" to """{"ok":true}"""))
        val out = ByteArrayOutputStream()

        val all = exporter.export(inspector, TransactionQuery(), ExportFormat.HAR, out)
        val limited = exporter.export(inspector, TransactionQuery(), ExportFormat.HAR, ByteArrayOutputStream(), maxTransactions = 120)

        assertThat(all.transactionCount).isEqualTo(250)
        assertThat(all.truncated).isFalse()
        assertThat(limited.transactionCount).isEqualTo(120)
        assertThat(limited.truncated).isTrue()
        val entries = Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject.obj("log").getValue("entries").jsonArray
        assertThat(entries).hasSize(250)
    }

    @Test
    fun `문자열의 제어 문자와 따옴표를 이스케이프한다`() {
        val json = export(ExportFormat.PULSE_JSON, ExportEntry(detail("a\"b\n\u0001"), null, null))

        assertThat(
            json.getValue("transactions").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content,
        ).isEqualTo("a\"b\n\u0001")
    }

    @Test
    fun `저장하지 않은 본문 상태를 그대로 쓴다`() {
        val json = export(ExportFormat.PULSE_JSON, ExportEntry(detail("a", requestBodyState = BodyState.ONE_SHOT_UNSUPPORTED), null, null))

        val request = json.getValue("transactions").jsonArray.single().jsonObject.obj("request")
        assertThat(request.getValue("bodyState").jsonPrimitive.content).isEqualTo("ONE_SHOT_UNSUPPORTED")
    }
}
