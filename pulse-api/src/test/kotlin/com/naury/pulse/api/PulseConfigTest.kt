package com.naury.pulse.api

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PulseConfigTest {
    @Test
    fun `기본 설정은 메타데이터만 저장하고 기록을 시작한다`() {
        val config = PulseConfig.defaults()

        assertThat(config.captureMode).isEqualTo(CaptureMode.METADATA_ONLY)
        assertThat(config.startRecording).isTrue()
        assertThat(config.redaction).isEqualTo(RedactionPolicy.strict())
    }

    @Test
    fun `같은 값으로 만든 설정은 같다`() {
        val a = PulseConfig.Builder().limits(CaptureLimits(1024, 2048)).build()
        val b = PulseConfig.Builder().limits(CaptureLimits(1024, 2048)).build()

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(PulseConfig.debugDefaults())
    }

    @Test
    fun `newBuilder는 기존 값을 이어받는다`() {
        val base = PulseConfig.Builder().queueCapacity(64).startRecording(false).build()

        val changed = base.newBuilder().captureMode(CaptureMode.METADATA_AND_SAFE_BODIES).build()

        assertThat(changed.queueCapacity).isEqualTo(64)
        assertThat(changed.startRecording).isFalse()
        assertThat(changed.captureMode).isEqualTo(CaptureMode.METADATA_AND_SAFE_BODIES)
    }

    @Test
    fun `범위를 벗어난 값은 만들 때 거절한다`() {
        assertThrows(IllegalArgumentException::class.java) { PulseConfig.Builder().queueCapacity(1).build() }
        assertThrows(IllegalArgumentException::class.java) { CaptureLimits(maxRequestBodyBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { CaptureLimits(maxResponseBodyBytes = CaptureLimits.MAX_BODY_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { RetentionPolicy(maxTransactions = 0) }
        assertThrows(IllegalArgumentException::class.java) { RedactionPolicy.strict().withHeaders(" ") }
    }

    @Test
    fun `가릴 이름은 소문자로 정규화해 더한다`() {
        val policy = RedactionPolicy.strict().withHeaders("X-Session-Key").withSensitiveNames(" Card_Number ")

        assertThat(policy.headers).contains("x-session-key")
        assertThat(policy.sensitiveNames).contains("card_number")
        assertThat(RedactionPolicy.strict().headers).doesNotContain("x-session-key")
    }
}
