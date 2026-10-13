package com.naury.pulse.ui

import androidx.annotation.StringRes
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import com.naury.pulse.api.ReplayBlockReason
import com.naury.pulse.api.ReplayDraft
import com.naury.pulse.api.ReplayOutcome
import com.naury.pulse.api.ReplayPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 저장된 요청을 고쳐 다시 보내는 화면. 보내기 전에 대상 호스트 허용 여부와 위험을 보여 주고,
 * 상태를 바꿀 수 있는 요청은 확인란을 체크해야 보낸다. 진행 중에는 취소할 수 있다.
 */
@Composable
internal fun ReplayDialog(inspector: PulseInspector, transactionId: String, onDismiss: () -> Unit, onOpenTransaction: (String) -> Unit) {
    val policy = inspector.config.replay ?: return
    val draft by produceState<ReplayDraft?>(null, transactionId) { value = inspector.replayDraft(transactionId) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            val loaded = draft
            if (loaded == null) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                ReplayEditor(inspector, policy, loaded, onDismiss, onOpenTransaction)
            }
        }
    }
}

@Composable
private fun ReplayEditor(
    inspector: PulseInspector,
    policy: ReplayPolicy,
    original: ReplayDraft,
    onDismiss: () -> Unit,
    onOpenTransaction: (String) -> Unit,
) {
    var method by rememberSaveable { mutableStateOf(original.method) }
    var url by rememberSaveable { mutableStateOf(original.url) }
    var headers by rememberSaveable { mutableStateOf(original.headers.joinToString("\n") { "${it.name}: ${it.value}" }) }
    var body by rememberSaveable { mutableStateOf(original.body.orEmpty()) }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ReplayOutcome?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val draft = ReplayDraft(original.sourceTransactionId, method, url, parseHeaders(headers), body.ifEmpty { null }, original.contentType)

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(stringResource(R.string.pulse_replay_title), style = MaterialTheme.typography.titleLarge)
        HostBadge(policy, url)
        if (original.removedHeaders.isNotEmpty()) {
            Notice(stringResource(R.string.pulse_replay_removed_headers, original.removedHeaders.joinToString()))
        }
        ReplayFields(method, { method = it }, url, { url = it }, headers, { headers = it }, body, { body = it })
        if (body.contains(REDACTED)) Notice(stringResource(R.string.pulse_replay_redacted_body), MaterialTheme.colorScheme.error)
        if (draft.mutatesState) {
            Notice(stringResource(R.string.pulse_replay_mutation_warning), MaterialTheme.colorScheme.error)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(acknowledged, role = Role.Checkbox) { acknowledged = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = acknowledged, onCheckedChange = null)
                Text(stringResource(R.string.pulse_replay_acknowledge), modifier = Modifier.padding(start = Spacing.s))
            }
        }
        outcome?.let { ReplayResult(it, onOpenTransaction) }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            if (job?.isActive == true) {
                CircularProgressIndicator(Modifier.padding(end = Spacing.s))
                OutlinedButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.pulse_cancel)) }
            } else {
                Button(
                    enabled = !draft.mutatesState || acknowledged,
                    onClick = {
                        outcome = null
                        // 보내기 버튼을 누른 것 자체가 확인이다. 상태를 바꾸는 요청은 확인란도 체크해야 버튼이 켜진다.
                        job = scope.launch { outcome = inspector.replay(draft, confirmed = true) }
                    },
                ) { Text(stringResource(R.string.pulse_replay_send)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.pulse_close)) }
            }
        }
    }
}

@Composable
private fun HostBadge(policy: ReplayPolicy, url: String) {
    val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore(':').substringAfterLast('@')
    val allowed = host.isNotEmpty() && policy.allowsHost(host)
    val colors = LocalStatusColors.current
    Text(
        stringResource(if (allowed) R.string.pulse_replay_host_allowed else R.string.pulse_replay_host_blocked, host.ifEmpty { "?" }),
        color = if (allowed) colors.success else colors.serverError,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Suppress("LongParameterList")
@Composable
private fun ReplayFields(
    method: String,
    onMethod: (String) -> Unit,
    url: String,
    onUrl: (String) -> Unit,
    headers: String,
    onHeaders: (String) -> Unit,
    body: String,
    onBody: (String) -> Unit,
) {
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    OutlinedTextField(method, onMethod, label = { Text(stringResource(R.string.pulse_field_method)) }, singleLine = true, textStyle = mono)
    OutlinedTextField(url, onUrl, label = {
        Text(stringResource(R.string.pulse_field_url))
    }, textStyle = mono, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        headers,
        onHeaders,
        label = { Text(stringResource(R.string.pulse_replay_headers)) },
        textStyle = mono,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        body,
        onBody,
        label = { Text(stringResource(R.string.pulse_body)) },
        textStyle = mono,
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ReplayResult(outcome: ReplayOutcome, onOpenTransaction: (String) -> Unit) {
    when (outcome) {
        is ReplayOutcome.Completed -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(R.string.pulse_replay_completed, outcome.statusCode, outcome.redirects),
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(onClick = {
                onOpenTransaction(outcome.transactionId)
            }) { Text(stringResource(R.string.pulse_replay_open_result)) }
        }

        is ReplayOutcome.Failed -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Notice(stringResource(R.string.pulse_replay_failed, outcome.errorType.substringAfterLast('.'), outcome.message.orEmpty()))
            OutlinedButton(onClick = {
                onOpenTransaction(outcome.transactionId)
            }) { Text(stringResource(R.string.pulse_replay_open_result)) }
        }

        is ReplayOutcome.Blocked -> Notice(
            stringResource(outcome.reason.message(), outcome.detail.orEmpty()),
            MaterialTheme.colorScheme.error,
        )
    }
}

private fun parseHeaders(text: String): List<HeaderField> = text.lines()
    .mapNotNull { line ->
        val name = line.substringBefore(':', missingDelimiterValue = "").trim()
        if (name.isEmpty()) null else HeaderField(name, line.substringAfter(':').trim())
    }

@StringRes
private fun ReplayBlockReason.message(): Int = when (this) {
    ReplayBlockReason.DISABLED -> R.string.pulse_replay_blocked_disabled
    ReplayBlockReason.INVALID_REQUEST -> R.string.pulse_replay_blocked_invalid
    ReplayBlockReason.SCHEME_NOT_ALLOWED -> R.string.pulse_replay_blocked_scheme
    ReplayBlockReason.HOST_NOT_ALLOWED -> R.string.pulse_replay_blocked_host
    ReplayBlockReason.CONFIRMATION_REQUIRED -> R.string.pulse_replay_blocked_confirmation
    ReplayBlockReason.BODY_TOO_LARGE -> R.string.pulse_replay_blocked_body
    ReplayBlockReason.REDIRECT_NOT_ALLOWED -> R.string.pulse_replay_blocked_redirect
    ReplayBlockReason.TOO_MANY_REDIRECTS -> R.string.pulse_replay_blocked_redirects
    ReplayBlockReason.BUSY -> R.string.pulse_replay_blocked_busy
}
