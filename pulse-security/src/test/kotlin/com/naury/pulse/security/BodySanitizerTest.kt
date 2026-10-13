package com.naury.pulse.security

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.BodyFormats
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.RedactionPolicy
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import org.junit.Test

class BodySanitizerTest {
    private val sanitizer = BodySanitizer(Redactor(RedactionPolicy.strict()))

    private fun sanitize(body: String, contentType: String? = "application/json") = sanitizer.sanitize(body.toByteArray(), contentType)

    private fun captured(body: String, contentType: String? = "application/json") =
        sanitize(body, contentType) as BodySanitizeResult.Captured

    private fun rejectedState(body: ByteArray, contentType: String?) =
        (sanitizer.sanitize(body, contentType) as BodySanitizeResult.Rejected).state

    @Test
    fun `중첩 객체와 배열의 민감한 키를 가린다`() {
        val result = captured(
            """{"user":{"name":"kim","password":"secret"},"items":[{"accessToken":"secret"},{"id":1}],"api_key":"secret"}""",
        )

        assertThat(result.text).isEqualTo(
            """{"user":{"name":"kim","password":"$REDACTED"},"items":[{"accessToken":"$REDACTED"},{"id":1}],"api_key":"$REDACTED"}""",
        )
        assertThat(result.redactedValueCount).isEqualTo(3)
    }

    @Test
    fun `민감한 키의 값이 객체여도 통째로 가린다`() {
        val result = captured("""{"credentials":{"user":"a","pass":"secret"}}""")

        assertThat(result.text).isEqualTo("""{"credentials":"$REDACTED"}""")
    }

    @Test
    fun `잘못된 JSON은 비밀이 들어 있어도 저장하지 않는다`() {
        val result = sanitize("""{"password":"secret",""")

        assertThat(result).isInstanceOf(BodySanitizeResult.Rejected::class.java)
        assertThat((result as BodySanitizeResult.Rejected).state).isEqualTo(BodyState.REDACTION_FAILED)
    }

    @Test
    fun `너무 깊게 중첩된 JSON은 해석하지 않는다`() {
        val deep = "[".repeat(BodySanitizer.MAX_JSON_DEPTH + 1) + "]".repeat(BodySanitizer.MAX_JSON_DEPTH + 1)

        assertThat(rejectedState(deep.toByteArray(), "application/json")).isEqualTo(BodyState.REDACTION_FAILED)
    }

    @Test
    fun `문자열 안의 괄호는 깊이로 세지 않는다`() {
        val text = """{"note":"${"[".repeat(BodySanitizer.MAX_JSON_DEPTH + 5)}"}"""

        assertThat(captured(text).text).isEqualTo(text)
    }

    @Test
    fun `JSON이 아닌 형식은 저장하지 않는다`() {
        assertThat(rejectedState("password=secret".toByteArray(), "application/x-www-form-urlencoded"))
            .isEqualTo(BodyState.UNSUPPORTED_CONTENT)
        assertThat(rejectedState("secret".toByteArray(), "text/plain")).isEqualTo(BodyState.UNSUPPORTED_CONTENT)
        assertThat(rejectedState(byteArrayOf(1, 2, 3), "application/octet-stream")).isEqualTo(BodyState.UNSUPPORTED_CONTENT)
        assertThat(rejectedState("{}".toByteArray(), null)).isEqualTo(BodyState.UNSUPPORTED_CONTENT)
    }

    @Test
    fun `json 계열 형식을 인식한다`() {
        assertThat(BodySanitizer.isJson("application/json; charset=utf-8")).isTrue()
        assertThat(BodySanitizer.isJson("application/problem+json")).isTrue()
        assertThat(BodySanitizer.isJson("TEXT/JSON")).isTrue()
        assertThat(BodySanitizer.isJson("application/jsonp")).isFalse()
        assertThat(BodySanitizer.isJson("json")).isFalse()
    }

    @Test
    fun `잘못된 UTF-8은 해석 실패로 둔다`() {
        val malformed = byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), 0x28, '"'.code.toByte(), '}'.code.toByte())

        assertThat(rejectedState(malformed, "application/json")).isEqualTo(BodyState.DECODE_FAILED)
    }

    @Test
    fun `선언한 charset으로 해석하고 BOM을 지운다`() {
        val latin = """{"name":"café"}""".toByteArray(Charsets.ISO_8859_1)

        assertThat((sanitizer.sanitize(latin, "application/json; charset=ISO-8859-1") as BodySanitizeResult.Captured).text)
            .isEqualTo("""{"name":"café"}""")
        assertThat(captured(Char(0xFEFF) + """{"a":1}""").text).isEqualTo("""{"a":1}""")
    }

    @Test
    fun `빈 본문은 EMPTY다`() {
        assertThat(rejectedState(ByteArray(0), "application/json")).isEqualTo(BodyState.EMPTY)
    }

    @Test
    fun `비밀 없는 JSON은 그대로 저장한다`() {
        val result = captured("""{"id":7,"tags":["a","b"],"ok":true,"none":null}""")

        assertThat(result.text).isEqualTo("""{"id":7,"tags":["a","b"],"ok":true,"none":null}""")
        assertThat(result.redactedValueCount).isEqualTo(0)
    }

    @Test
    fun `문자열 값 안에 든 JSON의 민감한 키도 가린다`() {
        // httpbin처럼 받은 요청 본문을 문자열로 되돌려 주는 응답
        val result = captured("""{"data":"{\"email\":\"a@b.c\",\"password\":\"secret\"}","json":{"password":"secret"}}""")

        assertThat(result.text).doesNotContain("secret")
        assertThat(result.text).isEqualTo(
            """{"data":"{\"email\":\"a@b.c\",\"password\":\"$REDACTED\"}","json":{"password":"$REDACTED"}}""",
        )
        assertThat(result.redactedValueCount).isEqualTo(2)
    }

    @Test
    fun `문자열 값 안의 URL 쿼리와 name=value 비밀을 가린다`() {
        val result = captured("""{"url":"https://api.test/get?token=secret&page=1","log":"retry password=secret done"}""")

        assertThat(result.text).doesNotContain("secret")
        assertThat(result.text).contains("page=1")
        assertThat(result.redactedValueCount).isEqualTo(2)
    }

    @Test
    fun `비밀 없는 문자열 속 JSON은 원래 모양 그대로 둔다`() {
        val body = """{"data":"{ \"id\": 1 }","note":"a=b"}"""

        assertThat(captured(body).text).isEqualTo(body)
    }

    @Test
    fun `form과 텍스트는 켰을 때만 저장하고 비밀을 가린다`() {
        val all = BodySanitizer(Redactor(RedactionPolicy.strict()), BodyFormats(forms = true, text = true))
        val form = "application/x-www-form-urlencoded"

        assertThat(sanitize("a=1", form)).isInstanceOf(BodySanitizeResult.Rejected::class.java)
        val captured = all.sanitize("user=kim&password=pw&next=https%3A%2F%2Fa.test%2F%3Ftoken%3Dt".toByteArray(), form)
            as BodySanitizeResult.Captured
        assertThat(captured.text).startsWith("user=kim&password=$REDACTED&next=")
        assertThat(captured.text).doesNotContain("token%3Dt")
        assertThat(captured.redactedValueCount).isEqualTo(2)
        val text = all.sanitize("retry with password=abc".toByteArray(), "text/plain; charset=utf-8") as BodySanitizeResult.Captured
        assertThat(text.text).isEqualTo("retry with password=$REDACTED")
        assertThat(all.supports("application/soap+xml")).isTrue()
        assertThat(all.supports("image/png")).isFalse()
    }
}
