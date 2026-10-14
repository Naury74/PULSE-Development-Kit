package com.naury.pulse.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.HttpMessage
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.TimelineItemKind
import com.naury.pulse.api.TransactionDetail
import com.naury.pulse.export.CurlFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
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

    data class Loaded(
        val full: String,
        val preview: String,
        val truncated: Boolean,
        val tree: JsonTree?,
        val json: Boolean,
        val formFields: List<HeaderField>?,
    ) : BodyLoad
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
        DetailLoad.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PulseLoading() }
        DetailLoad.Missing -> Box(modifier.padding(Spacing.l)) { Notice(stringResource(R.string.pulse_detail_missing)) }
        is DetailLoad.Loaded -> DetailTabs(inspector, current.detail, modifier, onOpenTransaction)
    }
}

@Composable
private fun DetailTabs(inspector: PulseInspector, detail: TransactionDetail, modifier: Modifier, onOpenTransaction: (String) -> Unit) {
    var tab by rememberSaveable(detail.summary.id) { mutableIntStateOf(0) }
    val tabs = listOf(R.string.pulse_tab_summary, R.string.pulse_tab_request, R.string.pulse_tab_response, R.string.pulse_tab_timeline)
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        DetailHeader(inspector, detail, onOpenTransaction)
        PrimaryTabRow(
            selectedTabIndex = tab,
            containerColor = MaterialTheme.colorScheme.surface,
            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
            indicator = { TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(tab, matchContentSize = true), width = 32.dp) },
        ) {
            tabs.forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(stringResource(label), fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal) },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
            when (tab) {
                0 -> {
                    SummaryTab(detail)
                    detail.summary.parentTransactionId?.let { parent -> ParentComparison(inspector, detail, parent, onOpenTransaction) }
                    RequestEvidence(inspector, detail.summary.id, onOpenTransaction)
                }

                1 -> MessageTab(inspector, detail.summary.id, MessageKind.REQUEST, detail.request, detail.networkRequestHeaders)

                2 -> detail.response?.let {
                    MessageTab(inspector, detail.summary.id, MessageKind.RESPONSE, it, detail.networkResponseHeaders)
                }
                    ?: Box(Modifier.padding(top = Spacing.s)) { Notice(stringResource(R.string.pulse_no_response)) }

                else -> TimelineTab(detail)
            }
        }
    }
}

/** 상세 위쪽의 요약 카드. 어떤 요청인지, 결과가 어땠는지 탭을 넘기지 않고 알 수 있게 한다. */
@Composable
private fun DetailHeader(inspector: PulseInspector, detail: TransactionDetail, onOpenTransaction: (String) -> Unit) {
    val summary = detail.summary
    val slowNanos = inspector.config.insights.slowRequestMillis * NANOS_PER_MILLI
    Box(Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = Spacing.s)) {
        PulseCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                MethodTag(summary.method)
                StatusBadge(summary)
                SourceTag(summary)
            }
            Text(
                summary.path,
                style = MonospaceStyle.copy(fontSize = 16.sp, lineHeight = 22.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.s),
            )
            Text(summary.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = Spacing.m))
            HeaderMetrics(detail, slowNanos)
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                modifier = Modifier
                    .padding(top = Spacing.m)
                    .horizontalScroll(rememberScrollState()),
            ) {
                CurlButton(inspector, detail)
                ShareButton(inspector, detail)
                ReplayButton(inspector, summary.id, onOpenTransaction)
            }
        }
    }
}

