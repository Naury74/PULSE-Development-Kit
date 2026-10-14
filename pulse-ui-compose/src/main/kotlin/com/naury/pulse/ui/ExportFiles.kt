package com.naury.pulse.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.TransactionQuery
import com.naury.pulse.export.ExportFormat
import com.naury.pulse.export.ExportOptions
import com.naury.pulse.export.ExportResult
import com.naury.pulse.export.PulseExporter
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** HAR은 JSON MIME으로 만들면 문서 공급자가 `.har.json`처럼 확장자를 덧붙이므로 일반 바이너리로 만든다. */
internal fun documentMimeType(format: ExportFormat): String = if (format ==
    ExportFormat.HAR
) {
    "application/octet-stream"
} else {
    format.mimeType
}

internal class ExportMessages(val started: String, val failed: String)

private suspend fun writeExport(
    out: OutputStream,
    inspector: PulseInspector,
    query: TransactionQuery,
    format: ExportFormat,
    options: ExportOptions,
): ExportResult = PulseExporter(inspector.config.redaction).export(inspector, query, format, out, options)

@Suppress("LongParameterList")
internal suspend fun exportToDocument(
    context: Context,
    uri: Uri,
    inspector: PulseInspector,
    query: TransactionQuery,
    format: ExportFormat,
    options: ExportOptions,
): ExportResult? = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { writeExport(it, inspector, query, format, options) }
            ?: throw IOException("출력 스트림을 열 수 없습니다.")
    } catch (cancel: CancellationException) {
        withContext(NonCancellable) { deleteQuietly(context, uri) }
        throw cancel
    } catch (_: IOException) {
        deleteQuietly(context, uri)
        null
    }
}

/** 앱 캐시의 내보내기 폴더에 쓰고 공유 시트를 연다. 이전 파일은 먼저 지워 캐시에 쌓이지 않게 한다. */
@Suppress("LongParameterList")
internal suspend fun exportAndShare(
    context: Context,
    name: String,
    inspector: PulseInspector,
    query: TransactionQuery,
    format: ExportFormat,
    options: ExportOptions,
    chooserTitle: String,
): Boolean {
    val file = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, PulseFileProvider.DIRECTORY).apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val target = File(directory, name)
        try {
            target.outputStream().use { writeExport(it, inspector, query, format, options) }
            target
        } catch (cancel: CancellationException) {
            target.delete()
            throw cancel
        } catch (_: IOException) {
            target.delete()
            null
        }
    } ?: return false
    val uri = try {
        FileProvider.getUriForFile(context, PulseFileProvider.authority(context), file)
    } catch (_: IllegalArgumentException) {
        // 공급자 설정이 빠졌거나 경로 밖의 파일이면 공유하지 않고 실패로 알린다.
        return false
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType(format.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    return true
}

private fun deleteQuietly(context: Context, uri: Uri) {
    try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (_: IllegalArgumentException) {
        // 문서 공급자가 삭제를 지원하지 않으면 남은 파일은 사용자가 지운다.
    } catch (_: java.io.FileNotFoundException) {
        // 이미 없다.
    } catch (_: UnsupportedOperationException) {
        // 삭제를 지원하지 않는 공급자.
    }
}

internal fun exportSummary(context: Context, result: ExportResult): String = buildList {
    add(context.getString(R.string.pulse_export_done, result.transactionCount))
    if (result.truncated) add(context.getString(R.string.pulse_export_truncated))
    if (result.droppedBodyCount > 0) add(context.getString(R.string.pulse_export_dropped, result.droppedBodyCount))
}.joinToString(" ")

/** 내보낸 파일을 다른 앱에 넘기는 공급자. 호스트 앱의 FileProvider와 겹치지 않게 따로 둔다. */
internal class PulseFileProvider : FileProvider() {
    companion object {
        const val DIRECTORY = "pulse-export"

        fun authority(context: Context): String = "${context.packageName}.pulse.files"
    }
}
