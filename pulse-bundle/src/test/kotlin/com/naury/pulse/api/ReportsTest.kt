package com.naury.pulse.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReportsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val original = Thread.getDefaultUncaughtExceptionHandler()
    private val server = MockWebServer()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        Pulse.current()?.close()
        Thread.setDefaultUncaughtExceptionHandler(original)
        server.close()
    }

    private fun start(reporting: Reporting? = null): PulseHandle = Pulse.initialize(
        context,
        PulseConfig.debugDefaults().newBuilder()
            .monitoring(AppMonitoring(logcat = false, mainThreadBlockMillis = 0, memoryIntervalMillis = 0, exitReasons = false))
            .reporting(reporting)
            .build(),
    )

    private fun draft(id: String = "r1", sessionId: String? = null, evidence: List<EvidenceRef> = emptyList()) = ReportDraft(
        id = id,
        title = "결제 실패",
        description = "결제 버튼을 누르면 실패 token=abc123",
        steps = listOf("장바구니 열기", "결제 누르기"),
        severity = Severity.BLOCKER,
        sessionId = sessionId,
        evidence = evidence,
        hasScreenshot = true,
    )

    private suspend fun PulseHandle.awaitStatus(id: String, status: ReportStatus): ReportState =
        withTimeout(15_000) { reports.states.first { list -> list.any { it.id == id && it.status == status } }.first { it.id == id } }

    @Test
    fun `보고를 마스킹해 저장하고 기록을 지워도 남는다`() = runBlocking<Unit> {
        val handle = start()
        handle.reports.save(draft(), png)
        handle.clear()
        delay(300)

        val loaded = requireNotNull(handle.reports.load("r1"))
        assertThat(loaded.description).isEqualTo("결제 버튼을 누르면 실패 token=$REDACTED")
        assertThat(loaded.steps).containsExactly("장바구니 열기", "결제 누르기").inOrder()
        assertThat(loaded.hasScreenshot).isTrue()
        assertThat(handle.reports.loadScreenshot("r1")).isEqualTo(png)
        assertThat(handle.reports.canSend(ReportKind.MANUAL)).isFalse()
        handle.awaitStatus("r1", ReportStatus.DRAFT)
    }

    @Test
    fun `근거를 추천하고 재현 패키지에 마스킹한 내용만 넣는다`() = runBlocking<Unit> {
        val handle = start()
        val client = OkHttpClient.Builder().addInterceptor(handle.interceptor()).build()
        server.enqueue(MockResponse.Builder().code(503).body("{}").build())
        client.newCall(Request.Builder().url(server.url("/pay?token=secret-1")).build()).execute().close()
        handle.recordError(IllegalStateException("pay failed password=secret-2"))
        handle.action("checkout.submit")

        val evidence = withTimeout(10_000) {
            var found = emptyList<EvidenceRef>()
            while (found.size < 3) {
                delay(50)
                found = handle.reports.recommendEvidence(null, System.currentTimeMillis())
            }
            found
        }
        assertThat(evidence.map { it.kind }.toSet()).containsExactly(TimelineItemKind.EVENT, TimelineItemKind.REQUEST)
        assertThat(evidence.joinToString { it.title }).doesNotContain("secret")

        handle.reports.save(draft(evidence = evidence), png)
        val zip = ByteArrayOutputStream().also { handle.reports.exportPackage("r1", it) }.toByteArray()
        val entries = LinkedHashMap<String, String>()
        ZipInputStream(zip.inputStream()).use { input ->
            generateSequence { input.nextEntry }.forEach { entry -> entries[entry.name] = input.readBytes().toString(Charsets.ISO_8859_1) }
        }
        assertThat(entries.keys).containsAtLeast(
            "issue.json",
            "session/timeline.json",
            "network/requests.har",
            "app/events.json",
            "environment.json",
            "attachments/screenshot-redacted.png",
            "manifest.json",
        )
        assertThat(entries["network/requests.har"]).contains("/pay")
        assertThat(entries["manifest.json"]).contains("pulse.repro.v1")
        entries.filterKeys { it.endsWith(".json") || it.endsWith(".har") }.values.forEach { assertThat(it).doesNotContain("secret") }
    }

    @Test
    fun `목적지로 multipart를 보내고 보낼 때마다 인증 헤더를 받는다`() = runBlocking<Unit> {
        var calls = 0
        val destination = ReportDestination(
            id = "qa",
            endpoint = server.url("/reports").toString(),
            allowedHosts = setOf(server.hostName),
            allowCleartext = true,
            headerProvider = { _, _ -> mapOf("Authorization" to "Bearer t${++calls}") },
        )
        val handle = start(Reporting(manual = destination))
        server.enqueue(MockResponse.Builder().code(201).build())
        handle.reports.save(draft(), png)
        handle.reports.submit("r1")

        val request = requireNotNull(server.takeRequest(15, TimeUnit.SECONDS))
        assertThat(request.headers["X-Pulse-Report-Id"]).isEqualTo("r1")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer t1")
        assertThat(request.headers["Content-Type"]).startsWith("multipart/form-data")
        val body = request.body!!.utf8()
        assertThat(body).contains("pulse.report.v1")
        assertThat(body).contains("pulse-report-r1.zip")
        assertThat(handle.awaitStatus("r1", ReportStatus.SENT).destinationId).isEqualTo("qa")
        delay(300)
        assertThat(handle.loadTransactions(TransactionQuery(text = "/reports"), null, 10).items).isEmpty()
    }

    @Test
    fun `서버 오류는 다시 보낼 목록에 두고 사용자 전송의 재시도 불가 실패는 실패로 둔다`() = runBlocking<Unit> {
        val failing = ReportDestination(
            id = "qa",
            endpoint = server.url("/reports").toString(),
            allowedHosts = setOf(server.hostName),
            allowCleartext = true,
            maxAttempts = 3,
        )
        var received: ReportUpload? = null
        val custom = ReportDestination(id = "custom", transport = { upload, progress ->
            received = upload
            progress(1f)
            TransportResult.Failed("rejected", retryable = false)
        })
        val handle = start(Reporting(manual = failing, crash = custom))
        server.enqueue(MockResponse.Builder().code(503).build())
        handle.reports.save(draft(), null)
        handle.reports.submit("r1")
        val queued = handle.awaitStatus("r1", ReportStatus.QUEUED).let {
            withTimeout(15_000) { handle.reports.states.first { list -> list.any { r -> r.id == "r1" && r.attempts == 1 } } }.first {
                it.id ==
                    "r1"
            }
        }
        assertThat(queued.lastError).isEqualTo("HTTP 503")

        handle.reports.save(ReportDraft(id = "c1", kind = ReportKind.CRASH, title = "crash"), null)
        handle.reports.submit("c1")
        val failed = handle.awaitStatus("c1", ReportStatus.FAILED)
        assertThat(failed.lastError).isEqualTo("rejected")
        assertThat(received?.kind).isEqualTo(ReportKind.CRASH)
        assertThat(received?.attachments?.map { it.name }).containsExactly("pulse-report-c1.zip")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `허용 호스트에 없는 주소는 목적지로 만들 수 없다`() {
        ReportDestination(id = "x", endpoint = "https://evil.example.com/r", allowedHosts = setOf("qa.example.com"))
    }
}
