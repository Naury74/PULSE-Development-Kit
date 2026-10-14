package com.naury.pulse.ui

import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.ByteTotals
import com.naury.pulse.api.DurationPercentiles
import com.naury.pulse.api.EndpointStatistics
import com.naury.pulse.api.GroupStatistics
import com.naury.pulse.api.HostStatistics
import com.naury.pulse.api.Insight
import com.naury.pulse.api.InsightCertainty
import com.naury.pulse.api.InsightRule
import com.naury.pulse.api.InsightSeverity
import com.naury.pulse.api.InsightUnit
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.RecordingState
import com.naury.pulse.api.ReplayOutcome
import com.naury.pulse.api.ReplayPolicy
import com.naury.pulse.api.StatsDimension
import com.naury.pulse.api.StatsFacets
import com.naury.pulse.api.StatsWindow
import com.naury.pulse.api.StatusCounts
import com.naury.pulse.api.TrafficBucket
import com.naury.pulse.api.TrafficStatistics
import com.naury.pulse.api.TransactionState
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PulseInspectorScreenTest {
    // v2 규칙은 메인 디스패처를 테스트 디스패처로 바꿔 ViewModel의 검색 지연·Paging이 Robolectric Looper로 진행되지 않는다.
    @Suppress("DEPRECATION")
    @get:Rule
    val compose = createComposeRule()

    private fun show(inspector: FakeInspector) {
        compose.setContent { PulseInspectorScreen(inspector, onClose = {}) }
        settle()
    }

    /**
     * 검색 지연·페이지 읽기는 메인 Looper와 Compose 테스트 디스패처에 나뉘어 예약된다.
     * 서로가 다음 작업을 예약하므로 몇 번 번갈아 진행시킨다.
     */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            compose.mainClock.advanceTimeBy(SETTLE_STEP_MILLIS)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_STEP_MILLIS))
            compose.waitForIdle()
        }
    }

    @Test
    fun `개요는 숫자 타일과 차트 요약을 보여 주고 오류 타일을 누르면 거른 트래픽으로 간다`() {
        val inspector = FakeInspector(listOf(FakeInspector.detail("a", "/users", status = 503)))
        inspector.statistics = TrafficStatistics(
            window = StatsWindow.LAST_HOUR,
            fromEpochMillis = 0,
            toEpochMillis = 12_000,
            counts = StatusCounts(total = 4, success = 2, clientError = 1, serverError = 1, failed = 0, inFlight = 0),
            durations = DurationPercentiles(4, 100_000_000, 900_000_000, 900_000_000),
            bytes = ByteTotals(0, 2_048, 0),
            buckets = (0 until 12).map { TrafficBucket(it * 1_000L, (it + 1) * 1_000L, if (it == 3) 4 else 0, if (it == 3) 2 else 0) },
            topHosts = listOf(HostStatistics("api.test", 4, 2)),
            slowestEndpoints = listOf(EndpointStatistics("GET", "api.test", "/users", 4, 900_000_000, 900_000_000)),
            sampleLimitReached = false,
        )
        show(inspector)

        compose.onNodeWithText("Overview").performClick()
        settle()
        awaitText("p95 duration")

        compose.onAllNodesWithText("50.0%").onFirst().assertIsDisplayed()
        compose.onNodeWithText("900 ms").assertIsDisplayed()
        compose.onNodeWithContentDescription("Requests in 12 intervals", substring = true).assertExists()
        compose.onNodeWithText("Error rate").performClick()
        settle()
        compose.onNodeWithText("/users").assertIsDisplayed()
    }

    @Test
    fun `대시보드는 엔드포인트와 기준별 분류를 보여 주고 분류 값을 누르면 그 값으로 거른다`() {
        val inspector = FakeInspector(listOf(FakeInspector.detail("a", "/users")))
        inspector.statistics = TrafficStatistics(
            window = StatsWindow.LAST_HOUR, fromEpochMillis = 0, toEpochMillis = 60_000,
            counts = StatusCounts(total = 3, success = 3, clientError = 0, serverError = 0, failed = 0, inFlight = 0),
            durations = DurationPercentiles(3, 100_000_000, 200_000_000, 200_000_000),
            bytes = ByteTotals(0, 300, 0), buckets = emptyList(), topHosts = emptyList(), slowestEndpoints = emptyList(),
            sampleLimitReached = false,
            endpoints = listOf(EndpointStatistics("GET", "api.test", "/users/{id}", 3, 200_000_000, 200_000_000, 0, 100_000_000, 300)),
            breakdowns = mapOf(StatsDimension.SCREEN to listOf(GroupStatistics("HomeActivity", 3, 0, 100_000_000, 200_000_000, 300))),
            facets = StatsFacets(listOf("api.test"), listOf("HomeActivity"), emptyList()),
        )
        show(inspector)

        compose.onNodeWithText("Overview").performClick()
        settle()
        awaitText("Endpoints (1)")

        compose.onNodeWithText("/users/{id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("HomeActivity").performScrollTo().performClick()
        settle()
        assertThat(inspector.statsQueries.last().screen).isEqualTo("HomeActivity")
        compose.onNodeWithText("Screen: HomeActivity").assertExists()
    }

    @Test
    fun `진단은 근거와 추정 여부를 보여 주고 근거 요청을 누르면 상세로 간다`() {
        val inspector = FakeInspector(listOf(FakeInspector.detail("a", "/users/1")))
        inspector.insights = listOf(
            Insight(
                InsightRule.N_PLUS_ONE_SUSPECTED, 1, InsightSeverity.INFO, InsightCertainty.HEURISTIC, "api.test", "GET /users/{id}",
                InsightUnit.COUNT, 8, 9, 2_000, listOf("a"), 9,
            ),
        )
        show(inspector)

        compose.onNodeWithText("Issues").performClick()
        settle()
        compose.onNodeWithText("Network insights").performClick()
        settle()
        awaitText("/users/1")

        compose.onNodeWithText("Possible N+1 requests").assertIsDisplayed()
        compose.onNodeWithText("Heuristic").assertIsDisplayed()
        compose.onNodeWithText("Threshold 8 · observed 9 · requests: 9 · within 2 s").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("/users/1").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("https://api.test/users/1").assertIsDisplayed()
    }

    @Test
    fun `설정은 기록을 끄고 PulseConfig 값을 보여 준다`() {
        val inspector = FakeInspector()
        show(inspector)

        compose.onNodeWithText("Settings").performClick()
        settle()
        compose.onNodeWithText("Record requests").performClick()

        assertThat(inspector.recordingState.value).isEqualTo(RecordingState.PAUSED)
        compose.onNodeWithText("Metadata and redacted JSON bodies").assertIsDisplayed()
    }

    @Test
    fun `내보내기는 경고와 형식을 먼저 보여 주고 취소할 수 있다`() {
        show(FakeInspector(listOf(FakeInspector.detail("a", "/users"))))

        compose.onNodeWithContentDescription("Export").performClick()
        settle()

        compose.onNodeWithText("can still contain personal data", substring = true).assertIsDisplayed()
        compose.onNodeWithText("HAR 1.2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        settle()
        compose.onNodeWithText("Export requests").assertDoesNotExist()
    }

    @Test
    fun `요청 탭에 cURL 복사가 있다`() {
        show(FakeInspector(listOf(FakeInspector.detail("a", "/users"))))

        compose.onNodeWithText("/users").performClick()
        settle()
        compose.onNodeWithText("Request").performClick()
        settle()

        compose.onNodeWithText("Copy as cURL").assertIsDisplayed()
    }

    @Test
    fun `재실행은 정책이 있을 때만 보이고 상태를 바꾸는 요청은 확인란을 체크해야 보낸다`() {
        val inspector = FakeInspector(listOf(FakeInspector.detail("a", "/users")))
        inspector.config = PulseConfig.debugDefaults().newBuilder().replay(ReplayPolicy(setOf("api.test"))).build()
        inspector.replayOutcome = ReplayOutcome.Completed("replayed", 201, 0)
        show(inspector)

        compose.onNodeWithText("/users").performClick()
        settle()
        compose.onNodeWithText("Request").performClick()
        settle()
        compose.onNodeWithText("Replay").performClick()
        awaitText("Replay request")

        compose.onNodeWithText("Allowed host: api.test").assertIsDisplayed()
        compose.onNodeWithText("Headers removed because their values were redacted: Authorization", substring = true).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("GET")).performTextReplacement("DELETE")
        settle()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("I understand this request may change server data").performScrollTo().performClick()
        compose.onNodeWithText("Send").performScrollTo().performClick()
        awaitText("Response: HTTP 201 · redirects followed: 0")

        assertThat(inspector.replayed.single().first.method).isEqualTo("DELETE")
        assertThat(inspector.replayed.single().second).isTrue()
    }

    @Test
    fun `재실행 결과는 원본과의 차이를 보여 준다`() {
        val original = FakeInspector.detail("orig", "/users", status = 200)
        val replayed = FakeInspector.detail("rep", "/users", status = 500, parentId = "orig")
        show(FakeInspector(listOf(replayed, original)))

        compose.onAllNodesWithText("/users")[0].performClick()
        awaitText("200  →  500")

        compose.onNodeWithText("Compare with original").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("200  →  500").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `모의 응답 규칙을 추가·켜면 트래픽에 경고가 뜨고 잘못된 값은 저장하지 않는다`() {
        val inspector = FakeInspector()
        show(inspector)

        compose.onNodeWithText("Settings").performClick()
        settle()
        compose.onNodeWithText("Add rule").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Status code").performTextReplacement("999")
        compose.onNodeWithText("Save").performClick()
        settle()
        assertThat(inspector.mocks.rules.value).isEmpty()
        compose.onNodeWithText("Status code").performScrollTo().performTextClearance()
        compose.onNodeWithText("Status code").performTextInput("503")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        settle()
        compose.onNodeWithText("Use mock responses").performScrollTo().performClick()
        settle()

        assertThat(inspector.mocks.rules.value.single().response.statusCode).isEqualTo(503)
        assertThat(inspector.mocks.enabled.value).isTrue()
        compose.onNodeWithText("Traffic").performClick()
        settle()
        compose.onNodeWithText("Mock responses are on", substring = true).assertIsDisplayed()
    }

    @Test
    fun `다른 탭에 있는 동안 생긴 요청은 트래픽 탭으로 돌아오면 보인다`() {
        val inspector = FakeInspector(listOf(FakeInspector.detail("a", "/users")))
        show(inspector)

        compose.onNodeWithText("Overview").performClick()
        settle()
        inspector.details = inspector.details + FakeInspector.detail("b", "/orders")
        compose.onNodeWithText("Traffic").performClick()
        settle()

        compose.onNodeWithText("/orders").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w900dp-h700dp")
    fun `넓은 화면에서 요청을 고르기 전에는 안내를 보여 준다`() {
        show(FakeInspector(listOf(FakeInspector.detail("a", "/users"))))

        compose.onNodeWithText("Select a request on the left", substring = true).assertIsDisplayed()
        compose.onNodeWithText("/users").performClick()
        settle()
        compose.onNodeWithText("https://api.test/users").assertIsDisplayed()
    }

    /** 본문 해석처럼 백그라운드 디스패처에서 끝나는 작업은 시간이 정해지지 않아 텍스트가 나타날 때까지 기다린다. */
    private fun awaitText(text: String, substring: Boolean = false) {
        compose.waitUntil(AWAIT_MILLIS) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_STEP_MILLIS))
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val AWAIT_MILLIS = 5_000L
        const val SETTLE_ROUNDS = 4
        const val SETTLE_STEP_MILLIS = 500L
    }

    @Test
    fun `기록이 없으면 연동 안내를 보여 준다`() {
        show(FakeInspector())

        compose.onNodeWithText("No requests yet").assertIsDisplayed()
    }

    @Test
    fun `목록에서 상태를 글자로 보여 주고 누르면 상세로 간다`() {
        val inspector = FakeInspector(
            listOf(
                FakeInspector.detail("a", "/users", status = 404),
                FakeInspector.detail("b", "/orders", status = null, state = TransactionState.FAILED),
            ),
        )
        inspector.bodies["a" to MessageKind.RESPONSE] = """{"error":"[REDACTED]"}"""
        show(inspector)

        compose.onNodeWithText("404").assertIsDisplayed()
        // 필터 칩과 실패한 요청의 상태 표시 두 곳에 나온다.
        compose.onAllNodesWithText("Failed").assertCountEquals(2)
        compose.onNodeWithText("/users").performClick()
        settle()

        compose.onNodeWithText("https://api.test/users").assertIsDisplayed()
        compose.onNodeWithText("Response").performClick()
        settle()
        compose.onNodeWithText("Values redacted before storage: 1").assertIsDisplayed()
        awaitText("\"[REDACTED]\"")
        compose.onNodeWithText("\"[REDACTED]\"").assertIsDisplayed()
        compose.onNodeWithText("Raw").performClick()
        settle()
        compose.onNodeWithText("\"error\": \"[REDACTED]\"", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        settle()
        compose.onNodeWithText("/orders").assertIsDisplayed()
    }

    @Test
    fun `저장하지 않은 본문은 이유를 보여 준다`() {
        show(FakeInspector(listOf(FakeInspector.detail("a", "/big", responseBody = BodyState.TOO_LARGE))))

        compose.onNodeWithText("/big").performClick()
        settle()
        compose.onNodeWithText("Response").performClick()
        settle()

        compose.onNodeWithText("larger than the capture limit", substring = true).assertIsDisplayed()
    }

    @Test
    fun `타임라인은 관찰하지 못한 연결 단계를 알려 준다`() {
        show(FakeInspector(listOf(FakeInspector.detail("a", "/reuse"))))

        compose.onNodeWithText("/reuse").performClick()
        settle()
        compose.onNodeWithText("Timeline").performClick()
        settle()

        compose.onNodeWithText("Not observed: DNS, Connect, TLS", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Response headers").assertIsDisplayed()
        compose.onNodeWithText("Reused an existing connection", substring = true).assertIsDisplayed()
    }

    @Test
    fun `검색어로 목록을 거른다`() {
        show(FakeInspector(listOf(FakeInspector.detail("a", "/users"), FakeInspector.detail("b", "/orders"))))

        compose.onNodeWithText("Search URL").performTextInput("zzz")
        settle()

        compose.onNodeWithText("No requests match the search or filter.").assertIsDisplayed()
    }

    @Test
    fun `기록을 멈추고 확인 후에만 지운다`() {
        val inspector = FakeInspector(listOf(FakeInspector.detail("a", "/users")))
        show(inspector)

        compose.onNodeWithText("Recording").performClick()
        assertThat(inspector.recordingState.value).isEqualTo(RecordingState.PAUSED)

        compose.onNodeWithContentDescription("Clear").performClick()
        assertThat(inspector.cleared).isFalse()
        compose.onNodeWithText("Delete").performClick()
        assertThat(inspector.cleared).isTrue()
    }
}
