package com.naury.pulse.security

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.RedactionPolicy
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import org.junit.Test

class RedactorTest {
    private val redactor = Redactor(RedactionPolicy.strict())

    @Test
    fun `인증·쿠키 헤더는 대소문자와 상관없이 가린다`() {
        val headers = redactor.redactHeaders(
            listOf(
                "AUTHORIZATION" to "Bearer secret-1",
                "cookie" to "sid=secret-2",
                "Set-Cookie" to "sid=secret-3; HttpOnly",
                "Proxy-Authorization" to "Basic secret-4",
                "X-Api-Key" to "secret-5",
                "Accept" to "application/json",
            ),
        )

        assertThat(headers).containsExactly(
            HeaderField("AUTHORIZATION", REDACTED),
            HeaderField("cookie", REDACTED),
            HeaderField("Set-Cookie", REDACTED),
            HeaderField("Proxy-Authorization", REDACTED),
            HeaderField("X-Api-Key", REDACTED),
            HeaderField("Accept", "application/json"),
        ).inOrder()
        assertThat(headers.joinToString()).doesNotContain("secret")
    }

    @Test
    fun `이름에 민감한 단어가 들어간 헤더도 가린다`() {
        val headers = redactor.redactHeaders(listOf("X-Auth-Token" to "secret", "X-Refresh-Token" to "secret", "X-Request-Id" to "abc"))

        assertThat(headers.map { it.value }).containsExactly(REDACTED, REDACTED, "abc").inOrder()
    }

    @Test
    fun `정책에 더한 헤더도 가린다`() {
        val custom = Redactor(RedactionPolicy.strict().withHeaders("X-Tenant"))

        assertThat(custom.redactHeaders(listOf("x-tenant" to "acme")).single().value).isEqualTo(REDACTED)
    }

    @Test
    fun `URL을 값으로 갖는 헤더는 URL 규칙으로 마스킹한다`() {
        val location = redactor.redactHeaders(listOf("Location" to "https://a.test/cb?access_token=secret&page=2")).single()

        assertThat(location.value).isEqualTo("https://a.test/cb?access_token=$REDACTED&page=2")
    }

    @Test
    fun `민감한 쿼리 값은 가리고 나머지는 그대로 둔다`() {
        val url = "https://api.test/v1/items?page=2&token=secret&refresh_token=secret&api_key=secret&code=secret&q=pulse"

        assertThat(redactor.sanitizeUrl(url)).isEqualTo(
            "https://api.test/v1/items?page=2&token=$REDACTED&refresh_token=$REDACTED&api_key=$REDACTED&code=$REDACTED&q=pulse",
        )
    }

    @Test
    fun `쿼리 이름은 URL 디코딩한 뒤 비교한다`() {
        assertThat(redactor.sanitizeUrl("https://a.test/?pass%77ord=secret&acc%65ssToken=secret"))
            .isEqualTo("https://a.test/?pass%77ord=$REDACTED&acc%65ssToken=$REDACTED")
    }

    @Test
    fun `디코딩할 수 없는 쿼리 이름은 값을 가린다`() {
        assertThat(redactor.sanitizeUrl("https://a.test/?%zz=secret")).isEqualTo("https://a.test/?%zz=$REDACTED")
    }

    @Test
    fun `사용자 정보와 fragment를 지운다`() {
        assertThat(redactor.sanitizeUrl("https://user:secret@a.test:8443/path?x=1#token=secret"))
            .isEqualTo("https://a.test:8443/path?x=1")
    }

    @Test
    fun `값이 없는 파라미터와 빈 쿼리를 보존한다`() {
        assertThat(redactor.sanitizeUrl("https://a.test/p?flag&token")).isEqualTo("https://a.test/p?flag&token")
        assertThat(redactor.sanitizeUrl("https://a.test/p?")).isEqualTo("https://a.test/p?")
    }

    @Test
    fun `실패 메시지의 URL과 민감 값을 가리고 길이를 제한한다`() {
        val message = "unexpected end of stream on https://a.test/x?token=secret (password=secret)"

        val safe = redactor.sanitizeMessage(message)

        assertThat(safe).doesNotContain("secret")
        assertThat(redactor.sanitizeMessage("x".repeat(1_000))).hasLength(Redactor.MAX_MESSAGE_LENGTH + 1)
        assertThat(redactor.sanitizeMessage(null)).isNull()
    }
}
