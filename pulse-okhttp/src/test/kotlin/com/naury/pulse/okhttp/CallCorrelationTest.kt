package com.naury.pulse.okhttp

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.TimingPhaseType
import com.naury.pulse.api.TransactionState
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Cache
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CallCorrelationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `동시에 20개를 보내도 ID가 겹치지 않고 각 응답이 자기 요청에 붙는다`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .body("""{"path":"${request.url.encodedPath}"}""")
                .build()
        }
        val harness = CaptureHarness()
        val client = harness.client()
        val pool = Executors.newFixedThreadPool(20)

        val bodies = (1..20).map { index ->
            pool.submit(
                Callable {
                    client.newCall(Request.Builder().url(server.url("/item/$index")).build()).execute().use { it.body.string() }
                },
            )
        }.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        val captured = harness.finish()

        assertThat(bodies).containsExactlyElementsIn((1..20).map { """{"path":"/item/$it"}""" })
        val ids = captured.requests.map { it.transactionId }
        assertThat(ids.toSet()).hasSize(20)
        ids.forEach { id ->
            val path = captured.request(id).path
            assertThat(captured.body(MessageKind.RESPONSE, id).text).isEqualTo("""{"path":"$path"}""")
            assertThat(captured.finish(id).state).isEqualTo(TransactionState.COMPLETED)
        }
    }

    @Test
    fun `리다이렉트는 하나의 트랜잭션에 교환 두 개의 타이밍으로 남는다`() {
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/final").build())
        server.enqueue(MockResponse.Builder().body("{}").setHeader("Content-Type", "application/json").build())
        val harness = CaptureHarness()

        harness.client().newCall(Request.Builder().url(server.url("/start")).build()).execute().use { it.body.string() }
        val captured = harness.finish()

        assertThat(captured.requests).hasSize(1)
        assertThat(captured.response().statusCode).isEqualTo(200)
        val phases = requireNotNull(captured.timing()).phases
        assertThat(phases.filter { it.type == TimingPhaseType.REQUEST_HEADERS }.map { it.exchangeIndex }).containsExactly(0, 1).inOrder()
        // fast fallback이 여러 주소로 동시에 연결을 시도하면 CONNECT가 여러 개일 수 있다. 같은 서버라 두 번째 교환은 연결을 재사용한다.
        assertThat(phases.filter { it.type == TimingPhaseType.CONNECT }.map { it.exchangeIndex }.toSet()).containsExactly(0)
    }

    @Test
    fun `재사용한 연결에서는 DNS·연결 단계를 만들어 내지 않는다`() {
        repeat(2) { server.enqueue(MockResponse.Builder().body("{}").build()) }
        val harness = CaptureHarness()
        val client = harness.client()

        repeat(2) { client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body.string() } }
        val captured = harness.finish()

        val (first, second) = captured.requests.map { requireNotNull(captured.timing(it.transactionId)).phases }
        assertThat(first.map { it.type }).contains(TimingPhaseType.CONNECT)
        assertThat(second.map { it.type }).containsNoneOf(TimingPhaseType.DNS, TimingPhaseType.CONNECT, TimingPhaseType.TLS)
        assertThat(second.map { it.type }).contains(TimingPhaseType.RESPONSE_HEADERS)
        second.forEach { phase ->
            assertThat(phase.startOffsetNanos).isAtLeast(0)
            assertThat(phase.durationNanos).isNotNull()
        }
    }

    @Test
    fun `캐시 응답에는 네트워크 단계가 없다`() {
        server.enqueue(MockResponse.Builder().setHeader("Cache-Control", "max-age=60").body("""{"v":1}""").build())
        val harness = CaptureHarness()
        val client = harness.client(OkHttpClient.Builder().cache(Cache(temporaryFolder.newFolder(), 1024 * 1024)))

        val bodies = List(2) { client.newCall(Request.Builder().url(server.url("/c")).build()).execute().use { it.body.string() } }
        val captured = harness.finish()

        assertThat(bodies).containsExactly("""{"v":1}""", """{"v":1}""")
        assertThat(server.requestCount).isEqualTo(1)
        val cachedId = captured.requests[1].transactionId
        assertThat(captured.timing(cachedId)).isNull()
        assertThat(captured.finish(cachedId).state).isEqualTo(TransactionState.COMPLETED)
    }

    @Test
    fun `호스트의 기존 EventListener도 이벤트를 받는다`() {
        server.enqueue(MockResponse.Builder().body("{}").build())
        val hostEvents = AtomicInteger()
        val hostFactory = EventListener.Factory {
            object : EventListener() {
                override fun callEnd(call: Call) {
                    hostEvents.incrementAndGet()
                }
            }
        }
        val harness = CaptureHarness()

        harness.client(hostListener = hostFactory).newCall(Request.Builder().url(server.url("/")).build()).execute().use {
            it.body.string()
        }
        val captured = harness.finish()

        assertThat(hostEvents.get()).isEqualTo(1)
        assertThat(captured.timing()).isNotNull()
    }
}
