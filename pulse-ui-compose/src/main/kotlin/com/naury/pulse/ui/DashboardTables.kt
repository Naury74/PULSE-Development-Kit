package com.naury.pulse.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.naury.pulse.api.EndpointStatistics
import com.naury.pulse.api.GroupStatistics
import com.naury.pulse.api.NetworkType
import com.naury.pulse.api.StatsDimension
import com.naury.pulse.api.TrafficStatistics

private const val COLLAPSED_ROWS = 8

/** 관찰한 연결 단계 시간과 연결 재사용·캐시 근거. */
@Composable
internal fun NetworkCard(stats: TrafficStatistics) {
    if (stats.phases.isEmpty() && stats.connections.network == 0) return
    SectionTitle(stringResource(R.string.pulse_network_title))
    PulseCard {
        val max = stats.phases.maxOfOrNull { it.p95Nanos }?.coerceAtLeast(1) ?: 1
        val accents = LocalAccentColors.current
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            stats.phases.forEach { phase ->
                val label = if (phase.type == com.naury.pulse.api.TimingPhaseType.RESPONSE_HEADERS) {
                    stringResource(R.string.pulse_field_ttfb_short)
                } else {
                    stringResource(phase.type.label())
                }
                ProportionRow(
                    label,
                    (phase.p95Nanos * PROPORTION_SCALE / max).toInt(),
                    PROPORTION_SCALE.toInt(),
                    accents.phases.getValue(phase.type),
                    trailing = stringResource(
                        R.string.pulse_network_phase_value,
                        Formatters.duration(phase.p50Nanos),
                        Formatters.duration(phase.p95Nanos),
                        phase.count,
                    ),
                )
            }
        }
        val connections = stats.connections
        Text(
            stringResource(
                R.string.pulse_network_evidence,
                connections.reused,
                connections.newConnections,
                connections.cacheHits,
                connections.conditionalHits,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.m),
        )
    }
}

private const val PROPORTION_SCALE = 1_000L

private enum class EndpointSort(@param:StringRes val label: Int) {
    COUNT(R.string.pulse_sort_count),
    P95(R.string.pulse_sort_p95),
    ERRORS(R.string.pulse_sort_errors),
    BYTES(R.string.pulse_sort_bytes),
}

/** 엔드포인트 표. 기준을 바꿔 정렬하고, 누르면 그 경로로 트래픽을 검색한다. */
@Composable
internal fun EndpointCard(stats: TrafficStatistics, onSearchTraffic: (String) -> Unit) {
    if (stats.endpoints.isEmpty()) return
    var sort by rememberSaveable { mutableStateOf(EndpointSort.COUNT) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.pulse_endpoints_title, stats.endpoints.size))
    PulseCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            EndpointSort.entries.forEach { option ->
                FilterChip(
                    selected = sort == option,
                    onClick = { sort = option },
                    label = { Text(stringResource(option.label)) },
                    shape = MaterialTheme.shapes.small,
                    colors = dashboardChipColors(),
                    border = dashboardChipBorder(sort == option),
                )
            }
        }
        val sorted = when (sort) {
            EndpointSort.COUNT -> stats.endpoints.sortedByDescending { it.count }
            EndpointSort.P95 -> stats.endpoints.sortedByDescending { it.p95Nanos }
            EndpointSort.ERRORS -> stats.endpoints.sortedByDescending { it.errorCount.toDouble() / it.count }
            EndpointSort.BYTES -> stats.endpoints.sortedByDescending { it.responseBytes }
        }
        val visible = if (expanded) sorted else sorted.take(COLLAPSED_ROWS)
        visible.forEachIndexed { index, endpoint ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            EndpointRow(endpoint) { onSearchTraffic(endpoint.path.substringBefore('{').ifEmpty { endpoint.path }) }
        }
        if (sorted.size > COLLAPSED_ROWS) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.pulse_show_less else R.string.pulse_show_all, sorted.size))
            }
        }
    }
}

