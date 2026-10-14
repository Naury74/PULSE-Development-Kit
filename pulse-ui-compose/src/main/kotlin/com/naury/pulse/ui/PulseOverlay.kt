package com.naury.pulse.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.naury.pulse.api.PulseInspectorRegistry
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * 호스트 앱 화면 위에 띄우는 PULSE 버튼. 누르면 마커·구간·화면 캡처·인스펙터 열기 메뉴를 보여 준다.
 * 다른 앱 위에 그리는 권한 없이 각 Activity의 화면 안에만 붙이며, 인스펙터 화면에는 붙이지 않는다.
 * 켜고 끄는 값은 기기에 저장하고, 처음 값은 `pulse_overlay_enabled` 리소스를 따른다.
 */
internal object PulseOverlay : Application.ActivityLifecycleCallbacks {
    private const val PREFERENCES = "com.naury.pulse.ui"
    private const val KEY_ENABLED = "overlay_enabled"
    private const val KEY_X = "overlay_x"
    private const val KEY_Y = "overlay_y"
    private const val BUBBLE_DP = 48
    private const val MARGIN_DP = 8
    private const val TAP_SLOP_DP = 6
    private const val START_Y_FRACTION = 0.6f
    private const val ELEVATION_DP = 6
    private const val STROKE_DP = 2
    private const val MENU_MARK = 1
    private const val MENU_MARK_NAMED = 2
    private const val MENU_SPAN = 3
    private const val MENU_SCREENSHOT = 4
    private const val MENU_OPEN = 5
    private const val MENU_HIDE = 6
    private const val MENU_REPORT = 7

