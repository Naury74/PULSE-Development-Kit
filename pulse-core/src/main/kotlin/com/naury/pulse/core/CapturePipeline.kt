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

    /**
     * 묶음을 저장한다. 다른 연결이 잠시 쓰기 잠금을 잡고 있는 경우(같은 파일을 닫고 바로 다시 연 경우 등)를 넘기려고
     * 짧게 기다리며 몇 번 다시 시도한다. 저장소는 실패한 묶음을 되돌리므로 다시 써도 중복되지 않는다.
     */
    private fun write(records: List<CaptureRecord>) {
        var attempt = 0
        while (!tryWrite(records)) {
            if (!pause(attempt++)) {
                queue.recordWriteFailure()
                return
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun tryWrite(records: List<CaptureRecord>): Boolean = try {
        sink.write(records)
        true
    } catch (_: Exception) {
        false
    }

    /** [attempt]번째 실패 뒤 기다린다. 더 기다릴 차례가 없거나 끊겼으면 false. */
    private fun pause(attempt: Int): Boolean {
        val wait = RETRY_DELAYS_MILLIS.getOrNull(attempt) ?: return false
        return try {
            Thread.sleep(wait)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
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
        val RETRY_DELAYS_MILLIS = longArrayOf(50, 200, 800)
    }
}
