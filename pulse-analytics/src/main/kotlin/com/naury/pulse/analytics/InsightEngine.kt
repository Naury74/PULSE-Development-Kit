package com.naury.pulse.analytics

import com.naury.pulse.api.Insight
import com.naury.pulse.api.InsightCertainty
import com.naury.pulse.api.InsightRule
import com.naury.pulse.api.InsightSeverity
import com.naury.pulse.api.InsightThresholds
import com.naury.pulse.api.InsightUnit
import com.naury.pulse.api.ResponseSource
import com.naury.pulse.api.TransactionState

/**
 * 저장된 기록에 진단 규칙을 적용한다. 각 결과는 기준값·관찰값·근거 트랜잭션을 함께 내며, 추정 규칙은
 * [InsightCertainty.HEURISTIC]으로 표시한다. 서버 내부 원인은 판단하지 않는다.
 */
public class InsightEngine(private val thresholds: InsightThresholds) {
    public fun evaluate(samples: List<TrafficSample>): List<Insight> {
        val chronological = samples.sortedBy { it.startedAtEpochMillis }
        return (
            slowRequests(chronological) + errorBursts(chronological) + duplicatesInFlight(chronological) +
                oversizedResponses(chronological) + nPlusOne(chronological) + cacheOpportunities(chronological)
            )
            .sortedWith(compareByDescending<Insight> { it.severity.ordinal }.thenByDescending { it.matchCount })
    }

    private fun slowRequests(samples: List<TrafficSample>): List<Insight> {
        val limit = thresholds.slowRequestMillis * TrafficSample.NANOS_PER_MILLI
        return samples.filter { it.state == TransactionState.COMPLETED && (it.durationNanos ?: 0) > limit }
            .groupBy { it.host to it.endpoint }
            .map { (key, group) ->
                insight(
                    InsightRule.SLOW_REQUEST, InsightSeverity.WARNING, InsightCertainty.OBSERVED, key.first, key.second,
                    InsightUnit.NANOS, limit, group.maxOf { it.durationNanos ?: 0 }, null, group,
                )
            }
    }

    private fun errorBursts(samples: List<TrafficSample>): List<Insight> =
        samples.filter { it.isError }.groupBy { it.host }.mapNotNull { (host, errors) ->
            val burst = densestWindow(errors, thresholds.errorBurstWindowMillis)
            if (burst.size < thresholds.errorBurstCount) return@mapNotNull null
            insight(
                InsightRule.ERROR_BURST, InsightSeverity.ERROR, InsightCertainty.OBSERVED, host, null,
                InsightUnit.COUNT, thresholds.errorBurstCount.toLong(), burst.size.toLong(), thresholds.errorBurstWindowMillis, burst,
            )
        }

    private fun duplicatesInFlight(samples: List<TrafficSample>): List<Insight> =
        samples.filter { it.method in idempotentReads }.groupBy { it.method to it.url }.mapNotNull { (key, group) ->
            val overlapping = overlapping(group)
            if (overlapping.size < 2) return@mapNotNull null
            insight(
                InsightRule.DUPLICATE_IN_FLIGHT, InsightSeverity.WARNING, InsightCertainty.HEURISTIC, overlapping.first().host,
                "${key.first} ${overlapping.first().path}", InsightUnit.COUNT, 2, overlapping.size.toLong(), null, overlapping,
            )
        }

    private fun oversizedResponses(samples: List<TrafficSample>): List<Insight> =
        samples.filter { (it.responseBodyBytes ?: 0) > thresholds.oversizedResponseBytes }
            .groupBy { it.host to it.endpoint }
            .map { (key, group) ->
                insight(
                    InsightRule.OVERSIZED_RESPONSE, InsightSeverity.WARNING, InsightCertainty.OBSERVED, key.first, key.second,
                    InsightUnit.BYTES, thresholds.oversizedResponseBytes, group.maxOf { it.responseBodyBytes ?: 0 }, null, group,
                )
            }

