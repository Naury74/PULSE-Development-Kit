package com.naury.pulse.okhttp

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.CaptureLimits
import com.naury.pulse.api.CaptureMode
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import com.naury.pulse.api.TransactionState
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class PulseInterceptorTest {
    private val server = MockWebServer()
    private val json = "application/json; charset=utf-8".toMediaType()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun get(path: String = "/") = Request.Builder().url(server.url(path)).build()

    private fun jsonResponse(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `GET 200 JSON - 호스트 본문은 그대로이고 마스킹한 본문을 저장한다`() {
        val body = """{"id":1,"accessToken":"secret-token"}"""
        server.enqueue(jsonResponse(body))
        server.enqueue(jsonResponse(body))
        val harness = CaptureHarness()

        val withInspector = harness.client().newCall(get("/users?token=secret-query")).execute().use { it.body.string() }
        val without = OkHttpClient().newCall(get("/users?token=secret-query")).execute().use { it.body.string() }
        val captured = harness.finish()

        assertThat(withInspector).isEqualTo(without)
        assertThat(withInspector).isEqualTo(body)
        assertThat(captured.request().method).isEqualTo("GET")
        assertThat(captured.request().url).endsWith("/users?token=$REDACTED")
        assertThat(captured.response().statusCode).isEqualTo(200)
        val stored = captured.body(MessageKind.RESPONSE)
        assertThat(stored.state).isEqualTo(BodyState.CAPTURED)
        assertThat(stored.text).isEqualTo("""{"id":1,"accessToken":"$REDACTED"}""")
        assertThat(stored.transferredBytes).isEqualTo(body.length.toLong())
        assertThat(captured.body(MessageKind.REQUEST).state).isEqualTo(BodyState.EMPTY)
        assertThat(captured.finish().state).isEqualTo(TransactionState.COMPLETED)
        assertThat(captured.storedText()).doesNotContain("secret")
    }

    @Test
    fun `POST JSON - 서버는 원래 본문을 한 번 받고 저장 본문은 마스킹된다`() {
        server.enqueue(jsonResponse("{}"))
        val harness = CaptureHarness()
        val payload = """{"email":"a@test","password":"hunter2"}"""

        harness.client().newCall(
            Request.Builder().url(server.url("/login"))
                .header("Authorization", "Bearer secret-bearer")
                .post(payload.toRequestBody(json))
                .build(),
        ).execute().close()
        val captured = harness.finish()

        val received = server.takeRequest()
        assertThat(received.body?.utf8()).isEqualTo(payload)
        assertThat(received.headers["Authorization"]).isEqualTo("Bearer secret-bearer")
        val requestBody = captured.body(MessageKind.REQUEST)
        assertThat(requestBody.state).isEqualTo(BodyState.CAPTURED)
        assertThat(requestBody.text).isEqualTo("""{"email":"a@test","password":"$REDACTED"}""")
        assertThat(requestBody.transferredBytes).isEqualTo(payload.length.toLong())
        assertThat(captured.request().headers.single { it.name == "Authorization" }.value).isEqualTo(REDACTED)
        assertThat(captured.storedText()).doesNotContain("hunter2")
        assertThat(captured.storedText()).doesNotContain("secret-bearer")
    }

    @Test
    fun `one-shot 요청 본문은 한 번만 쓰고 저장하지 않는다`() {
        server.enqueue(jsonResponse("{}"))
        val writes = AtomicInteger()
        val oneShot = object : RequestBody() {
            override fun contentType() = json

            override fun isOneShot() = true

            override fun writeTo(sink: BufferedSink) {
                writes.incrementAndGet()
                sink.writeUtf8("""{"a":1}""")
            }
        }
        val harness = CaptureHarness()

        harness.client().newCall(Request.Builder().url(server.url("/")).post(oneShot).build()).execute().close()
        val captured = harness.finish()

        assertThat(writes.get()).isEqualTo(1)
        assertThat(server.takeRequest().body?.utf8()).isEqualTo("""{"a":1}""")
        assertThat(captured.body(MessageKind.REQUEST).state).isEqualTo(BodyState.ONE_SHOT_UNSUPPORTED)
    }

    @Test
    fun `4xx·5xx는 응답으로 완료되고 실패와 구분된다`() {
        server.enqueue(jsonResponse("""{"error":"unauthorized"}""", code = 401))
        server.enqueue(jsonResponse("""{"error":"boom"}""", code = 500))
        val harness = CaptureHarness()
        val client = harness.client()

        val codes = listOf("/a", "/b").map { path -> client.newCall(get(path)).execute().use { it.code } }
        val captured = harness.finish()

        assertThat(codes).containsExactly(401, 500).inOrder()
        assertThat(captured.responses.map { it.statusCode }).containsExactly(401, 500)
        assertThat(captured.finishes.map { it.state }).containsExactly(TransactionState.COMPLETED, TransactionState.COMPLETED)
        assertThat(captured.finishes.all { it.failureType == null }).isTrue()
    }

    @Test
    fun `연결 실패는 같은 예외로 호스트에 전달되고 FAILED로 기록된다`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val harness = CaptureHarness()

        assertThrows(ConnectException::class.java) {
            harness.client().newCall(Request.Builder().url("http://127.0.0.1:$closedPort/").build()).execute()
        }
        val captured = harness.finish()

        val finish = captured.finish()
        assertThat(finish.state).isEqualTo(TransactionState.FAILED)
        assertThat(finish.failureType).isEqualTo(ConnectException::class.java.name)
        assertThat(captured.responses).isEmpty()
    }

    @Test
    fun `응답 대기 시간 초과는 FAILED로 기록된다`() {
        server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body("{}").build())
        val harness = CaptureHarness()
        val client = harness.client(OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS))

        assertThrows(SocketTimeoutException::class.java) { client.newCall(get()).execute() }
        val captured = harness.finish()

        assertThat(captured.finish().state).isEqualTo(TransactionState.FAILED)
        assertThat(captured.finish().failureType).isEqualTo(SocketTimeoutException::class.java.name)
    }

    @Test
    fun `호스트가 취소하면 CANCELED로 기록하고 예외는 그대로 전달한다`() {
        server.enqueue(MockResponse.Builder().headersDelay(5, TimeUnit.SECONDS).body("{}").build())
        val harness = CaptureHarness()
        val call = harness.client().newCall(get())

        Thread {
            Thread.sleep(200)
            call.cancel()
        }.start()
        assertThrows(IOException::class.java) { call.execute() }
        val captured = harness.finish()

        assertThat(captured.finish().state).isEqualTo(TransactionState.CANCELED)
    }

    @Test
    fun `호스트가 일부만 읽고 닫으면 더 읽지 않고 INCOMPLETE_READ로 남긴다`() {
        val large = "[" + (1..50_000).joinToString(",") { "\"item-$it\"" } + "]"
        server.enqueue(jsonResponse(large))
        val harness = CaptureHarness()

        val prefix = harness.client().newCall(get()).execute().use { response -> response.body.source().readUtf8(10) }
        val captured = harness.finish()

        assertThat(prefix).isEqualTo(large.take(10))
        assertThat(captured.body(MessageKind.RESPONSE).state).isEqualTo(BodyState.INCOMPLETE_READ)
        assertThat(captured.finish().state).isEqualTo(TransactionState.COMPLETED)
    }

    @Test
    fun `OkHttp가 푼 gzip 응답은 풀린 본문을 저장한다`() {
        val body = """{"name":"pulse","session_id":"secret"}"""
        val gzipped = Buffer().also { buffer -> GzipSink(buffer).buffer().use { it.writeUtf8(body) } }
        server.enqueue(
            MockResponse.Builder().setHeader(
                "Content-Type",
                "application/json",
            ).setHeader("Content-Encoding", "gzip").body(gzipped).build(),
        )
        val harness = CaptureHarness()

        val received = harness.client().newCall(get()).execute().use { it.body.string() }
        val captured = harness.finish()

        assertThat(received).isEqualTo(body)
        assertThat(captured.body(MessageKind.RESPONSE).text).isEqualTo("""{"name":"pulse","session_id":"$REDACTED"}""")
        assertThat(captured.body(MessageKind.RESPONSE).transferredBytes).isEqualTo(body.length.toLong())
    }

    @Test
    fun `호스트가 직접 압축을 요청한 응답은 압축된 채로 전달하고 저장하지 않는다`() {
        val gzipped = Buffer().also { buffer -> GzipSink(buffer).buffer().use { it.writeUtf8("""{"a":1}""") } }
        server.enqueue(
            MockResponse.Builder().setHeader("Content-Type", "application/json").setHeader("Content-Encoding", "gzip").body(gzipped.copy())
                .build(),
        )
        val harness = CaptureHarness()

        val received = harness.client().newCall(Request.Builder().url(server.url("/")).header("Accept-Encoding", "gzip").build())
            .execute().use { it.body.bytes() }
        val captured = harness.finish()

        assertThat(received).isEqualTo(gzipped.readByteArray())
        assertThat(captured.body(MessageKind.RESPONSE).state).isEqualTo(BodyState.UNSUPPORTED_CONTENT)
    }

    @Test
    fun `상한을 넘는 응답은 끝까지 전달하되 본문은 저장하지 않는다`() {
        val large = """{"data":"${"x".repeat(5_000)}"}"""
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").chunkedBody(large, 512).build())
        val config = PulseConfig.debugDefaults().newBuilder().limits(CaptureLimits(maxResponseBodyBytes = 1_000)).build()
        val harness = CaptureHarness(config)

        val received = harness.client().newCall(get()).execute().use { it.body.string() }
        val captured = harness.finish()

        assertThat(received).isEqualTo(large)
        val stored = captured.body(MessageKind.RESPONSE)
        assertThat(stored.state).isEqualTo(BodyState.TOO_LARGE)
        assertThat(stored.transferredBytes).isEqualTo(large.length.toLong())
        assertThat(stored.text).isNull()
    }

    @Test
    fun `메타데이터 모드에서는 본문을 저장하지 않고 크기만 센다`() {
        server.enqueue(jsonResponse("""{"a":1}"""))
        val harness = CaptureHarness(PulseConfig.defaults())

        harness.client().newCall(Request.Builder().url(server.url("/")).post("""{"b":2}""".toRequestBody(json)).build())
            .execute().use { it.body.string() }
        val captured = harness.finish()

        assertThat(captured.body(MessageKind.REQUEST).state).isEqualTo(BodyState.OMITTED_BY_POLICY)
        assertThat(captured.body(MessageKind.RESPONSE).state).isEqualTo(BodyState.OMITTED_BY_POLICY)
        assertThat(captured.body(MessageKind.RESPONSE).transferredBytes).isEqualTo(7)
        assertThat(PulseConfig.defaults().captureMode).isEqualTo(CaptureMode.METADATA_ONLY)
    }

    @Test
    fun `HEAD와 204는 본문을 기다리지 않고 바로 완료한다`() {
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().setHeader("Content-Length", "100").build())
        val harness = CaptureHarness()
        val client = harness.client()

        val noContent = client.newCall(get("/a")).execute()
        val head = client.newCall(Request.Builder().url(server.url("/b")).head().build()).execute()
        val captured = harness.finish()
        noContent.close()
        head.close()

        assertThat(captured.finishes.map { it.state }).containsExactly(TransactionState.COMPLETED, TransactionState.COMPLETED)
        assertThat(
            captured.records.filterIsInstance<com.naury.pulse.core.BodyRecord>().filter {
                it.kind == MessageKind.RESPONSE
            }.map { it.state },
        )
            .containsExactly(BodyState.EMPTY, BodyState.EMPTY)
    }

    @Test
    fun `기록을 멈추면 아무것도 남기지 않는다`() {
        server.enqueue(jsonResponse("{}"))
        val harness = CaptureHarness()
        harness.engine.recorder.controller.pause()

        harness.client().newCall(get()).execute().use { it.body.string() }
        val captured = harness.finish()

        assertThat(captured.records).isEmpty()
    }
}