@Composable
private fun HeaderMetrics(detail: TransactionDetail, slowNanos: Long) {
    val summary = detail.summary
    val unknown = "—"
    val duration = summary.durationNanos
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Metric(
            stringResource(R.string.pulse_field_duration),
            duration?.let(Formatters::duration) ?: stringResource(R.string.pulse_not_finished),
            Modifier.weight(1f),
            valueColor = if (duration != null &&
                duration >= slowNanos
            ) {
                LocalStatusColors.current.slow
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Metric(
            stringResource(R.string.pulse_field_ttfb_short),
            detail.responseHeadersNanos?.let(Formatters::duration) ?: unknown,
            Modifier.weight(1f),
        )
        Metric(
            stringResource(R.string.pulse_field_response_size_short),
            summary.responseBodyBytes?.let(Formatters::bytes) ?: unknown,
            Modifier.weight(1f),
        )
        Metric(stringResource(R.string.pulse_field_protocol), detail.protocol ?: unknown, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryTab(detail: TransactionDetail) {
    val summary = detail.summary
    val unknown = stringResource(R.string.pulse_unknown)
    SectionTitle(stringResource(R.string.pulse_section_general))
    PulseCard {
        Field(stringResource(R.string.pulse_field_url), summary.url, monospace = true)
        Field(
            stringResource(R.string.pulse_field_status),
            listOfNotNull(summary.statusCode?.toString(), summary.state.name).joinToString(" · "),
        )
        Field(stringResource(R.string.pulse_field_started), Formatters.dateTime(summary.startedAtEpochMillis))
        detail.responseHeadersNanos?.let { Field(stringResource(R.string.pulse_field_ttfb), Formatters.duration(it)) }
        Field(stringResource(R.string.pulse_field_request_size), summary.requestBodyBytes?.let(Formatters::bytes) ?: unknown)
        Field(stringResource(R.string.pulse_field_response_size), summary.responseBodyBytes?.let(Formatters::bytes) ?: unknown)
        Field(stringResource(R.string.pulse_field_source), summary.source.name)
    }
    summary.context?.let { context ->
        SectionTitle(stringResource(R.string.pulse_section_context))
        PulseCard {
            context.screen?.let { Field(stringResource(R.string.pulse_field_screen), it, monospace = true) }
            context.foreground?.let {
                Field(
                    stringResource(R.string.pulse_field_app_state),
                    stringResource(if (it) R.string.pulse_app_foreground else R.string.pulse_app_background),
                )
            }
            context.networkType?.let { Field(stringResource(R.string.pulse_field_network), networkLabel(it)) }
            context.label?.let { Field(stringResource(R.string.pulse_field_label), it, monospace = true) }
        }
    }
    summary.failureType?.let { type ->
        SectionTitle(stringResource(R.string.pulse_field_failure))
        PulseCard {
            SelectionContainer {
                Text(
                    listOfNotNull(type, detail.failureMessage).joinToString("\n"),
                    style = MonospaceStyle,
                    color = LocalStatusColors.current.failure,
                )
            }
        }
    }
}

@Composable
private fun MessageTab(
    inspector: PulseInspector,
    transactionId: String,
    kind: MessageKind,
    message: HttpMessage,
    networkHeaders: List<HeaderField>,
) {
    HeadersSection(kind, message.headers, networkHeaders)
    SectionTitle(stringResource(R.string.pulse_body))
    if (message.bodyState != BodyState.CAPTURED) {
        Notice(stringResource(message.bodyState.explanation()))
        return
    }
    if (message.redactedValueCount > 0) {
        Notice(stringResource(R.string.pulse_body_redacted_count, message.redactedValueCount), LocalStatusColors.current.clientError)
    }
    BodyViewer(inspector, transactionId, kind, message.contentType)
}

/**
 * 앱 단계 헤더와 실제로 오간 헤더를 바꿔 본다. 실제 헤더가 있으면 그쪽을 먼저 보여 주고,
 * 앱 단계에 없던(OkHttp가 붙이거나 서버가 보내고 OkHttp가 지운) 헤더에는 표시를 단다.
 */
@Composable
private fun HeadersSection(kind: MessageKind, appHeaders: List<HeaderField>, networkHeaders: List<HeaderField>) {
    var showNetwork by rememberSaveable(kind) { mutableStateOf(networkHeaders.isNotEmpty()) }
    val shown = if (showNetwork && networkHeaders.isNotEmpty()) networkHeaders else appHeaders
    SectionTitle(stringResource(R.string.pulse_headers_count, shown.size))
    if (networkHeaders.isNotEmpty()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = Spacing.s)) {
            SegmentedButton(selected = !showNetwork, onClick = {
                showNetwork = false
            }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) {
                Text(stringResource(R.string.pulse_headers_app, appHeaders.size))
            }
            SegmentedButton(selected = showNetwork, onClick = {
                showNetwork = true
            }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) {
                Text(stringResource(R.string.pulse_headers_network, networkHeaders.size))
            }
        }
    }
    val appNames = appHeaders.map { it.name.lowercase() }.toSet()
    val tag = stringResource(
        if (kind ==
            MessageKind.REQUEST
        ) {
            R.string.pulse_headers_added_by_okhttp
        } else {
            R.string.pulse_headers_removed_by_okhttp
        },
    )
    PulseCard {
        HeaderTable(shown) { header -> if (showNetwork && header.name.lowercase() !in appNames) tag else null }
        val hint = when {
            networkHeaders.isEmpty() && kind == MessageKind.REQUEST -> R.string.pulse_headers_no_network_request

            networkHeaders.isEmpty() -> null

            showNetwork -> if (kind ==
                MessageKind.REQUEST
            ) {
                R.string.pulse_headers_network_hint_request
            } else {
                R.string.pulse_headers_network_hint_response
            }

            else -> R.string.pulse_headers_app_hint
        }
        hint?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.s),
            )
        }
    }
}

