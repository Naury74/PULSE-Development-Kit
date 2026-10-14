package com.naury.pulse.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.naury.pulse.api.ReportKind
import com.naury.pulse.api.ReportStatus
import com.naury.pulse.api.Severity
import kotlinx.coroutines.flow.Flow

/** 버그 보고 하나. 글자는 저장 전에 마스킹한 것이고, [screenshot]은 마스킹을 마친 이미지 파일 이름이다. */
@Entity(tableName = "reports", indices = [Index("status", "nextAttemptEpochMillis"), Index("updatedAtEpochMillis")])
internal data class ReportEntity(
    @PrimaryKey val id: String,
    val kind: ReportKind,
    val status: ReportStatus,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val title: String,
    val description: String,
    val expected: String,
    val actual: String,
    /** 재현 단계 JSON 배열. */
    val stepsJson: String,
    val severity: Severity,
    val sessionId: String?,
    /** 근거 JSON 배열(`kind`, `id`, `title`, `included`). */
    val evidenceJson: String,
    val screenshot: String?,
    val destinationId: String?,
    val attempts: Int = 0,
    val nextAttemptEpochMillis: Long = 0,
    val lastError: String? = null,
)

@Dao
internal interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: ReportEntity)

    @Query("SELECT * FROM reports WHERE id = :id")
    suspend fun byId(id: String): ReportEntity?

    @Query("SELECT * FROM reports ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    fun observeAll(limit: Int): Flow<List<ReportEntity>>

    @Query("SELECT * FROM reports WHERE status = 'QUEUED' AND nextAttemptEpochMillis <= :now ORDER BY updatedAtEpochMillis LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<ReportEntity>

    @Query(
        """UPDATE reports SET status = :status, attempts = :attempts, nextAttemptEpochMillis = :nextAttempt, lastError = :lastError,
        destinationId = :destinationId, updatedAtEpochMillis = :now WHERE id = :id""",
    )
    @Suppress("LongParameterList")
    suspend fun updateStatus(
        id: String,
        status: ReportStatus,
        attempts: Int,
        nextAttempt: Long,
        lastError: String?,
        destinationId: String?,
        now: Long,
    )

    /** 보내던 중에 프로세스가 끝난 보고를 다시 보낼 목록으로 돌린다. */
    @Query("UPDATE reports SET status = 'QUEUED' WHERE status = 'SENDING'")
    suspend fun requeueSending(): Int

    @Query("DELETE FROM reports WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT screenshot FROM reports WHERE screenshot IS NOT NULL")
    fun screenshots(): List<String>
}
