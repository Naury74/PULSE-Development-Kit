package com.naury.pulse.analytics

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.InsightCertainty
import com.naury.pulse.api.InsightRule
import com.naury.pulse.api.InsightThresholds
import com.naury.pulse.api.ResponseSource
import org.junit.Test

class InsightEngineTest {
    private val engine = InsightEngine(InsightThresholds())

    private fun rules(samples: List<TrafficSample>) = engine.evaluate(samples).map { it.rule }

    @Test
    fun `임계값을 넘은 요청만 느린 요청으로 보고 기준값과 관찰값을 담는다`() {
        val insight = engine.evaluate(
            listOf(sample("fast", 0, durationMillis = 1_500), sample("slow", 10_000, durationMillis = 2_000)),
        ).single()

        assertThat(insight.rule).isEqualTo(InsightRule.SLOW_REQUEST)
        assertThat(insight.certainty).isEqualTo(InsightCertainty.OBSERVED)
        assertThat(insight.threshold).isEqualTo(1_500_000_000)
        assertThat(insight.observed).isEqualTo(2_000_000_000)
        assertThat(insight.transactionIds).containsExactly("slow")
        assertThat(insight.endpoint).isEqualTo("GET /items")
    }

    @Test
    fun `한 호스트에서 시간 창 안에 오류가 기준 이상이면 오류 몰림`() {
        val burst = (0 until 5).map { sample("e$it", at = it * 10_000L, status = 500) }
        val spread = (0 until 5).map { sample("s$it", at = it * 61_000L, status = 500, host = "other.test") }

        val insights = engine.evaluate(burst + spread)

        assertThat(insights.map { it.rule }).containsExactly(InsightRule.ERROR_BURST)
        assertThat(insights.single().host).isEqualTo("api.test")
        assertThat(insights.single().observed).isEqualTo(5)
    }

    @Test
    fun `같은 GET이 겹쳐 진행되면 중복으로 보고 POST는 보지 않는다`() {
        val samples = listOf(
            sample("a", at = 0, durationMillis = 500),
            sample("b", at = 100, durationMillis = 500),
            sample("c", at = 2_000, durationMillis = 100),
            sample("p1", at = 0, method = "POST", durationMillis = 500),
            sample("p2", at = 100, method = "POST", durationMillis = 500),
        )

        val duplicate = engine.evaluate(samples).single { it.rule == InsightRule.DUPLICATE_IN_FLIGHT }

        assertThat(duplicate.certainty).isEqualTo(InsightCertainty.HEURISTIC)
        assertThat(duplicate.transactionIds).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `큰 응답`() {
        assertThat(rules(listOf(sample("big", 0, responseBytes = 2L * 1024 * 1024)))).containsExactly(InsightRule.OVERSIZED_RESPONSE)
        assertThat(rules(listOf(sample("unknown", 0, responseBytes = null)))).isEmpty()
    }

    @Test
    fun `ID만 다른 경로가 짧은 시간에 몰리면 N+1을 추정한다`() {
        val samples = (1..8).map { sample("u$it", at = it * 100L, path = "/users/$it/profile") }
        val sameUrl = (1..8).map { sample("same$it", at = it * 100L, path = "/config", host = "b.test") }

        val insight = engine.evaluate(samples + sameUrl).single { it.rule == InsightRule.N_PLUS_ONE_SUSPECTED }

        assertThat(insight.endpoint).isEqualTo("GET /users/{id}/profile")
        assertThat(insight.certainty).isEqualTo(InsightCertainty.HEURISTIC)
        assertThat(insight.matchCount).isEqualTo(8)
    }

    @Test
    fun `인증 없는 같은 GET 반복은 캐시 후보이고 자격 증명이 있거나 캐시 응답이면 아니다`() {
        val repeated = (1..5).map { sample("c$it", at = it * 1_000L, path = "/config") }
        val withCredentials = (1..5).map { sample("a$it", at = it * 1_000L, path = "/me", credentials = true) }
        val cached = (1..5).map { sample("h$it", at = it * 1_000L, path = "/cached", source = ResponseSource.CACHE) }

        val insights = engine.evaluate(repeated + withCredentials + cached).filter { it.rule == InsightRule.CACHE_OPPORTUNITY }

        assertThat(insights.map { it.endpoint }).containsExactly("GET /config")
    }

    @Test
    fun `심각도가 높은 순으로 정렬한다`() {
        val samples =
            (0 until 5).map { sample("e$it", at = it.toLong(), path = "/e/$it", status = 500) } + sample("slow", 10, durationMillis = 5_000)

        assertThat(rules(samples)).containsExactly(InsightRule.ERROR_BURST, InsightRule.SLOW_REQUEST).inOrder()
    }
}
