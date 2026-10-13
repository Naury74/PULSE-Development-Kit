package com.naury.pulse.core

import com.naury.pulse.api.BodyState
import com.naury.pulse.security.BodySanitizeResult
import com.naury.pulse.security.BodySanitizer

/** 큐를 비우며 원본 본문을 마스킹하고 [CaptureSink]에 넘기는 저장 스레드. */
internal class CapturePipeline(private val queue: CaptureQueue, private val sanitizer: BodySanitizer, private val sink: CaptureSink) {
    @Volatile
    private var running = true
    private val thread = Thread(::run, THREAD_NAME).apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    /** 남은 이벤트를 [timeoutMillis]까지 저장하고 멈춘다. */
    fun close(timeoutMillis: Long) {
        running = false
        thread.join(timeoutMillis)
    }

    private fun run() {
        while (running || !queue.isEmpty()) {
            val batch = queue.drain(MAX_BATCH, POLL_MILLIS)
            if (batch.isEmpty()) continue
            write(batch.map(::toRecord))
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun write(records: List<CaptureRecord>) {
        try {
            sink.write(records)
        } catch (_: Exception) {
            queue.recordWriteFailure()
        }
    }

    private fun toRecord(event: QueuedEvent): CaptureRecord = when (event) {
        is CaptureRecord -> event
        is RawBody -> sanitize(event)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun sanitize(body: RawBody): BodyRecord {
        val result = try {
            sanitizer.sanitize(body.bytes, body.contentType)
        } catch (_: RuntimeException) {
            BodySanitizeResult.Rejected(BodyState.REDACTION_FAILED)
        } catch (_: StackOverflowError) {
            BodySanitizeResult.Rejected(BodyState.REDACTION_FAILED)
        }
        return when (result) {
            is BodySanitizeResult.Captured ->
                BodyRecord(body.transactionId, body.kind, BodyState.CAPTURED, result.text, result.redactedValueCount, body.transferredBytes)

            is BodySanitizeResult.Rejected ->
                BodyRecord(body.transactionId, body.kind, result.state, text = null, redactedValueCount = 0, body.transferredBytes)
        }
    }

    private companion object {
        const val THREAD_NAME = "pulse-capture-writer"
        const val MAX_BATCH = 200
        const val POLL_MILLIS = 250L
    }
}
