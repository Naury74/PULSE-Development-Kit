package com.naury.pulse.api

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class InteractionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val original = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun tearDown() {
        Pulse.current()?.close()
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    private fun touch(activity: Activity, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        activity.window.callback.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test
    fun `누를 수 있는 View를 탭하면 id로, 뒤로 키는 화면 이름으로 남기고 글자는 남기지 않는다`() = runBlocking<Unit> {
        val handle = Pulse.initialize(
            context,
            PulseConfig.debugDefaults().newBuilder()
                .monitoring(
                    AppMonitoring(
                        crashes = false,
                        exitReasons = false,
                        mainThreadBlockMillis = 0,
                        memoryIntervalMillis = 0,
                        interactions = true,
                    ),
                )
                .build(),
        )
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val button = Button(activity).apply {
            id = android.R.id.button1
            text = "결제 password=1234"
            setOnClickListener { }
        }
        activity.setContentView(FrameLayout(activity).apply { addView(button, FrameLayout.LayoutParams(400, 200)) })
        ShadowLooper.idleMainLooper()
        val location = IntArray(2).also(button::getLocationOnScreen)
        val x = location[0] + 100f
        val y = location[1] + 100f

        touch(activity, MotionEvent.ACTION_DOWN, x, y)
        touch(activity, MotionEvent.ACTION_UP, x, y)
        activity.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))

        val actions = withTimeout(10_000) {
            var items = emptyList<AppEvent>()
            while (items.size < 2) {
                delay(20)
                items = handle.loadEvents(EventQuery(kinds = setOf(EventKind.ACTION)), null, 10).items
            }
            items.map { it.message }
        }
        assertThat(actions).containsExactly("tap #button1", "back Activity")
        assertThat(actions.joinToString()).doesNotContain("1234")
        controller.pause().stop().destroy()
    }
}
