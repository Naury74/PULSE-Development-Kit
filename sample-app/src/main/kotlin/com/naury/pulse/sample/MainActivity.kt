package com.naury.pulse.sample

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.pulse.api.Pulse
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as SampleApplication
        val runner = DemoRunner(app.client, app.demoServer)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SampleScreen(
                    runner = runner,
                    recordingLabel = app.pulse.recordingState.collectAsStateWithLifecycle().value.name,
                    onOpenInspector = {
                        if (!Pulse.open(this)) Toast.makeText(this, R.string.inspector_unavailable, Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SampleScreen(runner: DemoRunner, recordingLabel: String, onOpenInspector: () -> Unit) {
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateMapOf<DemoScenario, String>() }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sample_intro), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.recording_state, recordingLabel), style = MaterialTheme.typography.labelLarge)
                    Button(onClick = onOpenInspector, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_inspector)) }
                }
            }
            items(DemoScenario.entries) { scenario ->
                ScenarioCard(scenario, results[scenario]) {
                    results[scenario] = "…"
                    scope.launch { results[scenario] = runner.run(scenario) }
                }
            }
        }
    }
}

@Composable
private fun ScenarioCard(scenario: DemoScenario, result: String?, onRun: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onRun),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(scenario.title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(scenario.description), style = MaterialTheme.typography.bodySmall)
            result?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
