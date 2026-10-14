package com.naury.pulse.monitor

import com.naury.pulse.api.EnvironmentSnapshot
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.EventQuery
import com.naury.pulse.api.EvidenceRef
import com.naury.pulse.api.LogLevel
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.PulseReports
import com.naury.pulse.api.ReportAttachment
import com.naury.pulse.api.ReportDraft
import com.naury.pulse.api.ReportKind
import com.naury.pulse.api.ReportState
import com.naury.pulse.api.ReportStatus
import com.naury.pulse.api.ReportUpload
import com.naury.pulse.api.TimelineItemKind
import com.naury.pulse.api.TransactionQuery
import com.naury.pulse.api.TransactionState
import com.naury.pulse.export.ReproPackageWriter
import com.naury.pulse.security.Redactor
import com.naury.pulse.storage.PulseStorage
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 버그 보고 저장·근거 추천·패키지 내보내기. 전송은 [ReportUploader]가 맡는다.
 * 보고의 글자는 저장 전에 현재 마스킹 정책으로 가린다. 화면 캡처는 화면에서 마스킹을 마친 것만 받는다.
 */
internal class ReportService(
    private val config: PulseConfig,
    private val storage: PulseStorage,
    private val inspector: () -> PulseInspector,
    private val environment: () -> EnvironmentSnapshot,
    private val scope: CoroutineScope,
) : PulseReports {
    private val redactor = Redactor(config.redaction)
    private val progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val uploader = ReportUploader(config.reporting, storage, this, progress, scope)

    override val states: StateFlow<List<ReportState>> =
        combine(storage.observeReportStates(), progress) { rows, running ->
            rows.map { row -> running[row.id]?.let { row.withProgress(it) } ?: row }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override fun canSend(kind: ReportKind): Boolean = config.reporting?.destinationFor(kind) != null

    override suspend fun save(draft: ReportDraft, maskedPng: ByteArray?): String {
        val clean = ReportDraft(
            id = draft.id,
            kind = draft.kind,
            title = text(draft.title).take(ReportDraft.MAX_TITLE_CHARS),
            description = text(draft.description),
            expected = text(draft.expected),
            actual = text(draft.actual),
            steps = draft.steps.map(::text).filter { it.isNotBlank() },
            severity = draft.severity,
            sessionId = draft.sessionId,
            evidence = draft.evidence.map { EvidenceRef(it.kind, it.id, text(it.title), it.included) },
            hasScreenshot = draft.hasScreenshot,
            createdAtEpochMillis = draft.createdAtEpochMillis,
        )
        return storage.saveReport(clean, maskedPng, config.reporting?.destinationFor(draft.kind)?.id)
    }

    override suspend fun load(id: String): ReportDraft? = storage.loadReport(id)

    override suspend fun loadScreenshot(id: String): ByteArray? = storage.loadReportScreenshot(id)

    override suspend fun recommendEvidence(sessionId: String?, aroundEpochMillis: Long, windowMillis: Long): List<EvidenceRef> {
        val from = aroundEpochMillis - windowMillis
        val to = aroundEpochMillis + AFTER_MILLIS
        val handle = inspector()
        val issues = handle.loadEvents(
            EventQuery(kinds = ISSUE_KINDS, fromEpochMillis = from, toEpochMillis = to, sessionId = sessionId),
            null,
            MAX_PER_KIND,
        ).items
        val warnings = handle.loadEvents(
            EventQuery(
                kinds = setOf(EventKind.LOG),
                minLevel = LogLevel.WARN,
                fromEpochMillis = from,
                toEpochMillis = to,
                sessionId = sessionId,
            ),
            null,
            MAX_PER_KIND,
        ).items
        val actions = handle.loadEvents(
            EventQuery(
                kinds = setOf(EventKind.ACTION, EventKind.NAVIGATION, EventKind.MARKER),
                fromEpochMillis = from,
                toEpochMillis = to,
                sessionId = sessionId,
            ),
            null,
            MAX_PER_KIND,
        ).items
        val slowNanos = config.insights.slowRequestMillis * NANOS_PER_MILLI
        val requests = handle.loadTransactions(
            TransactionQuery(fromEpochMillis = from, toEpochMillis = to, sessionId = sessionId),
            null,
            MAX_REQUESTS,
        )
            .items
            .filter { (it.statusCode ?: 0) >= HTTP_ERROR || it.state in FAILED_STATES || (it.durationNanos ?: 0) >= slowNanos }
        val events = (issues + warnings + actions).sortedBy { it.timestampEpochMillis }.map { event ->
            val attributes = event.detail?.takeIf { event.kind == EventKind.ACTION }?.let { " " + compactAttributes(it) }.orEmpty()
            EvidenceRef(
                TimelineItemKind.EVENT,
                event.id,
                "${event.kind} ${event.message.lineSequence().first().take(TITLE_CHARS)}$attributes",
            )
        }
        val requestRefs = requests.sortedBy { it.startedAtEpochMillis }.map { request ->
            EvidenceRef(TimelineItemKind.REQUEST, request.id, "${request.method} ${request.path} ${request.statusCode ?: request.state}")
        }
        return (events + requestRefs).take(ReportDraft.MAX_EVIDENCE)
    }

    override fun submit(id: String) {
        check(config.reporting != null) { "보고 목적지(PulseConfig.reporting)가 없습니다." }
        scope.launch {
            val state = storage.reportState(id) ?: return@launch
            check(canSend(state.kind)) { "이 종류의 보고를 보낼 목적지가 없습니다." }
            val destination = config.reporting?.destinationFor(state.kind)?.id
            storage.updateReport(id, ReportStatus.QUEUED, 0, 0, null, destination)
            uploader.wake()
        }
    }

    override fun cancel(id: String) {
        uploader.cancel(id)
    }

    override fun retry(id: String) = submit(id)

    override fun delete(id: String) {
        scope.launch {
            uploader.cancel(id)
            storage.deleteReport(id)
        }
    }

    override suspend fun exportPackage(id: String, out: OutputStream): Long {
        val draft = storage.loadReport(id)
        val png = if (draft?.hasScreenshot == true) storage.loadReportScreenshot(id) else null
        val counting = CountingStream(out)
        ReproPackageWriter(config.redaction).write(inspector(), draft, draft?.sessionId, environment(), png, counting)
        counting.flush()
        return counting.count
    }

    /** 실행 하나 전체를 패키지로 쓴다(보고 없이). */
    suspend fun exportSession(sessionId: String?, out: OutputStream) {
        ReproPackageWriter(config.redaction).write(inspector(), null, sessionId, environment(), null, out)
    }

    /**
     * 전송할 내용을 만든다. JSON(`pulse.report.v1`)에는 보고·환경·근거 요약을, 첨부에는 마스킹한 화면 캡처와 재현 패키지를 넣는다.
     * 보고가 지워졌으면 null.
     */
    suspend fun buildUpload(id: String): ReportUpload? {
        val draft = storage.loadReport(id) ?: return null
        val png = if (draft.hasScreenshot) storage.loadReportScreenshot(id) else null
        val packageBytes = ByteArrayOutputStream().also {
            ReproPackageWriter(config.redaction).write(inspector(), draft, draft.sessionId, environment(), png, it)
        }.toByteArray()
        val attachments = listOfNotNull(
            png?.let { ReportAttachment("screenshot.png", "image/png", it) },
            ReportAttachment("pulse-report-$id.zip", "application/zip", packageBytes),
        )
        return ReportUpload(id, draft.kind, reportJson(draft), attachments)
    }

    private fun reportJson(draft: ReportDraft): String {
        val env = environment()
        return JSONObject()
            .put("schema", REPORT_SCHEMA)
            .put("id", draft.id)
            .put("kind", draft.kind.name)
            .put("severity", draft.severity.name)
            .put("title", draft.title)
            .put("description", draft.description)
            .put("expected", draft.expected)
            .put("actual", draft.actual)
            .put("steps", JSONArray(draft.steps))
            .putOpt("sessionId", draft.sessionId)
            .put("createdAt", draft.createdAtEpochMillis)
            .put(
                "evidence",
                JSONArray(
                    draft.evidence.filter {
                        it.included
                    }.map { JSONObject().put("kind", it.kind.name).put("id", it.id).put("title", it.title) },
                ),
            )
            .put(
                "environment",
                JSONObject().put("app", JSONObject(env.app)).put("device", JSONObject(env.device)).put("flags", JSONObject(env.flags)),
            )
            .toString()
    }

    private fun ReportState.withProgress(value: Float) =
        ReportState(id, title, kind, severity, status, attempts, lastError, value, destinationId, updatedAtEpochMillis)

    private fun text(value: String): String = redactor.sanitizeTextPublic(value).take(ReportDraft.MAX_TEXT_CHARS)

    private class CountingStream(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() = delegate.flush()
    }

    private companion object {
        val ISSUE_KINDS = setOf(EventKind.ERROR, EventKind.CRASH, EventKind.MAIN_THREAD_BLOCKED, EventKind.EXIT)
        val FAILED_STATES = setOf(TransactionState.FAILED, TransactionState.CANCELED, TransactionState.INTERRUPTED)
        const val MAX_PER_KIND = 50
        const val MAX_REQUESTS = 300
        const val HTTP_ERROR = 400
        const val TITLE_CHARS = 80
        const val NANOS_PER_MILLI = 1_000_000L
        const val REPORT_SCHEMA = "pulse.report.v1"
        const val AFTER_MILLIS = 30_000L

        /** `{"scenario":"GET_JSON"}`를 `scenario=GET_JSON`으로. 재현 단계 초안에 그대로 쓴다. */
        fun compactAttributes(json: String): String = runCatching {
            val obj = JSONObject(json)
            obj.keys().asSequence().joinToString(" ") { "$it=${obj.optString(it)}" }
        }.getOrDefault("")
    }
}
