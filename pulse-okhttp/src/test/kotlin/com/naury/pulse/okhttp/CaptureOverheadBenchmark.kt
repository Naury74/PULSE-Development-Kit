package com.naury.pulse.okhttp

import com.naury.pulse.api.CaptureMode
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.core.CaptureEngine
import com.naury.pulse.core.CaptureSink
import java.io.File
import java.util.Locale
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 인터셉터·EventListener·큐·마스킹이 요청 하나에 더하는 시간을 잰다. 저장소(Room)는 빼고 빈 sink를 쓴다.
 * 루프백 MockWebServer를 쓰므로 실제 네트워크 지연은 없다. `./gradlew :pulse-okhttp:benchmark`로만 실행한다.
 */
class CaptureOverheadBenchmark {
    private val body = """{"id":1,"name":"pulse","items":[${(1..20).joinToString(",")}],"token":"secret"}"""

    @Test
    fun measure() {
        assumeTrue(System.getProperty("pulse.benchmark") == "true")
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse.Builder().setHeader("Content-Type", "application/json").body(body).build()
            }
            server.start()
            val request = Request.Builder().url(server.url("/items?token=secret")).header("Authorization", "Bearer secret").build()
            val metadata = Pulsed(PulseConfig.defaults())
            val bodies = Pulsed(PulseConfig.Builder().captureMode(CaptureMode.METADATA_AND_SAFE_BODIES).build())
            val clients = linkedMapOf(
                "기준(PULSE 없음)" to OkHttpClient(),
                "메타데이터만" to metadata.client,
                "메타데이터 + JSON 본문" to bodies.client,
            )
            // JIT·서버 상태가 한 구성에만 유리하지 않도록 라운드마다 구성을 번갈아 돈다.
            repeat(WARMUP_ROUNDS) { clients.values.forEach { round(it, request) } }
            val results = clients.mapValues { mutableListOf<Double>() }
            repeat(MEASURED_ROUNDS) { clients.forEach { (name, client) -> results.getValue(name) += round(client, request) } }
            metadata.close()
            bodies.close()
            report(results)
        }
    }

    private class Pulsed(config: PulseConfig) {
        private val engine = CaptureEngine.start(config, CaptureSink { })
        private val registry = CallTimingRegistry()
        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(PulseInterceptor(engine.recorder, registry))
            .eventListenerFactory(PulseEventListenerFactory(engine.recorder, registry))
            .build()

        fun close() = engine.close()
    }

    private fun round(client: OkHttpClient, request: Request): Double {
        val start = System.nanoTime()
        repeat(CALLS) { client.newCall(request).execute().use { it.body.string() } }
        return (System.nanoTime() - start).toDouble() / CALLS
    }

    private fun report(results: Map<String, List<Double>>) {
        val baseline = results.values.first().sorted()
        val text = buildString {
            appendLine("JVM ${System.getProperty("java.version")} · ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
            appendLine("라운드당 요청 ${CALLS}개, 워밍업 ${WARMUP_ROUNDS}·측정 $MEASURED_ROUNDS 라운드, 단위 µs/요청")
            appendLine()
            appendLine("| 구성 | 중앙값 | p95 | 기준 대비 중앙값 차이 |")
            appendLine("| --- | --- | --- | --- |")
            results.forEach { (name, rounds) ->
                val sorted = rounds.sorted()
                val median = sorted[sorted.size / 2]
                val p95 = sorted[(sorted.size * P95 / PERCENT).coerceAtMost(sorted.size - 1)]
                val delta = median - baseline[baseline.size / 2]
                appendLine("| $name | ${micros(median)} | ${micros(p95)} | ${micros(delta)} |")
            }
        }
        println(text)
        File(System.getProperty("pulse.benchmark.report", "build/reports/benchmark.md")).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun micros(nanos: Double) = String.format(Locale.ROOT, "%.1f", nanos / NANOS_PER_MICRO)

    private companion object {
        const val CALLS = 200
        const val WARMUP_ROUNDS = 10
        const val MEASURED_ROUNDS = 30
        const val P95 = 95
        const val PERCENT = 100
        const val NANOS_PER_MICRO = 1_000.0
    }
}
