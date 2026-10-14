package com.naury.pulse.monitor

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.LogLevel
import com.naury.pulse.core.EventRecorder
import com.naury.pulse.storage.PulseStorage

/**
 * 지난 실행이 끝난 이유를 다음 실행 때 기록한다(Android 11+). 네이티브 크래시·ANR·메모리 부족 종료처럼
 * 앱 안에서 잡을 수 없는 종료도 시스템이 남긴 기록으로 알 수 있다. 이미 기록한 종료는 다시 쓰지 않는다.
 */
internal class ExitReasons(private val context: Context, private val events: EventRecorder, private val storage: PulseStorage) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** 저장 스레드·백그라운드에서 부른다. */
    suspend fun recordSinceLastRun() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val last = preferences.getLong(KEY_LAST_TIMESTAMP, 0)
        val infos = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
            .filter { it.timestamp > last && it.processName == context.applicationInfo.processName }
            .sortedBy { it.timestamp }
        infos.forEach { record(it) }
        infos.maxOfOrNull { it.timestamp }?.let { preferences.edit().putLong(KEY_LAST_TIMESTAMP, it).apply() }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private suspend fun record(info: ApplicationExitInfo) {
        val reason = reasonName(info.reason)
        val session = storage.loadSessions(null, info.timestamp, 1, events.sessionId).firstOrNull { !it.current }
        val crashed = info.reason in crashReasons
        val level = if (crashed) LogLevel.ERROR else LogLevel.INFO
        events.record(
            kind = EventKind.EXIT,
            message = listOfNotNull(reason, info.description).joinToString(": "),
            level = level,
            tag = reason,
            detail = trace(info),
            value = info.pss * KIB,
            timestampEpochMillis = info.timestamp,
            sessionId = session?.id ?: events.sessionId,
        )
        // 앱 안에서 이미 크래시로 기록한 세션은 이유를 덮지 않는다.
        if (session != null && session.exitReason == null) events.closeSession(session.id, reason, crashed)
    }

    @TargetApi(Build.VERSION_CODES.R)
    @Suppress("TooGenericExceptionCaught")
    private fun trace(info: ApplicationExitInfo): String? {
        // 네이티브 크래시의 trace는 이진(tombstone)이라 ANR만 글자로 읽는다.
        if (info.reason != ApplicationExitInfo.REASON_ANR) return null
        return try {
            info.traceInputStream?.use { readUpTo(it, MAX_TRACE_BYTES) }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        fun readUpTo(stream: java.io.InputStream, max: Int): String {
            val buffer = ByteArray(max)
            var read = 0
            var count = 0
            while (read < buffer.size && count >= 0) {
                count = stream.read(buffer, read, buffer.size - read)
                if (count > 0) read += count
            }
            return String(buffer, 0, read, Charsets.UTF_8)
        }

        const val PREFERENCES = "com.naury.pulse.monitor"
        const val KEY_LAST_TIMESTAMP = "last_exit_timestamp"
        const val MAX_EXITS = 16
        const val MAX_TRACE_BYTES = 64 * 1024
        const val KIB = 1024L

        val crashReasons = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
        )

        @Suppress("CyclomaticComplexMethod")
        fun reasonName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            else -> "OTHER($reason)"
        }
    }
}
