package com.naury.pulse.core

import com.naury.pulse.api.RecordingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 기록 켜기·멈추기 상태. [disable] 뒤에는 다시 켤 수 없다. */
public class RecordingController internal constructor(startRecording: Boolean) {
    private val state = MutableStateFlow(if (startRecording) RecordingState.RECORDING else RecordingState.PAUSED)

    public val recordingState: StateFlow<RecordingState> = state.asStateFlow()

    public val isRecording: Boolean
        get() = state.value == RecordingState.RECORDING

    public fun pause() {
        state.compareAndSet(RecordingState.RECORDING, RecordingState.PAUSED)
    }

    public fun resume() {
        state.compareAndSet(RecordingState.PAUSED, RecordingState.RECORDING)
    }

    internal fun disable() {
        state.value = RecordingState.DISABLED
    }
}
