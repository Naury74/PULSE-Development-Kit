package com.naury.pulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.naury.pulse.api.PulseInspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

private const val REFRESH_DEBOUNCE_MILLIS = 1_000L

/**
 * 처음 한 번, 그 뒤로는 저장된 기록이 바뀔 때마다(1초 묶음) [load]를 다시 부른다. 읽기에 실패하면 이전 값을 그대로 둔다.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun <T : Any> rememberLive(inspector: PulseInspector, key: Any, events: Boolean = false, load: suspend () -> T): T? {
    val value by produceState<T?>(null, inspector, key) {
        val changes = if (events) merge(inspector.observeChanges(), inspector.observeEvents()) else inspector.observeChanges()
        changes.debounce(REFRESH_DEBOUNCE_MILLIS).onStart { emit(Unit) }.collect {
            @Suppress("TooGenericExceptionCaught")
            try {
                value = load()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // DB가 닫히는 중이면 다음 변경 때 다시 읽는다.
            }
        }
    }
    return value
}
