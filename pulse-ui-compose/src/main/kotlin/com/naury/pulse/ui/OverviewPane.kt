package com.naury.pulse.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.StatsDimension
import com.naury.pulse.api.StatsQuery
import com.naury.pulse.api.StatsWindow
import com.naury.pulse.api.StatusFilter
import com.naury.pulse.api.TrafficStatistics

private val TwoColumnMinWidth = 640.dp

/**
 * 개요 탭(대시보드). 기간·호스트·화면·라벨로 거른 기록의 지표·추이·분포·엔드포인트·기준별 분류를 보여 준다.
 * 넓은 화면에서는 카드를 두 열로 놓는다.
 */
@Composable
internal fun OverviewPane(
    inspector: PulseInspector,
    window: StatsWindow,
    onWindowChange: (StatsWindow) -> Unit,
    onOpenTraffic: (StatusFilter) -> Unit,
    modifier: Modifier = Modifier,
    onSearchTraffic: (String) -> Unit = {},
    range: TimeRange = TimeRange.ALL,
    onRangeChange: (TimeRange) -> Unit = {},
) {
    var app by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf<String?>(null) }
    var screen by rememberSaveable { mutableStateOf<String?>(null) }
    var label by rememberSaveable { mutableStateOf<String?>(null) }
    var synthetic by rememberSaveable { mutableStateOf(false) }
    val query = StatsQuery(window, host, screen, label, synthetic, range.from, range.to)
    val stats = rememberLive(inspector, query) { inspector.loadStatistics(query) }
    val filters = DashboardFilters(
        query = query,
        onWindow = onWindowChange,
        onHost = { host = it },
        onScreen = { screen = it },
        onLabel = { label = it },
        onSynthetic = { synthetic = it },
    )
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        DashboardModeSwitch(app) { app = it }
        if (app) {
            Row(Modifier.padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                TimeRangeChip(inspector, range, onRangeChange)
                if (!range.active) WindowSelector(window, onWindowChange)
            }
            val now = System.currentTimeMillis()
            AppDashboard(inspector, range.from ?: window.millis?.let { now - it }, range.to, onRangeChange)
            return@Column
        }
        FilterBar(filters, stats, inspector, range, onRangeChange)
        when {
            stats == null -> Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) { PulseLoading() }
            stats.counts.total == 0 -> Box(Modifier.padding(top = Spacing.m)) { Notice(stringResource(R.string.pulse_overview_empty)) }
            else -> DashboardContent(stats, filters, onOpenTraffic, onSearchTraffic)
        }
    }
}

@Composable
private fun DashboardModeSwitch(app: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(R.string.pulse_dashboard_network, R.string.pulse_dashboard_app).forEachIndexed { index, label ->
            SegmentedButton(
                selected = app == (index == 1),
                onClick = { onChange(index == 1) },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                icon = {},
            ) { Text(stringResource(label)) }
        }
    }
}

/** 대시보드 거르기 상태와 바꾸는 방법. */
internal class DashboardFilters(
    val query: StatsQuery,
    val onWindow: (StatsWindow) -> Unit,
    val onHost: (String?) -> Unit,
    val onScreen: (String?) -> Unit,
    val onLabel: (String?) -> Unit,
    val onSynthetic: (Boolean) -> Unit,
) {
    /** 분류 표의 값을 눌렀을 때 그 값으로 거른다. 거를 수 없는 기준이면 false. */
    fun drillDown(dimension: StatsDimension, key: String): Boolean {
        when (dimension) {
            StatsDimension.HOST -> onHost(key)
            StatsDimension.SCREEN -> onScreen(key)
            StatsDimension.LABEL -> onLabel(key)
            else -> return false
        }
        return true
    }
}

@Composable
private fun DashboardContent(
    stats: TrafficStatistics,
    filters: DashboardFilters,
    onOpenTraffic: (StatusFilter) -> Unit,
    onSearchTraffic: (String) -> Unit,
) {
    if (stats.sampleLimitReached) {
        Box(Modifier.padding(top = Spacing.s)) {
            Notice(stringResource(R.string.pulse_overview_sample_limit, TrafficStatistics.MAX_SAMPLES))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= TwoColumnMinWidth
        Column {
            HealthCard(stats, onOpenTraffic)
            KpiGrid(stats, columns = if (wide) 4 else 2, onOpenTraffic = onOpenTraffic)
            val left: @Composable () -> Unit = {
                TrendCard(stats)
                LatencyCard(stats)
                StatusCard(stats)
            }
            val right: @Composable () -> Unit = {
                NetworkCard(stats)
                EndpointCard(stats, onSearchTraffic)
                BreakdownCard(stats, filters)
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    Column(Modifier.weight(1f)) { left() }
                    Column(Modifier.weight(1f)) { right() }
                }
            } else {
                left()
                right()
            }
        }
    }
}

