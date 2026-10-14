package com.naury.pulse.monitor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import com.naury.pulse.api.EnvironmentSnapshot
import com.naury.pulse.core.EventRecorder
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 호스트가 알린 기능 플래그·환경 값과 앱·기기 정보. 플래그가 바뀌면 [EventRecorder.config]로 이력을 남긴다.
 * 값은 마스킹하지 않으므로 호스트가 비밀을 넣지 않아야 한다.
 */
internal class EnvironmentRegistry(private val context: Context, private val events: EventRecorder) {
    private val state = MutableStateFlow<Map<String, String>>(emptyMap())

    val flags: StateFlow<Map<String, String>> = state.asStateFlow()

    fun setFlag(name: String, value: String?) {
        val key = name.trim().take(MAX_NAME_CHARS)
        if (key.isEmpty()) return
        val trimmed = value?.take(MAX_VALUE_CHARS)
        var changed = false
        state.update { current ->
            val next = when {
                trimmed == null -> current - key
                key in current || current.size < MAX_FLAGS -> current + (key to trimmed)
                else -> current
            }
            changed = next != current
            next
        }
        if (changed) events.config(key, trimmed)
    }

    fun snapshot(): EnvironmentSnapshot = EnvironmentSnapshot(app(), device(), state.value, System.currentTimeMillis())

    @Suppress("DEPRECATION")
    private fun app(): Map<String, String> {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val versionCode = info?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong() }
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return buildMap {
            put("package", context.packageName)
            info?.versionName?.let { put("versionName", it) }
            versionCode?.let { put("versionCode", it.toString()) }
            put("buildType", if (debuggable) "debug" else "release")
            put("targetSdk", context.applicationInfo.targetSdkVersion.toString())
        }
    }

    private fun device(): Map<String, String> {
        val configuration = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val dark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "locale" to (configuration.locales.get(0) ?: Locale.getDefault()).toLanguageTag(),
            "timeZone" to TimeZone.getDefault().id,
            "screen" to "${metrics.widthPixels}×${metrics.heightPixels} @${metrics.densityDpi}dpi",
            "fontScale" to configuration.fontScale.toString(),
            "theme" to if (dark) "dark" else "light",
        )
    }

    private companion object {
        const val MAX_FLAGS = 200
        const val MAX_NAME_CHARS = 64
        const val MAX_VALUE_CHARS = 256
    }
}
