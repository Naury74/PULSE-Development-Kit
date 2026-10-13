package com.naury.pulse.core

/** 표시용 벽시계와 시간 계산용 단조 시계. 길이는 항상 [monoNanos] 차이로 계산한다. */
public interface CaptureClock {
    public fun wallMillis(): Long

    public fun monoNanos(): Long
}

public object SystemCaptureClock : CaptureClock {
    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun monoNanos(): Long = System.nanoTime()
}
