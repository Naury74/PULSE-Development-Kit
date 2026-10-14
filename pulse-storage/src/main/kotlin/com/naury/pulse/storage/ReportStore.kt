package com.naury.pulse.storage

import com.naury.pulse.api.EvidenceRef
import com.naury.pulse.api.ReportDraft
import com.naury.pulse.api.ReportState
import com.naury.pulse.api.ReportStatus
import com.naury.pulse.api.TimelineItemKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * 버그 보고 저장. 화면 캡처는 기록 본문과 다른 폴더에 두어 기록을 지우거나 정리해도 남는다.
 * 글자는 넘겨받기 전에 마스킹한 것이어야 한다.
 */
@Suppress("TooManyFunctions")
internal class ReportStore(private val dao: ReportDao, private val files: BlobStore, private val wallClock: () -> Long) {
    suspend fun save(draft: ReportDraft, png: ByteArray?, destinationId: String?): String {
        val existing = dao.byId(draft.id)
        val screenshot = when {
            png == null -> existing?.screenshot
            png.isEmpty() -> null
            else -> "${draft.id}.png".also { files.writeBytes(it, png) }
        }
        if (png != null && png.isEmpty()) existing?.screenshot?.let { files.delete(listOf(it)) }
        val now = wallClock()
        dao.upsert(
            ReportEntity(
                id = draft.id,
                kind = draft.kind,
                status = existing?.status?.takeIf { it != ReportStatus.SENT } ?: ReportStatus.DRAFT,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: draft.createdAtEpochMillis.takeIf { it > 0 } ?: now,
                updatedAtEpochMillis = now,
                title = draft.title,
                description = draft.description,
                expected = draft.expected,
                actual = draft.actual,
                stepsJson = JSONArray(draft.steps).toString(),
                severity = draft.severity,
                sessionId = draft.sessionId,
                evidenceJson = evidenceJson(draft.evidence),
                screenshot = screenshot,
                destinationId = destinationId,
                attempts = existing?.attempts ?: 0,
                nextAttemptEpochMillis = existing?.nextAttemptEpochMillis ?: 0,
                lastError = existing?.lastError,
            ),
        )
        return draft.id
    }

    suspend fun load(id: String): ReportDraft? = dao.byId(id)?.toDraft()

    suspend fun screenshot(id: String): ByteArray? = dao.byId(id)?.screenshot?.let(files::readBytes)

    fun observeStates(): Flow<List<ReportState>> = dao.observeAll(MAX_REPORTS).map { rows -> rows.map { it.toState() } }

    suspend fun state(id: String): ReportState? = dao.byId(id)?.toState()

    suspend fun due(now: Long, limit: Int): List<ReportState> = dao.due(now, limit).map { it.toState() }

    @Suppress("LongParameterList")
    suspend fun update(id: String, status: ReportStatus, attempts: Int, nextAttempt: Long, lastError: String?, destinationId: String?) {
        dao.updateStatus(id, status, attempts, nextAttempt, lastError, destinationId, wallClock())
    }

    suspend fun requeueSending(): Int = dao.requeueSending()

    suspend fun delete(id: String) {
        dao.byId(id)?.screenshot?.let { files.delete(listOf(it)) }
        dao.delete(id)
    }

    private fun ReportEntity.toDraft() = ReportDraft(
        id = id,
        kind = kind,
        title = title,
        description = description,
        expected = expected,
        actual = actual,
        steps = JSONArray(stepsJson).let { array -> List(array.length()) { array.getString(it) } },
        severity = severity,
        sessionId = sessionId,
        evidence = parseEvidence(evidenceJson),
        hasScreenshot = screenshot != null,
        createdAtEpochMillis = createdAtEpochMillis,
    )

    private fun ReportEntity.toState() =
        ReportState(id, title, kind, severity, status, attempts, lastError, null, destinationId, updatedAtEpochMillis)

    private companion object {
        const val MAX_REPORTS = 500

        fun evidenceJson(evidence: List<EvidenceRef>): String = JSONArray(
            evidence.map { JSONObject().put("kind", it.kind.name).put("id", it.id).put("title", it.title).put("included", it.included) },
        ).toString()

        fun parseEvidence(json: String): List<EvidenceRef> {
            val array = JSONArray(json)
            return List(array.length()) { index ->
                val item = array.getJSONObject(index)
                EvidenceRef(
                    TimelineItemKind.valueOf(item.getString("kind")),
                    item.getString("id"),
                    item.optString("title"),
                    item.optBoolean("included", true),
                )
            }
        }
    }
}
