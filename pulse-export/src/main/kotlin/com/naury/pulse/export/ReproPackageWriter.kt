package com.naury.pulse.export

import com.naury.pulse.api.EnvironmentSnapshot
import com.naury.pulse.api.EventQuery
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.RedactionPolicy
import com.naury.pulse.api.ReportDraft
import com.naury.pulse.api.TimelineItem
import com.naury.pulse.api.TimelineItemKind
import com.naury.pulse.api.TimelineQuery
import com.naury.pulse.api.TransactionQuery
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 버그 보고 하나(또는 한 실행)를 다시 분석할 수 있게 ZIP 하나로 묶는다.
 *
 * ```
 * manifest.json              schema pulse.repro.v1, 파일 목록과 SHA-256
 * issue.json                 보고 내용(있을 때)
 * session/timeline.json      요청·이벤트 통합 타임라인
 * network/requests.har       근거로 고른 요청(없으면 그 실행의 요청)
 * app/events.json            그 실행의 앱 이벤트(로그·오류·크래시·성능)
 * environment.json           앱·기기 환경과 플래그
 * attachments/screenshot-redacted.png  마스킹을 마친 화면 캡처(있을 때)
 * ```
 *
 * 모든 글자는 쓰기 직전에 현재 정책으로 다시 가린다. 화면 캡처는 이미 마스킹한 것만 받는다.
 */
