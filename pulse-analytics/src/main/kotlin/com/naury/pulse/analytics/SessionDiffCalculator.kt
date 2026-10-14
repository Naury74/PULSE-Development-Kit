package com.naury.pulse.analytics

import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.EndpointDiff
import com.naury.pulse.api.EndpointGrouping
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.SessionDiff
import com.naury.pulse.api.SessionSummary
import com.naury.pulse.api.TransactionState
import com.naury.pulse.api.TransactionSummary
import kotlin.math.abs

/** 한 실행에서 비교에 쓸 재료. */
public class SessionMaterial(
    public val session: SessionSummary,
    public val requests: List<TransactionSummary>,
    public val events: List<AppEvent>,
    public val truncated: Boolean,
)

/** 두 실행의 요청·이벤트·화면·플래그 차이를 계산한다. 상태가 없어 어느 스레드에서 불러도 된다. */
public object SessionDiffCalculator {
    private val issueKinds = setOf(EventKind.ERROR, EventKind.CRASH, EventKind.MAIN_THREAD_BLOCKED, EventKind.EXIT)
    private val digits = Regex("\\d+")

    @JvmStatic
    public fun compute(base: SessionMaterial, target: SessionMaterial, grouping: EndpointGrouping): SessionDiff {
        val templater = EndpointTemplater(grouping)
        val baseGroups = base.requests.groupBy { "${it.method} ${templater.template(it.path)}" }
        val targetGroups = target.requests.groupBy { "${it.method} ${templater.template(it.path)}" }
        val endpoints = (baseGroups.keys + targetGroups.keys).map { key ->
            val a = baseGroups[key].orEmpty()
            val b = targetGroups[key].orEmpty()
            EndpointDiff(key, a.size, b.size, a.count(::isError), b.count(::isError), median(a), median(b))
        }.sortedByDescending { score(it) }
        val kinds = (base.events.map { it.kind } + target.events.map { it.kind }).toSet()
        val eventCounts = kinds.associateWith { kind -> base.events.count { it.kind == kind } to target.events.count { it.kind == kind } }
        val baseScreens = screens(base.events)
        val targetScreens = screens(target.events)
        val baseFlags = flags(base.events)
        val targetFlags = flags(target.events)
        val flagDiff = (baseFlags.keys + targetFlags.keys).filter { baseFlags[it] != targetFlags[it] }.associateWith {
            baseFlags[it] to
                targetFlags[it]
        }
        val baseIssues = issues(base.events).toSet()
        return SessionDiff(
            base = base.session,
            target = target.session,
            endpoints = endpoints,
            eventCounts = eventCounts,
            screensOnlyInBase = (baseScreens - targetScreens.toSet()).distinct(),
            screensOnlyInTarget = (targetScreens - baseScreens.toSet()).distinct(),
            flags = flagDiff,
            newIssues = issues(target.events).filter { it !in baseIssues }.distinct(),
            truncated = base.truncated || target.truncated,
        )
    }

    private fun isError(request: TransactionSummary): Boolean =
        (request.statusCode ?: 0) >= HTTP_ERROR || request.state in setOf(TransactionState.FAILED, TransactionState.INTERRUPTED)

    private fun median(requests: List<TransactionSummary>): Long? =
        requests.mapNotNull { it.durationNanos }.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    /** 오류가 늘거나, 한쪽에만 있거나, 요청 수·소요 시간 차이가 큰 엔드포인트를 앞에 둔다. */
    private fun score(diff: EndpointDiff): Long {
        val errors = (diff.targetErrors - diff.baseErrors).toLong() * ERROR_WEIGHT
        val presence = if (diff.baseCount == 0 || diff.targetCount == 0) PRESENCE_WEIGHT else 0
        val time = abs((diff.targetMedianNanos ?: 0) - (diff.baseMedianNanos ?: 0)) / NANOS_PER_MILLI
        return errors + presence + abs(diff.targetCount - diff.baseCount) + time
    }

    private fun screens(events: List<AppEvent>): List<String> = events.filter {
        it.kind == EventKind.NAVIGATION
    }.sortedBy { it.timestampEpochMillis }.map { it.message.substringAfter("→ ").trim() }

    /** 실행 안에서 마지막으로 알린 값. 지운 플래그는 null이다. */
    private fun flags(events: List<AppEvent>): Map<String, String?> = events.filter { it.kind == EventKind.CONFIG && it.tag != null }
        .sortedBy { it.timestampEpochMillis }
        .associate { event -> event.tag!! to event.message.substringAfter(" = ").takeUnless { it == "(removed)" } }

    private fun issues(events: List<AppEvent>): List<String> = events.filter { it.kind in issueKinds }
        .map { "${it.kind} ${it.message.lineSequence().first().take(ISSUE_CHARS).replace(digits, "#")}" }

    private const val HTTP_ERROR = 400
    private const val ERROR_WEIGHT = 1_000_000L
    private const val PRESENCE_WEIGHT = 100_000L
    private const val NANOS_PER_MILLI = 1_000_000L
    private const val ISSUE_CHARS = 160
}
