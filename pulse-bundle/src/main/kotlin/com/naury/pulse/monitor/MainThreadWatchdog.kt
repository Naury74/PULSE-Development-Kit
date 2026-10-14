package com.naury.pulse.monitor

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.LogLevel
import com.naury.pulse.core.EventRecorder
import java.util.concurrent.atomic.AtomicLong

/**
 * 메인 스레드에 주기적으로 작업을 넣고 [thresholdMillis] 안에 실행되지 않으면 그때의 메인 스레드 스택을 잡아 둔다.
 * 멈춤이 풀리면 전체 길이와 함께 기록한다. 풀리기 전에 시스템이 앱을 끝내면(ANR) 다음 실행 때 종료 이유로 남는다.
 * 디버거가 붙어 멈춘 경우와, 감시 스레드도 함께 멈춘 경우(백그라운드에서 시스템이 프로세스를 얼린 경우 등)는 세지 않는다.
 */
internal class MainThreadWatchdog(private val events: EventRecorder, private val thresholdMillis: Long) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private val thread = Thread(::loop, "pulse-main-watchdog").apply { isDaemon = true }

    fun start() {
        running = true
        thread.start()
    }

    fun stop() {
        running = false
        thread.interrupt()
    }

    private fun loop() {
        try {
            while (running) {
                check()
                Thread.sleep(INTERVAL_MILLIS)
            }
        } catch (_: InterruptedException) {
            // stop()이 깨웠다.
        }
    }

    /**
     * 작업 하나를 메인 스레드에 넣고 실행될 때까지 기다린다. 기준을 넘기면 그때의 스택을 잡아 두었다가 풀린 뒤 기록한다.
     * 실제로 실행된 시각으로 길이를 재므로, 감시 스레드가 늦게 깨어나 기준을 넘긴 것처럼 보인 경우는 기록하지 않는다.
     */
    private fun check() {
        val posted = SystemClock.uptimeMillis()
        val ran = AtomicLong()
        handler.post { ran.set(SystemClock.uptimeMillis()) }
        var stack: String? = null
        var processPaused = false
        while (running && ran.get() == 0L) {
            val before = SystemClock.uptimeMillis()
            Thread.sleep(POLL_MILLIS)
            val now = SystemClock.uptimeMillis()
            // 감시 스레드의 짧은 잠이 크게 늘어났으면 프로세스 전체가 멈췄던 것이다(얼림·일시 중지). 메인 스레드 탓이 아니다.
            if (now - before > POLL_MILLIS + PAUSE_TOLERANCE_MILLIS) processPaused = true
            if (stack == null && now - posted >= thresholdMillis && !Debug.isDebuggerConnected()) stack = mainStack()
        }
        val blockedMillis = ran.get() - posted
        if (stack != null && !processPaused && blockedMillis >= thresholdMillis) report(stack, blockedMillis)
    }

    private fun report(stack: String, blockedMillis: Long) {
        events.record(
            EventKind.MAIN_THREAD_BLOCKED,
            "Main thread blocked for $blockedMillis ms",
            level = LogLevel.WARN,
            tag = "MainThread",
            detail = stack,
            durationMillis = blockedMillis,
        )
    }

    private fun mainStack(): String = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "\tat $it" }

    private companion object {
        const val POLL_MILLIS = 50L
        const val INTERVAL_MILLIS = 500L
        const val PAUSE_TOLERANCE_MILLIS = 1_000L
    }
}
