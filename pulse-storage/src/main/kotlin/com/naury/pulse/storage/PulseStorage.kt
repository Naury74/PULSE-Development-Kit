package com.naury.pulse.storage

import android.content.Context
import androidx.room.Room
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 마스킹된 트랜잭션을 Room DB와 본문 파일로 보관한다. 저장 위치는 백업 대상이 아닌 `noBackupFilesDir/pulse`다.
 *
 * [sink]는 저장 스레드에서만 쓰고, 조회 함수는 어느 스레드에서 불러도 된다. [startSession]·[clear]는 디스크 작업이므로
 * 메인 스레드에서 부르지 않는다.
 */
public class PulseStorage private constructor(
    private val database: PulseDatabase,
    blobs: BlobStore,
    retention: RetentionPolicy,
    wallClock: () -> Long,
) {
    private val writer = CaptureWriter(database, blobs, retention, wallClock)
    private val reader = TransactionReader(database.transactions(), blobs)

    public val sink: CaptureSink = writer

    public fun startSession(sessionId: String, startedAtEpochMillis: Long) {
        writer.startSession(sessionId, startedAtEpochMillis)
    }

    public suspend fun loadTransactions(query: TransactionQuery, after: PageCursor?, limit: Int): TransactionPage =
        reader.page(query, after, limit)

    public suspend fun loadTransaction(id: String): TransactionDetail? = reader.detail(id)

    public suspend fun loadBody(id: String, kind: MessageKind): String? = reader.body(id, kind)

    public fun observeChanges(): Flow<Unit> =
        database.invalidationTracker.createFlow("transactions", "messages", "timing_phases", emitInitialState = false).map { }

    public suspend fun clear() {
        withContext(Dispatchers.IO) { writer.clear() }
    }

    public fun close() {
        database.close()
    }

    public companion object {
        private const val DIRECTORY = "pulse"
        private const val DATABASE_FILE = "pulse.db"
        private const val BODY_DIRECTORY = "bodies"

        @JvmStatic
        public fun open(context: Context, retention: RetentionPolicy): PulseStorage {
            val root = File(context.applicationContext.noBackupFilesDir, DIRECTORY).apply { mkdirs() }
            val database = Room.databaseBuilder(context.applicationContext, PulseDatabase::class.java, File(root, DATABASE_FILE).path)
                .build()
            return PulseStorage(database, BlobStore(File(root, BODY_DIRECTORY)), retention, System::currentTimeMillis)
        }

        internal fun create(database: PulseDatabase, bodyDirectory: File, retention: RetentionPolicy, wallClock: () -> Long) =
            PulseStorage(database, BlobStore(bodyDirectory), retention, wallClock)
    }
}
