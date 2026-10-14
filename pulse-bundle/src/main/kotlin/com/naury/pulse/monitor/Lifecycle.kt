package com.naury.pulse.monitor

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import com.naury.pulse.api.EventKind
import com.naury.pulse.core.EventRecorder
import java.lang.ref.WeakReference

/** 앞에 있는 Activity를 약한 참조로 기억하고, 화면이 바뀌면 이동으로 기록한다. PULSE 자체 화면은 이동으로 치지 않는다. */
internal class ScreenTracker(private val events: EventRecorder) : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var resumed: WeakReference<Activity>? = null

    fun current(): Activity? = resumed?.get()

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        if (!activity.javaClass.name.startsWith(PULSE_UI_PACKAGE)) events.navigate(activity.javaClass.simpleName)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val PULSE_UI_PACKAGE = "com.naury.pulse.ui."
    }
}

/**
 * 앱이 화면에 보인 시간과 마지막으로 살아 있던 시각을 세션에 남긴다. 포그라운드·백그라운드로 바뀔 때 이벤트를 남기고,
 * [heartbeat]마다 세션을 갱신해 프로세스가 갑자기 끝나도 실행 시간을 [HEARTBEAT_MILLIS] 오차 안에서 알 수 있다.
 */
internal class SessionTracker(private val events: EventRecorder) : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var foregroundSince: Long? = null
    private var accumulated = 0L

    @Synchronized
    fun foregroundMillis(): Long = accumulated + (foregroundSince?.let { SystemClock.elapsedRealtime() - it } ?: 0)

    fun heartbeat() {
        events.touchSession(System.currentTimeMillis(), foregroundMillis())
    }

    @Synchronized
    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) {
            foregroundSince = SystemClock.elapsedRealtime()
            events.record(EventKind.LIFECYCLE, FOREGROUND)
        }
    }

    @Synchronized
    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) {
            foregroundSince?.let { accumulated += SystemClock.elapsedRealtime() - it }
            foregroundSince = null
            events.record(EventKind.LIFECYCLE, BACKGROUND)
            events.touchSession(System.currentTimeMillis(), accumulated)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        const val HEARTBEAT_MILLIS = 15_000L
        const val FOREGROUND = "foreground"
        const val BACKGROUND = "background"
    }
}
