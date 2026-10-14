package com.naury.pulse.export

import com.naury.pulse.api.BodyState
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.RedactionPolicy
import com.naury.pulse.api.TransactionDetail
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
    ): ExportResult = export(inspector, query, format, out, ExportOptions(maxTransactions = maxTransactions))

    /** [options]에 따라 고른 트랜잭션을 [format]으로 쓴다. [ExportOptions.transactionIds]가 있으면 그 순서대로 쓴다. */
    public suspend fun export(
        inspector: PulseInspector,
        query: TransactionQuery,
        format: ExportFormat,
        out: OutputStream,
        options: ExportOptions,
    ): ExportResult {
        val session = Session(format, out, options)
        val ids = options.transactionIds
        if (ids != null) {
            ids.take(options.maxTransactions).forEach { id ->
                currentCoroutineContext().ensureActive()
                inspector.loadTransaction(id)?.let { session.write(entry(inspector, it, options)) }
            }
            return session.finish(truncated = ids.size > options.maxTransactions)
        }
        var cursor: PageCursor? = null
        var truncated = false
        do {
            currentCoroutineContext().ensureActive()
            val page = inspector.loadTransactions(query, cursor, PAGE_SIZE)
            // 최신순이므로 기간보다 오래된 기록이 나오면 그 뒤는 볼 필요가 없다.
            val inRange = page.items.takeWhile { options.sinceEpochMillis == null || it.startedAtEpochMillis >= options.sinceEpochMillis }
            val reachedOld = inRange.size < page.items.size
            val wanted = inRange.filter { options.includeSynthetic || it.source == CaptureSource.ORIGINAL }
            val room = options.maxTransactions - session.count
            if (wanted.size > room) truncated = true
            for (summary in wanted.take(room)) {
                val detail = inspector.loadTransaction(summary.id) ?: continue
                session.write(entry(inspector, detail, options))
            }
            cursor = if (reachedOld) null else page.next
        } while (cursor != null && !truncated)
        return session.finish(truncated)
    }

    private suspend fun entry(inspector: PulseInspector, detail: TransactionDetail, options: ExportOptions): ExportEntry {
        val id = detail.summary.id
        val requestBody = if (options.includeRequestBodies && detail.request.bodyState == BodyState.CAPTURED) {
            inspector.loadBody(id, MessageKind.REQUEST)
        } else {
            null
        }
        val responseBody = if (options.includeResponseBodies && detail.response?.bodyState == BodyState.CAPTURED) {
            inspector.loadBody(id, MessageKind.RESPONSE)
        } else {
            null
        }
        return ExportEntry(detail, requestBody, responseBody)
    }

    /** 이미 읽어 둔 [entries]를 쓴다. */
    public fun write(entries: Sequence<ExportEntry>, format: ExportFormat, out: OutputStream): ExportResult {
        val session = Session(format, out, ExportOptions())
        entries.forEach(session::write)
        return session.finish(truncated = false)
    }

    private inner class Session(format: ExportFormat, out: OutputStream, private val options: ExportOptions) {
        private val writer = OutputStreamWriter(out, Charsets.UTF_8).buffered()
        private val jsonWriter: EntryWriter? = when (format) {
            ExportFormat.PULSE_JSON -> PulseJsonEntryWriter
            ExportFormat.HAR -> HarEntryWriter
            else -> null
        }
        private val textWriter: TextEntryWriter? = when (format) {
            ExportFormat.CSV -> CsvEntryWriter
            ExportFormat.MARKDOWN -> MarkdownEntryWriter
            else -> null
        }
        private val json = JsonStreamWriter(writer)
        var count = 0
            private set
        private var droppedBodies = 0

        init {
            val now = System.currentTimeMillis()
            jsonWriter?.begin(json, now, generator)
            textWriter?.begin(writer, now, generator)
        }

        fun write(entry: ExportEntry) {
            val requestBody = sanitizer.body(entry.requestBody, entry.detail.request.contentType)
            val responseBody = sanitizer.body(entry.responseBody, entry.detail.response?.contentType)
            if (entry.requestBody != null && requestBody == null) droppedBodies++
            if (entry.responseBody != null && responseBody == null) droppedBodies++
            val headers = options.includeHeaders
            val safe = SafeEntry(
                detail = entry.detail,
                url = sanitizer.url(entry.detail.summary.url),
                requestHeaders = if (headers) sanitizer.headers(entry.detail.request.headers) else emptyList(),
                responseHeaders = if (headers) sanitizer.headers(entry.detail.response?.headers.orEmpty()) else emptyList(),
                requestBody = requestBody,
                responseBody = responseBody,
                failureMessage = sanitizer.message(entry.detail.failureMessage),
                networkRequestHeaders = if (headers) sanitizer.headers(entry.detail.networkRequestHeaders) else emptyList(),
                networkResponseHeaders = if (headers) sanitizer.headers(entry.detail.networkResponseHeaders) else emptyList(),
                preferNetworkHeaders = options.preferNetworkHeaders,
            )
            jsonWriter?.entry(json, safe)
            textWriter?.entry(writer, safe)
            count++
        }

        fun finish(truncated: Boolean): ExportResult {
            jsonWriter?.end(json)
            textWriter?.end(writer)
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
