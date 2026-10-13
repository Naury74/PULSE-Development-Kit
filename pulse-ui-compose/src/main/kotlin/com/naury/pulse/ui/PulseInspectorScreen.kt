package com.naury.pulse.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.RecordingState
import com.naury.pulse.api.StatsWindow

private val TwoPaneMinWidth = 840.dp
private const val LIST_PANE_WEIGHT = 0.42f
private const val DETAIL_PANE_WEIGHT = 1f - LIST_PANE_WEIGHT

/**
 * PULSE 인스펙터 화면. 호스트 앱의 Compose 화면 안에 넣을 수 있다.
 *
 * 개요·트래픽·진단·설정 탭이 있다. 좁은 화면은 아래 탭과 목록·상세를 번갈아 보여 주고,
 * 너비가 840dp 이상이면 옆 레일과 목록·상세 두 칸으로 보여 준다.
 * 검색어·필터·선택한 요청은 화면 회전과 프로세스 재시작 뒤에도 유지된다.
 *
 * @param inspector 보통 `Pulse.initialize`가 돌려준 `PulseHandle`.
 * @param onClose 닫기 버튼을 눌렀을 때. 호스트 내비게이션에서 화면을 닫는다.
 */
@Composable
public fun PulseInspectorScreen(inspector: PulseInspector, onClose: () -> Unit, modifier: Modifier = Modifier) {
    PulseTheme {
        val trafficViewModel: TrafficViewModel = viewModel(
            key = "pulse-traffic",
            factory = viewModelFactory { initializer { TrafficViewModel(inspector, createSavedStateHandle()) } },
        )
        var destination by rememberSaveable { mutableStateOf(Destination.TRAFFIC) }
        var window by rememberSaveable { mutableStateOf(StatsWindow.LAST_HOUR) }
        var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
        var confirmClear by rememberSaveable { mutableStateOf(false) }

        BoxWithConstraints(modifier.fillMaxSize()) {
            val wide = maxWidth >= TwoPaneMinWidth
            val showingDetailOnly = !wide && destination == Destination.TRAFFIC && selectedId != null
            BackHandler(enabled = showingDetailOnly) { selectedId = null }
            BackHandler(enabled = !showingDetailOnly && destination != Destination.TRAFFIC) { destination = Destination.TRAFFIC }

            Scaffold(
                topBar = {
                    InspectorTopBar(inspector, showingDetailOnly, onBack = {
                        selectedId = null
                    }, onClear = { confirmClear = true }, onClose)
                },
                bottomBar = {
                    if (!wide && !showingDetailOnly) DestinationBar(destination) { destination = it }
                },
            ) { padding ->
                Row(Modifier.padding(padding).fillMaxSize()) {
                    if (wide) {
                        DestinationRail(destination) { destination = it }
                        VerticalDivider()
                    }
                    val content = Modifier.weight(1f).fillMaxHeight()
                    when (destination) {
                        Destination.OVERVIEW -> OverviewPane(inspector, window, { window = it }, onOpenTraffic = { filter ->
                            trafficViewModel.filter(filter)
                            selectedId = null
                            destination = Destination.TRAFFIC
                        }, content)

                        Destination.TRAFFIC -> TrafficDestination(inspector, trafficViewModel, wide, selectedId, {
                            selectedId = it
                        }, content)

                        Destination.INSIGHTS -> InsightsPane(inspector, window, { window = it }, onOpenTransaction = {
                            selectedId = it
                            destination = Destination.TRAFFIC
                        }, content)

                        Destination.SETTINGS -> SettingsPane(inspector, onClear = { confirmClear = true }, modifier = content)
                    }
                }
            }
        }

        if (confirmClear) {
            ClearDialog(onDismiss = { confirmClear = false }) {
                confirmClear = false
                selectedId = null
                inspector.clear()
            }
        }
    }
}

@Composable
private fun DestinationBar(current: Destination, onSelect: (Destination) -> Unit) {
    NavigationBar {
        Destination.entries.forEach { item ->
            NavigationBarItem(
                selected = current == item,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.label)) },
            )
        }
    }
}

@Composable
private fun DestinationRail(current: Destination, onSelect: (Destination) -> Unit) {
    NavigationRail {
        Destination.entries.forEach { item ->
            NavigationRailItem(
                selected = current == item,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.label)) },
            )
        }
    }
}

@Composable
private fun ClearDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pulse_clear_title)) },
        text = { Text(stringResource(R.string.pulse_clear_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.pulse_clear_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pulse_cancel)) } },
    )
}

private enum class Destination(@param:StringRes val label: Int, val icon: ImageVector) {
    OVERVIEW(R.string.pulse_tab_overview, Icons.Filled.Home),
    TRAFFIC(R.string.pulse_tab_traffic, Icons.AutoMirrored.Filled.List),
    INSIGHTS(R.string.pulse_tab_insights, Icons.Filled.Warning),
    SETTINGS(R.string.pulse_tab_settings, Icons.Filled.Settings),
}

@Composable
private fun TrafficDestination(
    inspector: PulseInspector,
    viewModel: TrafficViewModel,
    wide: Boolean,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier,
) {
    when {
        wide -> Row(modifier) {
            TrafficPane(inspector, viewModel, selectedId, { onSelect(it.id) }, Modifier.weight(LIST_PANE_WEIGHT))
            VerticalDivider()
            Box(Modifier.weight(DETAIL_PANE_WEIGHT).fillMaxHeight()) {
                selectedId?.let { TransactionDetailPane(inspector, it) }
            }
        }

        selectedId != null -> TransactionDetailPane(inspector, selectedId, modifier)

        else -> TrafficPane(inspector, viewModel, null, { onSelect(it.id) }, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InspectorTopBar(inspector: PulseInspector, detailOnly: Boolean, onBack: () -> Unit, onClear: () -> Unit, onClose: () -> Unit) {
    val recording by inspector.recordingState.collectAsStateWithLifecycle()
    TopAppBar(
        title = { Text(stringResource(R.string.pulse_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (detailOnly) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.pulse_back))
                }
            }
        },
        actions = {
            RecordingToggle(recording, onPause = inspector::pause, onResume = inspector::resume)
            IconButton(onClick = onClear) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.pulse_clear)) }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.pulse_close)) }
        },
    )
}

/** 현재 상태를 글자로 함께 보여 주는 기록 전환 버튼. */
@Composable
private fun RecordingToggle(state: RecordingState, onPause: () -> Unit, onResume: () -> Unit) {
    when (state) {
        RecordingState.RECORDING -> TextButton(onClick = onPause) {
            Text("● " + stringResource(R.string.pulse_state_recording) + " · " + stringResource(R.string.pulse_pause))
        }

        RecordingState.PAUSED -> TextButton(onClick = onResume) {
            Text(stringResource(R.string.pulse_state_paused) + " · " + stringResource(R.string.pulse_resume))
        }

        RecordingState.DISABLED -> Text(stringResource(R.string.pulse_state_disabled), style = MaterialTheme.typography.labelLarge)

        RecordingState.UNINITIALIZED -> Text(
            stringResource(R.string.pulse_state_uninitialized),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
