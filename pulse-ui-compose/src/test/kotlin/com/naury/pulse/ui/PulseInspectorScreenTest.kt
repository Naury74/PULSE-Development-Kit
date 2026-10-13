package com.naury.pulse.ui

import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.RecordingState
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

    /** 본문 해석처럼 백그라운드 디스패처에서 끝나는 작업은 시간이 정해지지 않아 텍스트가 나타날 때까지 기다린다. */
    private fun awaitText(text: String) {
        compose.waitUntil(AWAIT_MILLIS) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
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
        compose.onNodeWithText("GET /users").performClick()
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
        compose.onNodeWithText("GET /orders").assertIsDisplayed()
    }

    @Test
    fun `저장하지 않은 본문은 이유를 보여 준다`() {
        show(FakeInspector(listOf(FakeInspector.detail("a", "/big", responseBody = BodyState.TOO_LARGE))))

        compose.onNodeWithText("GET /big").performClick()
        settle()
        compose.onNodeWithText("Response").performClick()
        settle()

        compose.onNodeWithText("larger than the capture limit", substring = true).assertIsDisplayed()
    }

    @Test
    fun `타임라인은 관찰하지 못한 연결 단계를 알려 준다`() {
        show(FakeInspector(listOf(FakeInspector.detail("a", "/reuse"))))

        compose.onNodeWithText("GET /reuse").performClick()
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

        compose.onNodeWithText("Pause", substring = true).performClick()
        assertThat(inspector.recordingState.value).isEqualTo(RecordingState.PAUSED)

        compose.onNodeWithContentDescription("Clear").performClick()
        assertThat(inspector.cleared).isFalse()
        compose.onNodeWithText("Delete").performClick()
        assertThat(inspector.cleared).isTrue()
    }
}