@Composable
private fun FilterBar(
    filters: DashboardFilters,
    stats: TrafficStatistics?,
    inspector: PulseInspector,
    range: TimeRange,
    onRangeChange: (TimeRange) -> Unit,
) {
    Row(Modifier.padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        TimeRangeChip(inspector, range, onRangeChange)
        if (!range.active) WindowSelector(filters.query.window, filters.onWindow)
    }
    val facets = stats?.facets
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = Modifier
            .padding(top = Spacing.s)
            .horizontalScroll(rememberScrollState()),
    ) {
        FacetChip(stringResource(R.string.pulse_filter_host), filters.query.host, facets?.hosts.orEmpty(), filters.onHost)
        FacetChip(stringResource(R.string.pulse_filter_screen), filters.query.screen, facets?.screens.orEmpty(), filters.onScreen)
        FacetChip(stringResource(R.string.pulse_filter_label), filters.query.label, facets?.labels.orEmpty(), filters.onLabel)
        stats?.let { ReportChip(it, filters.query) }
        FilterChip(
            selected = filters.query.includeSynthetic,
            onClick = { filters.onSynthetic(!filters.query.includeSynthetic) },
            label = { Text(stringResource(R.string.pulse_filter_synthetic)) },
            shape = MaterialTheme.shapes.small,
            colors = dashboardChipColors(),
            border = dashboardChipBorder(filters.query.includeSynthetic),
        )
    }
}

/** 지금 보이는 통계를 보고서로 공유하는 칩. */
@Composable
private fun ReportChip(stats: TrafficStatistics, query: StatsQuery) {
    var open by remember { mutableStateOf(false) }
    FilterChip(
        selected = false,
        onClick = { open = true },
        label = { Text(stringResource(R.string.pulse_report_chip)) },
        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
        shape = MaterialTheme.shapes.small,
        colors = dashboardChipColors(),
        border = dashboardChipBorder(false),
    )
    if (open) StatsReportSheet(stats, query) { open = false }
}

/** 값 하나를 고르는 칩. 고른 값이 있으면 이름과 지우기 단추를 보여 준다. 선택지가 없으면 누를 수 없다. */
@Composable
private fun FacetChip(name: String, selected: String?, options: List<String>, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            enabled = selected != null || options.isNotEmpty(),
            onClick = { if (selected != null) onSelect(null) else expanded = true },
            label = { Text(if (selected == null) name else "$name: $selected") },
            trailingIcon = {
                Icon(
                    if (selected == null) Icons.Filled.ArrowDropDown else Icons.Filled.Close,
                    contentDescription = if (selected == null) null else stringResource(R.string.pulse_filter_clear, name),
                )
            },
            shape = MaterialTheme.shapes.small,
            colors = dashboardChipColors(),
            border = dashboardChipBorder(selected != null),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    expanded = false
                    onSelect(option)
                })
            }
        }
    }
}

@Composable
internal fun WindowSelector(window: StatsWindow, onChange: (StatsWindow) -> Unit) {
    val labels = mapOf(
        StatsWindow.LAST_5_MINUTES to R.string.pulse_window_5m,
        StatsWindow.LAST_HOUR to R.string.pulse_window_1h,
        StatsWindow.LAST_24_HOURS to R.string.pulse_window_24h,
        StatsWindow.ALL to R.string.pulse_window_all,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        StatsWindow.entries.forEach { option ->
            FilterChip(
                selected = option == window,
                onClick = { onChange(option) },
                label = { Text(stringResource(labels.getValue(option))) },
                shape = MaterialTheme.shapes.small,
                colors = dashboardChipColors(),
                border = dashboardChipBorder(option == window),
            )
        }
    }
}

@Composable
internal fun dashboardChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
internal fun dashboardChipBorder(selected: Boolean) = FilterChipDefaults.filterChipBorder(
    enabled = true,
    selected = selected,
    borderColor = MaterialTheme.colorScheme.outlineVariant,
)
