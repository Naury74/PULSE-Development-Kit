package com.naury.pulse.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.startup.Initializer
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.PulseInspectorRegistry
import com.naury.pulse.api.RecordingState
import com.naury.pulse.api.StatsWindow
import com.naury.pulse.api.TransactionQuery
import com.naury.pulse.api.TransactionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * 앱 시작 때 androidx.startup이 부른다. PULSE가 초기화되면 알림을 띄우기 시작하고, 닫히면 알림을 지운다.
 * 호스트 앱이 `pulse_notification_enabled`를 false로 덮어쓰면 아무것도 하지 않는다.
 */
internal class PulseUiInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val app = context.applicationContext
        (app as? android.app.Application)?.let(PulseOverlay::install)
        if (!app.resources.getBoolean(R.bool.pulse_notification_enabled)) return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            PulseInspectorRegistry.updates.collectLatest { inspector ->
                if (inspector == null) PulseNotifier.cancel(app) else PulseNotifier(app, inspector).run()
            }
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

/** 기록한 요청 수·오류 수와 최근 요청을 보여 주는 알림. 알림 권한이 없으면 아무것도 띄우지 않는다. */
internal class PulseNotifier(private val context: Context, private val inspector: PulseInspector) {
    @OptIn(FlowPreview::class)
    suspend fun run() {
        createChannel()
        combine(
            inspector.observeChanges().onStart {
                emit(Unit)
            },
            inspector.recordingState,
            refreshes.onStart { emit(Unit) },
        ) { _, state, _ -> state }
            .debounce(UPDATE_DEBOUNCE_MILLIS)
            .collect { state -> if (canNotify()) post(state) }
    }

    private suspend fun post(state: RecordingState) {
        val stats = inspector.loadStatistics(StatsWindow.LAST_HOUR).counts
        val recent = inspector.loadTransactions(TransactionQuery(), null, RECENT_LINES).items
        val errors = stats.clientError + stats.serverError + stats.failed
        val title = context.getString(R.string.pulse_notification_counts, stats.total, errors)
        val text = when {
            state == RecordingState.PAUSED -> context.getString(R.string.pulse_notification_paused)
            recent.isEmpty() -> context.getString(R.string.pulse_notification_idle)
            else -> line(recent.first())
        }
        val style = NotificationCompat.InboxStyle().setSummaryText(context.getString(R.string.pulse_notification_open))
        recent.forEach { style.addLine(line(it)) }
        val paused = state == RecordingState.PAUSED
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.pulse_ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.pulse_brand))
            .setContentTitle("PULSE · $title")
            .setContentText(text)
            .setStyle(style)
            .setContentIntent(openInspector())
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // 잠금 화면에서는 요청 경로를 숨기고 개수만 보여 준다.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.pulse_ic_notification)
                    .setContentTitle("PULSE · $title")
                    .build(),
            )
            .addAction(
                0,
                context.getString(if (paused) R.string.pulse_resume else R.string.pulse_pause),
                broadcast(if (paused) PulseNotificationReceiver.ACTION_RESUME else PulseNotificationReceiver.ACTION_PAUSE),
            )
            .addAction(0, context.getString(R.string.pulse_clear), broadcast(PulseNotificationReceiver.ACTION_CLEAR))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // 확인한 뒤 사용자가 권한을 거둔 경우. 다음 변경 때 다시 확인한다.
        }
    }

    private fun line(summary: TransactionSummary): String {
        val result = summary.statusCode?.toString() ?: summary.failureType?.substringAfterLast('.') ?: summary.state.name
        val duration = summary.durationNanos?.let { " · " + Formatters.duration(it) }.orEmpty()
        return "$result  ${summary.method} ${summary.path}$duration"
    }

    private fun canNotify(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.pulse_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.pulse_notification_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun openInspector(): PendingIntent {
        val intent = Intent(context, PulseInspectorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun broadcast(action: String): PendingIntent {
        val intent = Intent(context, PulseNotificationReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val CHANNEL_ID = "pulse.activity"
        private const val NOTIFICATION_ID = 0x50554c
        private const val RECENT_LINES = 5
        private const val UPDATE_DEBOUNCE_MILLIS = 700L

        private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /** 알림 권한을 새로 받았을 때처럼 데이터 변경 없이 알림을 다시 그려야 할 때 부른다. */
        fun refresh() {
            refreshes.tryEmit(Unit)
        }

        fun cancel(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
}

/** 알림의 멈춤·재개·지우기 버튼. 앱 안에서만 받는다(`exported=false`). */
internal class PulseNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val inspector = PulseInspectorRegistry.current ?: return
        when (intent.action) {
            ACTION_PAUSE -> inspector.pause()
            ACTION_RESUME -> inspector.resume()
            ACTION_CLEAR -> inspector.clear()
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.naury.pulse.ui.action.PAUSE"
        const val ACTION_RESUME = "com.naury.pulse.ui.action.RESUME"
        const val ACTION_CLEAR = "com.naury.pulse.ui.action.CLEAR"
    }
}
