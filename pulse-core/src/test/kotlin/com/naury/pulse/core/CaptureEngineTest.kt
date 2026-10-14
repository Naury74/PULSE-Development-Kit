package com.naury.pulse.core

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.CaptureMode
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.RecordingState
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import com.naury.pulse.api.TransactionState
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

class CaptureEngineTest {
    private val written = CopyOnWriteArrayList<CaptureRecord>()

    private fun start(config: PulseConfig = PulseConfig.debugDefaults(), sink: CaptureSink = CaptureSink { written += it }) =
        CaptureEngine.start(config, sink)

    @Test
    fun `헤더·URL은 큐에 넣기 전에 마스킹한다`() {
        val engine = start()

        engine.recorder.requestStarted(
            transactionId = "t1",
            startedAtEpochMillis = 1,
            method = "GET",
            url = "https://a.test/p?token=secret",
            scheme = "https",
            host = "a.test",
            path = "/p",
            headers = listOf("Authorization" to "Bearer secret"),
            contentType = null,
            declaredContentLength = null,
        )
        engine.close()

        val request = written.filterIsInstance<RequestRecord>().single()
        assertThat(request.url).isEqualTo("https://a.test/p?token=$REDACTED")
        assertThat(request.headers.single().value).isEqualTo(REDACTED)
        assertThat(request.sessionId).isEqualTo(engine.recorder.sessionId)
    }

    @Test
    fun `원본 본문은 저장 스레드에서 마스킹한 뒤 넘긴다`() {
        val engine = start()

        engine.recorder.bodyCaptured("t1", MessageKind.RESPONSE, """{"password":"secret","id":1}""".toByteArray(), "application/json")
        engine.recorder.bodyCaptured("t2", MessageKind.RESPONSE, """{"password":"secret",""".toByteArray(), "application/json")
        engine.close()

        val bodies = written.filterIsInstance<BodyRecord>().associateBy { it.transactionId }
        assertThat(bodies.getValue("t1").state).isEqualTo(BodyState.CAPTURED)
        assertThat(bodies.getValue("t1").text).isEqualTo("""{"password":"$REDACTED","id":1}""")
        assertThat(bodies.getValue("t1").redactedValueCount).isEqualTo(1)
        assertThat(bodies.getValue("t2").state).isEqualTo(BodyState.REDACTION_FAILED)
        assertThat(bodies.getValue("t2").text).isNull()
        assertThat(written.joinToString { (it as? BodyRecord)?.text.orEmpty() }).doesNotContain("secret")
    }

    @Test
    fun `실패 메시지는 마스킹하고 예외 종류를 남긴다`() {
        val engine = start()

        engine.recorder.finished("t1", TransactionState.FAILED, 10, IOException("failed https://a.test/?token=secret"))
        engine.close()

        val finish = written.filterIsInstance<FinishRecord>().single()
        assertThat(finish.failureType).isEqualTo("java.io.IOException")
        assertThat(finish.failureMessage).doesNotContain("secret")
    }

    @Test
    fun `계속 실패하는 묶음은 세고 다음 기록을 계속 저장한다`() {
        val firstAttempt = CountDownLatch(1)
        val engine = start(
            sink = CaptureSink { records ->
                if (records.any { it.transactionId == "t1" }) {
                    firstAttempt.countDown()
                    error("disk full")
                }
                written += records
            },
        )

        engine.recorder.finished("t1", TransactionState.COMPLETED, 1, null)
        assertThat(firstAttempt.await(5, TimeUnit.SECONDS)).isTrue()
        engine.recorder.finished("t2", TransactionState.COMPLETED, 1, null)
        engine.close()

        assertThat(engine.health.value.writeFailures).isEqualTo(1)
        assertThat(written.map { it.transactionId }).containsExactly("t2")
    }

    @Test
    fun `잠깐 실패한 묶음은 다시 시도해 저장한다`() {
        var calls = 0
        val engine = start(
            sink = CaptureSink { records ->
                if (++calls == 1) error("database is locked")
                written += records
            },
        )

        engine.recorder.finished("t1", TransactionState.COMPLETED, 1, null)
        engine.close()

        assertThat(engine.health.value.writeFailures).isEqualTo(0)
        assertThat(written.map { it.transactionId }).containsExactly("t1")
    }

    @Test
    fun `기록 상태를 멈추고 다시 시작하며 종료 후에는 바꿀 수 없다`() {
        val engine = start(PulseConfig.Builder().captureMode(CaptureMode.METADATA_ONLY).startRecording(false).build())
        val recorder = engine.recorder
        val controller = recorder.controller

        assertThat(controller.recordingState.value).isEqualTo(RecordingState.PAUSED)
        assertThat(recorder.capturesBodies).isFalse()
        controller.resume()
        assertThat(recorder.isRecording).isTrue()
        controller.pause()
        assertThat(recorder.isRecording).isFalse()

        engine.close()
        controller.resume()
        assertThat(controller.recordingState.value).isEqualTo(RecordingState.DISABLED)
    }

    @Test
    fun `CAPTURED는 bodyNotCaptured로 기록할 수 없고 호스트로 예외가 나가지 않는다`() {
        val engine = start()

        engine.recorder.bodyNotCaptured("t1", MessageKind.REQUEST, BodyState.CAPTURED, null)
        engine.close()

        assertThat(written).isEmpty()
        assertThat(engine.health.value.droppedEvents).isEqualTo(1)
    }
}