    private fun nPlusOne(samples: List<TrafficSample>): List<Insight> = samples.groupBy { Triple(it.method, it.host, pathPattern(it.path)) }
        .filterKeys { (_, _, pattern) -> pattern.contains(EndpointTemplater.ID_PLACEHOLDER) }
        .mapNotNull { (key, group) ->
            val burst = densestWindow(group, thresholds.nPlusOneWindowMillis)
            if (burst.size < thresholds.nPlusOneCount || burst.map { it.path }.distinct().size < 2) return@mapNotNull null
            insight(
                InsightRule.N_PLUS_ONE_SUSPECTED, InsightSeverity.INFO, InsightCertainty.HEURISTIC, key.second,
                "${key.first} ${key.third}", InsightUnit.COUNT, thresholds.nPlusOneCount.toLong(), burst.size.toLong(),
                thresholds.nPlusOneWindowMillis, burst,
            )
        }

    private fun cacheOpportunities(samples: List<TrafficSample>): List<Insight> =
        samples.filter { it.method == "GET" && !it.hasCredentials && it.statusCode == HTTP_OK && it.responseSource != ResponseSource.CACHE }
            .groupBy { it.url }
            .mapNotNull { (_, group) ->
                val repeats = densestWindow(group, thresholds.cacheWindowMillis)
                if (repeats.size < thresholds.cacheRepeatCount) return@mapNotNull null
                insight(
                    InsightRule.CACHE_OPPORTUNITY, InsightSeverity.INFO, InsightCertainty.HEURISTIC, repeats.first().host,
                    repeats.first().endpoint, InsightUnit.COUNT, thresholds.cacheRepeatCount.toLong(), repeats.size.toLong(),
                    thresholds.cacheWindowMillis, repeats,
                )
            }

    /** 시작 시각 기준으로 [windowMillis] 안에 가장 많이 몰린 구간의 기록. [chronological]은 시간순이어야 한다. */
    private fun densestWindow(chronological: List<TrafficSample>, windowMillis: Long): List<TrafficSample> {
        var best = IntRange.EMPTY
        var start = 0
        for (end in chronological.indices) {
            while (chronological[end].startedAtEpochMillis - chronological[start].startedAtEpochMillis > windowMillis) start++
            if (end - start + 1 > best.count()) best = start..end
        }
        return chronological.slice(best)
    }

    /** 시간순 기록에서 다른 기록과 진행 시간이 겹친 기록. 끝 시각을 모르면(진행 중·실패) 겹침을 판단하지 않는다. */
    private fun overlapping(chronological: List<TrafficSample>): List<TrafficSample> {
        val result = LinkedHashSet<TrafficSample>()
        var latest: TrafficSample? = null
        for (sample in chronological) {
            val end = sample.endEpochMillis ?: continue
            val previous = latest
            val previousEnd = previous?.endEpochMillis
            if (previous != null && previousEnd != null && sample.startedAtEpochMillis < previousEnd) {
                result += previous
                result += sample
            }
            if (previousEnd == null || end > previousEnd) latest = sample
        }
        return result.sortedBy { it.startedAtEpochMillis }
    }

    @Suppress("LongParameterList")
    private fun insight(
        rule: InsightRule,
        severity: InsightSeverity,
        certainty: InsightCertainty,
        host: String?,
        endpoint: String?,
        unit: InsightUnit,
        threshold: Long,
        observed: Long,
        windowMillis: Long?,
        matches: List<TrafficSample>,
    ) = Insight(
        rule = rule,
        ruleVersion = RULE_VERSION,
        severity = severity,
        certainty = certainty,
        host = host,
        endpoint = endpoint,
        unit = unit,
        threshold = threshold,
        observed = observed,
        windowMillis = windowMillis,
        transactionIds = matches.sortedByDescending { it.startedAtEpochMillis }.take(Insight.MAX_TRANSACTION_IDS).map { it.id },
        matchCount = matches.size,
    )

    private companion object {
        const val RULE_VERSION = 1
        const val HTTP_OK = 200
        val idempotentReads = setOf("GET", "HEAD")

        /** N+1 추정에만 쓰는 경로 묶음. 숫자·UUID·긴 16진수 조각을 같은 자리표시로 본다. */
        fun pathPattern(path: String): String = EndpointTemplater.autoTemplate(path)
    }
}
