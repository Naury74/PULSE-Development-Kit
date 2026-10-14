package com.naury.pulse.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.EventQuery
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.export.EventExportFormat
import com.naury.pulse.export.EventExporter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AROUND_MILLIS = 60_000L

/**
 * 이벤트 하나의 전체 내용. 마커·구간·오류는 그 시점(앞뒤 1분)이나 구간으로 모든 탭을 거를 수 있다.
 * 화면 캡처가 있으면 함께 보여 준다.
 */
@Composable
internal fun EventDetailSheet(inspector: PulseInspector, event: AppEvent, onRangeChange: (TimeRange) -> Unit, onDismiss: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, event.attachment) {
        value = event.attachment?.let { name ->
            withContext(Dispatchers.IO) {
                inspector.loadAttachment(name)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
        }
    }
    PulseSheet(
        icon = Icons.Filled.Info,
        title = eventBadge(event) + (event.tag?.let { " · $it" } ?: ""),
        subtitle = Formatters.dateTime(event.timestampEpochMillis),
        onDismiss = onDismiss,
        actions = { EventDetailActions(inspector, event, onRangeChange, onDismiss) },
    ) {
        PulseCard {
            Field(stringResource(R.string.pulse_events_kind), event.kind.name + (event.level?.let { " · $it" } ?: ""))
            event.screen?.let { Field(stringResource(R.string.pulse_field_screen), it, monospace = true) }
            event.durationMillis?.let { Field(stringResource(R.string.pulse_field_duration), Formatters.duration(it * NANOS_PER_MILLI)) }
            event.value?.let {
                Field(
                    stringResource(R.string.pulse_events_value),
                    if (event.kind ==
                        EventKind.MEMORY
                    ) {
                        Formatters.bytes(it)
                    } else {
                        it.toString()
                    },
                )
            }
            Field(stringResource(R.string.pulse_events_session), event.sessionId.take(SESSION_PREFIX), monospace = true)
        }
        SheetLabel(stringResource(R.string.pulse_events_message))
        SelectionContainer { Text(event.message, style = MonospaceStyle) }
        event.detail?.let { detail ->
            SheetLabel(stringResource(R.string.pulse_events_detail))
            SelectionContainer {
                Text(
                    detail,
                    style = MonospaceStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LocalAccentColors.current.codeBackground, MaterialTheme.shapes.medium)
                        .horizontalScroll(rememberScrollState())
                        .padding(Spacing.m),
                )
            }
        }
        image?.let {
            SheetLabel(stringResource(R.string.pulse_events_screenshot))
            Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp))
        }
    }
}

/** 구간 시작·끝 이벤트로 구간의 기간을 만든다. 아직 끝나지 않은 구간은 지금까지로 본다. */
private suspend fun spanRange(inspector: PulseInspector, event: AppEvent): TimeRange {
    val label = event.message
    val duration = event.durationMillis
    if (event.kind == EventKind.SPAN_END && duration != null) {
        return TimeRange(event.timestampEpochMillis - duration, event.timestampEpochMillis, label)
    }
    val end = inspector.loadEvents(
        EventQuery(kinds = setOf(EventKind.SPAN_END), fromEpochMillis = event.timestampEpochMillis),
        null,
        SPAN_SEARCH,
    )
        .items.firstOrNull { it.spanId == event.spanId }
    return TimeRange(event.timestampEpochMillis, end?.timestampEpochMillis, label)
}

/** 그 시점 앞뒤 1분·구간만 보기·복사 버튼. */
@Composable
private fun EventDetailActions(inspector: PulseInspector, event: AppEvent, onRangeChange: (TimeRange) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val time = SimpleDateFormat("MM.dd HH:mm:ss", currentLocale()).format(Date(event.timestampEpochMillis))
    val aroundLabel = stringResource(R.string.pulse_events_around_label, time)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        OutlinedButton(onClick = {
            onRangeChange(TimeRange(event.timestampEpochMillis - AROUND_MILLIS, event.timestampEpochMillis + AROUND_MILLIS, aroundLabel))
            onDismiss()
        }) { Text(stringResource(R.string.pulse_events_around)) }
        if (event.spanId != null) {
            OutlinedButton(onClick = {
                scope.launch {
                    onRangeChange(spanRange(inspector, event))
                    onDismiss()
                }
            }) { Text(stringResource(R.string.pulse_events_span_only)) }
        }
        CopyButton { listOfNotNull(event.message, event.detail).joinToString("\n\n") }
    }
}

/** 로그 탭 내보내기. 지금 거른 조건 그대로, 또는 조건 없이 전부를 텍스트·JSON·CSV로 저장하거나 공유한다. */
@Composable
internal fun EventExportSheet(inspector: PulseInspector, filtered: EventQuery, scope: CoroutineScope, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var format by rememberSaveable { mutableStateOf(EventExportFormat.TEXT) }
    var useFilter by rememberSaveable { mutableStateOf(true) }
    var details by rememberSaveable { mutableStateOf(true) }
    var share by rememberSaveable { mutableStateOf(false) }
    val failed = stringResource(R.string.pulse_export_failed)
    val title = stringResource(R.string.pulse_events_export)
    val query = if (useFilter) filtered else EventQuery()
    val write: suspend (OutputStream) -> Int = { EventExporter(inspector.config.redaction).export(inspector, query, format, it, details) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onDismiss()
        scope.launch {
            val count = writeToDocument(context, uri, write)
            Toast.makeText(context, count?.let { resources.getString(R.string.pulse_export_done, it) } ?: failed, Toast.LENGTH_LONG).show()
        }
    }
    PulseSheet(
        icon = Icons.Filled.Share,
        title = title,
        onDismiss = onDismiss,
        actions = {
            SheetButtons(stringResource(if (share) R.string.pulse_export_share else R.string.pulse_export_confirm), onDismiss) {
                val name = "pulse-events-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.${format.fileExtension}"
                if (share) {
                    onDismiss()
                    scope.launch {
                        val shared = writeAndShare(context, name, format.mimeType, title) { write(it) }
                        if (!shared) Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
                    }
                } else {
                    launcher.launch(name)
                }
            }
        },
    ) {
        Notice(stringResource(R.string.pulse_events_export_warning))
        SheetLabel(stringResource(R.string.pulse_export_format))
        EventFormatChips(format) { format = it }
        SheetLabel(stringResource(R.string.pulse_export_range))
        TwoChoiceRow(
            stringResource(R.string.pulse_events_export_filtered),
            stringResource(R.string.pulse_events_export_all),
            useFilter,
        ) { useFilter = it }
        if (format != EventExportFormat.CSV) SwitchRow(stringResource(R.string.pulse_events_export_details), details) { details = it }
        DestinationChoice(share) { share = it }
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
private const val SESSION_PREFIX = 8
private const val SPAN_SEARCH = 200

@Composable
private fun EventFormatChips(selected: EventExportFormat, onSelect: (EventExportFormat) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        EventExportFormat.entries.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(option.name) },
                shape = MaterialTheme.shapes.small,
                colors = dashboardChipColors(),
                border = dashboardChipBorder(selected == option),
            )
        }
    }
}
