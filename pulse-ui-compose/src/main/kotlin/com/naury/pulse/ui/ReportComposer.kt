package com.naury.pulse.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.EvidenceRef
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.PulseReports
import com.naury.pulse.api.ReportDraft
import com.naury.pulse.api.ReportKind
import com.naury.pulse.api.Severity
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 보고 작성기를 여는 요청. 플로팅 버튼·이벤트 상세·설정 탭이 넣고 인스펙터 화면이 연다. */
internal class ComposeRequest(
    val reportId: String? = null,
    val png: ByteArray? = null,
    val capturedAtEpochMillis: Long = System.currentTimeMillis(),
    val evidence: EvidenceRef? = null,
    val kind: ReportKind = ReportKind.MANUAL,
)

/** 다음에 열 보고 작성 요청. 화면 캡처는 마스킹 전이므로 이 메모리에만 두고 저장하지 않는다. */
internal object ReportRequests {
    var pending by mutableStateOf<ComposeRequest?>(null)
}

/** 보고 작성기의 입력 값. */
private class ReportForm {
    var title by mutableStateOf("")
    var description by mutableStateOf("")
    var expected by mutableStateOf("")
    var actual by mutableStateOf("")
    var steps by mutableStateOf("")
    var severity by mutableStateOf(Severity.MAJOR)
    var png by mutableStateOf<ByteArray?>(null)
    var evidence by mutableStateOf(listOf<EvidenceRef>())
    var sessionId by mutableStateOf<String?>(null)
    var createdAt = 0L
    var consent by mutableStateOf(false)

    fun toDraft(id: String, kind: ReportKind) = ReportDraft(
        id = id,
        kind = kind,
        title = title.trim().take(ReportDraft.MAX_TITLE_CHARS),
        description = description.trim(),
        expected = expected.trim(),
        actual = actual.trim(),
        steps = steps.lines().map { it.trim() }.filter { it.isNotEmpty() },
        severity = severity,
        sessionId = sessionId,
        evidence = evidence,
        hasScreenshot = png != null,
        createdAtEpochMillis = createdAt,
    )
}

/**
 * 버그 보고 작성기. 제목·심각도·설명·기대/실제 결과·재현 단계를 쓰고, 화면 캡처를 가리고 표시하며, 붙일 근거를 고른다.
 * 저장·내보내기·보내기 전에 글자는 마스킹되고, 보내기는 "개인정보를 확인했습니다"를 체크해야 한다.
 */
@Composable
internal fun ReportComposer(inspector: PulseInspector, request: ComposeRequest, onDismiss: () -> Unit) {
    val reports = inspector.reports ?: return onDismiss()
    val id = remember(request) { request.reportId ?: UUID.randomUUID().toString() }
    val form = remember(request) {
        ReportForm().apply {
            png = request.png
            createdAt = request.capturedAtEpochMillis
        }
    }
    var editing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(request) { form.load(inspector, reports, id, request) }
    PulseSheet(
        icon = Icons.Filled.Edit,
        title = stringResource(if (request.reportId == null) R.string.pulse_bug_new else R.string.pulse_bug_edit),
        subtitle = stringResource(R.string.pulse_bug_subtitle),
        onDismiss = onDismiss,
        actions = { ComposerActions(reports, form, id, request.kind, scope, onDismiss) },
    ) {
        ReportFields(form)
        SheetLabel(stringResource(R.string.pulse_bug_screenshot))
        ScreenshotBlock(form.png, onEdit = { editing = true }, onRemove = { form.png = null })
        SheetLabel(stringResource(R.string.pulse_bug_evidence))
        EvidenceList(form)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(role = Role.Checkbox) {
                form.consent =
                    !form.consent
            },
        ) {
            Checkbox(checked = form.consent, onCheckedChange = { form.consent = it })
            Text(stringResource(R.string.pulse_bug_consent), style = MaterialTheme.typography.bodySmall)
        }
    }
    val png = form.png
    if (editing && png != null) {
        ScreenshotEditor(png, onDone = {
            form.png = it
            editing = false
        }, onDismiss = { editing = false })
    }
}

private suspend fun ReportForm.load(inspector: PulseInspector, reports: PulseReports, id: String, request: ComposeRequest) {
    sessionId = inspector.loadSessions(null, null, 1).firstOrNull { it.current }?.id
    val saved = request.reportId?.let { reports.load(it) }
    if (saved != null) {
        title = saved.title
        description = saved.description
        expected = saved.expected
        actual = saved.actual
        steps = saved.steps.joinToString("\n")
        severity = saved.severity
        evidence = saved.evidence
        sessionId = saved.sessionId ?: sessionId
        createdAt = saved.createdAtEpochMillis
        if (saved.hasScreenshot) png = reports.loadScreenshot(id)
        return
    }
    val recommended = reports.recommendEvidence(sessionId, request.capturedAtEpochMillis)
    evidence = (listOfNotNull(request.evidence) + recommended).distinctBy { it.kind to it.id }
    steps = draftSteps(evidence.map { it.title }).joinToString("\n")
}

/**
 * 근거 제목에서 재현 단계 초안을 만든다. 앱이 남긴 의미 있는 행동이 있으면 자동 탭 기록은 빼고,
 * 화면 이동은 `→ 화면`으로 줄이며, 마지막 [MAX_DRAFT_STEPS]단계만 남긴다.
 */
