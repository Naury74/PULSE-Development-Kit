package com.naury.pulse.sample

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.pulse.api.Pulse
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.RecordingState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as SampleApplication
        val inspector: PulseInspector = app.pulse
        val runner = DemoRunner({ app.client }, app.demoServer) { inspector.remote?.flushNow() }
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) SampleDarkColors else SampleLightColors) {
                SampleScreen(
                    runner = runner,
                    recording = app.pulse.recordingState.collectAsStateWithLifecycle().value,
                    onOpenInspector = {
                        if (!Pulse.open(this)) Toast.makeText(this, R.string.inspector_unavailable, Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
    }
}

private val SampleLightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    primaryContainer = Color(0xFFE3E9FF),
    onPrimaryContainer = Color(0xFF1C2D7A),
    background = Color(0xFFF3F4F7),
    surface = Color(0xFFF3F4F7),
    surfaceContainerLow = Color.White,
    outlineVariant = Color(0xFFE2E5EA),
    onSurfaceVariant = Color(0xFF596171),
)

private val SampleDarkColors = darkColorScheme(
    primary = Color(0xFF91A7FF),
    primaryContainer = Color(0xFF26336E),
    onPrimaryContainer = Color(0xFFDBE4FF),
    background = Color(0xFF0C0E12),
    surface = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF15181F),
    outlineVariant = Color(0xFF262B35),
    onSurfaceVariant = Color(0xFFA2AAB8),
)

/** 실행 한 번의 결과. [ok]는 HTTP 2xx·3xx로 끝났는지다. */
private class RunResult(val text: String, val ok: Boolean, val millis: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleScreen(runner: DemoRunner, recording: RecordingState, onOpenInspector: () -> Unit) {
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateMapOf<DemoScenario, RunResult>() }
    val running = remember { mutableStateMapOf<DemoScenario, Boolean>() }
    var runAllProgress by remember { mutableStateOf<Int?>(null) }
    var confirmCrash by remember { mutableStateOf(false) }
    val runAll = DemoScenario.entries.filter { it.inRunAll }
    suspend fun run(scenario: DemoScenario) {
        // 의미 있는 행동 ID로 남기면 타임라인과 버그 보고의 재현 단계에 쓰인다.
        Pulse.action("sample.run", mapOf("scenario" to scenario.name))
        running[scenario] = true
        val start = System.nanoTime()
        val text = runner.run(scenario)
        results[scenario] =
            RunResult(
                text,
                text.startsWith("OK") || text.contains("HTTP 2") || text.contains("HTTP 3"),
                (System.nanoTime() - start) / NANOS_PER_MILLI,
            )
        running.remove(scenario)
    }
    Scaffold(
        topBar = { SampleTopBar() },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item {
                HeroCard(
                    recording = recording,
                    results = results.values.toList(),
                    runAllProgress = runAllProgress,
                    runAllCount = runAll.size,
                    onOpenInspector = onOpenInspector,
                    onRunAll = {
                        scope.launch {
                            runAll.forEachIndexed { index, scenario ->
                                runAllProgress = index
                                run(scenario)
                            }
                            runAllProgress = null
                        }
                    },
                    onClear = { results.clear() },
                )
            }
            scenarioGroups(results, running) { scenario ->
                if (scenario == DemoScenario.APP_CRASH) confirmCrash = true else scope.launch { run(scenario) }
            }
        }
    }
    if (confirmCrash) {
        CrashConfirmDialog(onDismiss = { confirmCrash = false }) {
            confirmCrash = false
            scope.launch { run(DemoScenario.APP_CRASH) }
        }
    }
}

private fun LazyListScope.scenarioGroups(
    results: Map<DemoScenario, RunResult>,
    running: Map<DemoScenario, Boolean>,
    onRun: (DemoScenario) -> Unit,
) {
    ScenarioGroup.entries.forEach { group ->
        item(key = group.name) {
            Text(
                stringResource(group.title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
            )
        }
        items(DemoScenario.entries.filter { it.group == group }, key = { it.name }) { scenario ->
            ScenarioCard(scenario, results[scenario], running[scenario] == true) { onRun(scenario) }
        }
    }
}

@Composable
private fun CrashConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_confirm_title)) },
        text = { Text(stringResource(R.string.crash_confirm_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.crash_confirm_ok), color = ErrorRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleTopBar() {
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.sample_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun HeroCard(
    recording: RecordingState,
    results: List<RunResult>,
    runAllProgress: Int?,
    runAllCount: Int,
    onOpenInspector: () -> Unit,
    onRunAll: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val active = recording == RecordingState.RECORDING
                Box(Modifier.size(10.dp).background(if (active) RecordingRed else IdleGray, CircleShape))
                Text(stringResource(R.string.recording_state, recording.name), style = MaterialTheme.typography.labelLarge)
            }
            Text(
                stringResource(R.string.sample_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (results.isNotEmpty()) {
                Text(
                    stringResource(R.string.session_summary, results.size, results.count { it.ok }, results.count { !it.ok }),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            runAllProgress?.let { index ->
                Text(stringResource(R.string.running_all, index + 1, runAllCount), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { (index + 1f) / runAllCount },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
            Button(onClick = onOpenInspector, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.open_inspector), fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRunAll, enabled = runAllProgress == null, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.run_all))
                }
                TextButton(onClick = onClear, enabled = results.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.clear_results))
                }
            }
        }
    }
}

@Composable
private fun ScenarioCard(scenario: DemoScenario, result: RunResult?, running: Boolean, onRun: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !running, role = Role.Button, onClick = onRun)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(scenario.method, methodColor(scenario.method))
                    Text(
                        scenario.endpoint,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(stringResource(scenario.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(scenario.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                result?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag(if (it.ok) "OK" else "ERR", if (it.ok) SuccessGreen else ErrorRed)
                        Text(
                            "${it.text} · ${it.millis} ms",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (running) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            } else {
                Text(
                    stringResource(R.string.run),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier
            .background(color.copy(alpha = TAG_ALPHA), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private fun methodColor(method: String): Color = when (method) {
    "POST" -> SuccessGreen
    "PUT", "PATCH" -> WriteOrange
    "DELETE" -> ErrorRed
    "APP" -> AppPurple
    else -> ReadBlue
}

private val SuccessGreen = Color(0xFF2B8A3E)
private val ErrorRed = Color(0xFFC92A2A)
private val WriteOrange = Color(0xFFD9480F)
private val ReadBlue = Color(0xFF1971C2)
private val AppPurple = Color(0xFF7048E8)

private const val NANOS_PER_MILLI = 1_000_000L
private const val TAG_ALPHA = 0.14f
private val RecordingRed = Color(0xFFE03131)
private val IdleGray = Color(0xFF868E96)
