package com.naury.pulse.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.pulse.api.CaptureFilter
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
        PulseCard {
            RecordingSwitch(inspector, recording)
            OverlaySwitch()
        }
        Box(Modifier.padding(top = Spacing.s)) { Notice(stringResource(R.string.pulse_settings_readonly)) }

        ConfigSections(config)
        SectionTitle(stringResource(R.string.pulse_settings_health))
        PulseCard {
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
        }

        EnvironmentSection(inspector)

        inspector.remote?.let { RemoteSection(it) }

        MockRulesSection(inspector.mocks)

        SectionTitle(stringResource(R.string.pulse_settings_data))
        OutlinedButton(onClick = onClear, modifier = Modifier.padding(bottom = Spacing.xl)) {
            Text(stringResource(R.string.pulse_clear), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun OverlaySwitch() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(PulseOverlay.isEnabled(context)) }
    SwitchRow(stringResource(R.string.pulse_settings_overlay), enabled) {
        enabled = it
        PulseOverlay.setEnabled(context, it)
    }
    Text(
        stringResource(R.string.pulse_settings_overlay_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    PulseCard {
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
        Field(stringResource(R.string.pulse_settings_formats), bodyFormatsText(config))
        Field(stringResource(R.string.pulse_settings_filter), filterText(config), monospace = true)
        Field(stringResource(R.string.pulse_settings_context), contextText(config))
        Field(stringResource(R.string.pulse_settings_endpoints), endpointsText(config), monospace = true)
    }

    SectionTitle(stringResource(R.string.pulse_settings_retention))
    PulseCard {
        Field(
            stringResource(R.string.pulse_settings_retention),
            stringResource(
                R.string.pulse_settings_retention_value,
                config.retention.maxTransactions,
                config.retention.maxAgeMillis / MILLIS_PER_HOUR,
                Formatters.bytes(config.retention.maxBodyBytes),
            ),
        )
    }

    SectionTitle(stringResource(R.string.pulse_settings_privacy))
    PulseCard {
        Field(stringResource(R.string.pulse_settings_headers), config.redaction.headers.sorted().joinToString(", "), monospace = true)
        Field(stringResource(R.string.pulse_settings_query), config.redaction.queryParameters.sorted().joinToString(", "), monospace = true)
        Field(stringResource(R.string.pulse_settings_names), config.redaction.sensitiveNames.sorted().joinToString(", "), monospace = true)
    }
}

@Composable
private fun bodyFormatsText(config: PulseConfig): String = listOfNotNull(
    "JSON",
    if (config.bodyFormats.forms) "form" else null,
    if (config.bodyFormats.text) "text·XML" else null,
).joinToString(", ")

@Composable
private fun filterText(config: PulseConfig): String {
    val filter = config.filter
    if (filter == CaptureFilter.ALL) return stringResource(R.string.pulse_settings_filter_all)
    return listOfNotNull(
        filter.onlyHosts.takeIf { it.isNotEmpty() }?.let { "only: " + it.sorted().joinToString() },
        filter.ignoredHosts.takeIf { it.isNotEmpty() }?.let { "skip: " + it.sorted().joinToString() },
        filter.ignoredPathPrefixes.takeIf { it.isNotEmpty() }?.let { "skip paths: " + it.sorted().joinToString() },
    ).joinToString("\n")
}

@Composable
private fun contextText(config: PulseConfig): String {
    val context = config.context
    val parts = listOfNotNull(
        if (context.screen) stringResource(R.string.pulse_field_screen) else null,
        if (context.appState) stringResource(R.string.pulse_field_app_state) else null,
        if (context.networkType) stringResource(R.string.pulse_field_network) else null,
    )
    return if (parts.isEmpty()) stringResource(R.string.pulse_settings_off) else parts.joinToString(", ")
}

@Composable
private fun endpointsText(config: PulseConfig): String {
    val grouping = config.endpoints
    return listOfNotNull(
        stringResource(if (grouping.autoTemplates) R.string.pulse_settings_endpoints_auto else R.string.pulse_settings_endpoints_exact),
        grouping.templates.takeIf { it.isNotEmpty() }?.joinToString(),
    ).joinToString("\n")
}
