package com.naury.pulse.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.naury.pulse.api.PulseInspector
import com.naury.pulse.api.RecordingState
import com.naury.pulse.api.StatsWindow
import com.naury.pulse.api.StatusFilter
import com.naury.pulse.api.TransactionQuery

private val TwoPaneMinWidth = 840.dp
private const val LIST_PANE_WEIGHT = 0.42f
private const val DETAIL_PANE_WEIGHT = 1f - LIST_PANE_WEIGHT

/**
 * PULSE 인스펙터 화면. 호스트 앱의 Compose 화면 안에 넣을 수 있다.
 *
 * 개요(네트워크·앱 대시보드)·트래픽·로그·이슈·설정 탭이 있고, 날짜·실행(세션) 기간을 모든 탭이 함께 쓴다. 좁은 화면은 아래 탭과 목록·상세를 번갈아 보여 주고,
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
        val eventsViewModel: EventsViewModel = viewModel(
            key = "pulse-events",
            factory = viewModelFactory { initializer { EventsViewModel(inspector, createSavedStateHandle(), trafficViewModel.range) } },
        )
        val navigation = rememberSaveable(saver = InspectorNavigation.Saver) { InspectorNavigation() }

        BoxWithConstraints(modifier.fillMaxSize()) {
            val wide = maxWidth >= TwoPaneMinWidth
            val detailOnly = !wide && navigation.destination == Destination.TRAFFIC && navigation.selectedId != null
            BackHandler(enabled = detailOnly) { navigation.selectedId = null }
            BackHandler(enabled = !detailOnly && navigation.destination != Destination.TRAFFIC) {
                navigation.destination =
                    Destination.TRAFFIC
            }
            val exportQuery = if (navigation.destination == Destination.TRAFFIC && !detailOnly) {
                {
                    val range = trafficViewModel.range.value
                    TransactionQuery(trafficViewModel.searchText.value, trafficViewModel.status.value, range.from, range.to)
                }
            } else {
                null
            }
            Scaffold(
                topBar = { InspectorTopBar(inspector, detailOnly, navigation, onClose, exportQuery) },
                bottomBar = { if (!wide && !detailOnly) DestinationBar(navigation.destination) { navigation.destination = it } },
            ) { padding ->
                Row(Modifier.padding(padding).fillMaxSize()) {
                    if (wide) {
                        DestinationRail(navigation.destination) { navigation.destination = it }
                        VerticalDivider()
                    }
                    DestinationContent(navigation, inspector, trafficViewModel, eventsViewModel, wide, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }

        if (navigation.confirmClear) {
            ClearDialog(onDismiss = { navigation.confirmClear = false }) {
                navigation.confirmClear = false
                navigation.selectedId = null
                inspector.clear()
            }
        }
    }
}

/** 탭·선택·기간·지우기 확인 상태. 화면 회전과 프로세스 재시작 뒤에도 유지한다. */
@Stable
private class InspectorNavigation(
    destination: Destination = Destination.TRAFFIC,
    window: StatsWindow = StatsWindow.LAST_HOUR,
    selectedId: String? = null,
    confirmClear: Boolean = false,
) {
    var destination by mutableStateOf(destination)
    var window by mutableStateOf(window)
    var selectedId by mutableStateOf(selectedId)
    var confirmClear by mutableStateOf(confirmClear)

    fun open(target: Destination, transactionId: String?) {
        selectedId = transactionId
        destination = target
    }

    companion object {
        val Saver = listSaver<InspectorNavigation, Any?>(
            save = { listOf(it.destination.name, it.window.name, it.selectedId, it.confirmClear) },
            restore = {
                InspectorNavigation(
                    Destination.entries.firstOrNull { entry -> entry.name == it[0] } ?: Destination.TRAFFIC,
                    StatsWindow.valueOf(it[1] as String),
                    it[2] as String?,
                    it[3] as Boolean,
                )
            },
        )
    }
}

