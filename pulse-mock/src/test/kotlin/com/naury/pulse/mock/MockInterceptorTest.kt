package com.naury.pulse.mock

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.MockResponseSpec
import com.naury.pulse.api.MockRule
import com.naury.pulse.api.PathMatch
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.core.CaptureEngine
import com.naury.pulse.core.CaptureRecord
import com.naury.pulse.core.CaptureSink
import com.naury.pulse.core.MockMarker
import com.naury.pulse.core.RequestRecord
import com.naury.pulse.core.ResponseRecord
import com.naury.pulse.okhttp.CallTimingRegistry
import com.naury.pulse.okhttp.PulseInterceptor
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class MockInterceptorTest {
    private val server = MockWebServer()
    private val registry = MockRegistry()
    private var now = 1_000L
    private val client = OkHttpClient.Builder().addInterceptor(MockInterceptor(registry) { now }).build()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun get(path: String) = client.newCall(Request.Builder().url(server.url(path)).build()).execute().use {
        it.code to
            it.body.string()
    }

    private fun rule(
        path: String,
        status: Int,
        match: PathMatch = PathMatch.EXACT,
        priority: Int = 0,
        uses: Int? = null,
        expires: Long? = null,
    ) = MockRule(
        path,
        MockResponseSpec(status, """{"mock":$status}"""),
        match,
        priority = priority,
        remainingUses = uses,
        expiresAtEpochMillis = expires,
    )

    @Test
    fun `전체 스위치가 꺼져 있으면 진짜 서버로 보낸다`() {
        server.enqueue(MockResponse.Builder().code(200).body("real").build())
        registry.upsert(rule("/a", 500))

        assertThat(get("/a")).isEqualTo(200 to "real")
        assertThat(registry.enabled.value).isFalse()
    }

    @Test
    fun `맞는 규칙은 네트워크 없이 표시가 붙은 합성 응답을 돌려준다`() {
        val mock = rule("/a", 503)
        registry.upsert(mock)
        registry.setEnabled(true)

        val response = client.newCall(Request.Builder().url(server.url("/a")).build()).execute()

        assertThat(response.code).isEqualTo(503)
        assertThat(response.header(MockMarker.HEADER)).isEqualTo(mock.id)
        assertThat(response.body.string()).isEqualTo("""{"mock":503}""")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `우선순위가 높은 규칙, 같으면 정확·접두·정규식 순으로 고른다`() {
        registry.upsert(rule("/users/.*", 501, PathMatch.REGEX))
        registry.upsert(rule("/users", 502, PathMatch.PREFIX))
        registry.upsert(rule("/users/1", 503))
        registry.upsert(rule("/users/2", 504, PathMatch.PREFIX, priority = 10))
        registry.setEnabled(true)

        assertThat(get("/users/1").first).isEqualTo(503)
        assertThat(get("/users/2").first).isEqualTo(504)
        assertThat(get("/users/3").first).isEqualTo(502)
    }

    @Test
    fun `사용 횟수가 끝나거나 만료되면 진짜 응답으로 돌아간다`() {
        repeat(2) { server.enqueue(MockResponse.Builder().code(200).body("real").build()) }
        registry.upsert(rule("/once", 500, uses = 1))
        registry.upsert(rule("/timed", 500, expires = 2_000))
        registry.setEnabled(true)

        assertThat(get("/once").first).isEqualTo(500)
        assertThat(get("/once").first).isEqualTo(200)
        assertThat(registry.rules.value.single { it.path == "/once" }.enabled).isFalse()
        now = 2_000
        assertThat(get("/timed").first).isEqualTo(200)
    }

    @Test
    fun `메서드·호스트 조건을 지킨다`() {
        server.enqueue(MockResponse.Builder().code(200).build())
        registry.upsert(MockRule("/a", MockResponseSpec(500), method = "post", host = server.hostName))
        registry.setEnabled(true)

        assertThat(get("/a").first).isEqualTo(200)
    }

    @Test
    fun `역추적이 폭주하는 정규식은 시간 예산을 넘으면 맞지 않는 것으로 본다`() {
        val start = System.nanoTime()

        val matched = MockInterceptor.regexMatches("(a+)+\$", "a".repeat(40) + "!")

        assertThat(matched).isFalse()
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000)
        assertThat(MockInterceptor.regexMatches("/users/\\d+", "/users/42")).isTrue()
    }

    @Test
    fun `잘못된 규칙은 만들 때 거절한다`() {
        assertThrows(IllegalArgumentException::class.java) { MockRule("(", MockResponseSpec(), PathMatch.REGEX) }
        assertThrows(IllegalArgumentException::class.java) { MockRule("a".repeat(300), MockResponseSpec(), PathMatch.REGEX) }
        assertThrows(IllegalArgumentException::class.java) { MockResponseSpec(statusCode = 700) }
        assertThrows(IllegalArgumentException::class.java) { MockRule("/a", MockResponseSpec(), remainingUses = 0) }
    }

    @Test
    fun `지연 중에 취소하면 IOException으로 끝난다`() {
        registry.upsert(MockRule("/slow", MockResponseSpec(delayMillis = 5_000)))
        registry.setEnabled(true)
        val call = client.newCall(Request.Builder().url(server.url("/slow")).build())
        Thread {
            Thread.sleep(200)
            call.cancel()
        }.start()

        assertThrows(IOException::class.java) { call.execute() }
    }

    @Test
    fun `PULSE 인터셉터 뒤에 붙이면 MOCK 출처로 기록된다`() {
        val records = CopyOnWriteArrayList<CaptureRecord>()
        val capture = CaptureEngine.start(PulseConfig.defaults(), CaptureSink { records += it })
        val recorded = OkHttpClient.Builder()
            .addInterceptor(PulseInterceptor(capture.recorder, CallTimingRegistry()))
            .addInterceptor(MockInterceptor(registry))
            .build()
        val mock = rule("/a", 418)
        registry.upsert(mock)
        registry.setEnabled(true)

        recorded.newCall(Request.Builder().url(server.url("/a")).build()).execute().use { it.body.string() }
        capture.close()

        assertThat(records.filterIsInstance<ResponseRecord>().single().mockRuleId).isEqualTo(mock.id)
        assertThat(records.filterIsInstance<RequestRecord>().single().source).isEqualTo(CaptureSource.ORIGINAL)
    }
}
