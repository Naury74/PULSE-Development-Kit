package com.naury.pulse.export

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.RedactionPolicy
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import org.junit.Test

class CurlFormatterTest {
    private val formatter = CurlFormatter(RedactionPolicy.strict())

    @Test
    fun `GET은 URL과 헤더만 쓰고 가려진 헤더는 넣지 않는다`() {
        val curl = formatter.format(
            detail("a", requestHeaders = listOf(HeaderField("Accept", "application/json"), HeaderField("Authorization", REDACTED))),
            null,
        )

        assertThat(curl).isEqualTo(
            "# PULSE: 가려진 헤더는 넣지 않았습니다: Authorization\n" +
                "curl 'https://api.test/items?page=1' \\\n  -H 'Accept: application/json'",
        )
        assertThat(curl).doesNotContain(REDACTED)
    }

    @Test
    fun `POST는 경고를 붙이고 본문의 작은따옴표를 셸에 안전하게 인용한다`() {
        val curl = formatter.format(detail("a", method = "POST", requestBodyState = BodyState.CAPTURED), """{"name":"O'Brien"}""")

        assertThat(curl).contains("# PULSE: 이 요청은 서버 상태를 바꿀 수 있습니다.")
        assertThat(curl).contains("-X 'POST'")
        assertThat(curl).contains("""--data-raw '{"name":"O'\''Brien"}'""")
    }

    @Test
    fun `저장하지 않은 본문은 넣지 않고 이유를 알린다`() {
        val curl = formatter.format(detail("a", method = "PUT", requestBodyState = BodyState.TOO_LARGE), null)

        assertThat(curl).contains("요청 본문은 저장되지 않았습니다(TOO_LARGE)")
        assertThat(curl).doesNotContain("--data-raw")
    }

    @Test
    fun `인용은 작은따옴표를 닫고 이스케이프한 뒤 다시 연다`() {
        assertThat(CurlFormatter.quote("a'b")).isEqualTo("""'a'\''b'""")
        assertThat(CurlFormatter.quote("\$HOME `x`")).isEqualTo("'\$HOME `x`'")
    }
}
