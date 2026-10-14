package com.naury.pulse.analytics

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.AppEvent
import com.naury.pulse.api.CaptureSource
import com.naury.pulse.api.EndpointGrouping
import com.naury.pulse.api.EventKind
import com.naury.pulse.api.SessionSummary
import com.naury.pulse.api.TransactionState
import com.naury.pulse.api.TransactionSummary
import org.junit.Test

class SessionDiffCalculatorTest {
    private fun session(id: String) = SessionSummary(id, 0, 1, 1, null, false, false)

    private fun request(session: String, path: String, status: Int, millis: Long, index: Int) = TransactionSummary(
        "$session-$index", session, "GET", "https://api.test$path", "api.test", path, index.toLong(), millis * 1_000_000, status,
        TransactionState.COMPLETED, null, 0, 0, CaptureSource.ORIGINAL,
    )

    private fun event(session: String, kind: EventKind, message: String, tag: String? = null, time: Long = 0) =
        AppEvent("$session-$kind-$message-$time", session, time, kind, null, tag, message)

    @Test
    fun `오류가 늘어난 엔드포인트·새 화면·바뀐 플래그·새 오류를 찾는다`() {
        val base = SessionMaterial(
            session("a"),
            listOf(request("a", "/users/1", 200, 100, 1), request("a", "/users/2", 200, 120, 2), request("a", "/config", 200, 10, 3)),
            listOf(
                event("a", EventKind.NAVIGATION, "- → Home"),
                event("a", EventKind.NAVIGATION, "Home → Cart", time = 1),
                event("a", EventKind.CONFIG, "server = staging", tag = "server"),
                event("a", EventKind.ERROR, "timeout after 300 ms"),
            ),
            truncated = false,
        )
        val target = SessionMaterial(
            session("b"),
            listOf(request("b", "/users/3", 503, 900, 1), request("b", "/users/4", 200, 950, 2), request("b", "/pay", 500, 40, 3)),
            listOf(
                event("b", EventKind.NAVIGATION, "- → Home"),
                event("b", EventKind.NAVIGATION, "Home → Pay", time = 1),
                event("b", EventKind.CONFIG, "server = staging", tag = "server"),
                event("b", EventKind.CONFIG, "server = prod", tag = "server", time = 5),
                event("b", EventKind.ERROR, "timeout after 900 ms"),
                event("b", EventKind.CRASH, "IllegalStateException: pay"),
            ),
            truncated = false,
        )

        val diff = SessionDiffCalculator.compute(base, target, EndpointGrouping())

        val users = diff.endpoints.single { it.endpoint == "GET /users/{id}" }
        assertThat(users.baseCount to users.targetCount).isEqualTo(2 to 2)
        assertThat(users.baseErrors to users.targetErrors).isEqualTo(0 to 1)
        assertThat(users.targetMedianNanos).isEqualTo(950_000_000L)
        assertThat(diff.endpoints.take(2).map { it.endpoint }).containsExactly("GET /pay", "GET /users/{id}").inOrder()
        assertThat(diff.endpoints.map { it.endpoint }).containsAtLeast("GET /config", "GET /pay")
        assertThat(diff.screensOnlyInBase).containsExactly("Cart")
        assertThat(diff.screensOnlyInTarget).containsExactly("Pay")
        assertThat(diff.flags).containsExactly("server", "staging" to "prod")
        assertThat(diff.newIssues).containsExactly("CRASH IllegalStateException: pay")
        assertThat(diff.eventCounts[EventKind.CRASH]).isEqualTo(0 to 1)
    }
}
