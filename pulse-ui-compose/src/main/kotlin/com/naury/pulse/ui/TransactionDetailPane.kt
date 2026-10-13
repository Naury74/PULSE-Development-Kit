package com.naury.pulse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.HttpMessage
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.TransactionDetail
import com.naury.pulse.export.CurlFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

private const val PREVIEW_CHARACTERS = 32 * 1024

private sealed interface DetailLoad {
    data object Loading : DetailLoad

    data object Missing : DetailLoad

    data class Loaded(val detail: TransactionDetail) : DetailLoad
}

private sealed interface BodyLoad {
    data object Loading : BodyLoad

    data object Missing : BodyLoad

    data class Loaded(val full: String, val preview: String, val truncated: Boolean, val tree: JsonTree?) : BodyLoad
}

@Composable
internal fun TransactionDetailPane(
    inspector: PulseInspector,
    transactionId: String,
    modifier: Modifier = Modifier,
    onOpenTransaction: (String) -> Unit = {},
) {
    // 진행 중인 요청은 응답·본문이 도착할 때마다 다시 읽는다.
    val load by produceState<DetailLoad>(DetailLoad.Loading, inspector, transactionId) {
        inspector.observeChanges().onStart { emit(Unit) }.conflate().collect {
            value = inspector.loadTransaction(transactionId)?.let { DetailLoad.Loaded(it) } ?: DetailLoad.Missing
        }
    }
    when (val current = load) {
        DetailLoad.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        DetailLoad.Missing -> Box(modifier.padding(Spacing.l)) { Notice(stringResource(R.string.pulse_detail_missing)) }
        is DetailLoad.Loaded -> DetailTabs(inspector, current.detail, modifier, onOpenTransaction)
    }
}

@Composable
private fun DetailTabs(inspector: PulseInspector, detail: TransactionDetail, modifier: Modifier, onOpenTransaction: (String) -> Unit) {
    var tab by rememberSaveable(detail.summary.id) { mutableIntStateOf(0) }
    val tabs = listOf(R.string.pulse_tab_summary, R.string.pulse_tab_request, R.string.pulse_tab_response, R.string.pulse_tab_timeline)
    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) })
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
        ) {
            when (tab) {
                0 -> SummaryTab(detail)

                1 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        CurlButton(inspector, detail)
                        ReplayButton(inspector, detail.summary.id, onOpenTransaction)
                    }
                    MessageTab(inspector, detail.summary.id, MessageKind.REQUEST, detail.request)
                }

                2 -> detail.response?.let { MessageTab(inspector, detail.summary.id, MessageKind.RESPONSE, it) }
                    ?: Notice(stringResource(R.string.pulse_no_response))

                else -> TimelineTab(detail)
            }
        }
    }
}

@Composable
private fun SummaryTab(detail: TransactionDetail) {
    val summary = detail.summary
    val unknown = stringResource(R.string.pulse_unknown)
    Field(stringResource(R.string.pulse_field_url), summary.url, monospace = true)
    Field(stringResource(R.string.pulse_field_method), summary.method)
    Field(
        stringResource(R.string.pulse_field_status),
        listOfNotNull(summary.statusCode?.toString(), summary.state.name).joinToString(" · "),
    )
    Field(stringResource(R.string.pulse_field_protocol), detail.protocol ?: unknown)
    Field(stringResource(R.string.pulse_field_started), Formatters.dateTime(summary.startedAtEpochMillis))
    Field(
        stringResource(R.string.pulse_field_duration),
        summary.durationNanos?.let(Formatters::duration) ?: stringResource(R.string.pulse_not_finished),
    )
    detail.responseHeadersNanos?.let { Field(stringResource(R.string.pulse_field_ttfb), Formatters.duration(it)) }
    Field(stringResource(R.string.pulse_field_request_size), summary.requestBodyBytes?.let(Formatters::bytes) ?: unknown)
    Field(stringResource(R.string.pulse_field_response_size), summary.responseBodyBytes?.let(Formatters::bytes) ?: unknown)
    summary.failureType?.let { type ->
        Field(stringResource(R.string.pulse_field_failure), listOfNotNull(type, detail.failureMessage).joinToString("\n"), monospace = true)
    }
    Field(stringResource(R.string.pulse_field_source), summary.source.name)
}

