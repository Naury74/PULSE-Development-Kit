package com.naury.pulse.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.pulse.api.ChaosAction
import com.naury.pulse.api.ChaosRule
import com.naury.pulse.api.PulseChaos

/** 설정 탭의 네트워크 장애 모사. 설정에서 허용한 호스트에만 지연·실패·시간 초과·429/500/503을 만든다. */
@Composable
internal fun ChaosSection(chaos: PulseChaos) {
    val enabled by chaos.enabled.collectAsStateWithLifecycle()
    val rules by chaos.rules.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.pulse_chaos_title))
    PulseCard {
        Notice(
            stringResource(R.string.pulse_chaos_warning, chaos.policy.allowedHosts.joinToString()),
            if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(stringResource(R.string.pulse_chaos_enabled), enabled) { chaos.setEnabled(it) }
        rules.forEach { rule ->
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${rule.host}${rule.pathPrefix} → ${rule.action}", style = MonospaceStyle)
                    Text(
                        stringResource(R.string.pulse_chaos_rule_summary, (rule.probability * PERCENT).toInt(), rule.delayMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = { chaos.upsert(rule.withEnabled(it)) })
                TextButton(onClick = { chaos.remove(rule.id) }) { Text(stringResource(R.string.pulse_mocks_delete)) }
            }
        }
        OutlinedButton(onClick = { adding = true }) { Text(stringResource(R.string.pulse_chaos_add)) }
    }
    if (adding) ChaosRuleSheet(chaos, onDismiss = { adding = false })
}

@Composable
private fun ChaosRuleSheet(chaos: PulseChaos, onDismiss: () -> Unit) {
    var host by rememberSaveable { mutableStateOf(chaos.policy.allowedHosts.firstOrNull { !it.startsWith("*") }.orEmpty()) }
    var path by rememberSaveable { mutableStateOf("/") }
    var action by rememberSaveable { mutableStateOf(ChaosAction.STATUS_503) }
    var percent by rememberSaveable { mutableStateOf("100") }
    var delay by rememberSaveable { mutableStateOf(ChaosRule.DEFAULT_DELAY_MILLIS.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    PulseSheet(
        icon = Icons.Filled.Warning,
        title = stringResource(R.string.pulse_chaos_add),
        subtitle = stringResource(R.string.pulse_chaos_sheet_subtitle),
        onDismiss = onDismiss,
        actions = {
            SheetButtons(stringResource(R.string.pulse_mocks_save), onDismiss) {
                error = try {
                    chaos.upsert(
                        ChaosRule(
                            host = host,
                            action = action,
                            pathPrefix = path.ifBlank { "/" },
                            probability = (percent.toIntOrNull() ?: PERCENT).coerceIn(0, PERCENT) / PERCENT.toFloat(),
                            delayMillis = delay.toLongOrNull() ?: 0,
                        ),
                    )
                    onDismiss()
                    null
                } catch (invalid: IllegalArgumentException) {
                    invalid.message
                }
            }
        },
    ) {
        OutlinedTextField(host, {
            host = it
        }, label = { Text(stringResource(R.string.pulse_chaos_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(path, {
            path = it
        }, label = { Text(stringResource(R.string.pulse_chaos_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            ChaosAction.entries.forEach { option ->
                FilterChip(
                    selected = action == option,
                    onClick = { action = option },
                    label = { Text(option.name) },
                    shape = MaterialTheme.shapes.small,
                    colors = dashboardChipColors(),
                    border = dashboardChipBorder(action == option),
                )
            }
        }
        NumberFields(percent, { percent = it }, delay) { delay = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun NumberFields(percent: String, onPercent: (String) -> Unit, delay: String, onDelay: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        listOf(
            Triple(percent, onPercent, R.string.pulse_chaos_probability),
            Triple(delay, onDelay, R.string.pulse_chaos_delay),
        ).forEach { (value, onChange, label) ->
            OutlinedTextField(
                value,
                { onChange(it.filter(Char::isDigit)) },
                label = { Text(stringResource(label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private const val PERCENT = 100
