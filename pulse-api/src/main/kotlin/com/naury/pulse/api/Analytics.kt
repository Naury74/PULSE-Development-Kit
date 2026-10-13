package com.naury.pulse.api

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private const val FIVE_MINUTES_MILLIS = 5 * MINUTE_MILLIS

/** 통계·진단을 계산할 기간. 끝은 항상 지금이다. */
public enum class StatsWindow(
    /** 기간 길이(ms). null이면 저장된 전체. */
    public val millis: Long?,
) {
    LAST_5_MINUTES(FIVE_MINUTES_MILLIS),
    LAST_HOUR(HOUR_MILLIS),
    LAST_24_HOURS(DAY_MILLIS),
    ALL(null),
}

/** 기간 안의 트래픽 집계. 모든 값은 저장된(마스킹된) 기록으로 계산한다. */
public class TrafficStatistics(
    public val window: StatsWindow,
    /** 계산에 쓴 기간 시작(epoch ms). [StatsWindow.ALL]이면 가장 오래된 기록의 시각, 기록이 없으면 null. */
    public val fromEpochMillis: Long?,
    public val toEpochMillis: Long,
    public val counts: StatusCounts,
    public val durations: DurationPercentiles,
    public val bytes: ByteTotals,
    /** 기간을 같은 길이로 나눈 구간별 요청 수. 오래된 구간부터. */
    public val buckets: List<TrafficBucket>,
    /** 요청 수가 많은 순. */
    public val topHosts: List<HostStatistics>,
    /** 엔드포인트(메서드+호스트+경로)별 p95가 긴 순. */
    public val slowestEndpoints: List<EndpointStatistics>,
    /** 계산 상한([MAX_SAMPLES])에 걸려 가장 최근 기록만 썼으면 true. */
    public val sampleLimitReached: Boolean,
) {
    public companion object {
        /** 한 번에 집계하는 최대 기록 수. */
        public const val MAX_SAMPLES: Int = 20_000
    }
}

/** 결과별 요청 수. 응답 코드가 있으면 코드로, 없으면 상태로 나눈다. */
public class StatusCounts(
    public val total: Int,
    /** 응답 코드 1xx~3xx. */
    public val success: Int,
    public val clientError: Int,
    public val serverError: Int,
    /** 응답 없이 실패·취소·중단. */
    public val failed: Int,
    public val inFlight: Int,
)

/**
 * 응답을 받아 끝난 요청의 소요 시간 백분위수(ns, nearest-rank). 그런 요청이 없으면 값은 null이며 0으로 채우지 않는다.
 */
public class DurationPercentiles(
    public val sampleCount: Int,
    public val p50Nanos: Long?,
    public val p95Nanos: Long?,
    public val p99Nanos: Long?,
)

/** 크기를 아는 본문 바이트 합. 응답은 압축 해제 후 크기다. */
public class ByteTotals(
    public val requestBytes: Long,
    public val responseBytes: Long,
    /** 응답 크기를 모르는 요청 수. [responseBytes]에 들어가지 않았다. */
    public val unknownResponseSizeCount: Int,
)

public class TrafficBucket(
    public val startEpochMillis: Long,
    public val endEpochMillis: Long,
    public val count: Int,
    public val errorCount: Int,
)

public class HostStatistics(public val host: String, public val count: Int, public val errorCount: Int)

/** 경로는 저장된 그대로 묶는다(경로 안의 ID를 임의로 합치지 않는다). */
public class EndpointStatistics(
    public val method: String,
    public val host: String,
    public val path: String,
    public val count: Int,
    public val p95Nanos: Long,
    public val maxNanos: Long,
)

/** 진단 규칙. */
public enum class InsightRule {
    /** 소요 시간이 임계값을 넘은 요청. */
    SLOW_REQUEST,

    /** 한 호스트에서 짧은 시간에 오류·실패가 몰렸다. */
    ERROR_BURST,

    /** 같은 GET/HEAD 요청이 동시에 진행됐다. */
    DUPLICATE_IN_FLIGHT,

    /** 응답 본문이 임계값보다 크다. */
    OVERSIZED_RESPONSE,

    /** 비슷한 경로(숫자·UUID만 다른) 요청이 짧은 시간에 많이 나갔다. 추정이며 원인을 단정하지 않는다. */
    N_PLUS_ONE_SUSPECTED,

    /** 인증 없는 같은 GET이 반복됐는데 캐시에서 응답하지 않았다. 캐시 가능 여부는 서버 정책을 확인해야 한다. */
    CACHE_OPPORTUNITY,
}

public enum class InsightSeverity { INFO, WARNING, ERROR }

/** 근거의 종류. [HEURISTIC]은 패턴에 따른 추정으로 문제라고 단정하지 않는다. */
public enum class InsightCertainty { OBSERVED, HEURISTIC }