@Composable
private fun BodyViewer(inspector: PulseInspector, transactionId: String, kind: MessageKind, contentType: String?) {
    val body by produceState<BodyLoad>(BodyLoad.Loading, inspector, transactionId, kind) {
        val text = inspector.loadBody(transactionId, kind)
        value = if (text == null) {
            BodyLoad.Missing
        } else {
            withContext(Dispatchers.Default) {
                val json = BodyKinds.isJson(contentType)
                val head = text.take(PREVIEW_CHARACTERS)
                BodyLoad.Loaded(
                    full = text,
                    preview = if (json) JsonPrettyPrinter.format(head) else head,
                    truncated = text.length > PREVIEW_CHARACTERS,
                    tree = if (json) JsonTreeBuilder.build(text) else null,
                    json = json,
                    formFields = if (BodyKinds.isForm(contentType)) BodyKinds.formFields(text) else null,
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
    val accents = LocalAccentColors.current
    val highlighted = remember(body.preview, accents) {
        if (body.json) JsonHighlighter.highlight(body.preview, accents) else AnnotatedString(body.preview)
    }
    body.formFields?.let { fields ->
        PulseCard(Modifier.padding(top = Spacing.s)) { HeaderTable(fields) }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = Spacing.s, bottom = Spacing.s),
    ) {
        if (body.tree != null) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(
                    selected = showTree,
                    onClick = { showTree = true },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = {},
                ) { Text(stringResource(R.string.pulse_view_tree)) }
                SegmentedButton(
                    selected = !showTree,
                    onClick = { showTree = false },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = {},
                ) { Text(stringResource(R.string.pulse_view_raw)) }
            }
        } else {
            Box(Modifier.weight(1f))
        }
        CopyButton { body.full }
    }
    val tree = body.tree
    Column(
        Modifier
            .fillMaxWidth()
            .background(accents.codeBackground, MaterialTheme.shapes.medium)
            .padding(Spacing.m),
    ) {
        if (showTree && tree != null) {
            JsonTreeView(tree)
        } else {
            if (body.truncated) Notice(stringResource(R.string.pulse_body_truncated, Formatters.bytes(PREVIEW_CHARACTERS.toLong())))
            SelectionContainer { Text(highlighted, style = MonospaceStyle) }
        }
    }
}

private const val NANOS_PER_MILLI = 1_000_000L

/** 요청과 이어진 이벤트·요청. 이벤트를 누르면 상세 시트를 띄운다. */
@Composable
private fun RequestEvidence(inspector: PulseInspector, id: String, onOpenTransaction: (String) -> Unit) {
    var opened by remember { mutableStateOf<AppEvent?>(null) }
    key(id) { RelatedEvidence(inspector, id, TimelineItemKind.REQUEST, onOpenTransaction) { opened = it } }
    opened?.let { event ->
        EventDetailSheet(inspector, event, onRangeChange = {}, onDismiss = { opened = null }, onOpenRequest = {
            opened = null
            onOpenTransaction(it)
        }, onOpenEvent = { opened = it })
    }
}
