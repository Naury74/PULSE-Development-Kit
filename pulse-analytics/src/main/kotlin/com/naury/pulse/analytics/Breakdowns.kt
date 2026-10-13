package com.naury.pulse.analytics

import com.naury.pulse.api.ConnectionStatistics
import com.naury.pulse.api.CountEntry
import com.naury.pulse.api.EndpointStatistics
import com.naury.pulse.api.GroupStatistics
import com.naury.pulse.api.HistogramBin
import com.naury.pulse.api.PhaseStatistics
import com.naury.pulse.api.ResponseSource
import com.naury.pulse.api.StatsDimension
import com.naury.pulse.api.TimingPhaseType
import com.naury.pulse.api.TrafficStatistics

/** [StatisticsCalculator]가 쓰는 세부 집계. */
internal object Breakdowns {
    const val P50 = 50
    const val P75 = 75
    const val P90 = 90
    const val P95 = 95
    const val P99 = 99
    private const val TOP_STATUS_CODES = 10
    private const val TOP_FAILURES = 10
    private const val FOREGROUND = "foreground"
    private const val BACKGROUND = "background"

    fun endpoints(samples: List<TrafficSample>, templater: EndpointTemplater): List<EndpointStatistics> =
        samples.groupBy { Triple(it.method, it.host, templater.template(it.path)) }
            .map { (key, group) ->
                val durations = group.completedDurations()
                EndpointStatistics(
                    method = key.first,
                    host = key.second,
                    path = key.third,
                    count = group.size,
                    p95Nanos = percentile(durations, P95) ?: 0,
                    maxNanos = durations.lastOrNull() ?: 0,
                    errorCount = group.count { it.isError },
                    p50Nanos = percentile(durations, P50),
                    responseBytes = group.sumOf { it.responseBodyBytes ?: 0 },
                )
            }
            .sortedWith(compareByDescending<EndpointStatistics> { it.count }.thenBy { it.path })

    fun latencyHistogram(sortedDurations: List<Long>): List<HistogramBin> {
        if (sortedDurations.isEmpty()) return emptyList()
        val bounds = HistogramBin.LATENCY_BOUNDS_NANOS
        val counts = IntArray(bounds.size + 1)
        sortedDurations.forEach { duration ->
            val index = bounds.indexOfFirst { duration <= it }
            counts[if (index < 0) bounds.size else index]++
        }
        return counts.mapIndexed { index, count -> HistogramBin(bounds.getOrNull(index), count) }
    }

    fun statusCodes(samples: List<TrafficSample>): List<CountEntry> =
        samples.mapNotNull { it.statusCode?.toString() }.ranked(TOP_STATUS_CODES)

    fun failureTypes(samples: List<TrafficSample>): List<CountEntry> =
        samples.filter { it.statusCode == null }.mapNotNull { it.failureType?.substringAfterLast('.') }.ranked(TOP_FAILURES)

    fun phases(samples: List<TrafficSample>): List<PhaseStatistics> = listOfNotNull(
        phase(TimingPhaseType.DNS, samples.mapNotNull { it.dnsNanos }),
        phase(TimingPhaseType.CONNECT, samples.mapNotNull { it.connectNanos }),
        phase(TimingPhaseType.TLS, samples.mapNotNull { it.tlsNanos }),
        phase(TimingPhaseType.RESPONSE_HEADERS, samples.mapNotNull { it.responseHeadersNanos }),
    )

    private fun phase(type: TimingPhaseType, values: List<Long>): PhaseStatistics? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return PhaseStatistics(type, sorted.size, percentile(sorted, P50) ?: 0, percentile(sorted, P95) ?: 0)
    }

    fun connections(samples: List<TrafficSample>) = ConnectionStatistics(
        reused = samples.count { it.connectionReused == true },
        newConnections = samples.count { it.connectionReused == false },
        cacheHits = samples.count { it.responseSource == ResponseSource.CACHE },
        conditionalHits = samples.count { it.responseSource == ResponseSource.CONDITIONAL_CACHE },
        network = samples.count { it.responseSource == ResponseSource.NETWORK },
    )

    fun byDimension(samples: List<TrafficSample>): Map<StatsDimension, List<GroupStatistics>> =
        StatsDimension.entries.associateWith { dimension -> groups(samples) { keyOf(dimension, it) } }

    private fun keyOf(dimension: StatsDimension, sample: TrafficSample): String? = when (dimension) {
        StatsDimension.HOST -> sample.host
        StatsDimension.SCREEN -> sample.context?.screen
        StatsDimension.LABEL -> sample.context?.label
        StatsDimension.NETWORK -> sample.context?.networkType?.name
        StatsDimension.APP_STATE -> sample.context?.foreground?.let { if (it) FOREGROUND else BACKGROUND }
        StatsDimension.METHOD -> sample.method
        StatsDimension.PROTOCOL -> sample.protocol
    }

    private fun groups(samples: List<TrafficSample>, key: (TrafficSample) -> String?): List<GroupStatistics> =
        samples.groupBy { key(it) ?: GroupStatistics.UNKNOWN_KEY }
            .map { (value, group) ->
                val durations = group.completedDurations()
                GroupStatistics(
                    key = value,
                    count = group.size,
                    errorCount = group.count { it.isError },
                    p50Nanos = percentile(durations, P50),
                    p95Nanos = percentile(durations, P95),
                    responseBytes = group.sumOf { it.responseBodyBytes ?: 0 },
                )
            }
            .sortedWith(compareByDescending<GroupStatistics> { it.count }.thenBy { it.key })
            .take(TrafficStatistics.MAX_GROUPS)
}

private fun List<String>.ranked(limit: Int): List<CountEntry> = groupingBy { it }.eachCount().entries
    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    .take(limit)
    .map { CountEntry(it.key, it.value) }
