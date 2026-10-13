package com.naury.pulse.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.pulse.api.CaptureMode
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.RecordingState

private const val MILLIS_PER_HOUR = 60L * 60 * 1000

/** 설정 탭. 기록 전환과 지우기만 바꿀 수 있고, 나머지 설정은 `PulseConfig`로 정하므로 읽기 전용으로 보여 준다. */
@Composable
internal fun SettingsPane(inspector: PulseInspector, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val recording by inspector.recordingState.collectAsStateWithLifecycle()
    val health by inspector.captureHealth.collectAsStateWithLifecycle()
    val config = inspector.config
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        RecordingSwitch(inspector, recording)
        Notice(stringResource(R.string.pulse_settings_readonly))

        ConfigSections(config)
        SectionTitle(stringResource(R.string.pulse_settings_health))
        Field(
            stringResource(R.string.pulse_settings_health),
            stringResource(
                R.string.pulse_settings_health_value,
                health.droppedEvents,
                health.droppedBodies,
                health.writeFailures,
                health.queueHighWater,
            ),
        )

        MockRulesSection(inspector.mocks)

        SectionTitle(stringResource(R.string.pulse_settings_data))
        OutlinedButton(onClick = onClear, modifier = Modifier.padding(bottom = Spacing.xl)) {
            Text(stringResource(R.string.pulse_clear), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RecordingSwitch(inspector: PulseInspector, recording: RecordingState) {
    val canToggle = recording == RecordingState.RECORDING || recording == RecordingState.PAUSED
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = recording == RecordingState.RECORDING,
                enabled = canToggle,
                role = Role.Switch,
                onValueChange = { on -> if (on) inspector.resume() else inspector.pause() },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.pulse_settings_recording),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = recording == RecordingState.RECORDING, onCheckedChange = null, enabled = canToggle)
    }
}

@Composable
private fun ConfigSections(config: PulseConfig) {
    SectionTitle(stringResource(R.string.pulse_settings_capture))
    Field(
        stringResource(R.string.pulse_settings_mode),
        stringResource(
            if (config.captureMode ==
                CaptureMode.METADATA_ONLY
            ) {
                R.string.pulse_settings_mode_metadata
            } else {
                R.string.pulse_settings_mode_bodies
            },
        ),
    )
    Field(
        stringResource(R.string.pulse_settings_limits),
        stringResource(
            R.string.pulse_settings_limits_value,
            Formatters.bytes(config.limits.maxRequestBodyBytes),
            Formatters.bytes(config.limits.maxResponseBodyBytes),
        ),
    )
    Field(stringResource(R.string.pulse_settings_queue), config.queueCapacity.toString())

    SectionTitle(stringResource(R.string.pulse_settings_retention))
    Field(
        stringResource(R.string.pulse_settings_retention),
        stringResource(
            R.string.pulse_settings_retention_value,
            config.retention.maxTransactions,
            config.retention.maxAgeMillis / MILLIS_PER_HOUR,
            Formatters.bytes(config.retention.maxBodyBytes),
        ),
    )

    SectionTitle(stringResource(R.string.pulse_settings_privacy))
    Field(stringResource(R.string.pulse_settings_headers), config.redaction.headers.sorted().joinToString(", "), monospace = true)
    Field(stringResource(R.string.pulse_settings_query), config.redaction.queryParameters.sorted().joinToString(", "), monospace = true)
    Field(stringResource(R.string.pulse_settings_names), config.redaction.sensitiveNames.sorted().joinToString(", "), monospace = true)
}
