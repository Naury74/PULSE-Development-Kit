package com.naury.pulse.analytics

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.StatsWindow
import com.naury.pulse.api.TransactionState
import org.junit.Test

class StatisticsCalculatorTest {
    @Test
    fun `nearest-rank 백분위수`() {
        val sorted = (1L..100L).toList()

        assertThat(percentile(sorted, 50)).isEqualTo(50)
        assertThat(percentile(sorted, 95)).isEqualTo(95)
        assertThat(percentile(sorted, 99)).isEqualTo(99)
        assertThat(percentile(listOf(7L), 99)).isEqualTo(7)
        assertThat(percentile(emptyList(), 50)).isNull()
    }

    @Test
    fun `상태별 개수와 끝난 요청만으로 백분위수를 낸다`() {
        val samples = listOf(
            sample("ok", at = 1_000, durationMillis = 100),
            sample("redirect", at = 2_000, durationMillis = 300, status = 304),
            sample("client", at = 3_000, durationMillis = 200, status = 404),
            sample("server", at = 4_000, durationMillis = 400, status = 503),
            sample("failed", at = 5_000, durationMillis = 900, status = null),
            sample("pending", at = 6_000, durationMillis = null, status = null, state = TransactionState.IN_FLIGHT, responseBytes = null),
        )

        val stats = StatisticsCalculator.compute(StatsWindow.LAST_5_MINUTES, samples, nowEpochMillis = 10_000, sampleLimitReached = false)

        assertThat(stats.counts.total).isEqualTo(6)
        assertThat(stats.counts.success).isEqualTo(2)
        assertThat(stats.counts.clientError).isEqualTo(1)
        assertThat(stats.counts.serverError).isEqualTo(1)
        assertThat(stats.counts.failed).isEqualTo(1)
        assertThat(stats.counts.inFlight).isEqualTo(1)
        assertThat(stats.durations.sampleCount).isEqualTo(4)
        assertThat(stats.durations.p50Nanos).isEqualTo(200_000_000)
        assertThat(stats.durations.p95Nanos).isEqualTo(400_000_000)
        assertThat(stats.bytes.responseBytes).isEqualTo(50)
        assertThat(stats.bytes.unknownResponseSizeCount).isEqualTo(1)
        assertThat(stats.buckets).hasSize(12)
        assertThat(stats.buckets.sumOf { it.count }).isEqualTo(6)
        assertThat(stats.buckets.sumOf { it.errorCount }).isEqualTo(3)
        assertThat(stats.topHosts.single().count).isEqualTo(6)
    }

    @Test
    fun `끝난 요청이 없으면 백분위수는 비어 있다`() {
        val stats = StatisticsCalculator.compute(StatsWindow.ALL, emptyList(), nowEpochMillis = 10, sampleLimitReached = false)

        assertThat(stats.durations.p50Nanos).isNull()
        assertThat(stats.fromEpochMillis).isNull()
        assertThat(stats.buckets).isEmpty()
    }

    @Test
    fun `가장 느린 엔드포인트는 경로를 합치지 않고 p95 순으로 고른다`() {
        val samples = (1..10).map { sample("a$it", at = it.toLong(), path = "/a", durationMillis = it * 10L) } +
            listOf(sample("b1", at = 20, path = "/b/1", durationMillis = 900), sample("b2", at = 21, path = "/b/2", durationMillis = 50))

        val slowest = StatisticsCalculator.compute(StatsWindow.ALL, samples, 100, false).slowestEndpoints

        assertThat(slowest.map { it.path }).containsExactly("/b/1", "/a", "/b/2").inOrder()
        assertThat(slowest[1].p95Nanos).isEqualTo(100_000_000)
        assertThat(slowest[1].count).isEqualTo(10)
    }
}
