package com.naury.pulse.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import com.naury.pulse.monitor.LogcatCollector
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppEventsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val original = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun tearDown() {
        Pulse.current()?.close()
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    private val config = PulseConfig.debugDefaults().newBuilder()
        .monitoring(AppMonitoring(logcat = false, mainThreadBlockMillis = 0, memoryIntervalMillis = 0))
        .build()

    private suspend fun PulseHandle.awaitEvents(count: Int, query: EventQuery = EventQuery()): List<AppEvent> = withTimeout(10_000) {
        var items = emptyList<AppEvent>()
        while (items.size < count) {
            delay(20)
            items = loadEvents(query, null, 100).items
        }
        items
    }

    @Test
    fun `로그·마커·구간·오류를 마스킹해 저장하고 거를 수 있다`() = runBlocking<Unit> {
        val handle = Pulse.initialize(context, config)

        Pulse.log(LogLevel.DEBUG, "Net", "calling https://api.test/me?token=secret-1")
        Pulse.log(LogLevel.WARN, "Auth", "retry password=secret-2")
        Pulse.mark("checkout pressed")
        val span = requireNotNull(Pulse.beginSpan("checkout"))
        Pulse.endSpan(span)
        Pulse.recordError(IllegalStateException("bad state"))

        val all = handle.awaitEvents(
            6,
            EventQuery(kinds = setOf(EventKind.LOG, EventKind.MARKER, EventKind.SPAN_START, EventKind.SPAN_END, EventKind.ERROR)),
        )
        assertThat(all.joinToString { it.message }).doesNotContain("secret")
        assertThat(all.map { it.message }).contains("calling https://api.test/me?token=$REDACTED")
        val warnings = handle.loadEvents(
            EventQuery(minLevel = LogLevel.WARN, kinds = setOf(EventKind.LOG, EventKind.ERROR)),
            null,
            50,
        ).items
        assertThat(warnings.map { it.kind }).containsExactly(EventKind.ERROR, EventKind.LOG)
        val end = all.single { it.kind == EventKind.SPAN_END }
        assertThat(end.spanId).isEqualTo(span)
        assertThat(end.durationMillis).isAtLeast(0)
        assertThat(handle.loadEvents(EventQuery(text = "checkout"), null, 50).items.map { it.kind })
            .containsExactly(EventKind.SPAN_END, EventKind.SPAN_START, EventKind.MARKER)
    }

    @Test
    fun `처리되지 않은 예외는 바로 저장하고 원래 핸들러로 넘긴다`() = runBlocking<Unit> {
        var forwarded: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, error -> forwarded = error }
        val handle = Pulse.initialize(context, config)
        val crash = IllegalArgumentException("boom token=abc")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), crash)

        assertThat(forwarded).isSameInstanceAs(crash)
        // 큐를 거치지 않으므로 기다리지 않아도 이미 저장돼 있다.
        val stored = handle.loadEvents(EventQuery(kinds = setOf(EventKind.CRASH)), null, 10).items.single()
        assertThat(stored.message).isEqualTo("java.lang.IllegalArgumentException: boom token=$REDACTED")
        assertThat(stored.detail).contains("Thread: ")
        val session = handle.loadSessions(null, null, 10).first { it.current }
        assertThat(session.crashed).isTrue()
        assertThat(handle.loadAppStatistics(null, null)?.crashes).isEqualTo(1)
    }

    @Test
    fun `logcat 한 줄을 읽는다`() {
        val line = LogcatCollector.parse("  1697260000.123  1234  1235 W OkHttp: --> GET https://a.test/x")!!

        assertThat(line.epochMillis).isEqualTo(1_697_260_000_123L)
        assertThat(line.level).isEqualTo(LogLevel.WARN)
        assertThat(line.tag).isEqualTo("OkHttp")
        assertThat(line.message).isEqualTo("--> GET https://a.test/x")
        assertThat(LogcatCollector.parse("--------- beginning of main")).isNull()
    }
}
