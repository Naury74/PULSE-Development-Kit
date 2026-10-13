package com.naury.pulse.storage

import android.content.Context
import androidx.room.Room
import com.naury.pulse.analytics.TrafficSample
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PageCursor
import com.naury.pulse.api.RetentionPolicy
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
public class PulseStorage private constructor(
    databaseFactory: () -> PulseDatabase,
    bodyDirectory: () -> File,
    retention: RetentionPolicy,
    wallClock: () -> Long,
) {
    private val databaseHolder = lazy(databaseFactory)
    private val database by databaseHolder
    private val blobs by lazy { BlobStore(bodyDirectory()) }
    private val writer by lazy { CaptureWriter(database, blobs, retention, wallClock) }
    private val reader by lazy { TransactionReader(database.transactions(), blobs) }

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
    ): Pair<List<TrafficSample>, Boolean> = withContext(Dispatchers.IO) { reader.samples(fromEpochMillis, limit, includeSynthetic) }

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
        private const val DIRECTORY = "pulse"
        private const val DATABASE_FILE = "pulse.db"
        private const val BODY_DIRECTORY = "bodies"

        @JvmStatic
        public fun open(context: Context, retention: RetentionPolicy): PulseStorage {
            val app = context.applicationContext
            val root = lazy { File(app.noBackupFilesDir, DIRECTORY).apply { mkdirs() } }
            return PulseStorage(
                databaseFactory = { Room.databaseBuilder(app, PulseDatabase::class.java, File(root.value, DATABASE_FILE).path).build() },
                bodyDirectory = { File(root.value, BODY_DIRECTORY) },
                retention = retention,
                wallClock = System::currentTimeMillis,
            )
        }

        internal fun create(database: PulseDatabase, bodyDirectory: File, retention: RetentionPolicy, wallClock: () -> Long) =
            PulseStorage({ database }, { bodyDirectory }, retention, wallClock)
    }
}