internal fun draftSteps(titles: List<String>): List<String> {
    val steps = titles.mapNotNull { title ->
        STEP_PREFIXES.firstOrNull { title.startsWith(it) }?.let { prefix -> prefix to title.removePrefix(prefix).trim() }
    }
    val hasSemantic = steps.any { (prefix, text) -> prefix == "ACTION " && !text.isInteraction() }
    return steps.filterNot { (prefix, text) -> hasSemantic && prefix == "ACTION " && text.isInteraction() }
        .map { (prefix, text) -> if (prefix == "NAVIGATION ") "→ " + text.substringAfterLast("→ ").trim() else text }
        .fold(emptyList<String>()) { acc, step -> if (acc.lastOrNull() == step) acc else acc + step }
        .takeLast(MAX_DRAFT_STEPS)
}

private fun String.isInteraction() = startsWith("tap ") || startsWith("back ")

private const val MAX_DRAFT_STEPS = 8

@Composable
private fun ReportFields(form: ReportForm) {
    OutlinedTextField(form.title, {
        form.title = it
    }, label = { Text(stringResource(R.string.pulse_bug_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Severity.entries.forEach { option ->
            FilterChip(
                selected = form.severity == option,
                onClick = { form.severity = option },
                label = { Text(stringResource(severityLabel(option))) },
                shape = MaterialTheme.shapes.small,
                colors = dashboardChipColors(),
                border = dashboardChipBorder(form.severity == option),
            )
        }
    }
    OutlinedTextField(form.description, {
        form.description = it
    }, label = { Text(stringResource(R.string.pulse_bug_description)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(form.steps, {
        form.steps = it
    }, label = { Text(stringResource(R.string.pulse_bug_steps)) }, minLines = 3, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(form.expected, {
        form.expected = it
    }, label = { Text(stringResource(R.string.pulse_bug_expected)) }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(form.actual, {
        form.actual = it
    }, label = { Text(stringResource(R.string.pulse_bug_actual)) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ScreenshotBlock(png: ByteArray?, onEdit: () -> Unit, onRemove: () -> Unit) {
    if (png == null) {
        Text(
            stringResource(R.string.pulse_bug_no_screenshot),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val image = remember(png) { BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() }
    image?.let {
        Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.pulse_bug_edit_screenshot)) }
        TextButton(onClick = onRemove) { Text(stringResource(R.string.pulse_bug_remove_screenshot)) }
    }
    Text(
        stringResource(R.string.pulse_bug_screenshot_note, Formatters.bytes(png.size.toLong())),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EvidenceList(form: ReportForm) {
    if (form.evidence.isEmpty()) {
        Text(
            stringResource(R.string.pulse_bug_no_evidence),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    PulseCard {
        form.evidence.forEachIndexed { index, ref ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Checkbox) {
                    form.evidence = form.evidence.toMutableList().also { it[index] = ref.withIncluded(!ref.included) }
                },
            ) {
                Checkbox(checked = ref.included, onCheckedChange = null)
                Text(
                    ref.title,
                    style = MonospaceStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = Spacing.s),
                )
            }
        }
        Text(
            stringResource(R.string.pulse_bug_evidence_count, form.evidence.count { it.included }, form.evidence.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComposerActions(
    reports: PulseReports,
    form: ReportForm,
    id: String,
    kind: ReportKind,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val canSend = reports.canSend(kind)
    val ready = form.title.isNotBlank()
    suspend fun save() = reports.save(form.toDraft(id, kind), form.png ?: ByteArray(0))
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (!canSend) {
            Text(
                stringResource(R.string.pulse_bug_no_destination),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
            TextButton(enabled = ready, onClick = {
                scope.launch {
                    save()
                    Toast.makeText(context, resources.getString(R.string.pulse_bug_saved), Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }) { Text(stringResource(R.string.pulse_bug_save)) }
            OutlinedButton(enabled = ready && form.consent, onClick = {
                scope.launch {
                    save()
                    val shared =
                        writeAndShare(context, "pulse-report-$id.zip", ZIP_MIME, resources.getString(R.string.pulse_bug_export)) {
                            reports.exportPackage(id, it)
                        }
                    if (!shared) Toast.makeText(context, resources.getString(R.string.pulse_export_failed), Toast.LENGTH_LONG).show()
                }
            }) { Text(stringResource(R.string.pulse_bug_export)) }
            Button(enabled = ready && form.consent && canSend, onClick = {
                scope.launch {
                    save()
                    reports.submit(id)
                    Toast.makeText(context, resources.getString(R.string.pulse_bug_queued), Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }) { Text(stringResource(R.string.pulse_bug_send)) }
        }
    }
}

internal fun severityLabel(severity: Severity): Int = when (severity) {
    Severity.BLOCKER -> R.string.pulse_severity_blocker
    Severity.MAJOR -> R.string.pulse_severity_major
    Severity.MINOR -> R.string.pulse_severity_minor
    Severity.TRIVIAL -> R.string.pulse_severity_trivial
}

private val STEP_PREFIXES = listOf("ACTION ", "NAVIGATION ", "MARKER ")
private const val ZIP_MIME = "application/zip"
