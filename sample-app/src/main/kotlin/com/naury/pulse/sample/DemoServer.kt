package com.naury.pulse.sample

import java.io.IOException
import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_CREATED
import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.net.HttpURLConnection.HTTP_MOVED_TEMP
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.json.JSONException
import org.json.JSONObject

/**
 * 인터넷 없이 여러 응답을 재현하는 앱 안의 로컬 서버. 127.0.0.1에만 열린다.
 * 원격 보고를 받는 /collect도 여기서 받는다. 보고 주소를 설정에 미리 적어야 해서 [PORT]에 먼저 열어 본다.
 */
class DemoServer : AutoCloseable {
    private val received = AtomicInteger()
    private val server = MockWebServer().apply { dispatcher = Routes(received) }

    /** /collect로 받은 보고 수. */
    val receivedReports: Int get() = received.get()

    /** 네트워크 작업이므로 메인 스레드가 아닌 곳에서 부른다. */
    @Synchronized
    fun start(): HttpUrl {
        if (!server.started) {
            val loopback = InetAddress.getByName("127.0.0.1")
            try {
                server.start(loopback, PORT)
            } catch (_: IOException) {
                // 다른 앱이 포트를 쓰면 아무 포트로 연다. 원격 보고만 받지 못한다.
                server.start(loopback, 0)
            }
        }
        return server.url("/")
    }

    override fun close() {
        server.close()
    }

    private class Routes(private val received: AtomicInteger) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
            "/collect" -> collect(request)

            "/reports" -> json("""{"received":"${request.headers["X-Pulse-Report-Id"].orEmpty()}"}""", code = HTTP_CREATED)

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

        private fun collect(request: RecordedRequest): MockResponse {
            val count = try {
                JSONObject(request.body?.utf8().orEmpty()).getJSONArray("reports").length()
            } catch (_: JSONException) {
                return json("""{"error":"bad_payload"}""", code = HTTP_BAD_REQUEST)
            }
            received.addAndGet(count)
            return json("""{"accepted":$count}""")
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

    companion object {
        /** 샘플 설정의 원격 보고 주소가 쓰는 포트. */
        const val PORT = 8787
        private const val SLOW_MILLIS = 1_500L
        private const val CHUNK_BYTES = 8 * 1024
        private const val LARGE_ITEM_COUNT = 30_000
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
