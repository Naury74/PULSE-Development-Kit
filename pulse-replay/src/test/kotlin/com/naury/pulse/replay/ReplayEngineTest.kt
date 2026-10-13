package com.naury.pulse.replay

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.HttpMessage
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import com.naury.pulse.api.ReplayAuthorizer
import com.naury.pulse.api.ReplayBlockReason
import com.naury.pulse.api.ReplayDraft
import com.naury.pulse.api.ReplayOutcome
import com.naury.pulse.api.ReplayPolicy
import com.naury.pulse.api.TransactionDetail
import com.naury.pulse.api.TransactionState
import com.naury.pulse.api.TransactionSummary
import com.naury.pulse.core.CaptureEngine
import com.naury.pulse.core.CaptureRecord
import com.naury.pulse.core.CaptureSink
import com.naury.pulse.core.RequestRecord
import com.naury.pulse.okhttp.CallTimingRegistry
import com.naury.pulse.okhttp.PulseInterceptor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class ReplayEngineTest {
    private val server = MockWebServer()
    private lateinit var policy: ReplayPolicy

    @Before
    fun setUp() {
        server.start()
        policy = ReplayPolicy(setOf(server.hostName), allowCleartext = true)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun engine(policy: ReplayPolicy = this.policy, client: OkHttpClient = OkHttpClient(), authorizer: ReplayAuthorizer? = null) =
        ReplayEngine(policy, client) { authorizer }

    private fun draft(method: String = "GET", path: String = "/items", body: String? = null, url: String = server.url(path).toString()) =
        ReplayDraft("origin", method, url, listOf(HeaderField("Accept", "application/json")), body, "application/json")

    @Test
    fun `허용 목록에 없는 호스트와 평문 http는 보내지 않는다`() = runBlocking {
        val httpsOnly = ReplayPolicy(setOf(server.hostName))
        val other = ReplayPolicy(setOf("api.example.com"), allowCleartext = true)

        val cleartext = engine(httpsOnly).execute(draft(), confirmed = true) as ReplayOutcome.Blocked
        val host = engine(other).execute(draft(), confirmed = true) as ReplayOutcome.Blocked

        assertThat(cleartext.reason).isEqualTo(ReplayBlockReason.SCHEME_NOT_ALLOWED)
        assertThat(host.reason).isEqualTo(ReplayBlockReason.HOST_NOT_ALLOWED)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `상태를 바꾸는 요청은 확인이 있어야 보낸다`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).build())

        val blocked = engine().execute(draft("POST", body = """{"a":1}"""), confirmed = false) as ReplayOutcome.Blocked
        val sent = engine().execute(draft("POST", body = """{"a":1}"""), confirmed = true) as ReplayOutcome.Completed

        assertThat(blocked.reason).isEqualTo(ReplayBlockReason.CONFIRMATION_REQUIRED)
        assertThat(sent.statusCode).isEqualTo(201)
        val received = server.takeRequest()
        assertThat(received.method).isEqualTo("POST")
        assertThat(received.body?.utf8()).isEqualTo("""{"a":1}""")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `자격 증명은 콜백이 준 것만 원래 호스트에 붙이고 확인이 필요하다`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())
        val authorizer = ReplayAuthorizer { listOf(HeaderField("Authorization", "Bearer fresh")) }

        val blocked = engine(authorizer = authorizer).execute(draft(), confirmed = false) as ReplayOutcome.Blocked
        engine(authorizer = authorizer).execute(draft(), confirmed = true)

        assertThat(blocked.reason).isEqualTo(ReplayBlockReason.CONFIRMATION_REQUIRED)
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer fresh")
    }

    @Test
    fun `허용하지 않은 호스트로의 리다이렉트는 따라가지 않는다`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "https://evil.example/steal").build())

        val outcome = engine().execute(draft(), confirmed = true) as ReplayOutcome.Blocked

        assertThat(outcome.reason).isEqualTo(ReplayBlockReason.REDIRECT_NOT_ALLOWED)
        assertThat(outcome.detail).isEqualTo("evil.example")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `허용한 호스트 안의 리다이렉트는 상한까지 따라가고 303은 GET으로 바꾼다`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(303).setHeader("Location", "/done").build())
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        repeat(3) { server.enqueue(MockResponse.Builder().code(307).setHeader("Location", "/loop").build()) }

        val followed = engine().execute(draft("POST", body = "{}"), confirmed = true) as ReplayOutcome.Completed
        val loop = engine(ReplayPolicy(setOf(server.hostName), allowCleartext = true, maxRedirects = 1)).execute(draft(), true)

        assertThat(followed.redirects).isEqualTo(1)
        assertThat(followed.statusCode).isEqualTo(200)
        server.takeRequest()
        assertThat(server.takeRequest().method).isEqualTo("GET")
        assertThat((loop as ReplayOutcome.Blocked).reason).isEqualTo(ReplayBlockReason.TOO_MANY_REDIRECTS)
    }

    @Test
    fun `본문 상한과 잘못된 요청을 거른다`() = runBlocking {
        val small = ReplayPolicy(setOf(server.hostName), allowCleartext = true, maxRequestBodyBytes = 2)

        val large = engine(small).execute(draft("POST", body = "{\"a\":1}"), confirmed = true) as ReplayOutcome.Blocked
        val invalid = engine().execute(draft(url = "not a url"), confirmed = true) as ReplayOutcome.Blocked
        val badMethod = engine().execute(draft(method = "GE T"), confirmed = true) as ReplayOutcome.Blocked

        assertThat(large.reason).isEqualTo(ReplayBlockReason.BODY_TOO_LARGE)
        assertThat(invalid.reason).isEqualTo(ReplayBlockReason.INVALID_REQUEST)
        assertThat(badMethod.reason).isEqualTo(ReplayBlockReason.INVALID_REQUEST)
    }

    @Test
    fun `취소하면 진행 중인 호출을 끊는다`() = runBlocking {
        server.enqueue(MockResponse.Builder().headersDelay(5, TimeUnit.SECONDS).build())
        val job = async { engine().execute(draft(), confirmed = true) }

        delay(200)
        job.cancel()
        delay(200)

        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun `시간 초과는 실패로 돌려준다`() = runBlocking {
        server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).build())
        val quick = ReplayPolicy(setOf(server.hostName), allowCleartext = true, timeoutMillis = 200)

        val outcome = engine(quick).execute(draft(), confirmed = true)

        assertThat(outcome).isInstanceOf(ReplayOutcome.Failed::class.java)
    }

    @Test
    fun `PULSE 인터셉터가 붙어 있으면 재실행 출처와 원본으로 기록된다`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val records = CopyOnWriteArrayList<CaptureRecord>()
        val capture = CaptureEngine.start(PulseConfig.defaults(), CaptureSink { records += it })
        val client = OkHttpClient.Builder().addInterceptor(PulseInterceptor(capture.recorder, CallTimingRegistry())).build()

        val outcome = engine(client = client).execute(draft(), confirmed = true) as ReplayOutcome.Completed
        capture.close()

        val request = records.filterIsInstance<RequestRecord>().single()
        assertThat(request.transactionId).isEqualTo(outcome.transactionId)
        assertThat(request.source).isEqualTo(CaptureSource.REPLAY)
        assertThat(request.parentTransactionId).isEqualTo("origin")
    }

    @Test
    fun `초안은 가려진 헤더·쿼리 값과 전송 헤더를 뺀다`() {
        val detail = TransactionDetail(
            summary = TransactionSummary(
                "t1", "s", "GET", "https://api.test/x?token=$REDACTED&page=2", "api.test", "/x", 0, 1, 200, TransactionState.COMPLETED,
                null, 0, 0, CaptureSource.ORIGINAL,
            ),
            protocol = "h2",
            responseHeadersNanos = null,
            failureMessage = null,
            request = HttpMessage(
                listOf(HeaderField("Authorization", REDACTED), HeaderField("Host", "api.test"), HeaderField("X-Trace", "abc")),
                null,
                null,
                BodyState.EMPTY,
                null,
                0,
            ),
            response = null,
            timings = emptyList(),
        )

        val draft = ReplayDrafts.from(detail, null)

        assertThat(draft.url).isEqualTo("https://api.test/x?page=2")
        assertThat(draft.headers).containsExactly(HeaderField("X-Trace", "abc"))
        assertThat(draft.removedHeaders).containsExactly("Authorization")
        assertThat(draft.mutatesState).isFalse()
    }

    @Test
    fun `와일드카드 호스트는 하위 도메인만 허용한다`() {
        val wildcard = ReplayPolicy(setOf("*.Example.com"))

        assertThat(wildcard.allowsHost("api.example.com")).isTrue()
        assertThat(wildcard.allowsHost("example.com")).isFalse()
        assertThat(wildcard.allowsHost("evilexample.com")).isFalse()
    }

    @Test
    fun `메인 스레드처럼 쓰는 단일 스레드에서 불러도 응답 본문은 그 스레드에서 읽지 않는다`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(10_000)).build())
        val readers = java.util.concurrent.CopyOnWriteArraySet<String>()
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val source = object : okio.ForwardingSource(response.body.source()) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    readers += Thread.currentThread().name
                    return super.read(sink, byteCount)
                }
            }
            response.newBuilder().body(
                source.buffer().asResponseBody(response.body.contentType(), response.body.contentLength()),
            ).build()
        }.build()
        val main = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()

        val outcome = kotlinx.coroutines.withContext(main) { engine(client = client).execute(draft(), confirmed = true) }
        main.close()

        assertThat(outcome).isInstanceOf(ReplayOutcome.Completed::class.java)
        assertThat(readers).isNotEmpty()
        assertThat(readers.filter { it.startsWith("fake-main") }).isEmpty()
    }
}
