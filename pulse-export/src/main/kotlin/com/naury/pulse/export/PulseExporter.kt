package com.naury.pulse.export

import com.naury.pulse.api.BodyState
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.RedactionPolicy
import com.naury.pulse.api.TransactionQuery
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 저장된 트랜잭션을 [ExportFormat]으로 스트림에 쓴다. 한 번에 한 페이지씩 읽어 메모리에 모으지 않고,
 * 쓰기 전에 [policy]로 다시 마스킹한다. 코루틴이 취소되면 그 자리에서 멈추므로 받는 쪽이 만든 파일을 지워야 한다.
 * 자동으로 공유하거나 업로드하지 않는다.
 */
public class PulseExporter(policy: RedactionPolicy, private val generator: String = GENERATOR) {
    private val sanitizer = ExportSanitizer(policy)

    /** [query]에 맞는 트랜잭션을 최신순으로 [maxTransactions]개까지 쓴다. */
    public suspend fun export(
        inspector: PulseInspector,
        query: TransactionQuery,
        format: ExportFormat,
        out: OutputStream,
        maxTransactions: Int = DEFAULT_MAX_TRANSACTIONS,
    ): ExportResult {
        require(maxTransactions >= 1) { "maxTransactions는 1 이상이어야 합니다." }
        val session = Session(format, out)
        var cursor: PageCursor? = null
        var truncated = false
        do {
            currentCoroutineContext().ensureActive()
            val page = inspector.loadTransactions(query, cursor, PAGE_SIZE)
            val room = maxTransactions - session.count
            if (page.items.size > room) truncated = true
            for (summary in page.items.take(room)) {
                val detail = inspector.loadTransaction(summary.id) ?: continue
                val requestBody = if (detail.request.bodyState ==
                    BodyState.CAPTURED
                ) {
                    inspector.loadBody(summary.id, MessageKind.REQUEST)
                } else {
                    null
                }
                val responseBody = if (detail.response?.bodyState ==
                    BodyState.CAPTURED
                ) {
                    inspector.loadBody(summary.id, MessageKind.RESPONSE)
                } else {
                    null
                }
                session.write(ExportEntry(detail, requestBody, responseBody))
            }
            cursor = page.next
        } while (cursor != null && !truncated)
        return session.finish(truncated)
    }

    /** 이미 읽어 둔 [entries]를 쓴다. */
    public fun write(entries: Sequence<ExportEntry>, format: ExportFormat, out: OutputStream): ExportResult {
        val session = Session(format, out)
        entries.forEach(session::write)
        return session.finish(truncated = false)
    }

    private inner class Session(format: ExportFormat, out: OutputStream) {
        private val writer = OutputStreamWriter(out, Charsets.UTF_8).buffered()
        private val json = JsonStreamWriter(writer)
        private val entryWriter = when (format) {
            ExportFormat.PULSE_JSON -> PulseJsonEntryWriter
            ExportFormat.HAR -> HarEntryWriter
        }
        var count = 0
            private set
        private var droppedBodies = 0

        init {
            entryWriter.begin(json, System.currentTimeMillis(), generator)
        }

        fun write(entry: ExportEntry) {
            val requestBody = sanitizer.body(entry.requestBody, entry.detail.request.contentType)
            val responseBody = sanitizer.body(entry.responseBody, entry.detail.response?.contentType)
            if (entry.requestBody != null && requestBody == null) droppedBodies++
            if (entry.responseBody != null && responseBody == null) droppedBodies++
            entryWriter.entry(
                json,
                SafeEntry(
                    detail = entry.detail,
                    url = sanitizer.url(entry.detail.summary.url),
                    requestHeaders = sanitizer.headers(entry.detail.request.headers),
                    responseHeaders = sanitizer.headers(entry.detail.response?.headers.orEmpty()),
                    requestBody = requestBody,
                    responseBody = responseBody,
                    failureMessage = sanitizer.message(entry.detail.failureMessage),
                ),
            )
            count++
        }

        fun finish(truncated: Boolean): ExportResult {
            entryWriter.end(json)
            writer.flush()
            return ExportResult(count, droppedBodies, truncated)
        }
    }

    public companion object {
        public const val DEFAULT_MAX_TRANSACTIONS: Int = 10_000
        private const val PAGE_SIZE = 100
        private const val GENERATOR = "PULSE"
    }
}
