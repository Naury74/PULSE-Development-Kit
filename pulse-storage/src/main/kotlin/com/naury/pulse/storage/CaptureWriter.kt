package com.naury.pulse.storage

import com.naury.pulse.api.BodyState
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.RetentionPolicy
import com.naury.pulse.api.TransactionState
import com.naury.pulse.core.BodyRecord
import com.naury.pulse.core.CaptureRecord
import com.naury.pulse.core.CaptureSink
import com.naury.pulse.core.FinishRecord
import com.naury.pulse.core.RequestRecord
import com.naury.pulse.core.ResponseRecord
import com.naury.pulse.core.TimingRecord

/**
 * 마스킹된 기록을 DB와 본문 파일에 쓰고 보관 정책을 지킨다. 저장 스레드 하나와 [clear]·[startSession]이
 * [lock]으로 순서를 맞추므로, 파일을 지우는 작업이 아직 커밋되지 않은 본문 파일을 지우지 않는다.
 */
internal class CaptureWriter(
    private val database: PulseDatabase,
    private val blobs: BlobStore,
    private val retention: RetentionPolicy,
    private val wallClock: () -> Long,
) : CaptureSink {
    private val dao = database.transactions()
    private val lock = Any()
    private var batchesSinceRetention = 0

    // 롤백된 묶음이 쓴 본문 파일을 지운 뒤 같은 예외를 다시 던져 파이프라인이 실패로 세게 한다.
    @Suppress("TooGenericExceptionCaught")
    override fun write(records: List<CaptureRecord>) = synchronized(lock) {
        val writtenBlobs = ArrayList<String>()
        try {
            database.runInTransaction { records.forEach { apply(it, writtenBlobs) } }
        } catch (error: Exception) {
            blobs.delete(writtenBlobs)
            throw error
        }
        if (++batchesSinceRetention >= RETENTION_INTERVAL_BATCHES) {
            batchesSinceRetention = 0
            enforceRetention()
        }
    }

    /** 이전 프로세스에서 끝나지 않은 요청을 중단으로 표시하고, DB가 가리키지 않는 본문 파일과 보관 기간이 지난 기록을 지운다. */
    fun startSession(sessionId: String, startedAtEpochMillis: Long) = synchronized(lock) {
        dao.insertSession(SessionEntity(sessionId, startedAtEpochMillis))
        dao.markInterrupted(sessionId)
        blobs.deleteUnreferenced(dao.allBlobFiles().toSet())
        enforceRetention()
        dao.deleteEmptySessions(keep = sessionId)
    }

    fun clear() = synchronized(lock) {
        dao.deleteAllTransactions()
        blobs.deleteAll()
    }

    fun enforceRetention() = synchronized(lock) {
        delete(dao.idsOlderThan(wallClock() - retention.maxAgeMillis))
        val overflow = dao.count() - retention.maxTransactions
        if (overflow > 0) delete(dao.oldestIds(overflow))
        var excess = dao.storedBodyBytes() - retention.maxBodyBytes
        while (excess > 0) {
            val oldest = dao.oldestBlobUsage(RETENTION_SCAN_ROWS).groupBy { it.transactionId }
            if (oldest.isEmpty()) break
            val victims = ArrayList<String>()
            var freed = 0L
            for ((id, usages) in oldest) {
                victims += id
                freed += usages.sumOf { it.storedBodyBytes ?: 0 }
                if (freed >= excess) break
            }
            delete(victims)
            excess = dao.storedBodyBytes() - retention.maxBodyBytes
        }
    }

    private fun delete(ids: List<String>) {
        ids.chunked(SQL_VARIABLE_CHUNK).forEach { chunk ->
            val files = dao.blobFiles(chunk)
            dao.deleteTransactions(chunk)
            blobs.delete(files)
        }
    }

    private fun apply(record: CaptureRecord, writtenBlobs: MutableList<String>) {
        when (record) {
            is RequestRecord -> insertRequest(record)

            is ResponseRecord -> insertResponse(record)

            is BodyRecord -> updateBody(record, writtenBlobs)

            is FinishRecord -> dao.updateFinish(
                record.transactionId,
                record.state,
                record.durationNanos,
                record.failureType,
                record.failureMessage,
            )

            is TimingRecord -> insertTimings(record)
        }
    }

    private fun insertTimings(record: TimingRecord) {
        dao.insertTimings(
            record.phases.mapIndexed { position, phase ->
                TimingEntity(record.transactionId, position, phase.type, phase.exchangeIndex, phase.startOffsetNanos, phase.durationNanos)
            },
        )
        dao.updateNetworkEvidence(record.transactionId, record.responseSource, record.connectionReused)
    }

    private fun insertRequest(record: RequestRecord) {
        val inserted = dao.insertTransaction(
            TransactionEntity(
                id = record.transactionId,
                sessionId = record.sessionId,
                source = record.source,
                parentTransactionId = record.parentTransactionId,
                method = record.method,
                url = record.url,
                scheme = record.scheme,
                host = record.host,
                path = record.path,
                startedAtEpochMillis = record.startedAtEpochMillis,
                state = TransactionState.IN_FLIGHT,
            ),
        )
        if (inserted == -1L) return
        dao.insertMessage(
            MessageEntity(record.transactionId, MessageKind.REQUEST, record.contentType, record.declaredContentLength, BodyState.PENDING),
        )
        dao.insertHeaders(record.headers.toEntities(record.transactionId, MessageKind.REQUEST))
    }

    private fun insertResponse(record: ResponseRecord) {
        if (dao.updateResponse(record.transactionId, record.statusCode, record.protocol, record.responseHeadersNanos) == 0) return
        dao.insertMessage(
            MessageEntity(record.transactionId, MessageKind.RESPONSE, record.contentType, record.declaredContentLength, BodyState.PENDING),
        )
        dao.insertHeaders(record.headers.toEntities(record.transactionId, MessageKind.RESPONSE))
    }

    private fun updateBody(record: BodyRecord, writtenBlobs: MutableList<String>) {
        val text = record.text
        val blobFile = if (record.state == BodyState.CAPTURED && text != null) blobName(record.transactionId, record.kind) else null
        val storedBytes = blobFile?.let { name -> blobs.write(name, text.orEmpty()).also { writtenBlobs += name } }
        val updated = dao.updateBody(record.transactionId, record.kind, record.state, blobFile, storedBytes, record.redactedValueCount)
        if (updated == 0) {
            blobFile?.let { blobs.delete(listOf(it)) }
            return
        }
        when (record.kind) {
            MessageKind.REQUEST -> dao.updateRequestBodyBytes(record.transactionId, record.transferredBytes)
            MessageKind.RESPONSE -> dao.updateResponseBodyBytes(record.transactionId, record.transferredBytes)
        }
    }

    private fun List<HeaderField>.toEntities(transactionId: String, kind: MessageKind) =
        mapIndexed { position, header -> HeaderEntity(transactionId, kind, position, header.name, header.value) }

    private companion object {
        const val RETENTION_INTERVAL_BATCHES = 20
        const val RETENTION_SCAN_ROWS = 200
        const val SQL_VARIABLE_CHUNK = 500

        fun blobName(transactionId: String, kind: MessageKind) = "$transactionId-${kind.name.lowercase()}.json"
    }
}
