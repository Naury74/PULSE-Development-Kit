package com.naury.pulse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.SessionSummary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 화면 전체가 함께 쓰는 기간. 둘 다 null이면 기간 제한이 없다. [label]은 칩에 보여 줄 이름, [sessionId]는 실행 하나를 고른 경우다. */
@Immutable
internal class TimeRange(val from: Long?, val to: Long?, val label: String?, val sessionId: String? = null) {
    val active: Boolean
        get() = from != null || to != null

    /** [epochMillis]가 이 기간 안인지. */
    operator fun contains(epochMillis: Long): Boolean = (from == null || epochMillis >= from) && (to == null || epochMillis <= to)

    companion object {
        val ALL = TimeRange(null, null, null)

        fun startOfToday(now: Long = System.currentTimeMillis()): Long = startOfDay(now, 0)

        /** 오늘에서 [startDaysAgo]일 전 0시부터 [endDaysAgo]일 전 23:59:59.999까지. 일광 절약 시간에도 날짜 경계를 지킨다. */
        fun days(startDaysAgo: Int, endDaysAgo: Int, label: String, now: Long = System.currentTimeMillis()): TimeRange =
            TimeRange(startOfDay(now, -startDaysAgo), startOfDay(now, 1 - endDaysAgo) - 1, label)

        /** 달력은 UTC 0시로 날짜를 주므로 기기 시간대의 그날 0시~23:59:59.999로 바꾼다. */
        fun fromPicker(startUtc: Long, endUtc: Long): TimeRange {
            val start = localMidnight(startUtc)
            val end = startOfDay(localMidnight(endUtc), 1) - 1
            val format = SimpleDateFormat("MM.dd", Locale.getDefault())
            return TimeRange(start, end, format.format(Date(start)) + " – " + format.format(Date(end)))
        }

        /** [epochMillis]가 속한 날에서 [dayOffset]일 옮긴 날의 기기 시간대 0시. */
        private fun startOfDay(epochMillis: Long, dayOffset: Int): Long = Calendar.getInstance().apply {
            timeInMillis = epochMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, dayOffset)
        }.timeInMillis

        private fun localMidnight(utcMidnight: Long): Long {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
            return Calendar.getInstance().apply {
                clear()
                set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }

        fun ofSession(session: SessionSummary, label: String): TimeRange =
            TimeRange(session.startedAtEpochMillis, session.lastSeenEpochMillis.takeUnless { session.current }, label, session.id)
    }
}

/** 기간 칩. 누르면 기간·날짜·실행(세션)을 고르는 시트를 연다. 기간이 걸려 있으면 지우기 단추를 보여 준다. */
@Composable
internal fun TimeRangeChip(inspector: PulseInspector, range: TimeRange, onChange: (TimeRange) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FilterChip(
        selected = range.active,
        onClick = { if (range.active) onChange(TimeRange.ALL) else open = true },
        label = { Text(range.label ?: stringResource(R.string.pulse_range_any)) },
        leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
        trailingIcon = if (range.active) {
            { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.pulse_range_clear)) }
        } else {
            null
        },
        shape = MaterialTheme.shapes.small,
        colors = dashboardChipColors(),
        border = dashboardChipBorder(range.active),
    )
    if (open) {
        TimeRangeSheet(inspector, onDismiss = { open = false }) {
            open = false
            onChange(it)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRangeSheet(inspector: PulseInspector, onDismiss: () -> Unit, onPick: (TimeRange) -> Unit) {
    var calendar by remember { mutableStateOf(false) }
    val sessions by produceState(emptyList<SessionSummary>(), inspector) { value = inspector.loadSessions(null, null, MAX_SESSIONS) }
    PulseSheet(
        icon = Icons.Filled.DateRange,
        title = stringResource(R.string.pulse_range_title),
        subtitle = stringResource(R.string.pulse_range_subtitle),
        onDismiss = onDismiss,
        actions = {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pulse_cancel)) }
        },
    ) {
        RangePresets(onPick)
        FilterChip(
            selected = false,
            onClick = { calendar = true },
            label = { Text(stringResource(R.string.pulse_range_calendar)) },
            leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
            shape = MaterialTheme.shapes.small,
            colors = dashboardChipColors(),
            border = dashboardChipBorder(false),
        )
        if (sessions.isNotEmpty()) {
            SheetLabel(stringResource(R.string.pulse_range_sessions))
            sessions.forEachIndexed { index, session ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SessionRow(session) { label -> onPick(TimeRange.ofSession(session, label)) }
            }
        }
    }
    if (calendar) {
        CalendarDialog(onDismiss = { calendar = false }) {
            calendar = false
            onPick(it)
        }
    }
}

@Composable
private fun RangePresets(onPick: (TimeRange) -> Unit) {
    val presets = listOf(
        TimeRange.days(startDaysAgo = 0, endDaysAgo = 0, label = stringResource(R.string.pulse_range_today)),
        TimeRange.days(startDaysAgo = 1, endDaysAgo = 1, label = stringResource(R.string.pulse_range_yesterday)),
        TimeRange.days(startDaysAgo = 6, endDaysAgo = 0, label = stringResource(R.string.pulse_range_7d)),
        TimeRange.days(startDaysAgo = 29, endDaysAgo = 0, label = stringResource(R.string.pulse_range_30d)),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        presets.forEach { preset ->
            FilterChip(
                selected = false,
                onClick = { onPick(preset) },
                label = { Text(preset.label.orEmpty()) },
                shape = MaterialTheme.shapes.small,
                colors = dashboardChipColors(),
                border = dashboardChipBorder(false),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDialog(onDismiss: () -> Unit, onPick: (TimeRange) -> Unit) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val start = state.selectedStartDateMillis ?: return@TextButton
                    onPick(TimeRange.fromPicker(start, state.selectedEndDateMillis ?: start))
                },
            ) { Text(stringResource(R.string.pulse_range_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pulse_cancel)) } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SessionRow(session: SessionSummary, onPick: (String) -> Unit) {
    val format = SimpleDateFormat("MM.dd HH:mm", currentLocale())
    val label = format.format(Date(session.startedAtEpochMillis))
    val duration = Formatters.duration((session.lastSeenEpochMillis - session.startedAtEpochMillis) * NANOS_PER_MILLI)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onPick(label) }
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(
                    R.string.pulse_range_session_detail,
                    duration,
                    Formatters.duration(session.foregroundMillis * NANOS_PER_MILLI),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            session.current -> Pill(stringResource(R.string.pulse_range_session_current), MaterialTheme.colorScheme.primary)
            session.crashed -> Pill(session.exitReason ?: "CRASH", LocalStatusColors.current.serverError)
            else -> session.exitReason?.let { Pill(it, MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private const val MAX_SESSIONS = 30
private const val NANOS_PER_MILLI = 1_000_000L
