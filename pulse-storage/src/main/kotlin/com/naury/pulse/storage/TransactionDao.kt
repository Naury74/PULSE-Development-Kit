package com.naury.pulse.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.MessageKind
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

    @Query("UPDATE transactions SET statusCode = :statusCode, protocol = :protocol, responseHeadersNanos = :headersNanos WHERE id = :id")
    fun updateResponse(id: String, statusCode: Int, protocol: String, headersNanos: Long): Int

    @Query(
        """UPDATE messages SET bodyState = :state, blobFile = :blobFile, storedBodyBytes = :storedBytes,
        redactedValueCount = :redactedCount WHERE transactionId = :id AND kind = :kind""",
    )
    fun updateBody(id: String, kind: MessageKind, state: BodyState, blobFile: String?, storedBytes: Long?, redactedCount: Int): Int

    @Query("UPDATE transactions SET requestBodyBytes = :bytes WHERE id = :id")
    fun updateRequestBodyBytes(id: String, bytes: Long?)

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
        AND (:afterStarted IS NULL OR startedAtEpochMillis < :afterStarted
            OR (startedAtEpochMillis = :afterStarted AND id < :afterId))
        ORDER BY startedAtEpochMillis DESC, id DESC
        LIMIT :limit""",
    )
    suspend fun page(text: String, status: String, afterStarted: Long?, afterId: String?, limit: Int): List<TransactionEntity>

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

    @Query("DELETE FROM sessions WHERE id NOT IN (SELECT DISTINCT sessionId FROM transactions) AND id != :keep")
    fun deleteEmptySessions(keep: String)
}
