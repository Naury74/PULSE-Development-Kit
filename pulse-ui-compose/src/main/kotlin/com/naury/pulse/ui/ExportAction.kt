package com.naury.pulse.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.TransactionQuery
import com.naury.pulse.export.ExportFormat
import com.naury.pulse.export.ExportOptions
import com.naury.pulse.export.ExportResult
import com.naury.pulse.export.PulseExporter
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 상단 바의 내보내기 단추. 현재 검색·필터를 기본 범위로 시트를 연다. */
@Composable
internal fun ExportAction(inspector: PulseInspector, query: () -> TransactionQuery) {
    var open by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.pulse_export))
    }
    if (open) ExportSheet(inspector, query(), selectedIds = null, scope = scope) { open = false }
}

private enum class ExportRange(@param:StringRes val label: Int, val millis: Long?) {
    CURRENT(R.string.pulse_export_range_current, null),
    HOUR(R.string.pulse_window_1h, HOUR_MILLIS),
    DAY(R.string.pulse_window_24h, DAY_MILLIS),
    ALL(R.string.pulse_window_all, null),
}

private const val HOUR_MILLIS = 60L * 60 * 1000
private const val DAY_MILLIS = 24 * HOUR_MILLIS

/**
 * 내보내기 시트. 형식·범위·포함할 내용·보낼 곳을 고른다. 파일은 사용자가 고른 위치(SAF)에만 쓰거나,
 * 공유를 고르면 앱 캐시에 만든 뒤 사용자가 고른 앱으로만 넘긴다. 실패하거나 취소되면 반쯤 쓴 파일을 지운다.
 *
 * @param selectedIds 목록에서 고른 요청. 있으면 범위는 이 요청들로 고정된다.
 * @param scope 시트를 닫은 뒤에도 내보내기를 이어 갈 바깥 스코프. 시트 안의 스코프는 닫을 때 취소된다.
 */
@Composable
internal fun ExportSheet(
    inspector: PulseInspector,
    query: TransactionQuery,
    selectedIds: List<String>?,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val choices = rememberExportChoices()
    val effectiveQuery = if (choices.range == ExportRange.CURRENT) query else TransactionQuery()
    val messages = ExportMessages(stringResource(R.string.pulse_export_started), stringResource(R.string.pulse_export_failed))
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(documentMimeType(choices.format))) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onDismiss()
        Toast.makeText(context, messages.started, Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = exportToDocument(context, uri, inspector, effectiveQuery, choices.format, choices.options(selectedIds))
            Toast.makeText(context, result?.let { exportSummary(context, it) } ?: messages.failed, Toast.LENGTH_LONG).show()
        }
    }
    val chooserTitle = stringResource(R.string.pulse_export_title)
    PulseSheet(
        icon = Icons.Filled.Share,
        title = stringResource(R.string.pulse_export_title),
        subtitle = selectedIds?.let { pluralStringResource(R.plurals.pulse_export_selected, it.size, it.size) },
        onDismiss = onDismiss,
        actions = {
            SheetButtons(
                confirm = stringResource(if (choices.share) R.string.pulse_export_share else R.string.pulse_export_confirm),
                onDismiss = onDismiss,
            ) {
                val name = "pulse-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.${choices.format.fileExtension}"
                if (choices.share) {
                    onDismiss()
                    scope.launch {
                        val options = choices.options(selectedIds)
                        val shared = exportAndShare(context, name, inspector, effectiveQuery, choices.format, options, chooserTitle)
                        if (!shared) Toast.makeText(context, messages.failed, Toast.LENGTH_LONG).show()
                    }
                } else {
                    launcher.launch(name)
                }
            }
        },
    ) {
        ExportChoicesContent(choices, showRange = selectedIds == null)
    }
}

/** 시트 아래의 취소·확인 단추 줄. */
@Composable
internal fun SheetButtons(confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.pulse_cancel)) }
        Button(onClick = onConfirm, modifier = Modifier.weight(1f).height(48.dp)) { Text(confirm, fontWeight = FontWeight.SemiBold) }
    }
}