@Composable
private fun MessageTab(inspector: PulseInspector, transactionId: String, kind: MessageKind, message: HttpMessage) {
    SectionTitle(stringResource(R.string.pulse_headers))
    HeaderTable(message.headers)
    SectionTitle(stringResource(R.string.pulse_body))
    if (message.bodyState != BodyState.CAPTURED) {
        Notice(stringResource(message.bodyState.explanation()))
        return
    }
    if (message.redactedValueCount > 0) {
        Notice(stringResource(R.string.pulse_body_redacted_count, message.redactedValueCount))
    }
    BodyViewer(inspector, transactionId, kind)
}

@Composable
private fun BodyViewer(inspector: PulseInspector, transactionId: String, kind: MessageKind) {
    val body by produceState<BodyLoad>(BodyLoad.Loading, inspector, transactionId, kind) {
        val text = inspector.loadBody(transactionId, kind)
        value = if (text == null) {
            BodyLoad.Missing
        } else {
            withContext(Dispatchers.Default) {
                BodyLoad.Loaded(
                    full = text,
                    preview = JsonPrettyPrinter.format(text.take(PREVIEW_CHARACTERS)),
                    truncated = text.length > PREVIEW_CHARACTERS,
                    tree = JsonTreeBuilder.build(text),
                )
            }
        }
    }
    when (val current = body) {
        BodyLoad.Loading -> Text(stringResource(R.string.pulse_body_loading))
        BodyLoad.Missing -> Notice(stringResource(R.string.pulse_body_file_missing))
        is BodyLoad.Loaded -> LoadedBody(current)
    }
}

@Composable
private fun LoadedBody(body: BodyLoad.Loaded) {
    var showTree by rememberSaveable { mutableStateOf(body.tree != null) }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        CopyButton { body.full }
        if (body.tree != null) {
            FilterChip(selected = showTree, onClick = { showTree = true }, label = { Text(stringResource(R.string.pulse_view_tree)) })
            FilterChip(selected = !showTree, onClick = { showTree = false }, label = { Text(stringResource(R.string.pulse_view_raw)) })
        }
    }
    val tree = body.tree
    if (showTree && tree != null) {
        JsonTreeView(tree)
    } else {
        if (body.truncated) Notice(stringResource(R.string.pulse_body_truncated, Formatters.bytes(PREVIEW_CHARACTERS.toLong())))
        SelectionContainer { Text(body.preview, style = MonospaceStyle, modifier = Modifier.padding(vertical = Spacing.s)) }
    }
}

/** 요청을 cURL 명령으로 복사한다. 가려진 헤더는 넣지 않으며, 누를 때만 만든다. */
@Composable
private fun CurlButton(inspector: PulseInspector, detail: TransactionDetail) {
    CopyButton(label = stringResource(R.string.pulse_copy_curl)) {
        val body = if (detail.request.bodyState == BodyState.CAPTURED) inspector.loadBody(detail.summary.id, MessageKind.REQUEST) else null
        CurlFormatter(inspector.config.redaction).format(detail, body)
    }
}

/** 재실행이 켜져 있을 때만 보인다([com.naury.pulse.api.PulseConfig.replay]). */
@Composable
private fun ReplayButton(inspector: PulseInspector, transactionId: String, onOpenTransaction: (String) -> Unit) {
    if (inspector.config.replay == null) return
    var open by rememberSaveable(transactionId) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) { Text(stringResource(R.string.pulse_replay)) }
    if (open) {
        ReplayDialog(inspector, transactionId, onDismiss = { open = false }) {
            open = false
            onOpenTransaction(it)
        }
    }
}
