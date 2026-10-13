package com.naury.pulse.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** PULSE 모듈끼리만 쓰는 API. 호스트 앱에서 쓰면 다음 버전에서 예고 없이 바뀔 수 있다. */
@RequiresOptIn(message = "PULSE 모듈 내부 연결용 API입니다.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class PulseInternalApi

/**
 * 현재 초기화된 [PulseInspector]. 인스펙터 화면이 호스트 앱의 객체 그래프 없이 데이터를 찾을 때 쓴다.
 * 초기화 전이거나 no-op artifact에서는 null이다.
 */
public object PulseInspectorRegistry {
    private val instance = MutableStateFlow<PulseInspector?>(null)

    @JvmStatic
    public val current: PulseInspector?
        get() = instance.value

    /** 초기화·종료에 따라 바뀌는 [current]. 앱 시작 전에 등록된 구독자도 초기화를 알 수 있다. */
    @JvmStatic
    public val updates: StateFlow<PulseInspector?> = instance.asStateFlow()

    @PulseInternalApi
    @JvmStatic
    public fun register(inspector: PulseInspector?) {
        instance.value = inspector
    }
}