/**
 * 진단 결과 하나. 근거로 쓴 기준값([threshold])과 관찰값([observed]), 요청 수와 해당 트랜잭션을 함께 담는다.
 * [threshold]와 [observed]의 단위는 규칙마다 다르며 [unit]이 알려 준다.
 */
public class Insight(
    public val rule: InsightRule,
    /** 규칙 판단 방식이 바뀌면 올린다. */
    public val ruleVersion: Int,
    public val severity: InsightSeverity,
    public val certainty: InsightCertainty,
    /** 대상 호스트. 없으면 null. */
    public val host: String?,
    /** 대상 `METHOD /path`. 호스트 단위 규칙이면 null. */
    public val endpoint: String?,
    public val unit: InsightUnit,
    public val threshold: Long,
    public val observed: Long,
    /** 판단에 쓴 시간 창(ms). 창이 없는 규칙이면 null. */
    public val windowMillis: Long?,
    /** 근거가 된 트랜잭션 ID(최신순, 많아야 [MAX_TRANSACTION_IDS]개). */
    public val transactionIds: List<String>,
    /** 근거가 된 트랜잭션 수. [transactionIds]보다 클 수 있다. */
    public val matchCount: Int,
) {
    public companion object {
        public const val MAX_TRANSACTION_IDS: Int = 50
    }
}

/** [Insight.threshold]·[Insight.observed]의 단위. */
public enum class InsightUnit { NANOS, BYTES, COUNT }

/**
 * 진단 규칙 임계값. 기본값은 일반적인 모바일 API 기준이며 앱에 맞게 바꿀 수 있다.
 *
 * @property slowRequestMillis [InsightRule.SLOW_REQUEST] 기준 소요 시간(ms), 1 이상.
 * @property errorBurstCount [InsightRule.ERROR_BURST] 기준 오류 수, 2 이상.
 * @property errorBurstWindowMillis [InsightRule.ERROR_BURST] 시간 창(ms), 1 이상.
 * @property oversizedResponseBytes [InsightRule.OVERSIZED_RESPONSE] 기준 크기(바이트), 1 이상.
 * @property nPlusOneCount [InsightRule.N_PLUS_ONE_SUSPECTED] 기준 요청 수, 2 이상.
 * @property nPlusOneWindowMillis [InsightRule.N_PLUS_ONE_SUSPECTED] 시간 창(ms), 1 이상.
 * @property cacheRepeatCount [InsightRule.CACHE_OPPORTUNITY] 기준 반복 수, 2 이상.
 * @property cacheWindowMillis [InsightRule.CACHE_OPPORTUNITY] 시간 창(ms), 1 이상.
 */
public class InsightThresholds @JvmOverloads constructor(
    public val slowRequestMillis: Long = DEFAULT_SLOW_REQUEST_MILLIS,
    public val errorBurstCount: Int = DEFAULT_ERROR_BURST_COUNT,
    public val errorBurstWindowMillis: Long = MINUTE_MILLIS,
    public val oversizedResponseBytes: Long = DEFAULT_OVERSIZED_RESPONSE_BYTES,
    public val nPlusOneCount: Int = DEFAULT_N_PLUS_ONE_COUNT,
    public val nPlusOneWindowMillis: Long = DEFAULT_N_PLUS_ONE_WINDOW_MILLIS,
    public val cacheRepeatCount: Int = DEFAULT_CACHE_REPEAT_COUNT,
    public val cacheWindowMillis: Long = FIVE_MINUTES_MILLIS,
) {
    init {
        require(slowRequestMillis >= 1 && errorBurstWindowMillis >= 1 && oversizedResponseBytes >= 1) { "임계값은 1 이상이어야 합니다." }
        require(nPlusOneWindowMillis >= 1 && cacheWindowMillis >= 1) { "시간 창은 1 이상이어야 합니다." }
        require(errorBurstCount >= 2 && nPlusOneCount >= 2 && cacheRepeatCount >= 2) { "반복 기준은 2 이상이어야 합니다." }
    }

    private fun values() = listOf(
        slowRequestMillis,
        errorBurstCount,
        errorBurstWindowMillis,
        oversizedResponseBytes,
        nPlusOneCount,
        nPlusOneWindowMillis,
        cacheRepeatCount,
        cacheWindowMillis,
    )

    override fun equals(other: Any?): Boolean = other is InsightThresholds && values() == other.values()

    override fun hashCode(): Int = values().hashCode()

    override fun toString(): String = "InsightThresholds(${values().joinToString()})"

    public companion object {
        public const val DEFAULT_SLOW_REQUEST_MILLIS: Long = 1_500
        public const val DEFAULT_ERROR_BURST_COUNT: Int = 5
        public const val DEFAULT_OVERSIZED_RESPONSE_BYTES: Long = 1_024L * 1_024
        public const val DEFAULT_N_PLUS_ONE_COUNT: Int = 8
        public const val DEFAULT_N_PLUS_ONE_WINDOW_MILLIS: Long = 2_000
        public const val DEFAULT_CACHE_REPEAT_COUNT: Int = 5
    }
}
