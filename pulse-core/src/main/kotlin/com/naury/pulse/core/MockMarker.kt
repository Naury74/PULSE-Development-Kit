package com.naury.pulse.core

/** 모의 응답임을 알리는 응답 헤더. 값은 규칙 ID다. 수집기는 이 헤더가 있으면 출처를 MOCK으로 기록한다. */
public object MockMarker {
    public const val HEADER: String = "X-Pulse-Mock"
}