@Composable
private fun EndpointRow(endpoint: EndpointStatistics, onClick: () -> Unit) {
    val status = LocalStatusColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                MethodTag(endpoint.method)
                Text(
                    endpoint.path,
                    style = MonospaceStyle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.pulse_endpoint_detail, endpoint.host, Formatters.bytes(endpoint.responseBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                stringResource(R.string.pulse_endpoint_count, endpoint.count),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(
                    endpoint.p50Nanos?.let { "p95 " + Formatters.duration(endpoint.p95Nanos) },
                    if (endpoint.errorCount >
                        0
                    ) {
                        stringResource(R.string.pulse_endpoint_errors, percentText(endpoint.errorCount, endpoint.count))
                    } else {
                        null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (endpoint.errorCount > 0) status.serverError else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 화면·라벨·호스트 등 기준별로 나눠 본 표. 화면·라벨·호스트 값을 누르면 그 값으로 대시보드를 거른다. */
@Composable
internal fun BreakdownCard(stats: TrafficStatistics, filters: DashboardFilters) {
    var dimension by rememberSaveable { mutableStateOf(StatsDimension.SCREEN) }
    SectionTitle(stringResource(R.string.pulse_breakdown_title))
    PulseCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            StatsDimension.entries.forEach { option ->
                FilterChip(
                    selected = dimension == option,
                    onClick = { dimension = option },
                    label = { Text(stringResource(option.label())) },
                    shape = MaterialTheme.shapes.small,
                    colors = dashboardChipColors(),
                    border = dashboardChipBorder(dimension == option),
                )
            }
        }
        val groups = stats.breakdowns[dimension].orEmpty()
        if (groups.isEmpty() || groups.all { it.key == GroupStatistics.UNKNOWN_KEY }) {
            Text(
                stringResource(R.string.pulse_breakdown_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.m),
            )
        }
        val total = stats.counts.total
        Column(Modifier.padding(top = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            groups.forEach { group ->
                val name = groupName(dimension, group.key)
                val drillable = group.key != GroupStatistics.UNKNOWN_KEY &&
                    dimension in setOf(StatsDimension.HOST, StatsDimension.SCREEN, StatsDimension.LABEL)
                Column(
                    if (drillable) Modifier.clickable(role = Role.Button) { filters.drillDown(dimension, group.key) } else Modifier,
                ) {
                    ProportionRow(name, group.count, total, MaterialTheme.colorScheme.primary)
                    Text(
                        listOfNotNull(
                            group.p95Nanos?.let { "p95 " + Formatters.duration(it) },
                            stringResource(R.string.pulse_endpoint_errors, percentText(group.errorCount, group.count)),
                            Formatters.bytes(group.responseBytes),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (group.errorCount >
                            0
                        ) {
                            LocalStatusColors.current.serverError
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = Spacing.xs),
                    )
                }
            }
        }
    }
}

@StringRes
private fun StatsDimension.label(): Int = when (this) {
    StatsDimension.HOST -> R.string.pulse_dimension_host
    StatsDimension.SCREEN -> R.string.pulse_dimension_screen
    StatsDimension.LABEL -> R.string.pulse_dimension_label
    StatsDimension.NETWORK -> R.string.pulse_dimension_network
    StatsDimension.APP_STATE -> R.string.pulse_dimension_app_state
    StatsDimension.METHOD -> R.string.pulse_dimension_method
    StatsDimension.PROTOCOL -> R.string.pulse_dimension_protocol
}

@Composable
private fun groupName(dimension: StatsDimension, key: String): String = when {
    key == GroupStatistics.UNKNOWN_KEY -> stringResource(R.string.pulse_breakdown_unknown)

    dimension == StatsDimension.NETWORK -> NetworkType.entries.firstOrNull { it.name == key }?.let { networkLabel(it) } ?: key

    dimension == StatsDimension.APP_STATE -> stringResource(
        if (key ==
            "foreground"
        ) {
            R.string.pulse_app_foreground
        } else {
            R.string.pulse_app_background
        },
    )

    else -> key
}