    private var installed = false
    private var markerCount = 0
    private var spanId: String? = null
    private var spanName: String? = null
    private var spanStartedAt = 0L
    private var current: Activity? = null
    private var bubble: Bubble? = null

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
    }

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, context.resources.getBoolean(R.bool.pulse_overlay_enabled))

    /** 켜면 지금 앞에 있는 화면에 바로 붙이고, 끄면 바로 뗀다. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) current?.let(::attach) else detach()
    }

    override fun onActivityResumed(activity: Activity) {
        current = activity
        if (activity !is PulseInspectorActivity && isEnabled(activity)) attach(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (current === activity) {
            detach()
            current = null
        }
    }

    private fun attach(activity: Activity) {
        if (activity is PulseInspectorActivity) return
        detach()
        val root = activity.window.decorView as? ViewGroup ?: return
        val view = Bubble(activity)
        val size = dp(activity, BUBBLE_DP)
        val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val params = FrameLayout.LayoutParams(size, size).apply { gravity = Gravity.TOP or Gravity.START }
        root.addView(view, params)
        root.post {
            view.x =
                preferences.getFloat(
                    KEY_X,
                    (root.width - size - dp(activity, MARGIN_DP)).toFloat(),
                ).coerceIn(0f, (root.width - size).toFloat())
            view.y = preferences.getFloat(KEY_Y, root.height * START_Y_FRACTION).coerceIn(0f, (root.height - size).toFloat())
        }
        view.recording = spanId != null
        bubble = view
    }

    private fun detach() {
        bubble?.let { (it.parent as? ViewGroup)?.removeView(it) }
        bubble = null
    }

    private fun showMenu(activity: Activity, anchor: View) {
        val menu = PopupMenu(activity, anchor)
        menu.menu.add(0, MENU_MARK, MENU_MARK, activity.getString(R.string.pulse_overlay_mark))
        menu.menu.add(0, MENU_MARK_NAMED, MENU_MARK_NAMED, activity.getString(R.string.pulse_overlay_mark_named))
        val spanLabel = spanName?.let { name ->
            activity.getString(R.string.pulse_overlay_span_end, name, (SystemClock.elapsedRealtime() - spanStartedAt) / MILLIS_PER_SECOND)
        } ?: activity.getString(R.string.pulse_overlay_span_start)
        menu.menu.add(0, MENU_SPAN, MENU_SPAN, spanLabel)
        menu.menu.add(0, MENU_REPORT, MENU_REPORT, activity.getString(R.string.pulse_overlay_report))
        menu.menu.add(0, MENU_SCREENSHOT, MENU_SCREENSHOT, activity.getString(R.string.pulse_overlay_screenshot))
        menu.menu.add(0, MENU_OPEN, MENU_OPEN, activity.getString(R.string.pulse_overlay_open))
        menu.menu.add(0, MENU_HIDE, MENU_HIDE, activity.getString(R.string.pulse_overlay_hide))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_MARK -> mark(activity, null)

                MENU_MARK_NAMED -> askName(activity, R.string.pulse_overlay_mark_named) { mark(activity, it) }

                MENU_SPAN -> if (spanId == null) {
                    askName(activity, R.string.pulse_overlay_span_start) { beginSpan(activity, it) }
                } else {
                    endSpan(activity)
                }

                MENU_SCREENSHOT -> screenshot(activity)

                MENU_REPORT -> openReport(activity, bubble)

                MENU_OPEN -> activity.startActivity(Intent(activity, PulseInspectorActivity::class.java))

                MENU_HIDE -> setEnabled(activity, false)
            }
            true
        }
        menu.show()
    }

    private fun mark(activity: Activity, name: String?) {
        val inspector = PulseInspectorRegistry.current ?: return
        markerCount++
        val label =
            name?.takeIf { it.isNotBlank() }
                ?: activity.getString(R.string.pulse_overlay_marker_default, markerCount, activity.javaClass.simpleName)
        inspector.mark(label)
        toast(activity, activity.getString(R.string.pulse_overlay_marked, label))
    }

    private fun beginSpan(activity: Activity, name: String) {
        val inspector = PulseInspectorRegistry.current ?: return
        val label = name.ifBlank { activity.getString(R.string.pulse_overlay_span_default, activity.javaClass.simpleName) }
        spanId = inspector.beginSpan(label) ?: return
        spanName = label
        spanStartedAt = SystemClock.elapsedRealtime()
        bubble?.recording = true
        toast(activity, activity.getString(R.string.pulse_overlay_span_started, label))
    }

    private fun endSpan(activity: Activity) {
        val id = spanId ?: return
        PulseInspectorRegistry.current?.endSpan(id)
        toast(activity, activity.getString(R.string.pulse_overlay_span_ended, spanName.orEmpty()))
        spanId = null
        spanName = null
        bubble?.recording = false
    }

    private fun screenshot(activity: Activity) {
        val inspector = PulseInspectorRegistry.current ?: return
        val png = captureScreen(activity, bubble) ?: return
        inspector.recordScreenshot(png, activity.getString(R.string.pulse_overlay_screenshot_note, activity.javaClass.simpleName))
        toast(activity, activity.getString(R.string.pulse_overlay_screenshot_saved))
    }

    private const val MILLIS_PER_SECOND = 1000

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    /** 둥근 버튼. 끌면 옮기고 놓으면 가까운 옆 가장자리에 붙는다. 구간 기록 중에는 빨간 점을 그린다. */
    @SuppressLint("ViewConstructor")
    private class Bubble(private val activity: Activity) : View(activity) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(activity, R.color.pulse_brand) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(activity, STROKE_DP).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RECORDING_RED }
        private var downX = 0f
        private var downY = 0f
        private var startX = 0f
        private var startY = 0f
        private var dragging = false

        var recording = false
            set(value) {
                field = value
                invalidate()
            }

        init {
            contentDescription = activity.getString(R.string.pulse_overlay_description)
            elevation = dp(activity, ELEVATION_DP).toFloat()
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawCircle(r, r, r, fill)
            // 맥박 모양 선. 아이콘 리소스 없이 그려 크기와 색을 맞춘다.
            val w = width.toFloat()
            val path = android.graphics.Path().apply {
                moveTo(w * PULSE_LINE[0], w * PULSE_LINE[1])
                for (i in 2 until PULSE_LINE.size step 2) lineTo(w * PULSE_LINE[i], w * PULSE_LINE[i + 1])
            }
            canvas.drawPath(path, stroke)
            if (recording) canvas.drawCircle(w * RECORDING_DOT[0], w * RECORDING_DOT[1], w * RECORDING_DOT[2], dot)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val parent = parent as? View ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = x
                    startY = y
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > dp(activity, TAP_SLOP_DP) || abs(dy) > dp(activity, TAP_SLOP_DP))) dragging = true
                    if (dragging) {
                        x = (startX + dx).coerceIn(0f, (parent.width - width).toFloat())
                        y = (startY + dy).coerceIn(0f, (parent.height - height).toFloat())
                    }
                }

                MotionEvent.ACTION_UP -> if (dragging) snap(parent) else performClick()
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            showMenu(activity, this)
            return true
        }

        private fun snap(parent: View) {
            val margin = dp(activity, MARGIN_DP).toFloat()
            val targetX = if (x + width / 2f < parent.width / 2f) margin else parent.width - width - margin
            animate().x(targetX).setDuration(SNAP_MILLIS).start()
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putFloat(KEY_X, targetX).putFloat(KEY_Y, y).apply()
        }

        private companion object {
            const val SNAP_MILLIS = 150L
            val RECORDING_RED = Color.rgb(0xE0, 0x31, 0x31)
        }
    }
}

