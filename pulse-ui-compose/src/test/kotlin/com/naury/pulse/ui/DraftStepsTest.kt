package com.naury.pulse.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DraftStepsTest {
    @Test
    fun `의미 있는 행동이 있으면 탭 기록은 빼고 화면 이동은 줄여 마지막 단계만 남긴다`() {
        val titles = listOf("NAVIGATION - → MainActivity") +
            (1..6).flatMap { listOf("ACTION tap #scenario.S$it", "ACTION sample.run scenario=S$it") } +
            listOf("NAVIGATION MainActivity → CartActivity", "ERROR boom", "MARKER 결제 전")

        assertThat(draftSteps(titles)).containsExactly(
            "sample.run scenario=S2",
            "sample.run scenario=S3",
            "sample.run scenario=S4",
            "sample.run scenario=S5",
            "sample.run scenario=S6",
            "→ CartActivity",
            "결제 전",
        ).inOrder()
    }

    @Test
    fun `앱이 남긴 행동이 없으면 탭 기록을 쓴다`() {
        assertThat(
            draftSteps(listOf("ACTION tap #login", "ACTION back LoginActivity")),
        ).containsExactly("tap #login", "back LoginActivity").inOrder()
    }
}
