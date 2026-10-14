package com.naury.pulse.storage

import com.naury.pulse.core.EventRecord
import com.naury.pulse.core.SessionRecord

/** 앱 이벤트·세션을 쓴다. [CaptureWriter]가 같은 DB 트랜잭션 안에서 부른다. */
internal class EventWriter(private val events: EventDao, private val blobs: BlobStore) {
    fun insert(record: EventRecord, writtenBlobs: MutableList<String>) {
        events.ensureSession(record.sessionId, record.timestampEpochMillis)
        val attachment = record.attachmentPng?.let { png ->
            "${record.transactionId}.png".also { name ->
                blobs.writeBytes(name, png)
                writtenBlobs += name
            }
        }
        events.insert(
            EventEntity(
                id = record.transactionId,
                sessionId = record.sessionId,
                timestampEpochMillis = record.timestampEpochMillis,
                kind = record.kind,
                level = record.level,
                levelRank = record.level?.ordinal,
                tag = record.tag,
                message = record.message,
                detail = record.detail,
                screen = record.screen,
                spanId = record.spanId,
                durationMillis = record.durationMillis,
                value = record.value,
                attachment = attachment,
            ),
        )
    }

    fun updateSession(record: SessionRecord, now: Long) {
        events.ensureSession(record.targetSessionId, record.lastSeenEpochMillis ?: now)
        events.updateSession(record.targetSessionId, record.lastSeenEpochMillis, record.foregroundMillis, record.exitReason, record.crashed)
    }

    /** [cutoff]보다 오래된 이벤트와 [maxEvents]를 넘는 오래된 이벤트를 첨부 파일과 함께 지운다. */
    fun enforceRetention(cutoff: Long, maxEvents: Int) {
        delete(events.olderThan(cutoff))
        val overflow = events.count() - maxEvents
        if (overflow > 0) delete(events.oldest(overflow))
    }

    private fun delete(files: List<EventFile>) {
        files.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
            events.delete(chunk.map { it.id })
            blobs.delete(chunk.mapNotNull { it.attachment })
        }
    }

    private companion object {
        const val SQL_VARIABLE_CHUNK = 500
    }
}
