package com.naury.pulse.monitor

import android.os.Process
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.LogLevel
import com.naury.pulse.core.EventRecorder
import java.io.IOException

/**
 * 이 앱 프로세스의 logcat을 읽어 로그 이벤트로 남긴다. `--pid`로 자기 프로세스만 읽으므로 권한이 필요 없고 다른 앱의 로그는 보지 않는다.
 * 읽기는 별도 데몬 스레드에서 하며, 실패하면 조용히 멈춘다.
 */
internal class LogcatCollector(private val events: EventRecorder, private val minLevel: LogLevel, private val ignoredTags: Set<String>) {
    @Volatile
    private var process: java.lang.Process? = null
    private val thread = Thread(::read, "pulse-logcat").apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    fun stop() {
        process?.destroy()
        thread.interrupt()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun read() {
        try {
            // -T 1: 지난 로그는 한 줄만 받고 그 뒤로 새 로그를 계속 받는다.
            val started = ProcessBuilder("logcat", "-v", "epoch", "--pid=${Process.myPid()}", "-T", "1").redirectErrorStream(true).start()
            process = started
            started.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> parse(line)?.let(::record) }
            }
        } catch (_: IOException) {
            // logcat을 실행할 수 없는 기기에서는 로그 수집만 빠진다.
        } catch (_: RuntimeException) {
            // 같은 이유.
        }
    }

    private fun record(line: LogLine) {
        if (line.level.ordinal < minLevel.ordinal || line.tag in ignoredTags || line.tag in ownTags) return
        events.record(EventKind.LOG, line.message, level = line.level, tag = line.tag, timestampEpochMillis = line.epochMillis)
    }

    internal class LogLine(val epochMillis: Long, val level: LogLevel, val tag: String, val message: String)

    internal companion object {
        private val ownTags = setOf("pulse-logcat")

        /** `1697260000.123  1234  1235 D Tag: message` 한 줄을 읽는다. 형식이 다르면 null. */
        fun parse(line: String): LogLine? {
            val match = linePattern.matchEntire(line) ?: return null
            val groups = match.groupValues
            val level = levelOf(groups[GROUP_LEVEL]) ?: return null
            return LogLine(
                (groups[GROUP_TIME].toDouble() * MILLIS_PER_SECOND).toLong(),
                level,
                groups[GROUP_TAG].trim(),
                groups[GROUP_MESSAGE],
            )
        }

        private const val GROUP_TIME = 1
        private const val GROUP_LEVEL = 2
        private const val GROUP_TAG = 3
        private const val GROUP_MESSAGE = 4
        private val linePattern = Regex("""^\s*(\d+\.\d+)\s+\d+\s+\d+\s+([VDIWEFA])\s+(.*?):\s?(.*)$""")

        private fun levelOf(code: String): LogLevel? = when (code) {
            "V" -> LogLevel.VERBOSE
            "D" -> LogLevel.DEBUG
            "I" -> LogLevel.INFO
            "W" -> LogLevel.WARN
            "E" -> LogLevel.ERROR
            "F", "A" -> LogLevel.ASSERT
            else -> null
        }

        private const val MILLIS_PER_SECOND = 1000.0
    }
}
