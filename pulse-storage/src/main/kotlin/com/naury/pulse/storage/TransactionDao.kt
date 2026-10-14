package com.naury.pulse.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.RedactionPolicy
import com.naury.pulse.api.ResponseSource
import com.naury.pulse.api.TransactionState

@Dao
internal interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTransaction(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertHeaders(headers: List<HeaderEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTimings(timings: List<TimingEntity>)

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun transactionNow(id: String): TransactionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE id = :id)")
    fun transactionExists(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertNetworkHeaders(headers: List<NetworkHeaderEntity>)

    @Query("SELECT * FROM network_headers WHERE transactionId = :id ORDER BY kind, position")
    suspend fun networkHeaders(id: String): List<NetworkHeaderEntity>

    @Query("UPDATE transactions SET statusCode = :statusCode, protocol = :protocol, responseHeadersNanos = :headersNanos WHERE id = :id")
    fun updateResponse(id: String, statusCode: Int, protocol: String, headersNanos: Long): Int

    @Query(
        """UPDATE messages SET bodyState = :state, blobFile = :blobFile, storedBodyBytes = :storedBytes,
        redactedValueCount = :redactedCount WHERE transactionId = :id AND kind = :kind""",
    )
    fun updateBody(id: String, kind: MessageKind, state: BodyState, blobFile: String?, storedBytes: Long?, redactedCount: Int): Int

    @Query("UPDATE transactions SET source = 'MOCK' WHERE id = :id")
    fun markMock(id: String)

    @Query("UPDATE transactions SET responseSource = :source, connectionReused = :reused WHERE id = :id")
    fun updateNetworkEvidence(id: String, source: ResponseSource?, reused: Boolean?)

    @Query("UPDATE transactions SET requestBodyBytes = :bytes WHERE id = :id")
    fun updateRequestBodyBytes(id: String, bytes: Long?)

    @Query("SELECT * FROM transactions WHERE parentTransactionId = :id ORDER BY startedAtEpochMillis LIMIT :limit")
    suspend fun children(id: String, limit: Int): List<TransactionEntity>

    @Query("UPDATE transactions SET responseBodyBytes = :bytes WHERE id = :id")
    fun updateResponseBodyBytes(id: String, bytes: Long?)

    @Query(
        """UPDATE transactions SET state = :state, durationNanos = :durationNanos, failureType = :failureType,
        failureMessage = :failureMessage WHERE id = :id""",
    )
    fun updateFinish(id: String, state: TransactionState, durationNanos: Long, failureType: String?, failureMessage: String?)

    @Query("UPDATE transactions SET state = :interrupted WHERE state = :inFlight AND sessionId != :currentSessionId")
    fun markInterrupted(
        currentSessionId: String,
        inFlight: TransactionState = TransactionState.IN_FLIGHT,
        interrupted: TransactionState = TransactionState.INTERRUPTED,
    ): Int

    @Query(
        """SELECT * FROM transactions
        WHERE (:text = '' OR url LIKE '%' || :text || '%' ESCAPE '\')
        AND (
            :status = 'ALL'
            OR (:status = 'SUCCESS' AND statusCode BETWEEN 100 AND 399)
            OR (:status = 'CLIENT_ERROR' AND statusCode BETWEEN 400 AND 499)
            OR (:status = 'SERVER_ERROR' AND statusCode >= 500)
            OR (:status = 'FAILED' AND state IN ('FAILED', 'CANCELED', 'INTERRUPTED'))
            OR (:status = 'IN_FLIGHT' AND state = 'IN_FLIGHT')
        )
        AND (:from IS NULL OR startedAtEpochMillis >= :from)
        AND (:to IS NULL OR startedAtEpochMillis <= :to)
        AND (:sessionId IS NULL OR sessionId = :sessionId)
        AND (:spanId IS NULL OR spanId = :spanId)
        AND (:afterStarted IS NULL OR startedAtEpochMillis < :afterStarted
            OR (startedAtEpochMillis = :afterStarted AND id < :afterId))
        ORDER BY startedAtEpochMillis DESC, id DESC
        LIMIT :limit""",
    )
    @Suppress("LongParameterList")
    suspend fun page(
        text: String,
        status: String,
        from: Long?,
        to: Long?,
        sessionId: String?,
        spanId: String?,
        afterStarted: Long?,
        afterId: String?,
        limit: Int,
    ): List<TransactionEntity>

    /**
     * 집계·진단용 기록. 재실행·모의 응답은 원래 트래픽 통계를 흐리지 않도록 뺀다.
     * 자격 증명 여부는 저장 전에 가려진 요청 헤더가 있는지로 판단한다(값은 이미 남아 있지 않다).
     */
    @Query(
        """SELECT t.id, t.method, t.url, t.host, t.path, t.startedAtEpochMillis, t.durationNanos, t.statusCode, t.state,
        t.requestBodyBytes, t.responseBodyBytes, t.responseSource, t.source, t.protocol, t.responseHeadersNanos, t.failureType,
        t.connectionReused, t.screen, t.foreground, t.networkType, t.label,
        EXISTS(SELECT 1 FROM headers h WHERE h.transactionId = t.id AND h.kind = 'REQUEST' AND h.value = :redacted) AS hasCredentials,
        (SELECT SUM(p.durationNanos) FROM timing_phases p WHERE p.transactionId = t.id AND p.phase = 'DNS') AS dnsNanos,
        (SELECT SUM(p.durationNanos) FROM timing_phases p WHERE p.transactionId = t.id AND p.phase = 'CONNECT') AS connectNanos,
        (SELECT SUM(p.durationNanos) FROM timing_phases p WHERE p.transactionId = t.id AND p.phase = 'TLS') AS tlsNanos
        FROM transactions t WHERE t.startedAtEpochMillis >= :from AND t.startedAtEpochMillis <= :to
        AND (:includeSynthetic OR t.source = 'ORIGINAL')
        ORDER BY t.startedAtEpochMillis DESC LIMIT :limit""",
    )
    suspend fun samples(
        from: Long,
        to: Long,
        limit: Int,
        includeSynthetic: Boolean = false,
        redacted: String = RedactionPolicy.REDACTED,
    ): List<SampleRow>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun transaction(id: String): TransactionEntity?

    @Query("SELECT * FROM messages WHERE transactionId = :id")
    suspend fun messages(id: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE transactionId = :id AND kind = :kind")
    suspend fun message(id: String, kind: MessageKind): MessageEntity?

    @Query("SELECT * FROM headers WHERE transactionId = :id ORDER BY kind, position")
    suspend fun headers(id: String): List<HeaderEntity>

    @Query("SELECT * FROM timing_phases WHERE transactionId = :id ORDER BY position")
    suspend fun timings(id: String): List<TimingEntity>

    @Query("SELECT COUNT(*) FROM transactions")
    fun count(): Int

    @Query("SELECT COALESCE(SUM(storedBodyBytes), 0) FROM messages")
    fun storedBodyBytes(): Long

    @Query("SELECT id FROM transactions WHERE startedAtEpochMillis < :cutoff")
    fun idsOlderThan(cutoff: Long): List<String>

    @Query("SELECT id FROM transactions ORDER BY startedAtEpochMillis ASC, id ASC LIMIT :limit")
    fun oldestIds(limit: Int): List<String>

    @Query(
        """SELECT t.id AS transactionId, m.blobFile AS blobFile, m.storedBodyBytes AS storedBodyBytes
        FROM transactions t LEFT JOIN messages m ON m.transactionId = t.id
        ORDER BY t.startedAtEpochMillis ASC, t.id ASC LIMIT :limit""",
    )
    fun oldestBlobUsage(limit: Int): List<BlobUsage>

    @Query("SELECT blobFile FROM messages WHERE transactionId IN (:ids) AND blobFile IS NOT NULL")
    fun blobFiles(ids: List<String>): List<String>

    @Query("SELECT blobFile FROM messages WHERE blobFile IS NOT NULL")
    fun allBlobFiles(): List<String>

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    fun deleteTransactions(ids: List<String>)

    @Query("DELETE FROM transactions")
    fun deleteAllTransactions()

    /** 보관 기간이 지났고 남은 요청·이벤트가 없는 세션을 지운다. 실행 시간 통계에 쓰므로 기간 안의 세션은 남긴다. */
    @Query(
        """DELETE FROM sessions WHERE id != :keep AND COALESCE(lastSeenEpochMillis, startedAtEpochMillis) < :cutoff
        AND id NOT IN (SELECT DISTINCT sessionId FROM transactions) AND id NOT IN (SELECT DISTINCT sessionId FROM app_events)""",
    )
    fun deleteEmptySessions(keep: String, cutoff: Long)
}
