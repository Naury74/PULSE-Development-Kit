package com.naury.pulse.core

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.TransactionState
import org.junit.Test

class CaptureQueueTest {
    private fun finish(id: String) = FinishRecord(id, TransactionState.COMPLETED, 1, null, null)

    private fun body(id: String, size: Int) = RawBody(id, MessageKind.RESPONSE, ByteArray(size), "application/json", size.toLong())

    @Test
    fun `가득 차면 막지 않고 버린 수를 센다`() {
        val queue = CaptureQueue(capacity = 4, maxPendingBodyBytes = 1_000)

        val accepted = (1..10).count { queue.offer(finish("t$it")) }

        assertThat(accepted).isEqualTo(4)
        assertThat(queue.health.value.droppedEvents).isEqualTo(6)
        assertThat(queue.health.value.queueHighWater).isEqualTo(4)
    }

    @Test
    fun `큐가 3_4 이상 차면 본문 대신 버림 기록을 넣는다`() {
        val queue = CaptureQueue(capacity = 4, maxPendingBodyBytes = 1_000)
        repeat(3) { queue.offer(finish("t$it")) }

        assertThat(queue.offer(body("b", 10))).isFalse()

        val drained = queue.drain(10, 0)
        val dropped = drained.filterIsInstance<BodyRecord>().single()
        assertThat(dropped.state).isEqualTo(BodyState.DROPPED)
        assertThat(dropped.transferredBytes).isEqualTo(10)
        assertThat(drained.none { it is RawBody }).isTrue()
        assertThat(queue.health.value.droppedBodies).isEqualTo(1)
    }

    @Test
    fun `대기 중인 본문 메모리가 상한을 넘으면 본문을 버린다`() {
        val queue = CaptureQueue(capacity = 100, maxPendingBodyBytes = 100)

        assertThat(queue.offer(body("a", 60))).isTrue()
        assertThat(queue.offer(body("b", 60))).isFalse()

        queue.drain(100, 0)
        assertThat(queue.offer(body("c", 60))).isTrue()
    }

    @Test
    fun `비어 있으면 기다린 뒤 빈 목록을 돌려준다`() {
        val queue = CaptureQueue(capacity = 4, maxPendingBodyBytes = 10)

        assertThat(queue.drain(10, 1)).isEmpty()
    }
}
