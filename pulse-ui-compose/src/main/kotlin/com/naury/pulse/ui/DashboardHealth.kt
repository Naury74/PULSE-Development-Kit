package com.naury.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.StatusFilter
import com.naury.pulse.api.TrafficStatistics

/** 결과 구성 도넛과 범례. 가운데에는 성공률을, 범례 줄을 누르면 그 결과의 요청 목록으로 간다. */
@Composable
internal fun HealthCard(stats: TrafficStatistics, onOpenTraffic: (StatusFilter) -> Unit) {
    val counts = stats.counts
    val colors = LocalStatusColors.current
    val rows = listOf(
        HealthRow(R.string.pulse_filter_success, counts.success, colors.success, StatusFilter.SUCCESS),
        HealthRow(R.string.pulse_filter_client_error, counts.clientError, colors.clientError, StatusFilter.CLIENT_ERROR),
        HealthRow(R.string.pulse_filter_server_error, counts.serverError, colors.serverError, StatusFilter.SERVER_ERROR),
        HealthRow(R.string.pulse_filter_failed, counts.failed, colors.failure, StatusFilter.FAILED),
        HealthRow(R.string.pulse_filter_in_flight, counts.inFlight, colors.pending, StatusFilter.IN_FLIGHT),
    )
    val successRate = percentText(counts.success, counts.total)
    Box(Modifier.padding(top = Spacing.m)) {
        PulseCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                DonutChart(
                    segments = rows.map { it.count to it.color },
                    description = stringResource(R.string.pulse_health_description, successRate, counts.total),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(successRate, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.pulse_health_success_rate),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    rows.filter { it.count > 0 || it.filter != StatusFilter.IN_FLIGHT }.forEach { row ->
                        LegendRow(stringResource(row.label), row.count, counts.total, row.color) { onOpenTraffic(row.filter) }
                    }
                }
            }
        }
    }
}

private class HealthRow(val label: Int, val count: Int, val color: Color, val filter: StatusFilter)

@Composable
private fun LegendRow(label: String, count: Int, total: Int, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            percentText(count, total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.xs),
        )
    }
}
