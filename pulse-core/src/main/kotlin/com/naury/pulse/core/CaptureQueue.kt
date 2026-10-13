package com.naury.pulse.core

import com.naury.pulse.api.CaptureHealth
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 네트워크 스레드에서 저장 스레드로 넘기는 상한 있는 큐. [offer]는 막지 않고 즉시 돌아온다.
 *
 * 넘칠 때는 본문을 먼저 버린다(DROP_BODY_FIRST): 큐가 3/4 이상 찼거나 대기 중인 본문 바이트가
 * [maxPendingBodyBytes]를 넘으면 원본 본문 대신 버렸다는 기록만 넣고, 그마저 들어갈 자리가 없으면 이벤트를 버린다.
 */
internal class CaptureQueue(private val capacity: Int, private val maxPendingBodyBytes: Long) {
    private val queue = LinkedBlockingQueue<QueuedEvent>(capacity)
    private val pendingBodyBytes = AtomicLong()
    private val bodyThreshold = (capacity * BODY_HEADROOM_RATIO).toInt().coerceAtLeast(1)
    private val mutableHealth = MutableStateFlow(CaptureHealth())

    val health: StateFlow<CaptureHealth> = mutableHealth.asStateFlow()

    fun offer(event: QueuedEvent): Boolean = when (event) {
        is RawBody -> offerBody(event)
        is CaptureRecord -> offerRecord(event)
    }

    /** 최대 [maxItems]개를 꺼낸다. 비어 있으면 [timeoutMillis]까지 기다리고, 그래도 없으면 빈 목록을 돌려준다. */
    fun drain(maxItems: Int, timeoutMillis: Long): List<QueuedEvent> {
        val first = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return emptyList()
        val batch = ArrayList<QueuedEvent>(minOf(maxItems, queue.size + 1))
        batch.add(first)
        queue.drainTo(batch, maxItems - 1)
        batch.forEach { if (it is RawBody) pendingBodyBytes.addAndGet(-it.bytes.size.toLong()) }
        return batch
    }

    fun isEmpty(): Boolean = queue.isEmpty()

    fun recordLostEvent() {
        mutableHealth.update { it.copy(droppedEvents = it.droppedEvents + 1) }
    }

    fun recordWriteFailure() {
        mutableHealth.update { it.copy(writeFailures = it.writeFailures + 1) }
    }

    private fun offerBody(body: RawBody): Boolean {
        val size = body.bytes.size.toLong()
        val withinMemory = pendingBodyBytes.addAndGet(size) <= maxPendingBodyBytes
        if (withinMemory && queue.size < bodyThreshold && queue.offer(body)) {
            trackHighWater()
            return true
        }
        pendingBodyBytes.addAndGet(-size)
        mutableHealth.update { it.copy(droppedBodies = it.droppedBodies + 1) }
        offerRecord(CaptureRecorder.droppedBody(body))
        return false
    }

    private fun offerRecord(record: CaptureRecord): Boolean {
        if (queue.offer(record)) {
            trackHighWater()
            return true
        }
        recordLostEvent()
        return false
    }

    private fun trackHighWater() {
        val size = queue.size
        if (size > mutableHealth.value.queueHighWater) {
            mutableHealth.update { if (size > it.queueHighWater) it.copy(queueHighWater = size) else it }
        }
    }

    private fun CaptureHealth.copy(
        droppedEvents: Long = this.droppedEvents,
        droppedBodies: Long = this.droppedBodies,
        queueHighWater: Int = this.queueHighWater,
        writeFailures: Long = this.writeFailures,
    ) = CaptureHealth(droppedEvents, droppedBodies, queueHighWater, writeFailures)

    private companion object {
        /** 큐의 마지막 1/4은 메타데이터 이벤트 몫으로 남긴다. */
        const val BODY_HEADROOM_RATIO = 0.75
    }
}
