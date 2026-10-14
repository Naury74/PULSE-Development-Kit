package com.naury.pulse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.pulse.api.PulseRemote

/**
 * 설정 탭의 원격 보고 영역. 상태를 보여 주고, 이 기기에서만 켜고 끄거나 주소·토큰을 바꿀 수 있다.
 * 토큰은 화면에 다시 보여 주지 않는다.
 */
@Composable
internal fun RemoteSection(remote: PulseRemote) {
    val status by remote.status.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.pulse_remote_title))
    PulseCard {
        SwitchRow(stringResource(R.string.pulse_remote_enabled), status.enabled) { remote.setOverride(it, null, null) }
        Field(
            stringResource(R.string.pulse_remote_endpoint),
            status.endpoint ?: stringResource(R.string.pulse_remote_not_configured),
            monospace = true,
        )
        Field(
            stringResource(R.string.pulse_remote_counts),
            stringResource(R.string.pulse_remote_counts_value, status.pending, status.sent),
        )
        status.lastSuccessEpochMillis?.let { Field(stringResource(R.string.pulse_remote_last_success), Formatters.dateTime(it)) }
        status.lastError?.let {
            Text(it, style = MonospaceStyle, color = LocalStatusColors.current.serverError, modifier = Modifier.padding(top = Spacing.xs))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.s)) {
            OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.pulse_remote_edit)) }
            OutlinedButton(onClick = remote::flushNow, enabled = status.enabled) { Text(stringResource(R.string.pulse_remote_send_now)) }
        }
        if (status.overridden) {
            TextButton(onClick = remote::clearOverride) { Text(stringResource(R.string.pulse_remote_reset)) }
        }
        Text(
            stringResource(R.string.pulse_remote_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (editing) RemoteEditSheet(remote, status.endpoint.orEmpty()) { editing = false }
}

@Composable
private fun RemoteEditSheet(remote: PulseRemote, currentEndpoint: String, onDismiss: () -> Unit) {
    var endpoint by remember { mutableStateOf(currentEndpoint) }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    PulseSheet(
        icon = Icons.Filled.Send,
        title = stringResource(R.string.pulse_remote_edit),
        subtitle = stringResource(R.string.pulse_remote_edit_subtitle),
        onDismiss = onDismiss,
        actions = {
            SheetButtons(stringResource(R.string.pulse_mocks_save), onDismiss) {
                error = try {
                    remote.setOverride(enabled = true, endpoint = endpoint, bearerToken = token.takeIf { it.isNotBlank() })
                    onDismiss()
                    null
                } catch (invalid: IllegalArgumentException) {
                    invalid.message
                }
            }
        },
    ) {
        OutlinedTextField(
            endpoint,
            { endpoint = it },
            label = { Text(stringResource(R.string.pulse_remote_endpoint)) },
            singleLine = true,
            textStyle = MonospaceStyle,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            token,
            { token = it },
            label = { Text(stringResource(R.string.pulse_remote_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Notice(stringResource(R.string.pulse_remote_edit_note))
    }
}
