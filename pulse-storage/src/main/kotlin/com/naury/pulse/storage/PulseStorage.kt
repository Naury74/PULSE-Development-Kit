package com.naury.pulse.storage

import android.content.Context
import androidx.room.Room
import com.naury.pulse.analytics.AppStatisticsInput
import com.naury.pulse.analytics.TrafficSample
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.EventPage
import com.naury.pulse.api.EventQuery
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.RetentionPolicy
import com.naury.pulse.api.SessionSummary
import com.naury.pulse.api.TransactionDetail
import com.naury.pulse.api.TransactionPage
import com.naury.pulse.api.TransactionQuery
import com.naury.pulse.core.CaptureSink
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 마스킹된 트랜잭션을 Room DB와 본문 파일로 보관한다. 저장 위치는 백업 대상이 아닌 `noBackupFilesDir/pulse`다.
 *
 * 만들 때는 디스크에 손대지 않고, DB와 폴더는 처음 쓸 때 연다. 조회 함수는 I/O 디스패처로 옮겨 실행하므로 어느 스레드에서 불러도 되고,
 * [sink]·[startSession]은 저장 스레드·백그라운드에서만 부른다.
 */
@Suppress("TooManyFunctions")
public class PulseStorage private constructor(
    databaseFactory: () -> PulseDatabase,
    bodyDirectory: () -> File,
    retention: RetentionPolicy,
    wallClock: () -> Long,
    outboxFilter: OutboxFilter? = null,
) {
    private val databaseHolder = lazy(databaseFactory)
    private val database by databaseHolder
    private val blobs by lazy { BlobStore(bodyDirectory()) }
    private val writer by lazy { CaptureWriter(database, blobs, retention, wallClock, outboxFilter) }
    private val reader by lazy { TransactionReader(database.transactions(), blobs) }
    private val eventReader by lazy { EventReader(database.events(), blobs) }

    public val sink: CaptureSink = CaptureSink { records -> writer.write(records) }

    public fun startSession(sessionId: String, startedAtEpochMillis: Long) {
        writer.startSession(sessionId, startedAtEpochMillis)
    }

    public suspend fun loadTransactions(query: TransactionQuery, after: PageCursor?, limit: Int): TransactionPage =
        withContext(Dispatchers.IO) { reader.page(query, after, limit) }

    public suspend fun loadTransaction(id: String): TransactionDetail? = withContext(Dispatchers.IO) { reader.detail(id) }

    public suspend fun loadBody(id: String, kind: MessageKind): String? = withContext(Dispatchers.IO) { reader.body(id, kind) }

    /** [fromEpochMillis] 이후 최근 기록을 [limit]개까지 읽는다. 두 번째 값은 상한에 걸렸는지다. */
    public suspend fun loadSamples(
        fromEpochMillis: Long,
        limit: Int,
        includeSynthetic: Boolean = false,
        toEpochMillis: Long = Long.MAX_VALUE,
    ): Pair<List<TrafficSample>, Boolean> = withContext(Dispatchers.IO) {
        reader.samples(fromEpochMillis, limit, includeSynthetic, toEpochMillis)
    }

    public suspend fun loadEvents(query: EventQuery, after: PageCursor?, limit: Int): EventPage =
        withContext(Dispatchers.IO) { eventReader.page(query, after, limit) }

    public suspend fun loadSessions(from: Long?, to: Long?, limit: Int, currentSessionId: String?): List<SessionSummary> =
        withContext(Dispatchers.IO) { eventReader.sessions(from, to, limit, currentSessionId) }

    /** 앱 상태 집계에 쓸 재료. 이벤트는 종류별로 최신 [eventLimit]개까지. */
    public suspend fun loadAppStatisticsInput(from: Long?, to: Long?, currentSessionId: String?, eventLimit: Int): AppStatisticsInput =
        withContext(Dispatchers.IO) {
            AppStatisticsInput(
                sessions = eventReader.sessions(from, to, MAX_SESSIONS, currentSessionId),
                events = eventReader.statisticsEvents(STATISTICS_KINDS, from, to, eventLimit),
                logLevels = eventReader.logLevels(from, to),
                requestTimes = eventReader.requestTimes(from, to),
            )
        }

    public suspend fun loadAttachment(name: String): ByteArray? = withContext(Dispatchers.IO) { eventReader.attachment(name) }

    /** 지금 세션 바로 전의 세션. 지난 실행이 끝난 이유를 붙일 때 쓴다. 저장 스레드·백그라운드에서만 부른다. */
    public fun previousSession(currentSessionId: String): SessionSummary? = eventReader.previousSession(currentSessionId)

    /** 보낼 차례가 된 보고를 오래된 순으로 [limit]개까지. 원격 보고 스레드에서 부른다. */
    public fun dueReports(now: Long, limit: Int): List<PendingReport> =
        database.outbox().due(now, limit).map { PendingReport(it.id, it.payload, it.attempts) }

    public fun reportsSent(ids: List<Long>) {
        database.outbox().delete(ids)
    }

    /** 보내지 못한 보고를 [nextAttemptEpochMillis]에 다시 보낸다. [maxAttempts]번 넘게 실패한 것은 버린다. */
    public fun reportsFailed(ids: List<Long>, nextAttemptEpochMillis: Long, maxAttempts: Int) {
        database.outbox().retryLater(ids, nextAttemptEpochMillis)
        database.outbox().dropExhausted(maxAttempts)
    }

    public fun pendingReports(): Int = database.outbox().count()

    /** 보낼 목록이 바뀔 때마다 알린다. 원격 보고가 새 보고를 바로 보내는 데 쓴다. */
    public fun observeReports(): Flow<Unit> = flow {
        emitAll(database.invalidationTracker.createFlow("outbox", emitInitialState = false))
    }.map { }.flowOn(Dispatchers.IO)

    public fun observeEventChanges(): Flow<Unit> = flow {
        emitAll(database.invalidationTracker.createFlow("app_events", "sessions", emitInitialState = false))
    }.map { }.flowOn(Dispatchers.IO)

    public fun observeChanges(): Flow<Unit> = flow {
        emitAll(database.invalidationTracker.createFlow("transactions", "messages", "timing_phases", emitInitialState = false))
    }.map { }.flowOn(Dispatchers.IO)

    public suspend fun clear() {
        withContext(Dispatchers.IO) { writer.clear() }
    }

    public fun close() {
        if (databaseHolder.isInitialized()) database.close()
    }

    public companion object {
        private const val MAX_SESSIONS = 5_000
        private val STATISTICS_KINDS = listOf(
            EventKind.CRASH, EventKind.ERROR, EventKind.MAIN_THREAD_BLOCKED, EventKind.EXIT, EventKind.STARTUP,
            EventKind.SCREEN_LOAD, EventKind.FRAMES, EventKind.MEMORY, EventKind.MARKER,
        )
        private const val DIRECTORY = "pulse"
        private const val DATABASE_FILE = "pulse.db"
        private const val BODY_DIRECTORY = "bodies"

        @JvmStatic
        @JvmOverloads
        public fun open(context: Context, retention: RetentionPolicy, outboxFilter: OutboxFilter? = null): PulseStorage {
            val app = context.applicationContext
            val root = lazy { File(app.noBackupFilesDir, DIRECTORY).apply { mkdirs() } }
            return PulseStorage(
                databaseFactory = { Room.databaseBuilder(app, PulseDatabase::class.java, File(root.value, DATABASE_FILE).path).build() },
                bodyDirectory = { File(root.value, BODY_DIRECTORY) },
                retention = retention,
                wallClock = System::currentTimeMillis,
                outboxFilter = outboxFilter,
            )
        }

        internal fun create(database: PulseDatabase, bodyDirectory: File, retention: RetentionPolicy, wallClock: () -> Long) =
            PulseStorage({ database }, { bodyDirectory }, retention, wallClock)
    }
}

/** 보낼 보고 하나. [payload]는 JSON 객체 문자열이다. */
public class PendingReport(public val id: Long, public val payload: String, public val attempts: Int)
