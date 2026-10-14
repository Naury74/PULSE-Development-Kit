package com.naury.pulse.api

import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/** 기록하지 않는 [WebViewClient]. 모든 메서드가 기본 동작만 한다. */
public open class PulseWebViewClient : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        super.onReceivedHttpError(view, request, errorResponse)
    }
}

/** 기록하지 않는 [WebChromeClient]. */
@Suppress("UnusedPrivateProperty")
public open class PulseWebChromeClient @JvmOverloads constructor(private val minLevel: LogLevel = LogLevel.WARN) : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = super.onConsoleMessage(consoleMessage)
}
