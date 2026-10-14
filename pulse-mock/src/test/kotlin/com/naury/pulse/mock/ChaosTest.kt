package com.naury.pulse.mock

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.ChaosAction
import com.naury.pulse.api.ChaosPolicy
import com.naury.pulse.api.ChaosRule
import com.naury.pulse.core.MockMarker
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class ChaosTest {
    private val server = MockWebServer()
    private lateinit var chaos: ChaosRegistry
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.start()
        chaos = ChaosRegistry(ChaosPolicy(setOf(server.hostName)), Random(1))
        client = OkHttpClient.Builder().addInterceptor(MockInterceptor(MockRegistry(), chaos)).build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun call(path: String = "/api/a") = client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    @Test
    fun `꺼져 있으면 규칙이 있어도 진짜 서버로 보낸다`() {
        chaos.upsert(ChaosRule(server.hostName, ChaosAction.STATUS_503))
        server.enqueue(MockResponse.Builder().code(200).build())

        assertThat(call().use { it.code }).isEqualTo(200)
    }

    @Test
    fun `상태 코드 장애는 보내지 않고 표시가 붙은 응답을 돌려준다`() {
        chaos.upsert(ChaosRule(server.hostName, ChaosAction.STATUS_503, pathPrefix = "/api"))
        chaos.setEnabled(true)

        call().use { response ->
            assertThat(response.code).isEqualTo(503)
            assertThat(response.header("Retry-After")).isEqualTo("5")
            assertThat(response.header(MockMarker.HEADER)).isNotNull()
            assertThat(response.header(MockInterceptor.CHAOS_HEADER)).isEqualTo("STATUS_503")
        }
        assertThat(server.requestCount).isEqualTo(0)

        server.enqueue(MockResponse.Builder().code(200).build())
        assertThat(call("/other").use { it.code }).isEqualTo(200)
    }

    @Test
    fun `연결 실패와 시간 초과는 예외로 끝낸다`() {
        chaos.setEnabled(true)
        chaos.upsert(ChaosRule(server.hostName, ChaosAction.FAIL_IO, id = "r"))
        assertThrows(IOException::class.java) { call() }

        chaos.upsert(ChaosRule(server.hostName, ChaosAction.TIMEOUT, delayMillis = 10, id = "r"))
        assertThrows(SocketTimeoutException::class.java) { call() }
    }

    @Test
    fun `지연은 늦춘 뒤 진짜로 보내고 확률 0이면 적용하지 않는다`() {
        chaos.setEnabled(true)
        chaos.upsert(ChaosRule(server.hostName, ChaosAction.DELAY, delayMillis = 200, id = "d"))
        server.enqueue(MockResponse.Builder().code(200).build())
        val started = System.nanoTime()
        assertThat(call().use { it.code }).isEqualTo(200)
        assertThat((System.nanoTime() - started) / 1_000_000).isAtLeast(200)

        chaos.upsert(ChaosRule(server.hostName, ChaosAction.STATUS_500, probability = 0f, id = "d"))
        server.enqueue(MockResponse.Builder().code(200).build())
        assertThat(call().use { it.code }).isEqualTo(200)
    }

    @Test
    fun `허용하지 않은 호스트의 규칙은 만들 수 없다`() {
        assertThrows(IllegalArgumentException::class.java) { chaos.upsert(ChaosRule("api.prod.example.com", ChaosAction.FAIL_IO)) }
    }
}
