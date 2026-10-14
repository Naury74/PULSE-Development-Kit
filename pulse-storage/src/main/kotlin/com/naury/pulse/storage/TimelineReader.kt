package com.naury.pulse.storage

import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.EventQuery
import com.naury.pulse.api.EvidenceLink
import com.naury.pulse.api.LinkStrength
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.TimelineItem
import com.naury.pulse.api.TimelineItemKind
import com.naury.pulse.api.TimelinePage
import com.naury.pulse.api.TimelineQuery
import com.naury.pulse.api.TransactionQuery
import com.naury.pulse.api.TransactionSummary
import kotlin.math.abs

/**
 * 요청과 앱 이벤트를 한 목록으로 읽고, 항목 사이의 연결을 찾는다.
 * 두 표 모두 (시각, ID) 내림차순 커서를 쓰므로 각각 한 페이지씩 읽어 합치면 겹치거나 빠지는 항목이 없다.
 */
internal class TimelineReader(private val transactions: TransactionReader, private val events: EventReader) {
    suspend fun page(query: TimelineQuery, after: PageCursor?, limit: Int): TimelinePage {
        val eventPage = events.page(
            EventQuery(
                kinds = query.eventKinds,
                text = query.text,
                fromEpochMillis = query.fromEpochMillis,
                toEpochMillis = query.toEpochMillis,
                sessionId = query.sessionId,
                spanId = query.spanId,
            ),
            after,
            limit,
        )
        val requestPage = if (query.includeRequests) {
            transactions.page(
                TransactionQuery(
                    text = query.text,
                    fromEpochMillis = query.fromEpochMillis,
                    toEpochMillis = query.toEpochMillis,
                    sessionId = query.sessionId,
                    spanId = query.spanId,
                ),
                after,
                limit,
            )
        } else {
            null
        }
        val merged = (eventPage.items.map(::item) + requestPage?.items.orEmpty().map(::item))
            .sortedWith(compareByDescending<TimelineItem> { it.timestampEpochMillis }.thenByDescending { it.id })
        val items = merged.take(limit)
        val more = merged.size > limit || eventPage.next != null || requestPage?.next != null
        val next = if (more && items.isNotEmpty()) items.last().let { PageCursor(it.timestampEpochMillis, it.id) } else null
        return TimelinePage(items, next)
    }

    suspend fun related(id: String, kind: TimelineItemKind, windowMillis: Long): List<EvidenceLink> {
        val base = when (kind) {
            TimelineItemKind.EVENT -> events.event(id)?.let(::item)
            TimelineItemKind.REQUEST -> transactions.summary(id)?.let(::item)
        } ?: return emptyList()
        val time = base.timestampEpochMillis
        val session = base.event?.sessionId ?: base.request?.sessionId
        val screen = screenOf(base)
        val links = Links(base)
        explicit(base).forEach { (item, reason) -> links.add(item, LinkStrength.EXPLICIT, reason) }
        if (screen != null) {
            nearby(session, time - CONTEXT_WINDOW_MILLIS, time + CONTEXT_WINDOW_MILLIS)
                .filter { screenOf(it) == screen }
                .forEach { links.add(it, LinkStrength.CONTEXTUAL, "screen") }
        }
        nearby(session, time - windowMillis, time + windowMillis).forEach { links.add(it, LinkStrength.TEMPORAL, "time") }
        return links.sorted().take(MAX_LINKS)
    }

    /** 항목마다 가장 강한 연결 하나만 남긴다. */
    private inner class Links(private val base: TimelineItem) {
        private val byId = LinkedHashMap<String, EvidenceLink>()

        fun add(item: TimelineItem, strength: LinkStrength, reason: String) {
            if (item.id == base.id) return
            val existing = byId[item.id]
            if (existing == null || existing.strength > strength) {
                byId[item.id] =
                    EvidenceLink(item.id, item.kind, strength, reason, item.timestampEpochMillis - base.timestampEpochMillis, title(item))
            }
        }

        fun sorted(): List<EvidenceLink> = byId.values.sortedWith(compareBy<EvidenceLink> { it.strength }.thenBy { abs(it.offsetMillis) })
    }

    private fun screenOf(item: TimelineItem): String? = item.event?.screen ?: item.request?.context?.screen

    /** 기록에 남은 연결: 같은 구간, 재실행 원본과 재실행, 같은 실행의 크래시와 종료 이유. */
    private suspend fun explicit(base: TimelineItem): List<Pair<TimelineItem, String>> {
        val result = ArrayList<Pair<TimelineItem, String>>()
        val spanId = base.event?.spanId ?: base.request?.context?.spanId
        if (spanId != null) {
            events.page(EventQuery(spanId = spanId), null, MAX_LINKS).items.forEach { result += item(it) to "span" }
            transactions.page(TransactionQuery(spanId = spanId), null, MAX_LINKS).items.forEach { result += item(it) to "span" }
        }
        base.request?.let { request ->
            request.parentTransactionId?.let { parent -> transactions.summary(parent)?.let { result += item(it) to "replay" } }
            transactions.children(request.id, MAX_LINKS).forEach { result += item(it) to "replay" }
        }
        base.event?.takeIf { it.kind == EventKind.CRASH || it.kind == EventKind.EXIT }?.let { event ->
            val partner = if (event.kind == EventKind.CRASH) EventKind.EXIT else EventKind.CRASH
            events.page(EventQuery(kinds = setOf(partner), sessionId = event.sessionId), null, MAX_LINKS).items
                .forEach { result += item(it) to "crash-exit" }
        }
        return result
    }

    private suspend fun nearby(sessionId: String?, from: Long, to: Long): List<TimelineItem> =
        page(TimelineQuery(sessionId = sessionId, fromEpochMillis = from, toEpochMillis = to), null, MAX_NEARBY).items

    private fun item(event: AppEvent) = TimelineItem(event.id, event.timestampEpochMillis, TimelineItemKind.EVENT, event, null)

    private fun item(request: TransactionSummary) =
        TimelineItem(request.id, request.startedAtEpochMillis, TimelineItemKind.REQUEST, null, request)

    private fun title(item: TimelineItem): String = item.event?.let { "${it.kind} ${it.message.lineSequence().first().take(TITLE_CHARS)}" }
        ?: item.request!!.let { "${it.method} ${it.path} ${it.statusCode ?: it.state}" }

    private companion object {
        const val MAX_LINKS = 50
        const val MAX_NEARBY = 200
        const val CONTEXT_WINDOW_MILLIS = 60_000L
        const val TITLE_CHARS = 80
    }
}
