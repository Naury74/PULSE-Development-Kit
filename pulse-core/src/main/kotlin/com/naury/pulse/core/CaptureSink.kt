package com.naury.pulse.core

/**
 * 마스킹된 기록을 저장한다. 보통 저장 스레드 하나에서 순서대로 불리고, 크래시 직전에는 크래시 기록 스레드에서도 불린다.
 * 구현은 두 호출이 겹쳐도 안전해야 한다. 실패하면 예외를 던지고, 파이프라인이 실패 횟수를 센다.
 */
public fun interface CaptureSink {
    public fun write(records: List<CaptureRecord>)
}
