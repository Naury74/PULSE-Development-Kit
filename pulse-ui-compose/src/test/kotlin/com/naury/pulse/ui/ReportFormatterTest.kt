package com.naury.pulse.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReportFormatterTest {
    @Test
    fun `공유 글에 결과·URL·헤더·본문·cURL이 들어가고 큰 본문은 자른다`() {
        val detail = FakeInspector.detail("a", "/users", status = 503)
        val big = "{\"items\":\"" + "x".repeat(ReportFormatter.MAX_BODY_CHARS) + "\"}"

        val report = ReportFormatter.format(detail, null, big, "curl 'https://api.test/users'")

        assertThat(report).startsWith("### GET /users → HTTP 503")
        assertThat(report).contains("- URL: `https://api.test/users`")
        assertThat(report).contains("Authorization: [REDACTED]")
        assertThat(report).contains("… (truncated)")
        assertThat(report).contains("curl 'https://api.test/users'")
        assertThat(report.length).isLessThan(big.length + 2_000)
    }

    @Test
    fun `응답 없이 실패한 요청은 실패 종류를 결과로 쓴다`() {
        val detail = FakeInspector.detail("a", "/slow", status = null, state = com.naury.pulse.api.TransactionState.FAILED)

        val report = ReportFormatter.format(detail, null, null, null)

        assertThat(report).startsWith("### GET /slow → java.net.SocketTimeoutException")
        assertThat(report).contains("- Failure: java.net.SocketTimeoutException")
    }
}