public class ReproPackageWriter @JvmOverloads constructor(
    private val policy: RedactionPolicy,
    private val maxTimelineItems: Int = DEFAULT_MAX_TIMELINE_ITEMS,
    private val maxRequests: Int = DEFAULT_MAX_REQUESTS,
) {
    private val sanitizer = ExportSanitizer(policy)

    /**
     * [out]에 ZIP을 쓰고 결과를 돌려준다. [out]은 닫지 않는다.
     *
     * @param sessionId 타임라인·이벤트·요청을 모을 실행. null이면 [draft]의 실행을 쓴다.
     */
    @Suppress("LongParameterList")
    public suspend fun write(
        inspector: PulseInspector,
        draft: ReportDraft?,
        sessionId: String?,
        environment: EnvironmentSnapshot?,
        maskedPng: ByteArray?,
        out: OutputStream,
    ): ReproPackageResult {
        val session = sessionId ?: draft?.sessionId
        val files = ArrayList<PackageFile>()
        val zip = ZipOutputStream(NonClosingStream(out))
        fun add(path: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(bytes)
            zip.closeEntry()
            files += PackageFile(path, bytes.size.toLong(), sha256(bytes))
        }

        draft?.let { add(ISSUE, json { issue(it) }) }
        add(TIMELINE, timeline(inspector, session))
        add(REQUESTS, requests(inspector, draft, session))
        add(
            EVENTS,
            ByteArrayOutputStream().also {
                EventExporter(policy).export(inspector, EventQuery(sessionId = session), EventExportFormat.JSON, it)
            }.toByteArray(),
        )
        environment?.let { add(ENVIRONMENT, json { environment(it) }) }
        maskedPng?.takeIf { it.isNotEmpty() }?.let { add(SCREENSHOT, it) }
        val manifest = json { manifest(session, files) }
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(manifest)
        zip.closeEntry()
        zip.finish()
        return ReproPackageResult(listOf(MANIFEST) + files.map { it.path }, files.sumOf { it.bytes } + manifest.size)
    }

    private suspend fun timeline(inspector: PulseInspector, session: String?): ByteArray = json {
        name("schema").value(TIMELINE_SCHEMA).name("items").beginArray()
        var cursor: PageCursor? = null
        var written = 0
        do {
            currentCoroutineContext().ensureActive()
            val page = inspector.loadTimeline(TimelineQuery(sessionId = session), cursor, PAGE_SIZE)
            page.items.take(maxTimelineItems - written).forEach { item(it) }
            written += page.items.size
            cursor = page.next
        } while (cursor != null && written < maxTimelineItems)
        endArray()
    }

    private suspend fun requests(inspector: PulseInspector, draft: ReportDraft?, session: String?): ByteArray {
        val picked = draft?.evidence?.filter { it.included && it.kind == TimelineItemKind.REQUEST }?.map { it.id }
        val options = ExportOptions(transactionIds = picked?.takeIf { it.isNotEmpty() }, maxTransactions = maxRequests)
        return ByteArrayOutputStream().also {
            PulseExporter(policy).export(inspector, TransactionQuery(sessionId = session), ExportFormat.HAR, it, options)
        }.toByteArray()
    }

    private fun JsonStreamWriter.issue(draft: ReportDraft) {
        name("schema").value(ISSUE_SCHEMA)
        name("id").value(draft.id)
        name("kind").value(draft.kind.name)
        name("severity").value(draft.severity.name)
        name("title").value(sanitizer.text(draft.title))
        name("description").value(sanitizer.text(draft.description))
        name("expected").value(sanitizer.text(draft.expected))
        name("actual").value(sanitizer.text(draft.actual))
        name("steps").beginArray()
        draft.steps.forEach { value(sanitizer.text(it)) }
        endArray()
        name("sessionId").value(draft.sessionId)
        name("createdAt").value(isoTime(draft.createdAtEpochMillis))
        name("evidence").beginArray()
        draft.evidence.filter { it.included }.forEach { ref ->
            beginObject().name(
                "kind",
            ).value(ref.kind.name).name("id").value(ref.id).name("title").value(sanitizer.text(ref.title)).endObject()
        }
        endArray()
    }

    private fun JsonStreamWriter.item(item: TimelineItem) {
        beginObject().name("time").value(isoTime(item.timestampEpochMillis)).name("type").value(item.kind.name).name("id").value(item.id)
        item.event?.let { event ->
            name("kind").value(event.kind.name)
            name("level").value(event.level?.name)
            name("tag").value(event.tag)
            name("message").value(sanitizer.text(event.message))
            name("screen").value(event.screen)
            name("spanId").value(event.spanId)
            name("durationMillis").value(event.durationMillis)
        }
        item.request?.let { request ->
            name("method").value(request.method)
            name("url").value(sanitizer.text(request.url))
            name("status").value(request.statusCode?.toLong())
            name("state").value(request.state.name)
            name("durationNanos").value(request.durationNanos)
            name("screen").value(request.context?.screen)
            name("spanId").value(request.context?.spanId)
        }
        endObject()
    }

    private fun JsonStreamWriter.environment(environment: EnvironmentSnapshot) {
        name("capturedAt").value(isoTime(environment.capturedAtEpochMillis))
        listOf("app" to environment.app, "device" to environment.device, "flags" to environment.flags).forEach { (section, values) ->
            name(section).beginObject()
            values.forEach { (key, value) -> name(key).value(sanitizer.text(value)) }
            endObject()
        }
    }

    private fun JsonStreamWriter.manifest(session: String?, files: List<PackageFile>) {
        name("schema").value(SCHEMA)
        name("createdAt").value(isoTime(System.currentTimeMillis()))
        name("sessionId").value(session)
        name("files").beginArray()
        files.forEach {
            beginObject().name("path").value(it.path).name("bytes").value(it.bytes).name("sha256").value(it.sha256).endObject()
        }
        endArray()
    }

    private inline fun json(block: JsonStreamWriter.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val writer = OutputStreamWriter(bytes, Charsets.UTF_8).buffered()
        JsonStreamWriter(writer).apply {
            beginObject()
            block()
            endObject()
        }
        writer.flush()
        return bytes.toByteArray()
    }

    private class PackageFile(val path: String, val bytes: Long, val sha256: String)

    /** ZIP을 끝내도 호출한 쪽 스트림은 닫지 않는다. */
    private class NonClosingStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(b: Int) = delegate.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)

        override fun flush() = delegate.flush()

        override fun close() = delegate.flush()
    }

    public companion object {
        public const val SCHEMA: String = "pulse.repro.v1"
        public const val DEFAULT_MAX_TIMELINE_ITEMS: Int = 20_000
        public const val DEFAULT_MAX_REQUESTS: Int = 2_000
        private const val ISSUE_SCHEMA = "pulse.issue.v1"
        private const val TIMELINE_SCHEMA = "pulse.timeline.v1"
        private const val PAGE_SIZE = 500
        private const val MANIFEST = "manifest.json"
        private const val ISSUE = "issue.json"
        private const val TIMELINE = "session/timeline.json"
        private const val REQUESTS = "network/requests.har"
        private const val EVENTS = "app/events.json"
        private const val ENVIRONMENT = "environment.json"
        private const val SCREENSHOT = "attachments/screenshot-redacted.png"

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** 패키지에 들어간 파일 경로와 전체 크기(바이트, 압축 전). */
public class ReproPackageResult(public val files: List<String>, public val uncompressedBytes: Long)
