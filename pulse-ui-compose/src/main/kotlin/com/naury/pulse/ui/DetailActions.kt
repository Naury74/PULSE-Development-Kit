package com.naury.pulse.ui

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.TransactionDetail
import com.naury.pulse.export.CurlFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 요청을 cURL 명령으로 복사한다. 가려진 헤더는 넣지 않으며, 누를 때만 만든다. */
@Composable
internal fun CurlButton(inspector: PulseInspector, detail: TransactionDetail) {
    CopyButton(label = stringResource(R.string.pulse_copy_curl)) { curlOf(inspector, detail) }
}

private suspend fun curlOf(inspector: PulseInspector, detail: TransactionDetail): String {
    val body = if (detail.request.bodyState == BodyState.CAPTURED) inspector.loadBody(detail.summary.id, MessageKind.REQUEST) else null
    return CurlFormatter(inspector.config.redaction).format(detail, body)
}

/** 요청 하나를 이슈나 메신저에 붙일 수 있는 글로 공유한다. 누를 때만 만들고, 앱이 고른 대상에만 보낸다. */
@Composable
internal fun ShareButton(inspector: PulseInspector, detail: TransactionDetail) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.pulse_share_title)
    OutlinedButton(onClick = {
        scope.launch {
            val id = detail.summary.id
            val report = withContext(Dispatchers.Default) {
                val requestBody = if (detail.request.bodyState == BodyState.CAPTURED) inspector.loadBody(id, MessageKind.REQUEST) else null
                val responseBody =
                    if (detail.response?.bodyState == BodyState.CAPTURED) inspector.loadBody(id, MessageKind.RESPONSE) else null
                ReportFormatter.format(detail, requestBody, responseBody, curlOf(inspector, detail))
            }
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "${detail.summary.method} ${detail.summary.path}")
                .putExtra(Intent.EXTRA_TEXT, report)
            context.startActivity(Intent.createChooser(send, chooserTitle))
        }
    }) {
        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.padding(end = Spacing.xs))
        Text(stringResource(R.string.pulse_share))
    }
}

/** 재실행이 켜져 있을 때만 보인다([com.naury.pulse.api.PulseConfig.replay]). */
@Composable
internal fun ReplayButton(inspector: PulseInspector, transactionId: String, onOpenTransaction: (String) -> Unit) {
    if (inspector.config.replay == null) return
    var open by rememberSaveable(transactionId) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.padding(end = Spacing.xs))
        Text(stringResource(R.string.pulse_replay))
    }
    if (open) {
        ReplayDialog(inspector, transactionId, onDismiss = { open = false }) {
            open = false
            onOpenTransaction(it)
        }
    }
}
