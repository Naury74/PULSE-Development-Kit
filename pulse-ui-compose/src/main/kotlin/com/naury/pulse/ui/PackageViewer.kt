package com.naury.pulse.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 재현 패키지(ZIP)를 골라 여는 버튼. 이 기기에서 만든 것이든 동료가 보낸 것이든 읽기 전용으로 보여 준다. */
@Composable
internal fun OpenPackageButton() {
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> opened = uri?.toString() }
    OutlinedButton(onClick = { launcher.launch(arrayOf("application/zip", "application/octet-stream")) }) {
        Text(stringResource(R.string.pulse_package_open))
    }
    opened?.let { PackageViewerSheet(Uri.parse(it)) { opened = null } }
}

@Composable
private fun PackageViewerSheet(uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val result by produceState<Result<ReproPackage>?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                Result.success(requireNotNull(context.contentResolver.openInputStream(uri)).use(ReproPackageReader::read))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Result.failure(error)
            }
        }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    PulseSheet(
        icon = Icons.Filled.Info,
        title = stringResource(R.string.pulse_package_title),
        subtitle = result?.getOrNull()?.let { "${it.schema.orEmpty()} · ${it.createdAt.orEmpty()}" },
        onDismiss = onDismiss,
        actions = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pulse_cancel)) } },
    ) {
        when (val current = result) {
            null -> PulseLoading()

            else -> current.fold({ PackageContent(it, tab) { tab = it } }) {
                Notice(
                    stringResource(R.string.pulse_package_failed, it.message ?: it.javaClass.simpleName),
                    MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PackageContent(pack: ReproPackage, tab: Int, onTab: (Int) -> Unit) {
    val labels = listOf(R.string.pulse_package_summary, R.string.pulse_package_timeline, R.string.pulse_package_requests)
    PackageHeader(pack)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        labels.forEachIndexed { index, label ->
            val color = if (tab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            TextButton(onClick = { onTab(index) }) { Text(stringResource(label), color = color) }
        }
    }
    when (tab) {
        0 -> PackageSummary(pack)
        1 -> PackageLines(pack.timeline)
        else -> PackageLines(pack.requests)
    }
}

@Composable
private fun PackageHeader(pack: ReproPackage) {
    if (pack.integrity) {
        Notice(stringResource(R.string.pulse_package_verified))
    } else {
        Notice(stringResource(R.string.pulse_package_tampered, pack.mismatched.joinToString()), MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun PackageSummary(pack: ReproPackage) {
    PulseCard {
        listOf("title", "severity", "kind", "description", "expected", "actual", "createdAt").forEach { key ->
            pack.issue[key]?.takeIf { it.isNotBlank() }?.let { Field(key, it) }
        }
        if (pack.steps.isNotEmpty()) Field("steps", pack.steps.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n"))
        if (pack.evidence.isNotEmpty()) Field("evidence", pack.evidence.joinToString("\n"), monospace = true)
        if (pack.issue.isEmpty()) Text(stringResource(R.string.pulse_package_no_issue), style = MaterialTheme.typography.bodySmall)
    }
    val image = remember(pack) { pack.screenshot?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    image?.let {
        Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp))
    }
    pack.environment.forEach { (section, values) ->
        SheetLabel(section)
        PulseCard { values.forEach { (name, value) -> Field(name, value, monospace = true) } }
    }
}

@Composable
private fun PackageLines(lines: List<PackageLine>) {
    if (lines.isEmpty()) {
        Notice(stringResource(R.string.pulse_package_empty))
        return
    }
    lines.forEachIndexed { index, line ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                line.time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(84.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("${line.badge}  ${line.text}", style = MonospaceStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                line.detail?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