/** 내보내기 선택지. 화면 회전 뒤에도 남는다. */
private class ExportChoices(
    format: MutableState<ExportFormat>,
    range: MutableState<ExportRange>,
    headers: MutableState<Boolean>,
    networkHeaders: MutableState<Boolean>,
    requestBodies: MutableState<Boolean>,
    responseBodies: MutableState<Boolean>,
    synthetic: MutableState<Boolean>,
    share: MutableState<Boolean>,
) {
    var format by format
    var range by range
    var headers by headers
    var networkHeaders by networkHeaders
    var requestBodies by requestBodies
    var responseBodies by responseBodies
    var synthetic by synthetic
    var share by share

    fun options(selectedIds: List<String>?) = ExportOptions(
        includeHeaders = headers,
        preferNetworkHeaders = networkHeaders,
        includeRequestBodies = requestBodies,
        includeResponseBodies = responseBodies,
        includeSynthetic = synthetic,
        sinceEpochMillis = range.millis?.let { System.currentTimeMillis() - it },
        transactionIds = selectedIds,
    )
}

@Composable
private fun rememberExportChoices() = ExportChoices(
    rememberSaveable { mutableStateOf(ExportFormat.HAR) },
    rememberSaveable { mutableStateOf(ExportRange.CURRENT) },
    rememberSaveable { mutableStateOf(true) },
    rememberSaveable { mutableStateOf(true) },
    rememberSaveable { mutableStateOf(true) },
    rememberSaveable { mutableStateOf(true) },
    rememberSaveable { mutableStateOf(true) },
    rememberSaveable { mutableStateOf(false) },
)

@Composable
private fun ExportChoicesContent(choices: ExportChoices, showRange: Boolean) {
    Notice(stringResource(R.string.pulse_export_warning))
    SheetLabel(stringResource(R.string.pulse_export_format))
    FormatGrid(choices.format) { choices.format = it }
    if (showRange) {
        SheetLabel(stringResource(R.string.pulse_export_range))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            ExportRange.entries.forEach { option ->
                FilterChip(
                    selected = choices.range == option,
                    onClick = { choices.range = option },
                    label = { Text(stringResource(option.label)) },
                    shape = MaterialTheme.shapes.small,
                    colors = dashboardChipColors(),
                    border = dashboardChipBorder(choices.range == option),
                )
            }
        }
    }
    SheetLabel(stringResource(R.string.pulse_export_include))
    if (choices.format != ExportFormat.CSV) {
        SwitchRow(stringResource(R.string.pulse_export_headers), choices.headers) { choices.headers = it }
        if (choices.headers) {
            SwitchRow(stringResource(R.string.pulse_export_network_headers), choices.networkHeaders) {
                choices.networkHeaders =
                    it
            }
        }
        SwitchRow(stringResource(R.string.pulse_export_request_bodies), choices.requestBodies) { choices.requestBodies = it }
        SwitchRow(stringResource(R.string.pulse_export_response_bodies), choices.responseBodies) { choices.responseBodies = it }
    }
    SwitchRow(stringResource(R.string.pulse_export_synthetic), choices.synthetic) { choices.synthetic = it }
    DestinationChoice(choices.share) { choices.share = it }
}

@Composable
private fun FormatGrid(selected: ExportFormat, onSelect: (ExportFormat) -> Unit) {
    val formats = listOf(
        Triple(ExportFormat.HAR, R.string.pulse_export_har, R.string.pulse_export_har_detail),
        Triple(ExportFormat.PULSE_JSON, R.string.pulse_export_json, R.string.pulse_export_json_detail),
        Triple(ExportFormat.CSV, R.string.pulse_export_csv, R.string.pulse_export_csv_detail),
        Triple(ExportFormat.MARKDOWN, R.string.pulse_export_markdown, R.string.pulse_export_markdown_detail),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        formats.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                row.forEach { (format, title, detail) ->
                    val active = format == selected
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            if (active) 2.dp else 1.dp,
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .selectable(selected = active, role = Role.RadioButton) { onSelect(format) },
                    ) {
                        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                minLines = 2,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}
