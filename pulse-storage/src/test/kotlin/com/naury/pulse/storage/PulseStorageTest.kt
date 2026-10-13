package com.naury.pulse.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import com.naury.pulse.api.RetentionPolicy
import com.naury.pulse.api.StatusFilter
import com.naury.pulse.api.TimingPhase
import com.naury.pulse.api.TimingPhaseType
import com.naury.pulse.api.TransactionQuery
import com.naury.pulse.api.TransactionState
import com.naury.pulse.core.CaptureClock
import com.naury.pulse.core.CaptureEngine
import com.naury.pulse.core.CaptureRecorder
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PulseStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: PulseDatabase
    private lateinit var bodies: File
    private var now = 1_000_000L
    private val clock = object : CaptureClock {
        override fun wallMillis() = now

        override fun monoNanos() = System.nanoTime()
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PulseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        bodies = temporaryFolder.newFolder("bodies")
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun storage(retention: RetentionPolicy = RetentionPolicy()) = PulseStorage.create(database, bodies, retention) { now }

    /** 실제 수집 파이프라인으로 기록을 저장하고, 저장이 끝난 뒤 돌려준다. */
    private fun capture(storage: PulseStorage, sessionId: String = "s1", block: CaptureRecorder.() -> Unit) {
        val engine = CaptureEngine.start(PulseConfig.debugDefaults(), storage.sink, clock, sessionId)
        engine.recorder.block()
        engine.close()
    }

    private fun CaptureRecorder.transaction(
        id: String,
        startedAt: Long,
        url: String = "https://api.test/items/$id",
        status: Int? = 200,
        responseBody: String? = """{"id":"$id"}""",
        state: TransactionState = TransactionState.COMPLETED,
    ) {
        requestStarted(id, startedAt, "GET", url, "https", "api.test", "/items/$id", listOf("Authorization" to "Bearer secret"), null, null)
        bodyNotCaptured(id, MessageKind.REQUEST, BodyState.EMPTY, 0)
        if (status != null) {
            responseReceived(id, status, "h2", listOf("Content-Type" to "application/json"), "application/json", null, 5_000)
            if (responseBody != null) bodyCaptured(id, MessageKind.RESPONSE, responseBody.toByteArray(), "application/json")
        }
        if (state != TransactionState.IN_FLIGHT) finished(id, state, 9_000, if (status == null) IOException("reset") else null)
    }

    private fun page(storage: PulseStorage, query: TransactionQuery = TransactionQuery(), limit: Int = 100) =
        runBlocking { storage.loadTransactions(query, null, limit) }

    @Test
    fun `요청부터 완료까지 저장하고 상세·본문을 읽는다`() {
        val storage = storage()

        capture(storage) {
            transaction(
                "t1",
                startedAt = 10,
                url = "https://api.test/items/t1?token=secret",
                responseBody = """{"password":"secret","ok":true}""",
            )
            timings("t1", listOf(TimingPhase(TimingPhaseType.CONNECT, 0, 10, 20), TimingPhase(TimingPhaseType.TLS, 0, 15, null)))
        }

        val summary = page(storage).items.single()
        assertThat(summary.url).isEqualTo("https://api.test/items/t1?token=$REDACTED")
        assertThat(summary.statusCode).isEqualTo(200)
        assertThat(summary.state).isEqualTo(TransactionState.COMPLETED)
        assertThat(summary.durationNanos).isEqualTo(9_000)
        assertThat(summary.responseBodyBytes).isEqualTo("""{"password":"secret","ok":true}""".length.toLong())
        val detail = runBlocking { storage.loadTransaction("t1") }!!
        assertThat(detail.protocol).isEqualTo("h2")
        assertThat(detail.request.headers.single().value).isEqualTo(REDACTED)
        assertThat(detail.request.bodyState).isEqualTo(BodyState.EMPTY)
        assertThat(detail.response!!.bodyState).isEqualTo(BodyState.CAPTURED)
        assertThat(detail.response!!.redactedValueCount).isEqualTo(1)
        assertThat(detail.timings.map { it.type }).containsExactly(TimingPhaseType.CONNECT, TimingPhaseType.TLS).inOrder()
        assertThat(detail.timings[1].durationNanos).isNull()
        assertThat(runBlocking { storage.loadBody("t1", MessageKind.RESPONSE) }).isEqualTo("""{"password":"$REDACTED","ok":true}""")
        assertThat(runBlocking { storage.loadBody("t1", MessageKind.REQUEST) }).isNull()
        val stored = bodies.listFiles()!!.joinToString { it.readText() } + database.openHelper.readableDatabase.dump()
        assertThat(stored).doesNotContain("secret")
    }

    @Test
    fun `최신순으로 페이지를 나누고 겹치거나 빠지는 항목이 없다`() {
        val storage = storage()
        capture(storage) { (1..25).forEach { transaction("t%02d".format(it), startedAt = (it / 2).toLong()) } }

        val ids = ArrayList<String>()
        var cursor: com.naury.pulse.api.PageCursor? = null
        var pages = 0
        do {
            val page = runBlocking { storage.loadTransactions(TransactionQuery(), cursor, 10) }
            ids += page.items.map { it.id }
            cursor = page.next
            pages++
        } while (cursor != null)

        assertThat(pages).isEqualTo(3)
        assertThat(ids).hasSize(25)
        assertThat(ids.toSet()).hasSize(25)
        assertThat(ids.first()).isEqualTo("t25")
        assertThat(ids.last()).isEqualTo("t01")
    }

    @Test
    fun `URL 검색과 상태 필터`() {
        val storage = storage()
        capture(storage) {
            transaction("ok", 1, url = "https://api.test/Users/100%")
            transaction("client", 2, status = 404)
            transaction("server", 3, status = 503)
            transaction("failed", 4, status = null, state = TransactionState.FAILED)
            transaction("canceled", 5, status = null, state = TransactionState.CANCELED)
            transaction("pending", 6, status = null, state = TransactionState.IN_FLIGHT)
        }

        fun ids(query: TransactionQuery) = page(storage, query).items.map { it.id }

        assertThat(ids(TransactionQuery("users/100%"))).containsExactly("ok")
        assertThat(ids(TransactionQuery("_"))).isEmpty()
        assertThat(ids(TransactionQuery(status = StatusFilter.SUCCESS))).containsExactly("ok")
        assertThat(ids(TransactionQuery(status = StatusFilter.CLIENT_ERROR))).containsExactly("client")
        assertThat(ids(TransactionQuery(status = StatusFilter.SERVER_ERROR))).containsExactly("server")
        assertThat(ids(TransactionQuery(status = StatusFilter.FAILED))).containsExactly("canceled", "failed").inOrder()
        assertThat(ids(TransactionQuery(status = StatusFilter.IN_FLIGHT))).containsExactly("pending")
        assertThat(ids(TransactionQuery())).hasSize(6)
    }

    @Test
    fun `건수 상한을 넘으면 오래된 트랜잭션과 본문 파일을 지운다`() {
        val storage = storage(RetentionPolicy(maxTransactions = 10))
        capture(storage) { (1..15).forEach { transaction("t%02d".format(it), startedAt = it.toLong()) } }

        storage.startSession("s2", now)

        val ids = page(storage).items.map { it.id }
        assertThat(ids).hasSize(10)
        assertThat(ids.last()).isEqualTo("t06")
        assertThat(bodies.listFiles()!!.map { it.name.substringBefore('-') }.toSet()).isEqualTo(ids.toSet())
    }

    @Test
    fun `보관 기간이 지난 트랜잭션을 지운다`() {
        val storage = storage(RetentionPolicy(maxAgeMillis = 100))
        capture(storage) {
            transaction("old", startedAt = now - 500)
            transaction("new", startedAt = now - 50)
        }

        storage.startSession("s2", now)

        assertThat(page(storage).items.map { it.id }).containsExactly("new")
    }

    @Test
    fun `본문 용량 상한을 넘으면 오래된 것부터 지운다`() {
        val body = """{"data":"${"x".repeat(90)}"}"""
        val storage = storage(RetentionPolicy(maxBodyBytes = body.length * 3L))
        capture(storage) { (1..5).forEach { transaction("t$it", startedAt = it.toLong(), responseBody = body) } }

        storage.startSession("s2", now)

        assertThat(page(storage).items.map { it.id }).containsExactly("t5", "t4", "t3").inOrder()
        assertThat(bodies.listFiles()).hasLength(3)
    }

    @Test
    fun `새 세션을 시작하면 이전 세션의 진행 중 요청은 중단으로, 주인 없는 파일은 지운다`() {
        val storage = storage()
        capture(storage, sessionId = "s1") { transaction("pending", 1, state = TransactionState.IN_FLIGHT) }
        File(bodies, "orphan-response.json").writeText("{}")
        File(bodies, "half.partial").writeText("{")

        storage.startSession("s2", now)

        assertThat(page(storage).items.single().state).isEqualTo(TransactionState.INTERRUPTED)
        assertThat(bodies.listFiles()!!.map { it.name }).containsExactly("pending-response.json")
    }

    @Test
    fun `전체 삭제는 행과 본문 파일을 모두 지운다`() {
        val storage = storage()
        capture(storage) { transaction("t1", 1) }

        runBlocking { storage.clear() }

        assertThat(page(storage).items).isEmpty()
        assertThat(bodies.listFiles()).isEmpty()
    }

    @Test
    fun `없는 트랜잭션의 본문은 저장하지 않고 파일도 남기지 않는다`() {
        val storage = storage()

        capture(storage) { bodyCaptured("ghost", MessageKind.RESPONSE, """{"a":1}""".toByteArray(), "application/json") }

        assertThat(bodies.listFiles()).isEmpty()
        assertThat(runBlocking { storage.loadTransaction("ghost") }).isNull()
    }

    @Test
    fun `저장된 내용이 바뀌면 알린다`() = runBlocking {
        val storage = storage()
        // 수집을 시작하기 전에 구독이 등록되도록 바로 실행한다.
        val change = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { storage.observeChanges().first() }
        }

        capture(storage) { transaction("t1", 1) }

        change.await()
        Unit
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.dump(): String = buildString {
        listOf("transactions", "messages", "headers").forEach { table ->
            query("SELECT * FROM $table").use { cursor ->
                while (cursor.moveToNext()) {
                    (0 until cursor.columnCount).forEach { append(cursor.getStringOrNull(it)).append('|') }
                }
            }
        }
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
}
