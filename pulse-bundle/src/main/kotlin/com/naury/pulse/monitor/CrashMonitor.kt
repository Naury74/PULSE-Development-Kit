package com.naury.pulse.monitor

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.LogLevel
import com.naury.pulse.core.EventRecorder
import java.io.ByteArrayOutputStream

/**
 * 처리되지 않은 예외를 죽기 직전에 저장하고 원래 핸들러에 그대로 넘긴다. 저장은 큐를 거치지 않고 지금 스레드에서 끝낸다.
 * 기록에 실패해도 원래 핸들러는 반드시 부른다.
 */
internal class CrashMonitor(
    private val events: EventRecorder,
    private val sessions: SessionTracker,
    private val screens: ScreenTracker,
    private val screenshot: Boolean,
) : Thread.UncaughtExceptionHandler {
    private var previous: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var installed = false

    fun install() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        installed = true
    }

    /** 다른 핸들러가 그 뒤에 바꿨으면 그대로 두고, 우리가 마지막이면 원래 것으로 되돌린다. */
    fun uninstall() {
        if (installed && Thread.getDefaultUncaughtExceptionHandler() === this) Thread.setDefaultUncaughtExceptionHandler(previous)
        installed = false
    }

    @Suppress("TooGenericExceptionCaught")
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val png = if (screenshot && Looper.myLooper() == Looper.getMainLooper()) screens.current()?.let(::capture) else null
            events.recordNow(
                kind = EventKind.CRASH,
                message = "${error.javaClass.name}: ${error.message.orEmpty()}",
                level = LogLevel.ASSERT,
                tag = error.javaClass.simpleName,
                detail = "Thread: ${thread.name}\n" + error.stackTraceToString(),
                attachmentPng = png,
                session = events.crashedSession(System.currentTimeMillis(), sessions.foregroundMillis()),
            )
        } catch (_: Throwable) {
            // 기록하다 실패해도 앱의 원래 크래시 처리를 막으면 안 된다.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val MAX_WIDTH = 720
        private const val PNG_QUALITY = 100

        /** 앞 화면을 줄여서 PNG로 만든다. 그릴 수 없으면 null. 메인 스레드에서만 부른다. */
        @Suppress("TooGenericExceptionCaught")
        fun capture(activity: Activity): ByteArray? = try {
            val view = activity.window.decorView
            if (view.width == 0 || view.height == 0) {
                null
            } else {
                val scale = minOf(1f, MAX_WIDTH.toFloat() / view.width)
                val bitmap = Bitmap.createBitmap((view.width * scale).toInt(), (view.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply { scale(scale, scale) }.also { view.draw(it) }
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                    bitmap.recycle()
                    out.toByteArray()
                }
            }
        } catch (_: RuntimeException) {
            null
        }
    }
}
