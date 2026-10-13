package com.naury.pulse.ui

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.TimingPhaseType
import org.junit.Test

class JsonHighlighterTest {
    private val colors = AccentColors(
        get = Color.Black, post = Color.Black, put = Color.Black, patch = Color.Black, delete = Color.Black, otherMethod = Color.Black,
        phases = TimingPhaseType.entries.associateWith { Color.Black }, waiting = Color.Black,
        jsonKey = Color.Red, jsonString = Color.Green, jsonNumber = Color.Blue, jsonLiteral = Color.Yellow, codeBackground = Color.White,
    )

    private fun colorAt(text: String, index: Int): Color? =
        JsonHighlighter.highlight(text, colors).spanStyles.lastOrNull { index >= it.start && index < it.end }?.item?.color

    @Test
    fun `키·문자열·숫자·리터럴을 구분해 칠한다`() {
        val json = """{ "name": "a\"b", "n": -1.5e3, "ok": true }"""

        assertThat(colorAt(json, json.indexOf("\"name\""))).isEqualTo(Color.Red)
        assertThat(colorAt(json, json.indexOf("\"a\\"))).isEqualTo(Color.Green)
        assertThat(colorAt(json, json.indexOf("-1.5e3") + 4)).isEqualTo(Color.Blue)
        assertThat(colorAt(json, json.indexOf("true"))).isEqualTo(Color.Yellow)
        assertThat(JsonHighlighter.highlight(json, colors).text).isEqualTo(json)
    }

    @Test
    fun `닫히지 않은 문자열도 실패하지 않는다`() {
        val json = """{"cut": "abc"""

        assertThat(colorAt(json, json.length - 1)).isEqualTo(Color.Green)
    }
}
