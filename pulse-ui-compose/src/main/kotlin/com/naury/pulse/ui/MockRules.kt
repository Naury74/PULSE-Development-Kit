package com.naury.pulse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.pulse.api.MockResponseSpec
import com.naury.pulse.api.MockRule
import com.naury.pulse.api.PathMatch
import com.naury.pulse.api.PulseMocks

/** 설정 탭의 모의 응답 영역. 켜져 있으면 실제 서버 대신 합성 응답이 나간다는 경고를 늘 보여 준다. */
@Composable
internal fun MockRulesSection(mocks: PulseMocks) {
    val enabled by mocks.enabled.collectAsStateWithLifecycle()
    val rules by mocks.rules.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.pulse_mocks_title))
    Notice(
        stringResource(R.string.pulse_mocks_warning),
        if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SwitchRow(stringResource(R.string.pulse_mocks_enabled), enabled) { mocks.setEnabled(it) }
    rules.forEach { rule ->
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${rule.method ?: "*"} ${rule.path} → ${rule.response.statusCode}", style = MonospaceStyle)
                Text(ruleSummary(rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = rule.enabled, onCheckedChange = { mocks.upsert(rule.copy(enabled = it)) })
            TextButton(onClick = { mocks.remove(rule.id) }) { Text(stringResource(R.string.pulse_mocks_delete)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.pulse_mocks_add)) }
        if (enabled) OutlinedButton(onClick = mocks::disableAll) { Text(stringResource(R.string.pulse_mocks_disable_all)) }
    }
    if (editing) {
        MockRuleDialog(onDismiss = { editing = false }) { rule ->
            mocks.upsert(rule)
            editing = false
        }
    }
}

@Composable
private fun ruleSummary(rule: MockRule): String = listOfNotNull(
    rule.host,
    stringResource(
        when (rule.pathMatch) {
            PathMatch.EXACT -> R.string.pulse_mocks_match_exact
            PathMatch.PREFIX -> R.string.pulse_mocks_match_prefix
            PathMatch.REGEX -> R.string.pulse_mocks_match_regex
        },
    ),
    stringResource(R.string.pulse_mocks_priority_value, rule.priority),
    rule.remainingUses?.let { stringResource(R.string.pulse_mocks_uses_value, it) },
    rule.response.delayMillis.takeIf { it > 0 }?.let { stringResource(R.string.pulse_mocks_delay_value, it) },
).joinToString(" · ")

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** 규칙 편집기. 저장할 때 규칙의 검사 규칙(정규식 길이·상태 코드 범위 등)을 그대로 적용하고 실패 이유를 보여 준다. */
@Composable
private fun MockRuleDialog(onDismiss: () -> Unit, onSave: (MockRule) -> Unit) {
    var method by rememberSaveable { mutableStateOf("GET") }
    var host by rememberSaveable { mutableStateOf("") }
    var match by rememberSaveable { mutableStateOf(PathMatch.EXACT) }
    var path by rememberSaveable { mutableStateOf("/") }
    var status by rememberSaveable { mutableStateOf("200") }
    var body by rememberSaveable { mutableStateOf("{}") }
    var delay by rememberSaveable { mutableStateOf("0") }
    var uses by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf("0") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val invalidNumber = stringResource(R.string.pulse_mocks_invalid_number)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text(stringResource(R.string.pulse_mocks_add), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(method, { method = it }, label = { Text(stringResource(R.string.pulse_mocks_method)) }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.pulse_mocks_host)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    PathMatch.entries.forEach { option ->
                        FilterChip(selected = match == option, onClick = { match = option }, label = { Text(option.name.lowercase()) })
                    }
                }
                OutlinedTextField(path, { path = it }, label = { Text(stringResource(R.string.pulse_mocks_path)) }, singleLine = true)
                NumberField(status, { status = it }, stringResource(R.string.pulse_mocks_status))
                OutlinedTextField(
                    body,
                    { body = it },
                    label = { Text(stringResource(R.string.pulse_body)) },
                    minLines = 2,
                    textStyle = MonospaceStyle,
                    modifier = Modifier.fillMaxWidth(),
                )
                NumberField(delay, { delay = it }, stringResource(R.string.pulse_mocks_delay))
                NumberField(uses, { uses = it }, stringResource(R.string.pulse_mocks_uses))
                NumberField(priority, { priority = it }, stringResource(R.string.pulse_mocks_priority))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                SaveOrCancel(onDismiss) {
                    error = try {
                        onSave(MockForm(method, host, match, path, status, body, delay, uses, priority).toRule(invalidNumber))
                        null
                    } catch (invalid: IllegalArgumentException) {
                        invalid.message
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveOrCancel(onDismiss: () -> Unit, onSave: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Button(onClick = onSave) { Text(stringResource(R.string.pulse_mocks_save)) }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.pulse_cancel)) }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private class MockForm(
    val method: String,
    val host: String,
    val match: PathMatch,
    val path: String,
    val status: String,
    val body: String,
    val delay: String,
    val uses: String,
    val priority: String,
) {
    /** 입력을 규칙으로 바꾼다. 숫자가 아니거나 규칙 검사에 걸리면 [IllegalArgumentException]. */
    fun toRule(invalidNumber: String): MockRule {
        fun number(text: String, default: String?): Long? =
            text.ifBlank { default }?.let { it.trim().toLongOrNull() ?: throw IllegalArgumentException(invalidNumber) }
        return MockRule(
            path = path,
            response = MockResponseSpec(
                statusCode = number(status, null)?.toInt() ?: throw IllegalArgumentException(invalidNumber),
                body = body,
                delayMillis = number(delay, "0") ?: 0,
            ),
            pathMatch = match,
            method = method,
            host = host,
            priority = number(priority, "0")?.toInt() ?: 0,
            remainingUses = number(uses, null)?.toInt(),
        )
    }
}
