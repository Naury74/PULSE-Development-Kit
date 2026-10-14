package com.naury.pulse.analytics

import com.naury.pulse.api.ByteTotals
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.DurationPercentiles
import com.naury.pulse.api.EndpointGrouping
import com.naury.pulse.api.HostStatistics
import com.naury.pulse.api.StatsFacets
import com.naury.pulse.api.StatsQuery
import com.naury.pulse.api.StatsWindow
import com.naury.pulse.api.StatusCounts
import com.naury.pulse.api.TrafficBucket
import com.naury.pulse.api.TrafficStatistics
import com.naury.pulse.api.TransactionState

/** 저장된 기록으로 [TrafficStatistics]를 만든다. 상태가 없어 여러 스레드에서 함께 써도 된다. */
public object StatisticsCalculator {
    private const val BUCKET_COUNT = 12
    private const val TOP_HOSTS = 5
    private const val SLOWEST_ENDPOINTS = 5
    private const val MILLIS_PER_MINUTE = 60_000.0

    /** [window] 기간 전체를 집계한다. [samples]는 거르지 않고 그대로 세며, 경로를 템플릿으로 묶지 않는다. */
    @JvmStatic
    public fun compute(
        window: StatsWindow,
        samples: List<TrafficSample>,
        nowEpochMillis: Long,
        sampleLimitReached: Boolean,
    ): TrafficStatistics = compute(
        StatsQuery(window, includeSynthetic = true),
        samples,
        nowEpochMillis,
        sampleLimitReached,
        EndpointGrouping(autoTemplates = false),
    )

    /**
     * @param query 기간과 거르기 조건. [samples]는 기간 안의 기록이어야 하며 나머지 조건은 여기서 거른다.
     * @param nowEpochMillis 기간 끝.
     * @param sampleLimitReached 저장소가 상한 때문에 일부만 넘겼는지.
     * @param grouping 엔드포인트를 묶는 방법.
     */
    @JvmStatic
    public fun compute(
        query: StatsQuery,
        samples: List<TrafficSample>,
        nowEpochMillis: Long,
        sampleLimitReached: Boolean,
        grouping: EndpointGrouping,
    ): TrafficStatistics {
        val inWindow = if (query.includeSynthetic) samples else samples.filter { it.source == CaptureSource.ORIGINAL }
        val selected = inWindow.filter { sample ->
            (query.host == null || sample.host == query.host) &&
                (query.screen == null || sample.context?.screen == query.screen) &&
                (query.label == null || sample.context?.label == query.label)
        }
        val from =
            query.fromEpochMillis ?: query.window.millis?.let { nowEpochMillis - it } ?: selected.minOfOrNull { it.startedAtEpochMillis }
        val endpoints = Breakdowns.endpoints(selected, EndpointTemplater(grouping))
        return TrafficStatistics(
            window = query.window,
            fromEpochMillis = from,
            toEpochMillis = nowEpochMillis,
            counts = counts(selected),
            durations = selected.completedDurations().percentiles(),
            bytes = ByteTotals(
                requestBytes = selected.sumOf { it.requestBodyBytes ?: 0 },
                responseBytes = selected.sumOf { it.responseBodyBytes ?: 0 },
                unknownResponseSizeCount = selected.count { it.responseBodyBytes == null },
            ),
            buckets = buckets(selected, from, nowEpochMillis),
            topHosts = selected.groupBy { it.host }
                .map { (host, group) -> HostStatistics(host, group.size, group.count { it.isError }) }
                .sortedWith(compareByDescending<HostStatistics> { it.count }.thenBy { it.host })
                .take(TOP_HOSTS),
            slowestEndpoints = endpoints.filter { it.p50Nanos != null }.sortedByDescending { it.p95Nanos }.take(SLOWEST_ENDPOINTS),
            sampleLimitReached = sampleLimitReached,
            requestsPerMinute = from?.let { start ->
                val minutes = (nowEpochMillis - start) / MILLIS_PER_MINUTE
                if (minutes > 0) selected.size / minutes else null
            },
            timeToFirstByte = selected.mapNotNull { it.responseHeadersNanos }.sorted().percentiles(),
            latencyHistogram = Breakdowns.latencyHistogram(selected.completedDurations()),
            statusCodes = Breakdowns.statusCodes(selected),
            failureTypes = Breakdowns.failureTypes(selected),
            phases = Breakdowns.phases(selected),
            connections = Breakdowns.connections(selected),
            endpoints = endpoints.take(TrafficStatistics.MAX_ENDPOINTS),
            breakdowns = Breakdowns.byDimension(selected),
            facets = StatsFacets(
                hosts = inWindow.rankedValues { it.host },
                screens = inWindow.rankedValues { it.context?.screen },
                labels = inWindow.rankedValues { it.context?.label },
            ),
        )
    }

    private fun counts(samples: List<TrafficSample>) = StatusCounts(
        total = samples.size,
        success = samples.count { it.statusCode != null && it.statusCode < TrafficSample.HTTP_CLIENT_ERROR },
        clientError = samples.count {
            it.statusCode != null && it.statusCode in TrafficSample.HTTP_CLIENT_ERROR until TrafficSample.HTTP_SERVER_ERROR
        },
        serverError = samples.count { it.statusCode != null && it.statusCode >= TrafficSample.HTTP_SERVER_ERROR },
        failed = samples.count { it.statusCode == null && it.state in TrafficSample.failedStates },
        inFlight = samples.count { it.state == TransactionState.IN_FLIGHT },
    )

    private fun List<TrafficSample>.rankedValues(selector: (TrafficSample) -> String?): List<String> =
        mapNotNull(selector).groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TrafficStatistics.MAX_GROUPS)
            .map { it.key }

    private fun buckets(samples: List<TrafficSample>, from: Long?, to: Long): List<TrafficBucket> {
        if (from == null || to <= from) return emptyList()
        val size = (to - from + BUCKET_COUNT - 1) / BUCKET_COUNT
        val grouped = samples.filter { it.startedAtEpochMillis in from..to }
            .groupBy { ((it.startedAtEpochMillis - from) / size).toInt().coerceAtMost(BUCKET_COUNT - 1) }
        return (0 until BUCKET_COUNT).map { index ->
            val start = from + index * size
            val group = grouped[index].orEmpty()
            val durations = group.completedDurations()
            TrafficBucket(
                start,
                minOf(start + size, to),
                group.size,
                group.count { it.isError },
                percentile(durations, Breakdowns.P50),
                percentile(durations, Breakdowns.P95),
            )
        }
    }
}

internal fun List<TrafficSample>.completedDurations(): List<Long> =
    filter { it.state == TransactionState.COMPLETED }.mapNotNull { it.durationNanos }.sorted()

/** 오름차순 값의 백분위수와 평균·최대. */
internal fun List<Long>.percentiles(): DurationPercentiles = DurationPercentiles(
    sampleCount = size,
    p50Nanos = percentile(this, Breakdowns.P50),
    p95Nanos = percentile(this, Breakdowns.P95),
    p99Nanos = percentile(this, Breakdowns.P99),
    p75Nanos = percentile(this, Breakdowns.P75),
    p90Nanos = percentile(this, Breakdowns.P90),
    meanNanos = if (isEmpty()) null else sum() / size,
    maxNanos = lastOrNull(),
)
