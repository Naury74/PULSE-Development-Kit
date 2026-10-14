package com.naury.pulse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naury.pulse.api.DataEntry
import com.naury.pulse.api.DataRows
import com.naury.pulse.api.DataSource
import com.naury.pulse.api.DataSourceKind
import com.naury.pulse.api.DataTable
import com.naury.pulse.api.PulseLocalData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 설정 탭의 로컬 데이터. 호스트가 등록한 SharedPreferences·DB만 읽기 전용으로 보여 준다. */
@Composable
internal fun LocalDataSection(data: PulseLocalData) {
    val sources = remember(data) { data.sources() }
    var opened by remember { mutableStateOf<DataSource?>(null) }
    SectionTitle(stringResource(R.string.pulse_data_title))
    PulseCard {
        sources.forEachIndexed { index, source ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clickable(enabled = source.exists, role = Role.Button) {
                    opened = source
                }.padding(vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Pill(if (source.kind == DataSourceKind.DATABASE) "DB" else "PREFS", MaterialTheme.colorScheme.primary)
                Text(source.name, style = MonospaceStyle, modifier = Modifier.weight(1f))
                Text(
                    source.bytes?.let(Formatters::bytes) ?: stringResource(R.string.pulse_data_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            stringResource(R.string.pulse_data_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    opened?.let { source -> DataSheet(data, source) { opened = null } }
}

@Composable
private fun DataSheet(data: PulseLocalData, source: DataSource, onDismiss: () -> Unit) {
    var table by remember { mutableStateOf<DataTable?>(null) }
    PulseSheet(
        icon = Icons.Filled.Info,
        title = table?.name ?: source.name,
        subtitle = stringResource(R.string.pulse_data_readonly),
        onDismiss = onDismiss,
        actions = {
            Row {
                if (table != null) TextButton(onClick = { table = null }) { Text(stringResource(R.string.pulse_data_back)) }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pulse_cancel)) }
            }
        },
    ) {
        when {
            source.kind == DataSourceKind.SHARED_PREFERENCES -> PreferencesView(data, source.name)
            table == null -> Loaded({ data.tables(source.name) }) { tables -> TableList(tables) { table = it } }
            else -> TableRows(data, source.name, table!!)
        }
    }
}

/** [load]가 끝나면 [content]를 보여 주고, 실패하면 오류를 보여 준다. */
@Composable
private fun <T> Loaded(load: suspend () -> T, content: @Composable (T) -> Unit) {
    var result by remember { mutableStateOf<Result<T>?>(null) }
    LaunchedEffect(Unit) {
        result = try {
            Result.success(load())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Result.failure(error)
        }
    }
    when (val current = result) {
        null -> PulseLoading()
        else -> current.fold({ content(it) }) { Notice(it.message ?: it.javaClass.simpleName, MaterialTheme.colorScheme.error) }
    }
}

/** 키 목록. 편집을 허용했으면 키를 눌러 값을 바꾸거나 지운다. 바꾸면 목록을 다시 읽는다. */
@Composable
private fun PreferencesView(data: PulseLocalData, name: String) {
    var version by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<DataEntry?>(null) }
    if (data.canEditPreferences) Notice(stringResource(R.string.pulse_data_edit_warning), MaterialTheme.colorScheme.error)
    key(version) { Loaded({ data.preferences(name) }) { entries -> PreferenceList(entries, data.canEditPreferences) { editing = it } } }
    editing?.let { entry ->
        PreferenceEditDialog(entry, onDismiss = { editing = null }) { value ->
            data.editPreference(name, entry.key, value)
            editing = null
            version++
        }
    }
}

@Composable
private fun PreferenceList(entries: List<DataEntry>, editable: Boolean, onEdit: (DataEntry) -> Unit) {
    if (entries.isEmpty()) Notice(stringResource(R.string.pulse_data_empty))
    entries.forEach { entry ->
        val canEdit = editable && entry.type != "HashSet"
        Column(Modifier.fillMaxWidth().clickable(enabled = canEdit, role = Role.Button) { onEdit(entry) }.padding(vertical = Spacing.xs)) {
            Text(
                "${entry.key} · ${entry.type}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(entry.value, style = MonospaceStyle, maxLines = MAX_VALUE_LINES, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 값 바꾸기 대화상자. 가린 값은 비워 두고 새 값만 받는다. 형식이 맞지 않으면 오류를 보여 준다. */
@Composable
private fun PreferenceEditDialog(entry: DataEntry, onDismiss: () -> Unit, onSave: suspend (String?) -> Unit) {
    var value by remember { mutableStateOf(if (entry.masked) "" else entry.value) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val save: (String?) -> Unit = { newValue ->
        scope.launch {
            error = try {
                onSave(newValue)
                null
            } catch (invalid: IllegalArgumentException) {
                invalid.message
            } catch (failed: IllegalStateException) {
                failed.message
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${entry.key} · ${entry.type}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedTextField(value, { value = it }, singleLine = true, textStyle = MonospaceStyle, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { save(value) }) { Text(stringResource(R.string.pulse_mocks_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    save(null)
                }) { Text(stringResource(R.string.pulse_data_remove), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.pulse_cancel)) }
            }
        },
    )
}

@Composable
private fun TableList(tables: List<DataTable>, onOpen: (DataTable) -> Unit) {
    if (tables.isEmpty()) Notice(stringResource(R.string.pulse_data_empty))
    tables.forEach { table ->
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(table) }.padding(vertical = Spacing.s)) {
            Text("${table.name} · ${table.rowCount}", style = MonospaceStyle, fontWeight = FontWeight.SemiBold)
            Text(
                table.columns.joinToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TableRows(data: PulseLocalData, database: String, table: DataTable) {
    var pages by remember(table) { mutableStateOf(listOf<DataRows>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(table) { pages = listOf(data.rows(database, table.name, 0)) }
    val first = pages.firstOrNull() ?: return PulseLoading()
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row { first.columns.forEach { Cell(it + if (it in first.maskedColumns) " 🔒" else "", header = true) } }
        pages.flatMap { it.rows }.forEach { row -> Row { row.forEach { Cell(it ?: "NULL") } } }
    }
    if (pages.last().hasMore) {
        OutlinedButton(onClick = {
            val offset = pages.sumOf { it.rows.size }
            scope.launch { pages = pages + data.rows(database, table.name, offset) }
        }) { Text(stringResource(R.string.pulse_data_more)) }
    }
}

@Composable
private fun Cell(text: String, header: Boolean = false) {
    Text(
        text,
        style = if (header) MaterialTheme.typography.labelMedium else MonospaceStyle,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(min = 72.dp, max = 220.dp).padding(end = Spacing.m, bottom = Spacing.xs),
    )
}

private const val MAX_VALUE_LINES = 6