@Composable
private fun DestinationContent(
    navigation: InspectorNavigation,
    inspector: PulseInspector,
    trafficViewModel: TrafficViewModel,
    eventsViewModel: EventsViewModel,
    wide: Boolean,
    modifier: Modifier,
) {
    val changeWindow: (StatsWindow) -> Unit = { navigation.window = it }
    val range by trafficViewModel.range.collectAsStateWithLifecycle()
    val changeRange: (TimeRange) -> Unit = trafficViewModel::setRange
    when (navigation.destination) {
        Destination.OVERVIEW -> OverviewPane(
            inspector,
            navigation.window,
            changeWindow,
            range = range,
            onRangeChange = changeRange,
            onOpenTraffic = { filter ->
                trafficViewModel.filter(filter)
                navigation.open(Destination.TRAFFIC, null)
            },
            modifier = modifier,
            onSearchTraffic = { text ->
                trafficViewModel.filter(StatusFilter.ALL)
                trafficViewModel.search(text)
                navigation.open(Destination.TRAFFIC, null)
            },
        )

        Destination.TRAFFIC -> TrafficDestination(inspector, trafficViewModel, wide, navigation.selectedId, {
            navigation.selectedId = it
        }, modifier)

        Destination.LOGS -> EventsPane(inspector, eventsViewModel, range, changeRange, modifier)

        Destination.ISSUES -> IssuesPane(
            inspector,
            navigation.window,
            changeWindow,
            range,
            changeRange,
            onOpenTransaction = { navigation.open(Destination.TRAFFIC, it) },
            modifier = modifier,
        )

        Destination.SETTINGS -> SettingsPane(inspector, onClear = { navigation.confirmClear = true }, modifier = modifier)
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
    PulseConfirmDialog(
        icon = Icons.Filled.Delete,
        title = stringResource(R.string.pulse_clear_title),
        message = stringResource(R.string.pulse_clear_message),
        confirmLabel = stringResource(R.string.pulse_clear_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
    )
}

private enum class Destination(@param:StringRes val label: Int, val icon: ImageVector) {
    OVERVIEW(R.string.pulse_tab_overview, Icons.Filled.Home),
    TRAFFIC(R.string.pulse_tab_traffic, Icons.AutoMirrored.Filled.List),
    LOGS(R.string.pulse_tab_logs, Icons.Filled.Info),
    ISSUES(R.string.pulse_tab_issues, Icons.Filled.Warning),
    SETTINGS(R.string.pulse_tab_settings, Icons.Filled.Settings),
}

@Composable
private fun TrafficDestination(
    inspector: PulseInspector,
    viewModel: TrafficViewModel,
    wide: Boolean,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    when {
        wide -> Row(modifier) {
            TrafficPane(inspector, viewModel, selectedId, { onSelect(it.id) }, Modifier.weight(LIST_PANE_WEIGHT))
            VerticalDivider()
            Box(Modifier.weight(DETAIL_PANE_WEIGHT).fillMaxHeight()) {
                if (selectedId != null) {
                    TransactionDetailPane(inspector, selectedId, onOpenTransaction = onSelect)
                } else {
                    Box(Modifier.padding(Spacing.l)) { Notice(stringResource(R.string.pulse_select_transaction)) }
                }
            }
        }

        selectedId != null -> TransactionDetailPane(inspector, selectedId, modifier, onOpenTransaction = onSelect)

        else -> TrafficPane(inspector, viewModel, null, { onSelect(it.id) }, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InspectorTopBar(
    inspector: PulseInspector,
    detailOnly: Boolean,
    navigation: InspectorNavigation,
    onClose: () -> Unit,
    exportQuery: (() -> TransactionQuery)?,
) {
    val recording by inspector.recordingState.collectAsStateWithLifecycle()
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        title = {
            Text(
                stringResource(R.string.pulse_title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        navigationIcon = {
            if (detailOnly) {
                IconButton(onClick = { navigation.selectedId = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.pulse_back))
                }
            }
        },
        actions = {
            RecordingToggle(recording, onPause = inspector::pause, onResume = inspector::resume)
            exportQuery?.let { ExportAction(inspector, it) }
            IconButton(onClick = {
                navigation.confirmClear = true
            }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.pulse_clear)) }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.pulse_close)) }
        },
    )
}

/** 현재 상태를 글자로 함께 보여 주는 기록 전환 버튼. 누르면 멈추거나 다시 기록한다. */
@Composable
private fun RecordingToggle(state: RecordingState, onPause: () -> Unit, onResume: () -> Unit) {
    val colors = LocalStatusColors.current
    val recording = state == RecordingState.RECORDING
    val label = stringResource(
        when (state) {
            RecordingState.RECORDING -> R.string.pulse_state_recording
            RecordingState.PAUSED -> R.string.pulse_state_paused
            RecordingState.DISABLED -> R.string.pulse_state_disabled
            RecordingState.UNINITIALIZED -> R.string.pulse_state_uninitialized
        },
    )
    val toggles = state == RecordingState.RECORDING || state == RecordingState.PAUSED
    val actionLabel = stringResource(if (recording) R.string.pulse_pause else R.string.pulse_resume)
    val base = Modifier
        .padding(end = Spacing.xs)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
    val modifier = if (toggles) {
        base.clickable(onClickLabel = actionLabel, role = Role.Button) {
            if (recording) onPause() else onResume()
        }
    } else {
        base
    }
    Row(
        modifier.padding(horizontal = Spacing.m, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (recording) colors.serverError else colors.pending, CircleShape),
        )
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
