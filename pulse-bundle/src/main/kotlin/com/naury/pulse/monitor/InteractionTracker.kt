package com.naury.pulse.monitor

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.res.Resources
import android.graphics.Rect
import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.naury.pulse.api.EventKind
import com.naury.pulse.core.EventRecorder
import kotlin.math.abs

/**
 * 앱 화면의 탭과 뒤로 키를 행동으로 남긴다. 각 Activity 창의 [Window.Callback]을 감싸 입력을 그대로 넘기고, 손가락을 뗄 때
 * 그 위치의 누를 수 있는 대상을 찾는다. 대상은 View id·클래스나 Compose testTag·role로만 남기고 글자 내용과 좌표는 남기지 않는다.
 * PULSE 화면과 다이얼로그 같은 별도 창은 다루지 않는다.
 */
internal class InteractionTracker(private val events: EventRecorder) : Application.ActivityLifecycleCallbacks {
    private val composeAvailable: Boolean = try {
        Class.forName("androidx.compose.ui.platform.ViewRootForTest")
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity.javaClass.name.startsWith(PULSE_UI_PACKAGE)) return
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (current !is TrackingCallback) window.callback = TrackingCallback(current, window, activity.javaClass.simpleName)
    }

    /** 감싼 콜백을 원래대로 돌린다. 다른 라이브러리가 그 위에 다시 감쌌으면 건드리지 않는다. */
    override fun onActivityDestroyed(activity: Activity) {
        val callback = activity.window?.callback as? TrackingCallback ?: return
        activity.window.callback = callback.delegate
    }

    private fun recordTap(window: Window, event: MotionEvent) {
        val target = target(window.decorView, event.rawX.toInt(), event.rawY.toInt()) ?: return
        events.record(EventKind.ACTION, "tap $target", tag = TAG)
    }

    private fun target(root: View, x: Int, y: Int): String? {
        val view = deepestClickable(root, x, y) ?: return null
        if (composeAvailable && view is ViewRootForTest) composeTarget(view, x, y)?.let { return it }
        return idName(view)?.let { "#$it" } ?: view.javaClass.simpleName
    }

    private fun deepestClickable(view: View, x: Int, y: Int): View? {
        if (view.visibility != View.VISIBLE || !contains(view, x, y)) return null
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                deepestClickable(view.getChildAt(index), x, y)?.let { return it }
            }
        }
        return view.takeIf { it.isClickable || (composeAvailable && it is ViewRootForTest) }
    }

    @SuppressLint("VisibleForTests")
    @Suppress("TooGenericExceptionCaught")
    private fun composeTarget(root: ViewRootForTest, x: Int, y: Int): String? = try {
        val location = IntArray(2).also { (root as View).getLocationOnScreen(it) }
        clickableNode(
            root.semanticsOwner.unmergedRootSemanticsNode,
            (x - location[0]).toFloat(),
            (y - location[1]).toFloat(),
        )?.let { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.let { "#$it" }
                ?: node.config.getOrNull(SemanticsProperties.Role)?.let { "Compose $it" }
                ?: "Compose node"
        }
    } catch (_: RuntimeException) {
        null
    }

    /** [x]·[y](Compose 뷰 기준)를 덮는, 누를 수 있는 가장 안쪽 노드. */
    private fun clickableNode(node: SemanticsNode, x: Float, y: Float): SemanticsNode? {
        if (!node.boundsInRoot.contains(Offset(x, y))) return null
        node.children.asReversed().forEach { child -> clickableNode(child, x, y)?.let { return it } }
        return node.takeIf { candidate -> candidate.config.any { it.key.name == ON_CLICK } }
    }

    private fun contains(view: View, x: Int, y: Int): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height).contains(x, y)
    }

    private fun idName(view: View): String? = if (view.id == View.NO_ID) {
        null
    } else {
        try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    /** 입력을 그대로 넘기면서 탭·뒤로 키만 본다. 다른 콜백은 모두 [delegate]에 맡긴다. */
    private inner class TrackingCallback(val delegate: Window.Callback, private val window: Window, private val screen: String) :
        Window.Callback {
        private var downX = 0f
        private var downY = 0f

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                }

                MotionEvent.ACTION_UP -> if (abs(event.rawX - downX) < TAP_SLOP && abs(event.rawY - downY) < TAP_SLOP) {
                    runCatching { recordTap(window, event) }
                }
            }
            return delegate.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                events.record(EventKind.ACTION, "back $screen", tag = TAG)
            }
            return delegate.dispatchKeyEvent(event)
        }

        override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean = delegate.dispatchKeyShortcutEvent(event)

        override fun dispatchTrackballEvent(event: MotionEvent): Boolean = delegate.dispatchTrackballEvent(event)

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = delegate.dispatchGenericMotionEvent(event)

        override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean =
            delegate.dispatchPopulateAccessibilityEvent(event)

        override fun onCreatePanelView(featureId: Int): View? = delegate.onCreatePanelView(featureId)

        override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = delegate.onCreatePanelMenu(featureId, menu)

        override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean = delegate.onPreparePanel(featureId, view, menu)

        override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = delegate.onMenuOpened(featureId, menu)

        override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = delegate.onMenuItemSelected(featureId, item)

        override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) = delegate.onWindowAttributesChanged(attrs)

        override fun onContentChanged() = delegate.onContentChanged()

        override fun onWindowFocusChanged(hasFocus: Boolean) = delegate.onWindowFocusChanged(hasFocus)

        override fun onAttachedToWindow() = delegate.onAttachedToWindow()

        override fun onDetachedFromWindow() = delegate.onDetachedFromWindow()

        override fun onPanelClosed(featureId: Int, menu: Menu) = delegate.onPanelClosed(featureId, menu)

        override fun onSearchRequested(): Boolean = delegate.onSearchRequested()

        override fun onSearchRequested(searchEvent: SearchEvent): Boolean = delegate.onSearchRequested(searchEvent)

        override fun onWindowStartingActionMode(callback: ActionMode.Callback): ActionMode? = delegate.onWindowStartingActionMode(callback)

        override fun onWindowStartingActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
            delegate.onWindowStartingActionMode(callback, type)

        override fun onActionModeStarted(mode: ActionMode) = delegate.onActionModeStarted(mode)

        override fun onActionModeFinished(mode: ActionMode) = delegate.onActionModeFinished(mode)

        override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) =
            delegate.onProvideKeyboardShortcuts(data, menu, deviceId)

        override fun onPointerCaptureChanged(hasCapture: Boolean) = delegate.onPointerCaptureChanged(hasCapture)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        const val TAG = "interaction"
        const val PULSE_UI_PACKAGE = "com.naury.pulse.ui."
        const val ON_CLICK = "OnClick"
        const val TAP_SLOP = 24f
    }
}
