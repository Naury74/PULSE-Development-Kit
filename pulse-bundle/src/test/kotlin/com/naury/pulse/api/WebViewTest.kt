package com.naury.pulse.api

import android.content.Context
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebViewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val original = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun tearDown() {
        Pulse.current()?.close()
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    private class Request(private val url: String, private val main: Boolean) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)

        override fun isForMainFrame() = main

        override fun isRedirect() = false

        override fun hasGesture() = false

        override fun getMethod() = "GET"

        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    @Test
    fun `페이지 이동·HTTP 오류·콘솔 오류를 마스킹해 로그로 남긴다`() = runBlocking<Unit> {
        val handle = Pulse.initialize(
            context,
            PulseConfig.debugDefaults().newBuilder()
                .monitoring(AppMonitoring(logcat = false, mainThreadBlockMillis = 0, memoryIntervalMillis = 0, exitReasons = false))
                .build(),
        )
        val webView = WebView(context)
        val client = PulseWebViewClient()
        client.onPageStarted(webView, "https://m.example.com/cart?token=abc", null)
        client.onPageFinished(webView, "https://m.example.com/cart?token=abc")
        client.onReceivedHttpError(
            webView,
            Request("https://m.example.com/api/pay", main = true),
            WebResourceResponse("text/html", "utf-8", 503, "x", emptyMap(), null),
        )
        PulseWebChromeClient().onConsoleMessage(
            ConsoleMessage("TypeError: x is undefined", "app.js", 12, ConsoleMessage.MessageLevel.ERROR),
        )
        PulseWebChromeClient().onConsoleMessage(ConsoleMessage("debug noise", "app.js", 1, ConsoleMessage.MessageLevel.LOG))

        val logs = withTimeout(10_000) {
            var items = emptyList<AppEvent>()
            while (items.size < 4) {
                delay(20)
                items =
                    handle.loadEvents(EventQuery(kinds = setOf(EventKind.LOG)), null, 50).items.filter {
                        it.tag?.startsWith("WebView") ==
                            true
                    }
            }
            items
        }
        val messages = logs.map { it.message }
        assertThat(messages.joinToString()).doesNotContain("abc")
        assertThat(messages).contains("page start https://m.example.com/cart?token=$REDACTED")
        assertThat(logs.single { it.message.startsWith("HTTP 503") }.level).isEqualTo(LogLevel.ERROR)
        assertThat(logs.single { it.tag == "WebView.console" }.message).startsWith("TypeError: x is undefined")
        assertThat(messages.none { it.contains("debug noise") }).isTrue()
    }
}
