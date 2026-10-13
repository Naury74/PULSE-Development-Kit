package com.naury.pulse.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.TransactionQuery
import com.naury.pulse.export.ExportFormat
import com.naury.pulse.export.ExportResult
import com.naury.pulse.export.PulseExporter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 현재 검색·필터에 맞는 기록을 파일로 내보낸다. 사용자가 고른 위치(SAF)에만 쓰고 자동으로 공유하지 않는다.
 * 실패하거나 화면을 떠나 취소되면 반쯤 쓴 파일을 지운다.
 */
@Composable
internal fun ExportAction(inspector: PulseInspector, query: () -> TransactionQuery) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var format by rememberSaveable { mutableStateOf(ExportFormat.HAR) }
    val messages = ExportMessages(
        started = stringResource(R.string.pulse_export_started),
        failed = stringResource(R.string.pulse_export_failed),
    )
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(documentMimeType(format))) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        Toast.makeText(context, messages.started, Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = runExport(context, uri, inspector, query(), format)
            val text = result?.let { exportSummary(context, it) } ?: messages.failed
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
    IconButton(onClick = { showDialog = true }) {
        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.pulse_export))
    }
    if (showDialog) {
        ExportDialog(
            format = format,
            onFormatChange = { format = it },
            onDismiss = { showDialog = false },
            onConfirm = {
                showDialog = false
                launcher.launch("pulse-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.${format.fileExtension}")
            },
        )
    }
}

/** HAR은 JSON MIME으로 만들면 문서 공급자가 `.har.json`처럼 확장자를 덧붙이므로 일반 바이너리로 만든다. */
private fun documentMimeType(format: ExportFormat): String = if (format == ExportFormat.HAR) "application/octet-stream" else format.mimeType

private class ExportMessages(val started: String, val failed: String)

private suspend fun runExport(
    context: Context,
    uri: Uri,
    inspector: PulseInspector,
    query: TransactionQuery,
    format: ExportFormat,
): ExportResult? = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            PulseExporter(inspector.config.redaction).export(inspector, query, format, out)
        } ?: throw IOException("출력 스트림을 열 수 없습니다.")
    } catch (cancel: CancellationException) {
        withContext(NonCancellable) { deleteQuietly(context, uri) }
        throw cancel
    } catch (_: IOException) {
        deleteQuietly(context, uri)
        null
    }
}

private fun deleteQuietly(context: Context, uri: Uri) {
    try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (_: IllegalArgumentException) {
        // 문서 공급자가 삭제를 지원하지 않으면 남은 파일은 사용자가 지운다.
    } catch (_: java.io.FileNotFoundException) {
        // 이미 없다.
    } catch (_: UnsupportedOperationException) {
        // 삭제를 지원하지 않는 공급자.
    }
}

private fun exportSummary(context: Context, result: ExportResult): String = buildList {
    add(context.getString(R.string.pulse_export_done, result.transactionCount))
    if (result.truncated) add(context.getString(R.string.pulse_export_truncated))
    if (result.droppedBodyCount > 0) add(context.getString(R.string.pulse_export_dropped, result.droppedBodyCount))
}.joinToString(" ")

@Composable
private fun ExportDialog(format: ExportFormat, onFormatChange: (ExportFormat) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pulse_export_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                Text(stringResource(R.string.pulse_export_warning), style = MaterialTheme.typography.bodyMedium)
                listOf(
                    ExportFormat.HAR to R.string.pulse_export_har,
                    ExportFormat.PULSE_JSON to R.string.pulse_export_json,
                ).forEach { (option, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = option == format, role = Role.RadioButton) { onFormatChange(option) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == format, onClick = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = Spacing.s))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.pulse_export_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pulse_cancel)) } },
    )
}
