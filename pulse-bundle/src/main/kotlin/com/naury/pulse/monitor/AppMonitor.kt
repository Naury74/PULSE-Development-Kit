package com.naury.pulse.monitor

import android.app.Application
import android.content.Context
import android.os.Debug
import com.naury.pulse.api.AppMonitoring
import com.naury.pulse.api.EventKind
import com.naury.pulse.core.EventRecorder
import com.naury.pulse.storage.PulseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** [AppMonitoring] 설정대로 앱 쪽 수집기를 켜고 끈다. */
internal class AppMonitor(
    context: Context,
    private val options: AppMonitoring,
    private val events: EventRecorder,
    private val storage: PulseStorage,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext as? Application
    private val screens = ScreenTracker(events)
    private val sessions = SessionTracker(events)
    private val performance = PerformanceMonitor(events)
    private val crashes = CrashMonitor(events, sessions, screens, options.screenshotOnCrash)
    private val exits = ExitReasons(context.applicationContext, events, storage)
    private val watchdog = options.mainThreadBlockMillis.takeIf { it > 0 }?.let { MainThreadWatchdog(events, it) }
    private val interactions = if (options.interactions) InteractionTracker(events) else null
    private val logcat = if (options.logcat) LogcatCollector(events, options.logcatMinLevel, options.logcatIgnoredTags) else null

    val currentScreenTracker: ScreenTracker
        get() = screens

    fun start() {
        app?.registerActivityLifecycleCallbacks(screens)
        app?.registerActivityLifecycleCallbacks(sessions)
        if (options.performance) app?.registerActivityLifecycleCallbacks(performance)
        interactions?.let { app?.registerActivityLifecycleCallbacks(it) }
        if (options.crashes) crashes.install()
        watchdog?.start()
        logcat?.start()
        scope.launch {
            while (isActive) {
                sessions.heartbeat()
                delay(SessionTracker.HEARTBEAT_MILLIS)
            }
        }
        if (options.memoryIntervalMillis > 0) {
            scope.launch {
                while (isActive) {
                    delay(options.memoryIntervalMillis)
                    if (screens.current() != null) sampleMemory()
                }
            }
        }
    }

    /** 지금 세션이 저장된 뒤에 부른다. 지난 세션에 종료 이유를 붙여야 하기 때문이다. */
    suspend fun recordExitReasons() {
        if (options.exitReasons) exits.recordSinceLastRun()
    }

    fun close() {
        app?.unregisterActivityLifecycleCallbacks(screens)
        app?.unregisterActivityLifecycleCallbacks(sessions)
        app?.unregisterActivityLifecycleCallbacks(performance)
        interactions?.let { app?.unregisterActivityLifecycleCallbacks(it) }
        crashes.uninstall()
        watchdog?.stop()
        logcat?.stop()
        sessions.heartbeat()
    }

    private fun sampleMemory() {
        val runtime = Runtime.getRuntime()
        val java = runtime.totalMemory() - runtime.freeMemory()
        val native = Debug.getNativeHeapAllocatedSize()
        // GC 횟수와 이 프로세스가 쓴 CPU 시간도 함께 남겨 메모리 추세와 같이 본다.
        val gc = Debug.getRuntimeStat("art.gc.gc-count")
        val cpuMillis = android.os.Process.getElapsedCpuTime()
        events.record(
            EventKind.MEMORY,
            "java ${java / MIB} MB · native ${native / MIB} MB",
            value = java + native,
            detail = listOfNotNull(gc?.let { "gc=$it" }, "cpuMillis=$cpuMillis").joinToString(" "),
        )
    }

    private companion object {
        const val MIB = 1024 * 1024
    }
}
