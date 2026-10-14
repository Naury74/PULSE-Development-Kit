package com.naury.pulse.core

import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.LogLevel
import com.naury.pulse.security.Redactor
import java.util.UUID

/**
 * 앱 이벤트(로그·마커·구간·오류·성능)를 마스킹해 저장 큐에 넣는다. 어느 스레드에서 불러도 되고, 실패는 밖으로 던지지 않는다.
 * 크래시처럼 프로세스가 곧 끝나는 경우는 [recordNow]로 큐를 거치지 않고 바로 저장한다.
 */
@Suppress("TooManyFunctions")
public class EventRecorder internal constructor(
    private val recorder: CaptureRecorder,
    private val redactor: Redactor,
    private val queue: CaptureQueue,
    private val sink: CaptureSink,
) {
    private val openSpans = java.util.concurrent.ConcurrentHashMap<String, OpenSpan>()

    /** 지금 앞에 있는 화면 이름. 앱 상태 수집기가 채운다. */
    @Volatile
    public var currentScreen: String? = null

    public val sessionId: String
        get() = recorder.sessionId

    /** 기록을 멈췄으면 크래시를 뺀 이벤트는 남기지 않는다. */
    public val isRecording: Boolean
        get() = recorder.isRecording

    @Suppress("LongParameterList")
    public fun record(
        kind: EventKind,
        message: String,
        level: LogLevel? = null,
        tag: String? = null,
        detail: String? = null,
        spanId: String? = null,
        durationMillis: Long? = null,
        value: Long? = null,
        attachmentPng: ByteArray? = null,
        timestampEpochMillis: Long = recorder.clock.wallMillis(),
        sessionId: String = recorder.sessionId,
    ) {
        if (!recorder.isRecording) return
        enqueue { build(kind, message, level, tag, detail, spanId, durationMillis, value, attachmentPng, timestampEpochMillis, sessionId) }
    }

    /**
     * 큐를 거치지 않고 바로 저장하고 끝날 때까지 기다린다(최대 [timeoutMillis]). 실패하거나 시간을 넘기면 false.
     * 크래시는 대개 메인 스레드에서 나는데 DB는 메인 스레드에서 쓸 수 없으므로, 따로 만든 스레드에서 쓰고 기다린다.
     * 기록을 멈췄어도 저장한다.
     */
    @Suppress("LongParameterList")
    public fun recordNow(
        kind: EventKind,
        message: String,
        level: LogLevel?,
        tag: String?,
        detail: String?,
        attachmentPng: ByteArray?,
        session: SessionRecord?,
        timeoutMillis: Long = CRASH_WRITE_TIMEOUT_MILLIS,
    ): Boolean {
        val event =
            build(kind, message, level, tag, detail, null, null, null, attachmentPng, recorder.clock.wallMillis(), recorder.sessionId)
        val written = java.util.concurrent.atomic.AtomicBoolean()
        val writer = Thread({
            @Suppress("TooGenericExceptionCaught")
            try {
                sink.write(listOfNotNull(event, session))
                written.set(true)
            } catch (_: Exception) {
                // 저장하지 못하면 원래 크래시 처리만 이어진다.
            }
        }, "pulse-crash-writer")
        writer.start()
        try {
            writer.join(timeoutMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return written.get()
    }

    public fun mark(name: String) {
        record(EventKind.MARKER, name)
    }

    public fun beginSpan(name: String): String {
        val id = UUID.randomUUID().toString()
        openSpans[id] = OpenSpan(name, recorder.clock.monoNanos())
        record(EventKind.SPAN_START, name, spanId = id)
        return id
    }

    public fun endSpan(spanId: String) {
        val span = openSpans.remove(spanId) ?: return
        val millis = (recorder.clock.monoNanos() - span.startNanos) / NANOS_PER_MILLI
        record(EventKind.SPAN_END, span.name, spanId = spanId, durationMillis = millis)
    }

    /** 가장 최근에 시작해 아직 열려 있는 구간. 그동안 시작한 요청에 붙인다. */
    public val currentSpanId: String?
        get() = openSpans.entries.maxByOrNull { it.value.startNanos }?.key

    /** 사용자 행동. [attributes]는 [MAX_ATTRIBUTES]개, 값은 [MAX_ATTRIBUTE_CHARS]자까지 JSON으로 [AppEvent.detail]에 넣는다. */
    public fun action(id: String, attributes: Map<String, String>) {
        val detail = attributes.entries.take(MAX_ATTRIBUTES).takeIf { it.isNotEmpty() }?.joinToString(",", "{", "}") { (key, value) ->
            "\"${jsonEscape(key.take(MAX_TAG_CHARS))}\":\"${jsonEscape(value.take(MAX_ATTRIBUTE_CHARS))}\""
        }
        record(EventKind.ACTION, id.take(MAX_TAG_CHARS), tag = ACTION_TAG, detail = detail)
    }

    /** 화면이 바뀌었으면 이동을 기록하고 지금 화면으로 삼는다. 같은 화면이면 아무것도 하지 않는다. */
    public fun navigate(screen: String) {
        val previous = currentScreen
        if (previous == screen) return
        currentScreen = screen
        record(EventKind.NAVIGATION, "${previous ?: "-"} → $screen", tag = NAVIGATION_TAG)
    }

    /** 플래그·환경 값이 바뀌었다. 값이 null이면 지운 것이다. */
    public fun config(name: String, value: String?) {
        record(EventKind.CONFIG, "$name = ${value ?: "(removed)"}", tag = name.take(MAX_TAG_CHARS))
    }

    /** 세션의 마지막 확인 시각과 포그라운드 시간을 갱신한다. */
    public fun touchSession(lastSeenEpochMillis: Long, foregroundMillis: Long) {
        enqueue { SessionRecord(recorder.sessionId, lastSeenEpochMillis, foregroundMillis, null, null) }
    }

    /** 다른(지난) 세션의 종료 이유를 기록한다. */
    public fun closeSession(sessionId: String, exitReason: String, crashed: Boolean) {
        enqueue { SessionRecord(sessionId, null, null, exitReason, crashed) }
    }

    /** 크래시 직전 세션 갱신. [recordNow]에 함께 넘긴다. */
    public fun crashedSession(lastSeenEpochMillis: Long, foregroundMillis: Long): SessionRecord =
        SessionRecord(recorder.sessionId, lastSeenEpochMillis, foregroundMillis, CRASH_REASON, true)

    @Suppress("LongParameterList")
    private fun build(
        kind: EventKind,
        message: String,
        level: LogLevel?,
        tag: String?,
        detail: String?,
        spanId: String?,
        durationMillis: Long?,
        value: Long?,
        attachmentPng: ByteArray?,
        timestampEpochMillis: Long,
        sessionId: String,
    ) = EventRecord(
        transactionId = UUID.randomUUID().toString(),
        sessionId = sessionId,
        timestampEpochMillis = timestampEpochMillis,
        kind = kind,
        level = level,
        tag = tag?.take(MAX_TAG_CHARS),
        message = redactor.sanitizeTextPublic(message).take(AppEvent.MAX_MESSAGE_CHARS),
        detail = detail?.let { redactor.sanitizeTextPublic(it).take(AppEvent.MAX_DETAIL_CHARS) },
        // 지난 실행의 이벤트(종료 이유 등)에 지금 화면을 붙이면 틀린 정보가 된다.
        screen = currentScreen.takeIf { sessionId == recorder.sessionId },
        spanId = spanId,
        durationMillis = durationMillis,
        value = value,
        attachmentPng = attachmentPng,
    )

    @Suppress("TooGenericExceptionCaught")
    private inline fun enqueue(create: () -> QueuedEvent) {
        try {
            queue.offer(create())
        } catch (_: RuntimeException) {
            queue.recordLostEvent()
        }
    }

    private class OpenSpan(val name: String, val startNanos: Long)

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { char ->
            when {
                char == '"' || char == '\\' -> append('\\').append(char)
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
    }

    public companion object {
        public const val CRASH_REASON: String = "CRASH"
        public const val CRASH_WRITE_TIMEOUT_MILLIS: Long = 2_000
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MAX_TAG_CHARS = 64
        private const val MAX_ATTRIBUTES = 32
        private const val MAX_ATTRIBUTE_CHARS = 256
        private const val ACTION_TAG = "action"
        private const val NAVIGATION_TAG = "navigation"
    }
}
