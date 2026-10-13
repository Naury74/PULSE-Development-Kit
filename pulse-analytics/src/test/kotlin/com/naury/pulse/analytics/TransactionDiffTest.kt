package com.naury.pulse.analytics

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.HttpMessage
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import com.naury.pulse.api.TransactionDetail
import com.naury.pulse.api.TransactionState
import com.naury.pulse.api.TransactionSummary
import org.junit.Test

class TransactionDiffTest {
    private fun side(
        status: Int = 200,
        url: String = "https://api.test/items?page=1",
        requestHeaders: List<HeaderField> = emptyList(),
        responseHeaders: List<HeaderField> = emptyList(),
        response: String? = null,
    ) = DiffSide(
        TransactionDetail(
            TransactionSummary(
                "id", "s", "GET", url, "api.test", "/items", 0, 1, status, TransactionState.COMPLETED, null, 0, 0, CaptureSource.ORIGINAL,
            ),
            "h2",
            null,
            null,
            HttpMessage(requestHeaders, null, null, BodyState.EMPTY, null, 0),
            HttpMessage(responseHeaders, "application/json", null, BodyState.CAPTURED, null, 0),
            emptyList(),
        ),
        null,
        response,
    )

    private fun diff(left: DiffSide, right: DiffSide) = TransactionDiff.compare(left, right).map { "${it.area}:${it.path}:${it.kind}" }

    @Test
    fun `같으면 차이가 없다`() {
        assertThat(TransactionDiff.compare(side(response = """{"a":1}"""), side(response = """{"a":1}"""))).isEmpty()
    }

    @Test
    fun `상태·쿼리·헤더는 이름을 대소문자 구분 없이 비교하고 매번 바뀌는 헤더는 뺀다`() {
        val result = diff(
            side(
                url = "https://api.test/items?page=1&q=a",
                requestHeaders = listOf(HeaderField("Accept", "a")),
                responseHeaders = listOf(HeaderField("Date", "1")),
            ),
            side(
                status = 500,
                url = "https://api.test/items?page=2",
                requestHeaders = listOf(HeaderField("accept", "a")),
                responseHeaders = listOf(HeaderField("Date", "2")),
            ),
        )

        assertThat(result).containsExactly("STATUS:status:CHANGED", "QUERY:page:CHANGED", "QUERY:q:REMOVED")
    }

    @Test
    fun `JSON은 경로별로 없음·종류 바뀜·값 바뀜·순서 바뀜을 구분한다`() {
        val result = diff(
            side(response = """{"name":"kim","age":7,"tags":["a","b"],"items":[1,2],"gone":true}"""),
            side(response = """{"name":"lee","age":"7","tags":["b","a"],"items":[1,2,3],"new":null}"""),
        )

        assertThat(result).containsExactly(
            "RESPONSE_BODY:$.age:TYPE_CHANGED",
            "RESPONSE_BODY:$.gone:REMOVED",
            "RESPONSE_BODY:$.items[2]:ADDED",
            "RESPONSE_BODY:$.name:CHANGED",
            "RESPONSE_BODY:$.new:ADDED",
            "RESPONSE_BODY:$.tags:REORDERED",
        )
    }

    @Test
    fun `가려진 값은 비교하지 않는다`() {
        val result = diff(
            side(requestHeaders = listOf(HeaderField("Authorization", REDACTED)), response = """{"token":"$REDACTED"}"""),
            side(requestHeaders = listOf(HeaderField("Authorization", "Bearer x")), response = """{"token":"abc"}"""),
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `JSON이 아니면 본문 전체를 한 차이로 본다`() {
        assertThat(diff(side(response = "plain a"), side(response = "plain b"))).containsExactly("RESPONSE_BODY:$:CHANGED")
    }
}
