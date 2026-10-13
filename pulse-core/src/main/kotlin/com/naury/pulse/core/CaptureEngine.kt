package com.naury.pulse.core

import com.naury.pulse.api.CaptureHealth
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.security.BodySanitizer
import com.naury.pulse.security.Redactor
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

/** 수집 큐·저장 스레드·[CaptureRecorder]를 묶는다. [start]로 만들고 [close]로 멈춘다. */
public class CaptureEngine private constructor(
    public val recorder: CaptureRecorder,
    private val queue: CaptureQueue,
    private val pipeline: CapturePipeline,
) {
    public val health: StateFlow<CaptureHealth> = queue.health

    /** 기록을 멈추고 큐에 남은 기록을 [timeoutMillis]까지 저장한다. 다시 시작할 수 없다. */
    public fun close(timeoutMillis: Long = DEFAULT_CLOSE_TIMEOUT_MILLIS) {
        recorder.controller.disable()
        pipeline.close(timeoutMillis)
    }

    public companion object {
        public const val DEFAULT_CLOSE_TIMEOUT_MILLIS: Long = 2_000

        @JvmStatic
        public fun start(
            config: PulseConfig,
            sink: CaptureSink,
            clock: CaptureClock = SystemCaptureClock,
            sessionId: String = UUID.randomUUID().toString(),
        ): CaptureEngine {
            val redactor = Redactor(config.redaction)
            val queue = CaptureQueue(config.queueCapacity, pendingBodyBudget(config))
            val recorder = CaptureRecorder(config, clock, queue, redactor, sessionId)
            val pipeline = CapturePipeline(queue, BodySanitizer(redactor), sink)
            pipeline.start()
            return CaptureEngine(recorder, queue, pipeline)
        }

        /** 저장을 기다리는 원본 본문 메모리 상한. 가장 큰 본문 네 개 또는 8 MiB 중 큰 값이다. */
        private fun pendingBodyBudget(config: PulseConfig): Long {
            val largestBody = maxOf(config.limits.maxRequestBodyBytes, config.limits.maxResponseBodyBytes)
            return maxOf(largestBody * PENDING_BODY_COUNT, MIN_PENDING_BODY_BYTES)
        }

        private const val PENDING_BODY_COUNT = 4
        private const val MIN_PENDING_BODY_BYTES = 8L * 1024 * 1024
    }
}
