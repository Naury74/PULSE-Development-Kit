package com.naury.pulse.export

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.RedactionPolicy
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import com.naury.pulse.api.TransactionDetail
import com.naury.pulse.api.TransactionQuery
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExportOptionsTest {
    private val exporter = PulseExporter(RedactionPolicy.strict())

    private fun withNetwork(id: String, startedAt: Long = 0): TransactionDetail {
        val base = detail(id, startedAt = startedAt, requestBodyState = BodyState.CAPTURED)
        return TransactionDetail(
            base.summary, base.protocol, base.responseHeadersNanos, base.failureMessage, base.request, base.response, base.timings,
            networkRequestHeaders = listOf(HeaderField("Host", "api.test"), HeaderField("Cookie", "sid=secret")),
            networkResponseHeaders = listOf(HeaderField("Content-Encoding", "gzip")),
        )
    }

    private suspend fun run(format: ExportFormat, options: ExportOptions, vararg details: TransactionDetail): String {
        val inspector = PagedInspector(details.toList(), details.associate { "${it.summary.id}/REQUEST" to """{"q":1}""" })
        val out = ByteArrayOutputStream()
        exporter.export(inspector, TransactionQuery(), format, out, options)
        return out.toString(Charsets.UTF_8)
    }

    @Test
    fun `CSV는 요청마다 한 줄이고 수식으로 읽히는 값을 막는다`() = runTest {
        val csv = run(ExportFormat.CSV, ExportOptions(), withNetwork("a"), withNetwork("b"))

        val lines = csv.trimEnd().split("\r\n")
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).startsWith("startedAt,method,host,path,url,statusCode")
        assertThat(lines[1]).contains(",GET,api.test,/items,")
        assertThat(lines[1]).contains(",120.00,80.00,")
        assertThat(CsvEntryWriter.quote("=HYPERLINK(1)")).isEqualTo("'=HYPERLINK(1)")
        assertThat(CsvEntryWriter.quote("-12.5")).isEqualTo("-12.5")
        assertThat(CsvEntryWriter.quote("a,\"b\"")).isEqualTo("\"a,\"\"b\"\"\"")
    }

    @Test
    fun `Markdown은 실제 전송 헤더를 쓰고 다시 마스킹한다`() = runTest {
        val md = run(ExportFormat.MARKDOWN, ExportOptions(), withNetwork("a"))

        assertThat(md).startsWith("# PULSE network report")
        assertThat(md).contains("### GET /items → HTTP 200")
        assertThat(md).contains("Host: api.test")
        assertThat(md).contains("Cookie: $REDACTED")
        assertThat(md).doesNotContain("sid=secret")
    }

    @Test
    fun `헤더·본문을 빼고 기간과 ID로 고를 수 있다`() = runTest {
        val noHeaders = run(ExportFormat.MARKDOWN, ExportOptions(includeHeaders = false, includeRequestBodies = false), withNetwork("a"))
        assertThat(noHeaders).doesNotContain("Host:")
        assertThat(noHeaders).doesNotContain("\"q\"")

        val recent =
            run(
                ExportFormat.CSV,
                ExportOptions(sinceEpochMillis = 500),
                withNetwork("new", startedAt = 1_000),
                withNetwork("old", startedAt = 0),
            )
        assertThat(recent.trimEnd().split("\r\n")).hasSize(2)

        val picked = run(ExportFormat.CSV, ExportOptions(transactionIds = listOf("b")), withNetwork("a"), withNetwork("b"))
        assertThat(picked).contains(",b\r\n")
        assertThat(picked).doesNotContain(",a\r\n")
    }

    @Test
    fun `HAR은 실제 전송 헤더를 고르면 그쪽을 쓰고 PULSE JSON은 둘 다 쓴다`() = runTest {
        val har = run(ExportFormat.HAR, ExportOptions(), withNetwork("a"))
        val appOnly = run(ExportFormat.HAR, ExportOptions(preferNetworkHeaders = false), withNetwork("a"))
        val json = run(ExportFormat.PULSE_JSON, ExportOptions(), withNetwork("a"))

        assertThat(har).contains("\"Host\"")
        assertThat(appOnly).doesNotContain("\"Host\"")
        assertThat(json).contains("\"networkHeaders\"")
        assertThat(json).contains("\"Accept\"")
    }
}
