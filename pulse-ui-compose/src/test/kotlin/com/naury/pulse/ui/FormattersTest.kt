package com.naury.pulse.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormattersTest {
    @Test
    fun `시간은 단위를 골라 보여 준다`() {
        assertThat(Formatters.duration(500_000)).isEqualTo("0.50 ms")
        assertThat(Formatters.duration(123_400_000)).isEqualTo("123 ms")
        assertThat(Formatters.duration(1_234_000_000)).isEqualTo("1.23 s")
    }

    @Test
    fun `크기는 1024 단위로 보여 준다`() {
        assertThat(Formatters.bytes(512)).isEqualTo("512 B")
        assertThat(Formatters.bytes(1_536)).isEqualTo("1.5 KB")
        assertThat(Formatters.bytes(50L * 1024 * 1024)).isEqualTo("50 MB")
    }
}
