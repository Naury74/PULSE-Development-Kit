package com.naury.pulse.sample

import android.os.Handler
import android.os.Looper
import com.naury.pulse.api.LogLevel
import com.naury.pulse.api.Pulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/** 네트워크 밖의 앱 진단(로그·구간·오류·원격 보고·멈춤·크래시) 시나리오. [request]는 PULSE를 붙인 클라이언트로 GET을 보낸다. */
internal class AppScenarios(
    private val server: DemoServer,
    private val flushRemote: () -> Unit,
    private val request: (HttpUrl, String) -> String,
) {
    suspend fun run(scenario: DemoScenario, base: HttpUrl): String = when (scenario) {
        DemoScenario.APP_LOGS -> logs()
        DemoScenario.APP_SPAN -> span(base)
        DemoScenario.APP_ERROR -> handledError()
        DemoScenario.APP_REMOTE -> remote()
        DemoScenario.APP_FREEZE -> freeze()
        DemoScenario.APP_CRASH -> crash()
        else -> error("앱 진단 시나리오가 아닙니다: $scenario")
    }

    private fun logs(): String {
        Pulse.log(LogLevel.VERBOSE, TAG, "장바구니 화면 데이터 준비")
        Pulse.log(LogLevel.DEBUG, TAG, "cart items=3 total=42000")
        Pulse.log(LogLevel.INFO, TAG, "결제 버튼 노출")
        Pulse.log(LogLevel.WARN, TAG, "쿠폰 만료가 하루 남았습니다")
        Pulse.log(LogLevel.ERROR, TAG, "가격 다시 계산 실패", IllegalStateException("price mismatch"))
        return "OK · 5 logs"
    }

    /** 구간 안의 요청은 인스펙터에서 "구간만 보기"로 따로 볼 수 있다. */
    private suspend fun span(base: HttpUrl): String {
        Pulse.mark("결제 흐름 시작 전")
        val spanId = Pulse.beginSpan("결제 흐름")
        request(base, "users/1")
        delay(SPAN_PAUSE_MILLIS)
        request(base, "items/7")
        spanId?.let(Pulse::endSpan)
        return "OK · span + 2 requests"
    }

    private fun handledError(): String {
        try {
            "abc".toInt()
        } catch (error: NumberFormatException) {
            Pulse.recordError(error, "수량 입력을 숫자로 바꾸지 못함")
        }
        return "OK · recorded"
    }

    /** 오류를 하나 만들고 바로 보낸 뒤, 데모 서버가 받은 보고 수를 보여 준다. */
    private suspend fun remote(): String {
        val before = server.receivedReports
        Pulse.recordError(IllegalStateException("remote demo"), "원격 보고 확인")
        delay(WRITE_SETTLE_MILLIS)
        flushRemote()
        delay(REMOTE_WAIT_MILLIS)
        return "OK · server received ${server.receivedReports - before} (total ${server.receivedReports})"
    }

    private suspend fun freeze(): String {
        withContext(Dispatchers.Main) { Thread.sleep(FREEZE_MILLIS) }
        return "OK · main thread blocked ${FREEZE_MILLIS}ms"
    }

    private fun crash(): String {
        Handler(Looper.getMainLooper()).post { throw IllegalStateException("PULSE sample crash") }
        return "crashing"
    }

    private companion object {
        const val TAG = "SampleCheckout"
        const val SPAN_PAUSE_MILLIS = 300L
        const val WRITE_SETTLE_MILLIS = 500L
        const val REMOTE_WAIT_MILLIS = 2_500L
        const val FREEZE_MILLIS = 3_000L
    }
}
