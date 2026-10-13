package com.naury.pulse.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.TimingPhaseType
import com.naury.pulse.api.TransactionState

@Entity(tableName = "sessions")
internal data class SessionEntity(@PrimaryKey val id: String, val startedAtEpochMillis: Long)

@Entity(
    tableName = "transactions",
    indices = [
        Index("startedAtEpochMillis", "id"),
        Index("sessionId", "startedAtEpochMillis"),
        Index("host", "startedAtEpochMillis"),
        Index("statusCode", "startedAtEpochMillis"),
        Index("state", "startedAtEpochMillis"),
        Index("durationNanos"),
    ],
)
internal data class TransactionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val source: CaptureSource,
    val method: String,
    val url: String,
    val scheme: String,
    val host: String,
    val path: String,
    val startedAtEpochMillis: Long,
    val state: TransactionState,
    val durationNanos: Long? = null,
    val statusCode: Int? = null,
    val protocol: String? = null,
    val responseHeadersNanos: Long? = null,
    val failureType: String? = null,
    val failureMessage: String? = null,
    val requestBodyBytes: Long? = null,
    val responseBodyBytes: Long? = null,
)

@Entity(
    tableName = "messages",
    primaryKeys = ["transactionId", "kind"],
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("blobFile")],
)
internal data class MessageEntity(
    val transactionId: String,
    val kind: MessageKind,
    val contentType: String?,
    val declaredContentLength: Long?,
    val bodyState: BodyState,
    val blobFile: String? = null,
    val storedBodyBytes: Long? = null,
    val redactedValueCount: Int = 0,
)

@Entity(
    tableName = "headers",
    primaryKeys = ["transactionId", "kind", "position"],
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class HeaderEntity(val transactionId: String, val kind: MessageKind, val position: Int, val name: String, val value: String)

@Entity(
    tableName = "timing_phases",
    primaryKeys = ["transactionId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class TimingEntity(
    val transactionId: String,
    val position: Int,
    val phase: TimingPhaseType,
    val exchangeIndex: Int,
    val startOffsetNanos: Long,
    val durationNanos: Long?,
)

internal data class BlobUsage(val transactionId: String, val blobFile: String?, val storedBodyBytes: Long?)
