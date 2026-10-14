package com.naury.pulse.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.GroupStatistics
import com.naury.pulse.api.StatsDimension
import com.naury.pulse.api.StatsQuery
import com.naury.pulse.api.TrafficStatistics
import java.util.Locale

/** 대시보드 통계를 글로 만든다. 저장된(마스킹된) 값으로 계산한 수치만 쓴다. */
internal object StatsReportFormatter {
    private const val TOP_ENDPOINTS = 15
    private const val TOP_GROUPS = 8

    fun markdown(stats: TrafficStatistics, query: StatsQuery): String = buildString {
        val counts = stats.counts
        appendLine("## PULSE dashboard report")
        appendLine()
        appendLine(
            "Window: ${query.window.name}" + listOfNotNull(
                query.host?.let { " · host=$it" },
                query.screen?.let { " · screen=$it" },
                query.label?.let { " · label=$it" },
            ).joinToString(""),
        )
        stats.fromEpochMillis?.let { appendLine("Range: ${Formatters.dateTime(it)} – ${Formatters.dateTime(stats.toEpochMillis)}") }
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("| --- | --- |")
        row("Requests", counts.total.toString())
        stats.requestsPerMinute?.let { row("Requests / min", String.format(Locale.ROOT, "%.1f", it)) }
        row("Success", "${counts.success} (${percentText(counts.success, counts.total)})")
        row("4xx / 5xx / failed", "${counts.clientError} / ${counts.serverError} / ${counts.failed}")
        row(
            "p50 / p95 / p99",
            listOf(stats.durations.p50Nanos, stats.durations.p95Nanos, stats.durations.p99Nanos).joinToString(" / ") {
                ms(it)
            },
        )
        row("Time to first byte p95", ms(stats.timeToFirstByte.p95Nanos))
        row("Response bytes", Formatters.bytes(stats.bytes.responseBytes))
        val connections = stats.connections
        row("Connection reuse", "${connections.reused} / ${connections.reused + connections.newConnections}")
        if (stats.statusCodes.isNotEmpty()) {
            appendLine()
            appendLine("Status codes: " + stats.statusCodes.joinToString(", ") { "${it.key}×${it.count}" })
        }
        if (stats.failureTypes.isNotEmpty()) appendLine("Failures: " + stats.failureTypes.joinToString(", ") { "${it.key}×${it.count}" })
        appendLine()
        appendLine("### Endpoints")
        appendLine()
        appendLine("| Endpoint | Requests | Errors | p50 | p95 | Bytes |")
        appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
        stats.endpoints.take(TOP_ENDPOINTS).forEach {
            appendLine(
                "| `${it.method} ${it.path}` | ${it.count} | ${percentText(it.errorCount, it.count)} | ${ms(it.p50Nanos)} | " +
                    "${ms(it.p95Nanos.takeIf { _ -> it.p50Nanos != null })} | ${Formatters.bytes(it.responseBytes)} |",
            )
        }
        listOf(StatsDimension.SCREEN, StatsDimension.LABEL, StatsDimension.NETWORK).forEach { dimension ->
            val groups = stats.breakdowns[dimension].orEmpty().filter { it.key != GroupStatistics.UNKNOWN_KEY }
            if (groups.isEmpty()) return@forEach
            appendLine()
            appendLine("### By ${dimension.name.lowercase()}")
            appendLine()
            groups.take(TOP_GROUPS).forEach {
                appendLine("- `${it.key}`: ${it.count} requests, errors ${percentText(it.errorCount, it.count)}, p95 ${ms(it.p95Nanos)}")
            }
        }
    }

    /** 엔드포인트 표. 스프레드시트에서 바로 열 수 있게 RFC 4180으로 쓴다. */
    fun endpointsCsv(stats: TrafficStatistics): String = buildString {
        append("method,host,path,requests,errors,p50Ms,p95Ms,maxMs,responseBytes\r\n")
        stats.endpoints.forEach {
            val values = listOf(
                it.method, it.host, it.path, it.count.toString(), it.errorCount.toString(),
                millis(it.p50Nanos), millis(it.p50Nanos?.let { _ -> it.p95Nanos }), millis(it.p50Nanos?.let { _ -> it.maxNanos }),
                it.responseBytes.toString(),
            )
            append(
                values.joinToString(",") { value ->
                    if (value.any { c -> c == ',' || c == '"' }) {
                        "\"" + value.replace("\"", "\"\"") +
                            "\""
                    } else {
                        value
                    }
                },
            )
            append("\r\n")
        }
    }

    private fun StringBuilder.row(name: String, value: String) {
        appendLine("| $name | $value |")
    }

    private fun ms(nanos: Long?): String = nanos?.let(Formatters::duration) ?: "—"

    private fun millis(nanos: Long?): String = nanos?.let { String.format(Locale.ROOT, "%.2f", it / NANOS_PER_MILLI) }.orEmpty()

    private const val NANOS_PER_MILLI = 1_000_000.0
}

/** 대시보드 통계를 Markdown 요약이나 엔드포인트 CSV로 공유한다. */
@Composable
internal fun StatsReportSheet(stats: TrafficStatistics, query: StatsQuery, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var csv by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.pulse_report_title)
    PulseSheet(
        icon = Icons.Filled.Info,
        title = title,
        subtitle = stringResource(R.string.pulse_report_subtitle, stats.counts.total),
        onDismiss = onDismiss,
        actions = {
            SheetButtons(stringResource(R.string.pulse_export_share), onDismiss) {
                val text = if (csv) StatsReportFormatter.endpointsCsv(stats) else StatsReportFormatter.markdown(stats, query)
                val send = Intent(Intent.ACTION_SEND)
                    .setType(if (csv) "text/csv" else "text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, title)
                    .putExtra(Intent.EXTRA_TEXT, text)
                onDismiss()
                context.startActivity(Intent.createChooser(send, title))
            }
        },
    ) {
        ReportOption(stringResource(R.string.pulse_report_markdown), stringResource(R.string.pulse_report_markdown_detail), !csv) {
            csv =
                false
        }
        ReportOption(stringResource(R.string.pulse_report_csv), stringResource(R.string.pulse_report_csv_detail), csv) { csv = true }
    }
}

@Composable
private fun ReportOption(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
