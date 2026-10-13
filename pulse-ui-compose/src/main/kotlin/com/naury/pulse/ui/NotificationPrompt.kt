package com.naury.pulse.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat

/**
 * 알림 권한이 없을 때 한 번 권하는 카드. "나중에"를 누르면 다시 보이지 않는다.
 * 호스트 앱이 알림을 껐거나(`pulse_notification_enabled`) Android 13 미만이면 보이지 않는다.
 */
@Composable
internal fun NotificationPrompt() {
    val context = LocalContext.current
    if (LocalInspectionMode.current || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (!context.resources.getBoolean(R.bool.pulse_notification_enabled)) return
    var visible by remember { mutableStateOf(!isGranted(context) && !isDismissed(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        visible = false
        if (granted) PulseNotifier.refresh() else dismiss(context)
    }
    if (!visible) return
    Box(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
        PulseCard {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Icon(Icons.Filled.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.pulse_notification_prompt), style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End),
                modifier = Modifier.padding(top = Spacing.s).align(Alignment.End),
            ) {
                TextButton(onClick = {
                    visible = false
                    dismiss(context)
                }) { Text(stringResource(R.string.pulse_notification_later)) }
                Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text(stringResource(R.string.pulse_notification_enable))
                }
            }
        }
    }
}

private const val PREFERENCES = "com.naury.pulse.ui"
private const val KEY_PROMPT_DISMISSED = "notification_prompt_dismissed"

private fun isGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun isDismissed(context: Context): Boolean =
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_PROMPT_DISMISSED, false)

private fun dismiss(context: Context) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_PROMPT_DISMISSED, true).apply()
}