private fun askName(activity: Activity, title: Int, onName: (String) -> Unit) {
    val input = EditText(activity).apply {
        setSingleLine()
        hint = activity.getString(R.string.pulse_overlay_name_hint)
    }
    AlertDialog.Builder(activity)
        .setTitle(title)
        .setView(input)
        .setPositiveButton(android.R.string.ok) { _, _ -> onName(input.text.toString().trim()) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

private fun dp(context: Context, value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

/** [view]를 너비 [maxWidth] 이하로 줄여 PNG로 만든다. 그리지 못하면 null. */
private fun capturePng(view: View, maxWidth: Int): ByteArray? = try {
    val scale = minOf(1f, maxWidth.toFloat() / view.width)
    val bitmap = Bitmap.createBitmap((view.width * scale).toInt(), (view.height * scale).toInt(), Bitmap.Config.ARGB_8888)
    Canvas(bitmap).apply { scale(scale, scale) }.also { view.draw(it) }
    ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        bitmap.recycle()
        out.toByteArray()
    }
} catch (_: RuntimeException) {
    null
}

private const val PNG_QUALITY = 100

/** 버튼 안 맥박 선의 꺾이는 점(x, y 쌍). 버튼 너비에 대한 비율이다. */
private val PULSE_LINE = floatArrayOf(0.22f, 0.52f, 0.38f, 0.52f, 0.46f, 0.34f, 0.56f, 0.68f, 0.64f, 0.46f, 0.78f, 0.46f)

/** 구간 기록 중 표시 점의 중심 x, y와 반지름. 버튼 너비에 대한 비율이다. */
private val RECORDING_DOT = floatArrayOf(0.82f, 0.18f, 0.12f)

/** 앱이 캡처를 막은 화면(FLAG_SECURE)과 설정에서 막은 화면은 찍지 않고 알린다. [hide](플로팅 버튼)는 빼고 찍는다. */
private fun captureScreen(activity: Activity, hide: View?): ByteArray? {
    val blocked = PulseInspectorRegistry.current?.config?.screenshots?.blockedScreens.orEmpty()
    if (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 || activity.javaClass.simpleName in blocked) {
        toast(activity, activity.getString(R.string.pulse_overlay_screenshot_secure))
        return null
    }
    hide?.visibility = View.INVISIBLE
    return try {
        capturePng(activity.window.decorView, MAX_CAPTURE_WIDTH)
    } finally {
        hide?.visibility = View.VISIBLE
    }
}

private const val MAX_CAPTURE_WIDTH = 1080

/** 지금 화면을 메모리에만 잡아 두고 보고 작성기를 연다. 가리기 전 이미지는 저장하지 않는다. */
private fun openReport(activity: Activity, hide: View?) {
    if (PulseInspectorRegistry.current?.reports == null) return
    ReportRequests.pending = ComposeRequest(png = captureScreen(activity, hide))
    activity.startActivity(Intent(activity, PulseInspectorActivity::class.java))
}
