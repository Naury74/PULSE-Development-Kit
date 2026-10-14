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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimelineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val original = Thread.getDefaultUncaughtExceptionHandler()
    private val server = MockWebServer()
    private lateinit var handle: PulseHandle
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.start()
        handle = Pulse.initialize(
            context,
            PulseConfig.debugDefaults().newBuilder()
                .monitoring(AppMonitoring(logcat = false, mainThreadBlockMillis = 0, memoryIntervalMillis = 0, exitReasons = false))
                .build(),
        )
        client = OkHttpClient.Builder().addInterceptor(handle.interceptor()).build()
    }

    @After
    fun tearDown() {
        handle.close()
        Thread.setDefaultUncaughtExceptionHandler(original)
        server.close()
    }

    private fun get(path: String, ignore: Boolean = false) {
        server.enqueue(MockResponse.Builder().body("{}").build())
        val request = Request.Builder().url(server.url(path)).apply { if (ignore) tag(PulseIgnore::class.java, PulseIgnore) }.build()
        client.newCall(request).execute().close()
    }

    private suspend fun <T> await(block: suspend () -> T?): T = withTimeout(10_000) {
        var value = block()
        while (value == null) {
            delay(20)
            value = block()
        }
        value
    }

    @Test
    fun `구간 안에서 시작한 요청은 구간 ID가 붙고 구간으로 찾을 수 있다`() = runBlocking<Unit> {
        get("/before")
        val span = handle.beginSpan("checkout")
        get("/inside")
        handle.endSpan(span)
        get("/after")

        val inside = await { handle.loadTransactions(TransactionQuery(spanId = span), null, 10).items.takeIf { it.isNotEmpty() } }
        assertThat(inside.map { it.path }).containsExactly("/inside")
        assertThat(inside.single().context?.spanId).isEqualTo(span)

        val related = handle.loadRelated(inside.single().id, TimelineItemKind.REQUEST)
        val explicit = related.filter { it.strength == LinkStrength.EXPLICIT }
        assertThat(explicit.map { it.reason }.toSet()).containsExactly("span")
        assertThat(explicit.map { it.targetKind }.toSet()).containsExactly(TimelineItemKind.EVENT)
    }

    @Test
    fun `타임라인은 요청과 이벤트를 시간순으로 섞고 페이지가 겹치지 않는다`() = runBlocking<Unit> {
        repeat(5) { index ->
            handle.mark("m$index")
            get("/r$index")
        }
        val all = await {
            handle.loadTimeline(TimelineQuery(eventKinds = setOf(EventKind.MARKER)), null, 100).items.takeIf { it.size >= 10 }
        }
        assertThat(all.map { it.kind }.toSet()).containsExactly(TimelineItemKind.EVENT, TimelineItemKind.REQUEST)
        assertThat(all.map { it.timestampEpochMillis }).isInOrder(Comparator.reverseOrder<Long>())

        val first = handle.loadTimeline(TimelineQuery(eventKinds = setOf(EventKind.MARKER)), null, 4)
        val second = handle.loadTimeline(TimelineQuery(eventKinds = setOf(EventKind.MARKER)), first.next, 100)
        assertThat(first.items.map { it.id } + second.items.map { it.id }).containsExactlyElementsIn(all.map { it.id }).inOrder()

        val requestsOnly = handle.loadTimeline(TimelineQuery(eventKinds = setOf(EventKind.MARKER), includeRequests = false), null, 100)
        assertThat(requestsOnly.items.map { it.kind }.toSet()).containsExactly(TimelineItemKind.EVENT)
    }

    @Test
    fun `PulseIgnore를 붙인 요청은 기록하지 않는다`() = runBlocking<Unit> {
        get("/ignored", ignore = true)
        get("/kept")
        val items = await { handle.loadTransactions(TransactionQuery(), null, 10).items.takeIf { it.isNotEmpty() } }
        delay(300)
        assertThat(handle.loadTransactions(TransactionQuery(), null, 10).items.map { it.path }).containsExactly("/kept")
        assertThat(items).isNotEmpty()
    }

    @Test
    fun `행동·화면·플래그는 이벤트로 남고 플래그는 바뀔 때만 남는다`() = runBlocking<Unit> {
        handle.action("checkout.submit", mapOf("items" to "3", "coupon" to "token=abc"))
        handle.screen("CartScreen")
        get("/cart")
        handle.setFlag("server", "staging")
        handle.setFlag("server", "staging")
        handle.setFlag("server", "prod")
        handle.setFlag("server", null)

        val kinds = setOf(EventKind.ACTION, EventKind.NAVIGATION, EventKind.CONFIG)
        val events = await { handle.loadEvents(EventQuery(kinds = kinds), null, 50).items.takeIf { it.size >= 5 } }
        val action = events.single { it.kind == EventKind.ACTION }
        assertThat(action.message).isEqualTo("checkout.submit")
        assertThat(action.detail).contains("\"items\":\"3\"")
        assertThat(action.detail).contains("token=$REDACTED")
        assertThat(events.single { it.kind == EventKind.NAVIGATION }.message).endsWith("→ CartScreen")
        assertThat(events.filter { it.kind == EventKind.CONFIG }.map { it.message })
            .containsExactly("server = (removed)", "server = prod", "server = staging")
        assertThat(handle.flags.value).isEmpty()

        val cart = await { handle.loadTransactions(TransactionQuery(text = "/cart"), null, 10).items.firstOrNull() }
        assertThat(cart.context?.screen).isEqualTo("CartScreen")
        assertThat(handle.environment().app["package"]).isEqualTo(context.packageName)
    }
}
