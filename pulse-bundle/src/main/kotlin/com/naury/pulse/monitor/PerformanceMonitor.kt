package com.naury.pulse.monitor

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.ViewTreeObserver
import android.view.Window
import com.naury.pulse.analytics.FrameCounts
import com.naury.pulse.api.EventKind
import com.naury.pulse.core.EventRecorder
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 콜드 스타트·화면 로딩 시간과 화면별 프레임을 기록한다. 시간은 단조 시계로 재고, 처음 그린 시점은 첫 draw 콜백으로 본다.
 * 프레임은 Android 7+의 FrameMetrics로 화면에 머무는 동안 세고 화면을 떠날 때 한 번에 남긴다.
 */
internal class PerformanceMonitor(private val events: EventRecorder) : Application.ActivityLifecycleCallbacks {
    private val startupRecorded = AtomicBoolean()
    private val createdAt = WeakHashMap<Activity, Long>()
    private val frames = WeakHashMap<Activity, FrameCounter>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        createdAt[activity] = SystemClock.uptimeMillis()
        val decor = activity.window.decorView
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            private var done = false

            override fun onDraw() {
                if (done) return
                done = true
                // draw 콜백 안에서는 리스너를 지울 수 없어 다음 메시지에서 지운다.
                handler.post { decor.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(this) }
                firstDraw(activity)
            }
        })
    }

    private fun firstDraw(activity: Activity) {
        val now = SystemClock.uptimeMillis()
        val name = activity.javaClass.simpleName
        createdAt.remove(activity)?.let { events.record(EventKind.SCREEN_LOAD, name, durationMillis = now - it) }
        if (startupRecorded.compareAndSet(false, true)) {
            val sinceProcessStart = now - Process.getStartUptimeMillis()
            // 앱이 백그라운드로 시작했다가 나중에 화면을 연 경우는 콜드 스타트가 아니다.
            if (sinceProcessStart in 0..MAX_COLD_START_MILLIS) {
                events.record(EventKind.STARTUP, name, durationMillis = sinceProcessStart)
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        val counter = FrameCounter(SystemClock.elapsedRealtime())
        frames[activity] = counter
        activity.window.addOnFrameMetricsAvailableListener(counter, handler)
    }

    override fun onActivityPaused(activity: Activity) {
        val counter = frames.remove(activity) ?: return
        try {
            activity.window.removeOnFrameMetricsAvailableListener(counter)
        } catch (_: IllegalArgumentException) {
            // 이미 지워졌다.
        }
        val counts = counter.snapshot()
        if (counts.total > 0) {
            events.record(
                EventKind.FRAMES,
                activity.javaClass.simpleName,
                detail = counts.format(),
                durationMillis = SystemClock.elapsedRealtime() - counter.startedAt,
                value = counts.slow,
            )
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        createdAt.remove(activity)
    }

    private class FrameCounter(val startedAt: Long) : Window.OnFrameMetricsAvailableListener {
        private var total = 0L
        private var slow = 0L
        private var frozen = 0L

        @Synchronized
        override fun onFrameMetricsAvailable(window: Window, metrics: FrameMetrics, dropCount: Int) {
            val nanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            // Android 12+는 화면 주사율에 맞춘 마감 시각을 주므로 90·120Hz 화면에서도 기준이 맞다.
            val deadline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) metrics.getMetric(FrameMetrics.DEADLINE) else 0L
            total++
            if (nanos > (if (deadline > 0) deadline else SLOW_FRAME_NANOS)) slow++
            if (nanos > FROZEN_FRAME_NANOS) frozen++
        }

        @Synchronized
        fun snapshot() = FrameCounts(total, slow, frozen)
    }

    private companion object {
        const val MAX_COLD_START_MILLIS = 30_000L
        const val SLOW_FRAME_NANOS = 16_700_000L
        const val FROZEN_FRAME_NANOS = 700_000_000L
    }
}
