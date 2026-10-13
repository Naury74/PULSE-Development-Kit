package com.naury.pulse.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import com.naury.pulse.export.CurlFormatter
import com.naury.pulse.export.ExportFormat
import com.naury.pulse.export.PulseExporter
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 보안 점검: 가짜 비밀 값을 여러 위치에 심어 실제 파이프라인을 거친 뒤, 기기에 남은 모든 파일(DB·WAL·본문)과
 * 내보내기 결과에 비밀이 하나도 없는지 바이트 단위로 확인한다.
 */
@RunWith(RobolectricTestRunner::class)
class SecretLeakAuditTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val secrets = (1..14).map { "pulse-canary-$it-${System.nanoTime()}" }

    @After
    fun tearDown() {
        Pulse.current()?.close()
    }

    @Test
    fun `심은 비밀이 저장소와 내보내기 어디에도 남지 않는다`() = runBlocking {
        val handle = Pulse.initialize(context, PulseConfig.debugDefaults())
        val client = OkHttpClient.Builder().addInterceptor(handle.interceptor()).eventListenerFactory(handle.eventListenerFactory()).build()
        val json = "application/json".toMediaType()

        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Set-Cookie", "sid=${secrets[0]}; HttpOnly")
                    .body(
                        """{"user":{"accessToken":"${secrets[1]}","profile":{"ssn":"${secrets[2]}"}},""" +
                            """"list":[{"refresh_token":"${secrets[3]}"}],""" +
                            // 받은 요청을 문자열로 되돌려 주는 서버(httpbin 등)
                            """"echo":"{\"password\":\"${secrets[12]}\"}","url":"https://api.test/get?token=${secrets[13]}"}""",
                    )
                    .build(),
            )
            server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/next?token=${secrets[4]}").build())
            server.enqueue(MockResponse.Builder().code(200).build())
            server.enqueue(
                MockResponse.Builder().setHeader("Content-Type", "application/json").body("""{"password":"${secrets[5]}",""").build(),
            )
            server.enqueue(MockResponse.Builder().code(200).build())

            client.newCall(
                Request.Builder()
                    .url(server.url("/login?api_key=${secrets[6]}&code=${secrets[7]}&page=1"))
                    .header("Authorization", "Bearer ${secrets[8]}")
                    .header("X-Auth-Token", secrets[9])
                    .post("""{"credentials":{"password":"${secrets[10]}"},"items":[1,2]}""".toRequestBody(json))
                    .build(),
            ).execute().use { it.body.string() }
            client.newCall(Request.Builder().url(server.url("/redirect")).build()).execute().use { it.body.string() }
            client.newCall(Request.Builder().url(server.url("/broken")).build()).execute().use { it.body.string() }
            client.newCall(Request.Builder().url(server.url("/form")).post(FormBody.Builder().add("password", secrets[11]).build()).build())
                .execute().use { it.body.string() }
        }
        val transactions = withTimeout(10_000) {
            var page: TransactionPage
            do {
                delay(50)
                page = handle.loadTransactions(TransactionQuery(), null, 50)
            } while (page.items.size < 4 || page.items.any { it.state == TransactionState.IN_FLIGHT })
            page.items
        }

        val stored = File(context.noBackupFilesDir, "pulse").walkTopDown().filter { it.isFile }.toList()
        assertWithMessage("저장 파일").that(stored).isNotEmpty()
        stored.forEach { file -> assertNoSecrets("저장 파일 ${file.name}", file.readBytes().toString(Charsets.ISO_8859_1)) }
        ExportFormat.entries.forEach { format ->
            val out = ByteArrayOutputStream()
            PulseExporter(handle.config.redaction).export(handle, TransactionQuery(), format, out)
            assertNoSecrets("내보내기 $format", out.toString(Charsets.UTF_8))
        }
        transactions.forEach { summary ->
            val detail = requireNotNull(handle.loadTransaction(summary.id))
            val body = handle.loadBody(summary.id, MessageKind.REQUEST)
            assertNoSecrets("cURL ${summary.path}", CurlFormatter(handle.config.redaction).format(detail, body))
        }
    }

    private fun assertNoSecrets(where: String, content: String) {
        secrets.forEach { secret -> assertWithMessage("$where 에 비밀이 남음").that(content).doesNotContain(secret) }
    }
}
