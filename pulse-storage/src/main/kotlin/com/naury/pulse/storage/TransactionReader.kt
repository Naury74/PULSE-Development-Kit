package com.naury.pulse.storage

import com.naury.pulse.analytics.TrafficSample
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.HttpMessage
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.NetworkType
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.RequestContext
import com.naury.pulse.api.TimingPhase
import com.naury.pulse.api.TransactionDetail
import com.naury.pulse.api.TransactionPage
import com.naury.pulse.api.TransactionQuery
import com.naury.pulse.api.TransactionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class TransactionReader(private val dao: TransactionDao, private val blobs: BlobStore) {
    suspend fun page(query: TransactionQuery, after: PageCursor?, limit: Int): TransactionPage {
        require(limit in 1..MAX_PAGE_SIZE) { "limit는 1..$MAX_PAGE_SIZE 범위여야 합니다: $limit" }
        val rows = dao.page(
            text = escapeLike(query.text.trim()),
            status = query.status.name,
            from = query.fromEpochMillis,
            to = query.toEpochMillis,
            sessionId = query.sessionId,
            spanId = query.spanId,
            afterStarted = after?.startedAtEpochMillis,
            afterId = after?.id,
            limit = limit + 1,
        )
        val items = rows.take(limit).map { it.toSummary() }
        val next = if (rows.size > limit) items.last().let { PageCursor(it.startedAtEpochMillis, it.id) } else null
        return TransactionPage(items, next)
    }

    suspend fun summary(id: String): TransactionSummary? = dao.transaction(id)?.toSummary()

    suspend fun children(id: String, limit: Int): List<TransactionSummary> = dao.children(id, limit).map { it.toSummary() }

    suspend fun detail(id: String): TransactionDetail? {
        val transaction = dao.transaction(id) ?: return null
        val messages = dao.messages(id).associateBy { it.kind }
        val headers = dao.headers(id).groupBy({ it.kind }, { HeaderField(it.name, it.value) })
        val network = dao.networkHeaders(id).groupBy({ it.kind }, { HeaderField(it.name, it.value) })
        val request = messages[MessageKind.REQUEST]?.toHttpMessage(headers[MessageKind.REQUEST].orEmpty())
            ?: HttpMessage(headers[MessageKind.REQUEST].orEmpty(), null, null, BodyState.PENDING, null, 0)
        return TransactionDetail(
            summary = transaction.toSummary(),
            protocol = transaction.protocol,
            responseHeadersNanos = transaction.responseHeadersNanos,
            failureMessage = transaction.failureMessage,
            request = request,
            response = messages[MessageKind.RESPONSE]?.toHttpMessage(headers[MessageKind.RESPONSE].orEmpty()),
            timings = dao.timings(id).map { TimingPhase(it.phase, it.exchangeIndex, it.startOffsetNanos, it.durationNanos) },
            responseSource = transaction.responseSource,
            connectionReused = transaction.connectionReused,
            networkRequestHeaders = network[MessageKind.REQUEST].orEmpty(),
            networkResponseHeaders = network[MessageKind.RESPONSE].orEmpty(),
        )
    }

    suspend fun body(id: String, kind: MessageKind): String? {
        val message = dao.message(id, kind) ?: return null
        if (message.bodyState != BodyState.CAPTURED) return null
        val file = message.blobFile ?: return null
        return withContext(Dispatchers.IO) { blobs.read(file) }
    }

    /** 기간 안의 최근 기록을 [limit]개까지 읽는다. 상한에 걸렸는지 함께 돌려준다. */
    suspend fun samples(
        fromEpochMillis: Long,
        limit: Int,
        includeSynthetic: Boolean = false,
        toEpochMillis: Long = Long.MAX_VALUE,
    ): Pair<List<TrafficSample>, Boolean> {
        val rows = dao.samples(fromEpochMillis, toEpochMillis, limit + 1, includeSynthetic)
        val samples = rows.take(limit).map {
            TrafficSample(
                id = it.id,
                method = it.method,
                url = it.url,
                host = it.host,
                path = it.path,
                startedAtEpochMillis = it.startedAtEpochMillis,
                durationNanos = it.durationNanos,
                statusCode = it.statusCode,
                state = it.state,
                requestBodyBytes = it.requestBodyBytes,
                responseBodyBytes = it.responseBodyBytes,
                hasCredentials = it.hasCredentials,
                responseSource = it.responseSource,
                source = it.source,
                protocol = it.protocol,
                responseHeadersNanos = it.responseHeadersNanos,
                failureType = it.failureType,
                connectionReused = it.connectionReused,
                context = context(it.screen, it.foreground, it.networkType, it.label),
                dnsNanos = it.dnsNanos,
                connectNanos = it.connectNanos,
                tlsNanos = it.tlsNanos,
            )
        }
        return samples to (rows.size > limit)
    }

    private fun TransactionEntity.toSummary() = TransactionSummary(
        id = id,
        sessionId = sessionId,
        method = method,
        url = url,
        host = host,
        path = path,
        startedAtEpochMillis = startedAtEpochMillis,
        durationNanos = durationNanos,
        statusCode = statusCode,
        state = state,
        failureType = failureType,
        requestBodyBytes = requestBodyBytes,
        responseBodyBytes = responseBodyBytes,
        source = source,
        parentTransactionId = parentTransactionId,
        context = context(screen, foreground, networkType, label, spanId),
    )

    private fun context(screen: String?, foreground: Boolean?, networkType: NetworkType?, label: String?, spanId: String? = null) =
        RequestContext(screen, foreground, networkType, label, spanId).takeUnless { it.isEmpty }

    private fun MessageEntity.toHttpMessage(headers: List<HeaderField>) =
        HttpMessage(headers, contentType, declaredContentLength, bodyState, storedBodyBytes, redactedValueCount)

    internal companion object {
        private const val MAX_PAGE_SIZE = 500

        fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
