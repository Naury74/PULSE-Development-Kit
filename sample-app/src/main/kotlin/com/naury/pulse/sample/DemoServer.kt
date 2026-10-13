package com.naury.pulse.sample

import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.net.HttpURLConnection.HTTP_MOVED_TEMP
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okio.Buffer
import okio.GzipSink
import okio.buffer

/** 인터넷 없이 여러 응답을 재현하는 앱 안의 로컬 서버. 127.0.0.1에만 열린다. */
class DemoServer : AutoCloseable {
    private val server = MockWebServer().apply { dispatcher = Routes() }

    /** 네트워크 작업이므로 메인 스레드가 아닌 곳에서 부른다. */
    fun start(): HttpUrl {
        if (!server.started) server.start(InetAddress.getByName("127.0.0.1"), 0)
        return server.url("/")
    }

    override fun close() {
        server.close()
    }

    private class Routes : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
            "/users/1" -> json("""{"id":1,"name":"Kim","email":"kim@example.com","accessToken":"demo-access-token"}""")

            "/login" -> json("""{"sessionId":"demo-session","expiresIn":3600}""")

            "/missing" -> json("""{"error":"not_found"}""", code = HTTP_NOT_FOUND)

            "/error" -> json("""{"error":"internal","traceId":"abc123"}""", code = HTTP_INTERNAL_ERROR)

            "/redirect" -> MockResponse.Builder().code(HTTP_MOVED_TEMP).setHeader("Location", "/users/1").build()

            "/slow" -> json("""{"slow":true}""").newBuilder().headersDelay(SLOW_MILLIS, TimeUnit.MILLISECONDS).build()

            "/gzip" -> gzipJson("""{"compressed":true,"password":"demo-password"}""")

            "/large" -> MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .chunkedBody(largeJson(), CHUNK_BYTES)
                .build()

            "/image" -> MockResponse.Builder().setHeader("Content-Type", "image/png").body(Buffer().write(PNG_SIGNATURE)).build()

            else -> json("""{"path":"${request.url.encodedPath}"}""")
        }

        private fun json(body: String, code: Int = 200) =
            MockResponse.Builder().code(code).setHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

        private fun gzipJson(body: String): MockResponse {
            val compressed = Buffer().also { buffer -> GzipSink(buffer).buffer().use { it.writeUtf8(body) } }
            return MockResponse.Builder()
                .setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip")
                .body(compressed)
                .build()
        }

        private fun largeJson(): String = (1..LARGE_ITEM_COUNT).joinToString(",", "[", "]") { """{"id":$it,"value":"item-$it"}""" }
    }

    private companion object {
        const val SLOW_MILLIS = 1_500L
        const val CHUNK_BYTES = 8 * 1024
        const val LARGE_ITEM_COUNT = 30_000
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
