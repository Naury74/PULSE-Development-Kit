package com.naury.pulse.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.naury.pulse.api.EventKind

@Dao
internal interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(event: EventEntity)

    @Query(
        """SELECT * FROM app_events
        WHERE (:anyKind OR kind IN (:kinds))
        AND (:minRank IS NULL OR levelRank IS NULL OR levelRank >= :minRank)
        AND (:text = '' OR message LIKE '%' || :text || '%' ESCAPE '\' OR tag LIKE '%' || :text || '%' ESCAPE '\'
            OR detail LIKE '%' || :text || '%' ESCAPE '\')
        AND (:tag IS NULL OR tag = :tag)
        AND (:from IS NULL OR timestampEpochMillis >= :from)
        AND (:to IS NULL OR timestampEpochMillis <= :to)
        AND (:sessionId IS NULL OR sessionId = :sessionId)
        AND (:afterTimestamp IS NULL OR timestampEpochMillis < :afterTimestamp
            OR (timestampEpochMillis = :afterTimestamp AND id < :afterId))
        ORDER BY timestampEpochMillis DESC, id DESC
        LIMIT :limit""",
    )
    @Suppress("LongParameterList")
    suspend fun page(
        anyKind: Boolean,
        kinds: List<EventKind>,
        minRank: Int?,
        text: String,
        tag: String?,
        from: Long?,
        to: Long?,
        sessionId: String?,
        afterTimestamp: Long?,
        afterId: String?,
        limit: Int,
    ): List<EventEntity>

    /** 집계용. 기간 안의 지정한 종류만 최신순으로. */
    @Query(
        """SELECT * FROM app_events WHERE kind IN (:kinds)
        AND (:from IS NULL OR timestampEpochMillis >= :from) AND (:to IS NULL OR timestampEpochMillis <= :to)
        ORDER BY timestampEpochMillis DESC LIMIT :limit""",
    )
    suspend fun forStatistics(kinds: List<EventKind>, from: Long?, to: Long?, limit: Int): List<EventEntity>

    @Query(
        """SELECT level, COUNT(*) AS count FROM app_events WHERE kind = 'LOG' AND level IS NOT NULL
        AND (:from IS NULL OR timestampEpochMillis >= :from) AND (:to IS NULL OR timestampEpochMillis <= :to) GROUP BY level""",
    )
    suspend fun logLevelCounts(from: Long?, to: Long?): List<LevelCount>

    @Query(
        """SELECT * FROM sessions
        WHERE (:to IS NULL OR startedAtEpochMillis <= :to)
        AND (:from IS NULL OR COALESCE(lastSeenEpochMillis, startedAtEpochMillis) >= :from)
        ORDER BY startedAtEpochMillis DESC LIMIT :limit""",
    )
    suspend fun sessions(from: Long?, to: Long?, limit: Int): List<SessionEntity>

    @Query(
        """SELECT startedAtEpochMillis FROM transactions
        WHERE (:from IS NULL OR startedAtEpochMillis >= :from) AND (:to IS NULL OR startedAtEpochMillis <= :to)""",
    )
    suspend fun requestTimes(from: Long?, to: Long?): List<Long>

    @Query("SELECT * FROM sessions WHERE id != :current ORDER BY startedAtEpochMillis DESC LIMIT 1")
    fun previousSession(current: String): SessionEntity?

    @Query("INSERT OR IGNORE INTO sessions (id, startedAtEpochMillis, foregroundMillis, crashed) VALUES (:id, :startedAt, 0, 0)")
    fun ensureSession(id: String, startedAt: Long)

    @Query(
        """UPDATE sessions SET lastSeenEpochMillis = COALESCE(:lastSeen, lastSeenEpochMillis),
        foregroundMillis = COALESCE(:foreground, foregroundMillis), exitReason = COALESCE(:exitReason, exitReason),
        crashed = COALESCE(:crashed, crashed) WHERE id = :id""",
    )
    fun updateSession(id: String, lastSeen: Long?, foreground: Long?, exitReason: String?, crashed: Boolean?)

    @Query("SELECT attachment FROM app_events WHERE attachment IS NOT NULL")
    fun allAttachments(): List<String>

    @Query("SELECT COUNT(*) FROM app_events")
    fun count(): Int

    @Query("SELECT id, attachment FROM app_events WHERE timestampEpochMillis < :cutoff")
    fun olderThan(cutoff: Long): List<EventFile>

    @Query("SELECT id, attachment FROM app_events ORDER BY timestampEpochMillis ASC, id ASC LIMIT :limit")
    fun oldest(limit: Int): List<EventFile>

    @Query("DELETE FROM app_events WHERE id IN (:ids)")
    fun delete(ids: List<String>)

    @Query("DELETE FROM app_events")
    fun deleteAll()
}

internal data class LevelCount(val level: String, val count: Int)

internal data class EventFile(val id: String, val attachment: String?)
