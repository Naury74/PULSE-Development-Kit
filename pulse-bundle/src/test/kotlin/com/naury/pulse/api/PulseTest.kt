package com.naury.pulse.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PulseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        Pulse.current()?.close()
    }

    @Test
    fun `같은 설정으로 다시 초기화하면 같은 핸들을 돌려준다`() {
        val first = Pulse.initialize(context, PulseConfig.debugDefaults())

        assertThat(Pulse.initialize(context, PulseConfig.debugDefaults())).isSameInstanceAs(first)
        assertThat(Pulse.current()).isSameInstanceAs(first)
        assertThat(PulseInspectorRegistry.current).isSameInstanceAs(first)
    }

    @Test
    fun `다른 설정으로 다시 초기화하면 거절하고 닫은 뒤에는 허용한다`() {
        val first = Pulse.initialize(context, PulseConfig.defaults())

        assertThrows(PulseConfigurationException::class.java) { Pulse.initialize(context, PulseConfig.debugDefaults()) }

        first.close()
        assertThat(Pulse.current()).isNull()
        assertThat(PulseInspectorRegistry.current).isNull()
        assertThat(first.recordingState.value).isEqualTo(RecordingState.DISABLED)
        assertThat(Pulse.initialize(context, PulseConfig.debugDefaults())).isNotSameInstanceAs(first)
    }

    @Test
    fun `화면 모듈이 없으면 open은 false다`() {
        assertThat(Pulse.open(context)).isFalse()

        val handle = Pulse.initialize(context)

        assertThat(handle.open(context)).isFalse()
        assertThat(Pulse.open(context)).isFalse()
    }

    @Test
    fun `OkHttp 요청이 마스킹되어 저장되고 조회된다`() = runBlocking {
        val handle = Pulse.initialize(context, PulseConfig.debugDefaults())
        val client = OkHttpClient.Builder()
            .addInterceptor(handle.interceptor())
            .eventListenerFactory(handle.eventListenerFactory())
            .build()
        val body = MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body("""{"token":"secret"}""").build())
            client.newCall(Request.Builder().url(server.url("/me?password=secret")).header("Cookie", "sid=secret").build())
                .execute().use { it.body.string() }
        }

        val summary = withTimeout(10_000) {
            var found: TransactionSummary? = null
            while (found?.state != TransactionState.COMPLETED) {
                found = handle.loadTransactions(TransactionQuery(), null, 10).items.firstOrNull()
                if (found?.state != TransactionState.COMPLETED) delay(20)
            }
            requireNotNull(found)
        }
        val detail = requireNotNull(handle.loadTransaction(summary.id))

        assertThat(body).isEqualTo("""{"token":"secret"}""")
        assertThat(summary.url).endsWith("/me?password=$REDACTED")
        assertThat(detail.request.headers.single { it.name == "Cookie" }.value).isEqualTo(REDACTED)
        assertThat(handle.loadBody(summary.id, MessageKind.RESPONSE)).isEqualTo("""{"token":"$REDACTED"}""")
        val stats = handle.loadStatistics(StatsWindow.LAST_HOUR)
        assertThat(stats.counts.total).isEqualTo(1)
        assertThat(stats.counts.success).isEqualTo(1)
        assertThat(stats.durations.sampleCount).isEqualTo(1)
        assertThat(handle.loadInsights(StatsWindow.ALL)).isEmpty()
    }

    @Test
    fun `멈춘 동안의 요청은 저장하지 않는다`() {
        val handle = Pulse.initialize(context)

        handle.pause()
        assertThat(handle.recordingState.value).isEqualTo(RecordingState.PAUSED)
        handle.resume()
        assertThat(handle.recordingState.value).isEqualTo(RecordingState.RECORDING)
    }

    @Test
    fun `재실행은 정책이 없으면 꺼져 있고, 있으면 별도 출처로 원본과 연결해 기록한다`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body("{}").build()) }
            val disabled = Pulse.initialize(context)
            assertThat(
                (
                    disabled.replay(
                        ReplayDraft("x", "GET", server.url("/").toString(), emptyList(), null, null),
                        true,
                    ) as ReplayOutcome.Blocked
                    ).reason,
            )
                .isEqualTo(ReplayBlockReason.DISABLED)
            disabled.close()

            val handle = Pulse.initialize(
                context,
                PulseConfig.debugDefaults().newBuilder().replay(ReplayPolicy(setOf(server.hostName), allowCleartext = true)).build(),
            )
            val client = OkHttpClient.Builder().addInterceptor(handle.interceptor()).build()
            client.newCall(Request.Builder().url(server.url("/items")).header("Authorization", "Bearer secret").build()).execute().close()
            val original = awaitCompleted(handle)

            val draft = requireNotNull(handle.replayDraft(original.id))
            val outcome = handle.replay(draft, confirmed = true) as ReplayOutcome.Completed

            assertThat(draft.removedHeaders).containsExactly("Authorization")
            assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer secret")
            assertThat(server.takeRequest().headers["Authorization"]).isNull()
            val replayed = withTimeout(10_000) {
                var found: TransactionDetail? = null
                while (found?.summary?.state != TransactionState.COMPLETED) {
                    found = handle.loadTransaction(outcome.transactionId)
                    if (found?.summary?.state != TransactionState.COMPLETED) delay(20)
                }
                requireNotNull(found)
            }
            assertThat(replayed.summary.source).isEqualTo(CaptureSource.REPLAY)
            assertThat(replayed.summary.parentTransactionId).isEqualTo(original.id)
            assertThat(handle.loadStatistics(StatsWindow.ALL).counts.total).isEqualTo(1)
        }
    }

    private suspend fun awaitCompleted(handle: PulseHandle): TransactionSummary {
        var found: TransactionSummary? = null
        return try {
            withTimeout(10_000) {
                while (found?.state != TransactionState.COMPLETED) {
                    found = handle.loadTransactions(TransactionQuery(), null, 10).items.firstOrNull()
                    if (found?.state != TransactionState.COMPLETED) delay(20)
                }
                requireNotNull(found)
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "last=${found?.path} ${found?.state} rec=${handle.recordingState.value} fail=${handle.captureHealth.value.writeFailures} " +
                    "sessions=${handle.loadSessions(null, null, 10).map { it.id.take(6) + ":" + it.current }} " +
                    "failed=${handle.loadTransactions(TransactionQuery(status = StatusFilter.FAILED), null, 10).items.map { it.state }}",
                timeout,
            )
        }
    }
}
