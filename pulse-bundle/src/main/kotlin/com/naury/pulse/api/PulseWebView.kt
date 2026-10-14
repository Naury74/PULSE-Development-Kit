package com.naury.pulse.api

import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap

/**
 * WebView 페이지 이동·로딩 오류를 PULSE 로그(태그 `WebView`)로 남기는 WebViewClient. 쓰던 WebViewClient 대신 이것을 상속하고,
 * 재정의한 메서드에서 `super`를 부르면 된다. 기록 말고는 기본 동작을 바꾸지 않는다. URL 쿼리 속 비밀은 저장 전에 가린다.
 *
 * - 페이지 시작·끝(걸린 시간): INFO
 * - HTTP 4xx·5xx 응답: WARN(메인 프레임이면 ERROR)
 * - 로딩 실패(연결·DNS·SSL 등): ERROR
 */
public open class PulseWebViewClient : WebViewClient() {
    private val started = ConcurrentHashMap<String, Long>()

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        started[url] = SystemClock.elapsedRealtime()
        Pulse.log(LogLevel.INFO, TAG, "page start $url")
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String) {
        val elapsed = started.remove(url)?.let { SystemClock.elapsedRealtime() - it }
        Pulse.log(LogLevel.INFO, TAG, "page finished $url" + (elapsed?.let { " (${it}ms)" } ?: ""))
        super.onPageFinished(view, url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        Pulse.log(LogLevel.ERROR, TAG, "load failed ${error.errorCode} ${error.description} ${frame(request)} ${request.url}")
        super.onReceivedError(view, request, error)
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        val level = if (request.isForMainFrame) LogLevel.ERROR else LogLevel.WARN
        Pulse.log(level, TAG, "HTTP ${errorResponse.statusCode} ${frame(request)} ${request.method} ${request.url}")
        super.onReceivedHttpError(view, request, errorResponse)
    }

    private fun frame(request: WebResourceRequest) = if (request.isForMainFrame) "main" else "resource"
}

/**
 * WebView의 `console.*` 메시지를 PULSE 로그(태그 `WebView.console`)로 남기는 WebChromeClient. 상속해서 쓰고 재정의하면 `super`를 부른다.
 * 콘솔에는 개인정보가 섞이기 쉬우므로 [minLevel] 이상만 남긴다(기본 WARN).
 */
public open class PulseWebChromeClient @JvmOverloads constructor(private val minLevel: LogLevel = LogLevel.WARN) : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val level = when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> LogLevel.ERROR
            ConsoleMessage.MessageLevel.WARNING -> LogLevel.WARN
            ConsoleMessage.MessageLevel.DEBUG, ConsoleMessage.MessageLevel.TIP -> LogLevel.DEBUG
            else -> LogLevel.INFO
        }
        if (level.ordinal >= minLevel.ordinal) {
            Pulse.log(level, CONSOLE_TAG, "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
        }
        return super.onConsoleMessage(consoleMessage)
    }
}

private const val TAG = "WebView"
private const val CONSOLE_TAG = "WebView.console"
