package com.naury.pulse.core

/** 마스킹된 기록을 저장한다. 저장 스레드 하나에서만 순서대로 불린다. 실패하면 예외를 던지고, 파이프라인이 실패 횟수를 센다. */
public fun interface CaptureSink {
    public fun write(records: List<CaptureRecord>)
}
