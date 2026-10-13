package com.naury.pulse.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JsonPrettyPrinterTest {
    @Test
    fun `객체와 배열을 들여쓴다`() {
        assertThat(JsonPrettyPrinter.format("""{"a":1,"b":[true,null],"c":{}}""")).isEqualTo(
            """
            {
              "a": 1,
              "b": [
                true,
                null
              ],
              "c": {}
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `문자열 안의 구분자와 이스케이프는 그대로 둔다`() {
        assertThat(JsonPrettyPrinter.format("""{"q":"a,b:{c}[d] \"e\" \\"}""")).isEqualTo(
            """
            {
              "q": "a,b:{c}[d] \"e\" \\"
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `잘린 입력도 예외 없이 처리한다`() {
        assertThat(JsonPrettyPrinter.format("""{"a":[1,2""")).isEqualTo("{\n  \"a\": [\n    1,\n    2")
        assertThat(JsonPrettyPrinter.format("]]}")).isEqualTo("\n]\n]\n}")
    }
}
