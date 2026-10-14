package com.naury.pulse.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.pulse.api.PulseInspector

/** 설정 탭의 앱·기기 환경과 호스트가 알린 기능 플래그. 플래그는 바뀔 때마다 타임라인에 이력이 남는다. */
@Composable
internal fun EnvironmentSection(inspector: PulseInspector) {
    val environment = remember(inspector) { inspector.environment() } ?: return
    val flags by inspector.flags.collectAsStateWithLifecycle()
    SectionTitle(stringResource(R.string.pulse_env_title))
    PulseCard {
        SheetLabel(stringResource(R.string.pulse_env_flags))
        if (flags.isEmpty()) {
            Text(
                stringResource(R.string.pulse_env_flags_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            flags.toSortedMap().forEach { (name, value) -> Field(name, value, monospace = true) }
        }
        SheetLabel(stringResource(R.string.pulse_env_app))
        environment.app.forEach { (name, value) -> Field(name, value, monospace = true) }
        SheetLabel(stringResource(R.string.pulse_env_device))
        environment.device.forEach { (name, value) -> Field(name, value, monospace = true) }
    }
}
