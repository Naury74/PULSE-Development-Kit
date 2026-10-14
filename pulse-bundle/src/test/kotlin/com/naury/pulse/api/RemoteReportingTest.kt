package com.naury.pulse.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteReportingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val original = Thread.getDefaultUncaughtExceptionHandler()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
        context.getSharedPreferences("com.naury.pulse.remote", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Pulse.current()?.close()
        Thread.setDefaultUncaughtExceptionHandler(original)
        server.close()
    }

    private fun config(remote: RemoteReporting) = PulseConfig.debugDefaults().newBuilder()
        .monitoring(AppMonitoring(logcat = false, mainThreadBlockMillis = 0, memoryIntervalMillis = 0, exitReasons = false))
        .remote(remote)
        .build()

    private fun remote(minLogLevel: LogLevel? = null) = RemoteReporting(
        endpoint = server.url("/collect").toString(),
        headers = mapOf("X-Api-Key" to "key-1"),
        minLogLevel = minLogLevel,
        allowCleartext = true,
        attributes = mapOf("build" to "test"),
    )

    @Test
    fun `오류와 경고 로그를 마스킹해 묶어 보내고 성공하면 목록에서 지운다`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).build())
        val handle = Pulse.initialize(context, config(remote(minLogLevel = LogLevel.WARN)))

        Pulse.log(LogLevel.DEBUG, "Net", "보내지 않는 로그")
        Pulse.log(LogLevel.WARN, "Auth", "retry password=secret-1")
        Pulse.recordError(IllegalStateException("bad state token=secret-2"))
        handle.remote.flushNow()

        val request = requireNotNull(server.takeRequest(10, TimeUnit.SECONDS))
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.headers["X-Api-Key"]).isEqualTo("key-1")
        val body = JSONObject(request.body!!.utf8())
        assertThat(body.getString("schema")).isEqualTo("pulse.remote.v1")
        assertThat(body.getJSONObject("app").getJSONObject("attributes").getString("build")).isEqualTo("test")
        val reports = body.getJSONArray("reports")
        val kinds = (0 until reports.length()).map { reports.getJSONObject(it).getString("kind") }
        assertThat(kinds).containsAtLeast("LOG", "ERROR")
        assertThat(reports.toString()).doesNotContain("secret")
        assertThat(reports.toString()).doesNotContain("보내지 않는 로그")

        val status = withTimeout(10_000) { handle.remote.status.first { it.sent >= reports.length() && it.pending == 0 } }
        assertThat(status.enabled).isTrue()
        assertThat(status.lastError).isNull()
    }

    @Test
    fun `실패하면 보고를 남기고 오류를 보여 준다`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(503).build())
        val handle = Pulse.initialize(context, config(remote()))

        Pulse.recordError(IllegalStateException("boom"))
        handle.remote.flushNow()

        assertThat(server.takeRequest(10, TimeUnit.SECONDS)).isNotNull()
        val status = withTimeout(10_000) { handle.remote.status.first { it.lastError != null } }
        assertThat(status.lastError).isEqualTo("HTTP 503")
        assertThat(status.pending).isAtLeast(1)
        assertThat(status.sent).isEqualTo(0)
    }

    @Test
    fun `이 기기에서 끄면 보낼 목록에 넣지 않고 다시 켜면 덮어쓴 주소로 보낸다`() = runBlocking<Unit> {
        val handle = Pulse.initialize(context, config(remote()))
        handle.remote.setOverride(enabled = false, endpoint = null, bearerToken = null)
        withTimeout(10_000) { handle.remote.status.first { !it.enabled && it.overridden } }

        Pulse.recordError(IllegalStateException("꺼져 있을 때"))
        delay(500)
        assertThat(handle.remote.status.value.pending).isEqualTo(0)

        server.enqueue(MockResponse.Builder().code(200).build())
        handle.remote.setOverride(enabled = true, endpoint = server.url("/other").toString(), bearerToken = "token-9")
        // flushNow 없이도 새 보고가 쌓이면 곧 보낸다.
        Pulse.recordError(IllegalStateException("켜진 뒤"))

        val request = requireNotNull(server.takeRequest(10, TimeUnit.SECONDS))
        assertThat(request.url.encodedPath).isEqualTo("/other")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer token-9")
        assertThat(request.body!!.utf8()).contains("켜진 뒤")
        assertThat(request.body!!.utf8()).doesNotContain("꺼져 있을 때")

        handle.remote.clearOverride()
        val status = withTimeout(10_000) { handle.remote.status.first { !it.overridden } }
        assertThat(status.endpoint).isEqualTo(server.url("/collect").toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `https가 아닌 주소는 allowCleartext 없이는 받지 않는다`() {
        val handle = Pulse.initialize(context, config(remote()).newBuilder().remote(null).build())
        handle.remote.setOverride(enabled = true, endpoint = "http://example.com/collect", bearerToken = null)
    }

    @Test
    fun `설정하지 않으면 꺼져 있고 보낼 목록에 넣지 않는다`() = runBlocking<Unit> {
        val handle = Pulse.initialize(context, config(remote()).newBuilder().remote(null).build())
        Pulse.recordError(IllegalStateException("no remote"))
        delay(500)
        val status = withTimeout(10_000) { handle.remote.status.first { !it.configured } }
        assertThat(status.enabled).isFalse()
        assertThat(status.pending).isEqualTo(0)
    }
}
