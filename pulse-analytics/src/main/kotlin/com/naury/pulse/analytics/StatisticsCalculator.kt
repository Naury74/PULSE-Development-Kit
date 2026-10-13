package com.naury.pulse.analytics

import com.naury.pulse.api.ByteTotals
import com.naury.pulse.api.DurationPercentiles
import com.naury.pulse.api.EndpointStatistics
import com.naury.pulse.api.HostStatistics
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
    private const val P50 = 50
    private const val P95 = 95
    private const val P99 = 99

    /**
     * @param samples 기간 안의 기록(순서 무관).
     * @param nowEpochMillis 기간 끝.
     * @param sampleLimitReached 저장소가 상한 때문에 일부만 넘겼는지.
     */
    @JvmStatic
    public fun compute(
        window: StatsWindow,
        samples: List<TrafficSample>,
        nowEpochMillis: Long,
        sampleLimitReached: Boolean,
    ): TrafficStatistics {
        val from = window.millis?.let { nowEpochMillis - it } ?: samples.minOfOrNull { it.startedAtEpochMillis }
        val durations = samples.completedDurations()
        return TrafficStatistics(
            window = window,
            fromEpochMillis = from,
            toEpochMillis = nowEpochMillis,
            counts = StatusCounts(
                total = samples.size,
                success = samples.count { it.statusCode != null && it.statusCode < TrafficSample.HTTP_CLIENT_ERROR },
                clientError = samples.count {
                    it.statusCode != null &&
                        it.statusCode in TrafficSample.HTTP_CLIENT_ERROR until TrafficSample.HTTP_SERVER_ERROR
                },
                serverError = samples.count { it.statusCode != null && it.statusCode >= TrafficSample.HTTP_SERVER_ERROR },
                failed = samples.count { it.statusCode == null && it.state in TrafficSample.failedStates },
                inFlight = samples.count { it.state == TransactionState.IN_FLIGHT },
            ),
            durations = DurationPercentiles(
                durations.size,
                percentile(durations, P50),
                percentile(durations, P95),
                percentile(durations, P99),
            ),
            bytes = ByteTotals(
                requestBytes = samples.sumOf { it.requestBodyBytes ?: 0 },
                responseBytes = samples.sumOf { it.responseBodyBytes ?: 0 },
                unknownResponseSizeCount = samples.count { it.responseBodyBytes == null },
            ),
            buckets = buckets(samples, from, nowEpochMillis),
            topHosts = samples.groupBy { it.host }
                .map { (host, group) -> HostStatistics(host, group.size, group.count { it.isError }) }
                .sortedWith(compareByDescending<HostStatistics> { it.count }.thenBy { it.host })
                .take(TOP_HOSTS),
            slowestEndpoints = samples.groupBy { Triple(it.method, it.host, it.path) }
                .mapNotNull { (key, group) ->
                    val sorted = group.completedDurations()
                    percentile(sorted, P95)?.let { p95 ->
                        EndpointStatistics(key.first, key.second, key.third, group.size, p95, sorted.last())
                    }
                }
                .sortedByDescending { it.p95Nanos }
                .take(SLOWEST_ENDPOINTS),
            sampleLimitReached = sampleLimitReached,
        )
    }

    private fun List<TrafficSample>.completedDurations(): List<Long> =
        filter { it.state == TransactionState.COMPLETED }.mapNotNull { it.durationNanos }.sorted()

    private fun buckets(samples: List<TrafficSample>, from: Long?, to: Long): List<TrafficBucket> {
        if (from == null || to <= from) return emptyList()
        val span = to - from
        val size = (span + BUCKET_COUNT - 1) / BUCKET_COUNT
        val counts = IntArray(BUCKET_COUNT)
        val errors = IntArray(BUCKET_COUNT)
        samples.forEach { sample ->
            if (sample.startedAtEpochMillis in from..to) {
                val index = ((sample.startedAtEpochMillis - from) / size).toInt().coerceAtMost(BUCKET_COUNT - 1)
                counts[index]++
                if (sample.isError) errors[index]++
            }
        }
        return (0 until BUCKET_COUNT).map { index ->
            val start = from + index * size
            TrafficBucket(start, minOf(start + size, to), counts[index], errors[index])
        }
    }
}
