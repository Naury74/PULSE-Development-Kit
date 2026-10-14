package com.naury.pulse.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.LogLevel
import org.json.JSONObject

/** 원격으로 보낼 보고 하나. [payload]는 마스킹을 마친 JSON 객체 문자열이다. */
@Entity(tableName = "outbox", indices = [Index("nextAttemptEpochMillis")])
internal data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAtEpochMillis: Long,
    val payload: String,
    val attempts: Int = 0,
    val nextAttemptEpochMillis: Long = 0,
)

@Dao
internal interface OutboxDao {
    @Insert
    fun insert(entry: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE nextAttemptEpochMillis <= :now ORDER BY id LIMIT :limit")
    fun due(now: Long, limit: Int): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    fun delete(ids: List<Long>)

    @Query("UPDATE outbox SET attempts = attempts + 1, nextAttemptEpochMillis = :next WHERE id IN (:ids)")
    fun retryLater(ids: List<Long>, next: Long)

    @Query("SELECT COUNT(*) FROM outbox")
    fun count(): Int

    @Query("DELETE FROM outbox WHERE id IN (SELECT id FROM outbox ORDER BY id LIMIT :count)")
    fun dropOldest(count: Int)

    @Query("DELETE FROM outbox WHERE attempts >= :maxAttempts")
    fun dropExhausted(maxAttempts: Int)

    @Query("DELETE FROM outbox")
    fun deleteAll()
}

/**
 * 어떤 기록을 보낼 목록에 넣을지. 저장 스레드에서 매번 [enabled]를 읽으므로 실행 중에 켜고 끈 값이 바로 반영된다.
 */
public class OutboxFilter(
    public val kinds: Set<EventKind>,
    public val minLogLevel: LogLevel?,
    public val networkFailures: Boolean,
    public val maxPending: Int,
    public val enabled: () -> Boolean,
) {
    internal fun accepts(kind: EventKind, level: LogLevel?): Boolean {
        if (!enabled()) return false
        if (kind == EventKind.LOG) return minLogLevel != null && level != null && level.ordinal >= minLogLevel.ordinal
        return kind in kinds
    }
}

/** 보낼 목록에 넣을 JSON. 저장한 기록과 같은 마스킹된 값만 쓴다. */
internal object OutboxPayloads {
    fun event(entity: EventEntity): String = JSONObject()
        .put("type", "event")
        .put("id", entity.id)
        .put("sessionId", entity.sessionId)
        .put("time", entity.timestampEpochMillis)
        .put("kind", entity.kind.name)
        .putOpt("level", entity.level?.name)
        .putOpt("tag", entity.tag)
        .put("message", entity.message)
        .putOpt("detail", entity.detail)
        .putOpt("screen", entity.screen)
        .putOpt("durationMillis", entity.durationMillis)
        .putOpt("value", entity.value)
        .toString()

    fun networkFailure(transaction: TransactionEntity): String = JSONObject()
        .put("type", "network")
        .put("id", transaction.id)
        .put("sessionId", transaction.sessionId)
        .put("time", transaction.startedAtEpochMillis)
        .put("method", transaction.method)
        .put("url", transaction.url)
        .put("state", transaction.state.name)
        .putOpt("statusCode", transaction.statusCode)
        .putOpt("durationNanos", transaction.durationNanos)
        .putOpt("failureType", transaction.failureType)
        .putOpt("failureMessage", transaction.failureMessage)
        .putOpt("screen", transaction.screen)
        .putOpt("label", transaction.label)
        .toString()
}
