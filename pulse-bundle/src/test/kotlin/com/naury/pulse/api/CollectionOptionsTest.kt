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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CollectionOptionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        Pulse.current()?.close()
    }

    private suspend fun PulseHandle.awaitCompleted(count: Int): List<TransactionSummary> = withTimeout(10_000) {
        var items = emptyList<TransactionSummary>()
        while (items.count { it.state == TransactionState.COMPLETED } < count) {
            delay(20)
            items = loadTransactions(TransactionQuery(), null, 50).items
        }
        items
    }

    @Test
    fun `필터·라벨·form 본문 옵션이 기록과 통계에 반영된다`() = runBlocking {
        val config = PulseConfig.debugDefaults().newBuilder()
            .filter(CaptureFilter(ignoredPathPrefixes = setOf("/health"), ignoredHosts = setOf("*.ads.test")))
            .bodyFormats(BodyFormats(forms = true))
            .endpoints(EndpointGrouping(templates = listOf("/items/{itemId}")))
            .build()
        val handle = Pulse.initialize(context, config)
        val client = OkHttpClient.Builder().addInterceptor(handle.interceptor()).eventListenerFactory(handle.eventListenerFactory()).build()

        MockWebServer().use { server ->
            server.start()
            repeat(4) { server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body("{}").build()) }
            client.newCall(Request.Builder().url(server.url("/health")).build()).execute().use { it.body.string() }
            client.newCall(
                Request.Builder().url(server.url("/oauth/token"))
                    .post(FormBody.Builder().add("username", "kim").add("password", "pw-secret").build())
                    .tag(PulseLabel::class.java, PulseLabel("auth"))
                    .build(),
            ).execute().use { it.body.string() }
            client.newCall(Request.Builder().url(server.url("/items/abc")).build()).execute().use { it.body.string() }
            client.newCall(Request.Builder().url(server.url("/items/def")).build()).execute().use { it.body.string() }
        }

        val items = handle.awaitCompleted(3)
        assertThat(items.map { it.path }).doesNotContain("/health")
        val form = items.single { it.path == "/oauth/token" }
        assertThat(form.context?.label).isEqualTo("auth")
        assertThat(handle.loadBody(form.id, MessageKind.REQUEST)).isEqualTo("username=kim&password=$REDACTED")
        // 앱은 헤더를 붙이지 않았지만 실제로 보낸 헤더(Host·Content-Type 등)는 따로 남는다.
        val formDetail = requireNotNull(handle.loadTransaction(form.id))
        assertThat(formDetail.request.headers).isEmpty()
        assertThat(formDetail.networkRequestHeaders.map { it.name }).containsAtLeast("Host", "Content-Type")
        assertThat(formDetail.networkResponseHeaders).isNotEmpty()
        val stats = handle.loadStatistics(StatsQuery(StatsWindow.LAST_HOUR))
        assertThat(stats.counts.total).isEqualTo(3)
        assertThat(stats.endpoints.first().path).isEqualTo("/items/{itemId}")
        assertThat(stats.facets.labels).containsExactly("auth")
        assertThat(handle.loadStatistics(StatsQuery(StatsWindow.LAST_HOUR, label = "auth")).counts.total).isEqualTo(1)
    }

    @Test
    fun `호스트 패턴은 하위 도메인까지 맞추고 잘못된 패턴은 거절한다`() {
        val filter = CaptureFilter(onlyHosts = setOf("*.example.com"), ignoredHosts = setOf("ads.example.com"))

        assertThat(filter.accepts("example.com", "/")).isTrue()
        assertThat(filter.accepts("api.EXAMPLE.com", "/")).isTrue()
        assertThat(filter.accepts("ads.example.com", "/")).isFalse()
        assertThat(filter.accepts("example.org", "/")).isFalse()
        runCatching { CaptureFilter(ignoredHosts = setOf("a*.b")) }.let { assertThat(it.isFailure).isTrue() }
        runCatching { CaptureFilter(ignoredPathPrefixes = setOf("health")) }.let { assertThat(it.isFailure).isTrue() }
    }
}
