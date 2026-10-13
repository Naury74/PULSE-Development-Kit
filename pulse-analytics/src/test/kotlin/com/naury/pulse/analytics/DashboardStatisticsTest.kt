package com.naury.pulse.analytics

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.EndpointGrouping
import com.naury.pulse.api.GroupStatistics
import com.naury.pulse.api.NetworkType
import com.naury.pulse.api.RequestContext
import com.naury.pulse.api.ResponseSource
import com.naury.pulse.api.StatsDimension
import com.naury.pulse.api.StatsQuery
import com.naury.pulse.api.StatsWindow
import com.naury.pulse.api.TimingPhaseType
import com.naury.pulse.api.TransactionState
import org.junit.Test

class DashboardStatisticsTest {
    @Suppress("LongParameterList")
    private fun rich(
        id: String,
        path: String = "/users/1",
        host: String = "api.test",
        durationMillis: Long? = 100,
        status: Int? = 200,
        screen: String? = "HomeActivity",
        label: String? = null,
        foreground: Boolean? = true,
        network: NetworkType? = NetworkType.WIFI,
        source: CaptureSource = CaptureSource.ORIGINAL,
        failure: String? = null,
        reused: Boolean? = true,
        dnsMillis: Long? = null,
    ) = TrafficSample(
        id = id, method = "GET", url = "https://$host$path", host = host, path = path, startedAtEpochMillis = 60_000,
        durationNanos = durationMillis?.let { it * 1_000_000 }, statusCode = status,
        state = if (status == null) TransactionState.FAILED else TransactionState.COMPLETED,
        requestBodyBytes = 0, responseBodyBytes = 100, hasCredentials = false, responseSource = ResponseSource.NETWORK,
        source = source, protocol = "h2", responseHeadersNanos = durationMillis?.let { it * 500_000 }, failureType = failure,
        connectionReused = reused, context = RequestContext(screen, foreground, network, label),
        dnsNanos = dnsMillis?.let {
            it * 1_000_000
        },
    )

    private fun compute(samples: List<TrafficSample>, query: StatsQuery = StatsQuery(StatsWindow.LAST_5_MINUTES)) =
        StatisticsCalculator.compute(query, samples, nowEpochMillis = 120_000, sampleLimitReached = false, grouping = EndpointGrouping())

    @Test
    fun `엔드포인트를 템플릿으로 묶고 오류·크기·백분위수를 함께 낸다`() {
        val stats = compute(listOf(rich("a", "/users/1"), rich("b", "/users/2", status = 500), rich("c", "/orders")))

        val users = stats.endpoints.first()
        assertThat(users.path).isEqualTo("/users/{id}")
        assertThat(users.count).isEqualTo(2)
        assertThat(users.errorCount).isEqualTo(1)
        assertThat(users.responseBytes).isEqualTo(200)
        assertThat(users.p50Nanos).isEqualTo(100_000_000)
    }

    @Test
    fun `직접 정한 템플릿이 자동 규칙보다 먼저다`() {
        val templater = EndpointTemplater(EndpointGrouping(templates = listOf("/shops/{shop}/items")))

        assertThat(templater.template("/shops/main/items")).isEqualTo("/shops/{shop}/items")
        assertThat(templater.template("/shops/42")).isEqualTo("/shops/{id}")
        assertThat(EndpointTemplater(EndpointGrouping(autoTemplates = false)).template("/shops/42")).isEqualTo("/shops/42")
    }

    @Test
    fun `질의 조건으로 거르고 필터 선택지는 거르기 전 값으로 준다`() {
        val samples = listOf(
            rich("a", host = "api.test", screen = "Home"),
            rich("b", host = "cdn.test", screen = "Detail"),
            rich("c", host = "api.test", screen = "Detail", label = "checkout"),
            rich("r", host = "api.test", source = CaptureSource.REPLAY),
        )

        val stats = compute(samples, StatsQuery(StatsWindow.LAST_5_MINUTES, host = "api.test", screen = "Detail"))

        assertThat(stats.counts.total).isEqualTo(1)
        assertThat(stats.facets.hosts).containsExactly("api.test", "cdn.test").inOrder()
        assertThat(stats.facets.labels).containsExactly("checkout")
        assertThat(compute(samples).counts.total).isEqualTo(3)
        assertThat(compute(samples, StatsQuery(StatsWindow.LAST_5_MINUTES, includeSynthetic = true)).counts.total).isEqualTo(4)
    }

    @Test
    fun `분포·상태 코드·실패 종류·연결·단계를 집계한다`() {
        val samples = listOf(
            rich("fast", durationMillis = 50, dnsMillis = 4, reused = false),
            rich("mid", durationMillis = 500, status = 404),
            rich("slow", durationMillis = 5_000, status = 503),
            rich("timeout", durationMillis = 300, status = null, failure = "java.net.SocketTimeoutException"),
        )

        val stats = compute(samples)

        assertThat(stats.latencyHistogram.map { it.count }).containsExactly(1, 0, 1, 0, 1).inOrder()
        assertThat(stats.statusCodes.map { it.key }).containsExactly("200", "404", "503")
        assertThat(stats.failureTypes.single().key).isEqualTo("SocketTimeoutException")
        assertThat(stats.connections.reused).isEqualTo(3)
        assertThat(stats.connections.newConnections).isEqualTo(1)
        assertThat(stats.phases.first { it.type == TimingPhaseType.DNS }.p50Nanos).isEqualTo(4_000_000)
        assertThat(stats.timeToFirstByte.p50Nanos).isEqualTo(150_000_000)
        assertThat(stats.requestsPerMinute).isWithin(0.01).of(0.8)
    }

    @Test
    fun `기준별 분류는 모르는 값을 따로 묶는다`() {
        val samples = listOf(
            rich("a", network = NetworkType.WIFI, foreground = true),
            rich("b", network = NetworkType.CELLULAR, foreground = false, status = 500),
            rich("c", network = null, foreground = null),
        )

        val stats = compute(samples)

        assertThat(stats.breakdowns.getValue(StatsDimension.NETWORK).map { it.key })
            .containsExactly("CELLULAR", GroupStatistics.UNKNOWN_KEY, "WIFI")
        val background = stats.breakdowns.getValue(StatsDimension.APP_STATE).first { it.key == "background" }
        assertThat(background.errorCount).isEqualTo(1)
    }
}
