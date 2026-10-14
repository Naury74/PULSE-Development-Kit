package com.naury.pulse.storage

import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.EventPage
import com.naury.pulse.api.EventQuery
import com.naury.pulse.api.LogLevel
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.SessionSummary

internal class EventReader(private val dao: EventDao, private val blobs: BlobStore) {
    suspend fun page(query: EventQuery, after: PageCursor?, limit: Int): EventPage {
        require(limit in 1..MAX_PAGE_SIZE) { "limit는 1..$MAX_PAGE_SIZE 범위여야 합니다: $limit" }
        val rows = dao.page(
            anyKind = query.kinds.isEmpty(),
            kinds = query.kinds.toList().ifEmpty { listOf(EventKind.LOG) },
            minRank = query.minLevel?.ordinal,
            text = TransactionReader.escapeLike(query.text.trim()),
            tag = query.tag,
            from = query.fromEpochMillis,
            to = query.toEpochMillis,
            sessionId = query.sessionId,
            afterTimestamp = after?.startedAtEpochMillis,
            afterId = after?.id,
            limit = limit + 1,
        )
        val items = rows.take(limit).map { it.toEvent() }
        val next = if (rows.size > limit) items.last().let { PageCursor(it.timestampEpochMillis, it.id) } else null
        return EventPage(items, next)
    }

    suspend fun sessions(from: Long?, to: Long?, limit: Int, currentSessionId: String?): List<SessionSummary> =
        dao.sessions(from, to, limit).map { it.toSummary(currentSessionId) }

    suspend fun statisticsEvents(kinds: List<EventKind>, from: Long?, to: Long?, limit: Int): List<AppEvent> =
        dao.forStatistics(kinds, from, to, limit).map { it.toEvent() }

    suspend fun logLevels(from: Long?, to: Long?): Map<LogLevel, Int> = dao.logLevelCounts(from, to).mapNotNull { row ->
        LogLevel.entries.firstOrNull { it.name == row.level }?.let { it to row.count }
    }.toMap()

    suspend fun requestTimes(from: Long?, to: Long?): List<Long> = dao.requestTimes(from, to)

    fun attachment(name: String): ByteArray? = blobs.readBytes(name)

    fun previousSession(current: String): SessionSummary? = dao.previousSession(current)?.toSummary(current)

    private fun EventEntity.toEvent() = AppEvent(
        id = id,
        sessionId = sessionId,
        timestampEpochMillis = timestampEpochMillis,
        kind = kind,
        level = level,
        tag = tag,
        message = message,
        detail = detail,
        screen = screen,
        spanId = spanId,
        durationMillis = durationMillis,
        value = value,
        attachment = attachment,
    )

    private fun SessionEntity.toSummary(currentSessionId: String?) = SessionSummary(
        id = id,
        startedAtEpochMillis = startedAtEpochMillis,
        lastSeenEpochMillis = lastSeenEpochMillis ?: startedAtEpochMillis,
        foregroundMillis = foregroundMillis,
        exitReason = exitReason,
        crashed = crashed,
        current = id == currentSessionId,
    )

    private companion object {
        const val MAX_PAGE_SIZE = 500
    }
}
