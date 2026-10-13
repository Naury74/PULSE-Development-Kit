package com.naury.pulse.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * 들여쓴 JSON 문자열에 키·문자열·숫자·리터럴 색을 입힌다. 해석하지 않고 한 번 훑기만 하므로 입력 길이에 비례하는 시간만 든다.
 * 잘린 JSON처럼 문법이 맞지 않아도 실패하지 않고 아는 만큼만 칠한다.
 */
internal object JsonHighlighter {
    fun highlight(json: String, colors: AccentColors): AnnotatedString = buildAnnotatedString {
        append(json)
        var index = 0
        while (index < json.length) {
            val char = json[index]
            index = when {
                char == '"' -> {
                    val end = stringEnd(json, index)
                    val color = if (isKey(json, end)) colors.jsonKey else colors.jsonString
                    addStyle(SpanStyle(color = color), index, end)
                    end
                }

                char == '-' || char.isDigit() -> {
                    val end = scan(json, index) { it.isDigit() || it in NUMBER_CHARS }
                    addStyle(SpanStyle(color = colors.jsonNumber), index, end)
                    end
                }

                char.isLetter() -> {
                    val end = scan(json, index) { it.isLetter() }
                    addStyle(SpanStyle(color = colors.jsonLiteral), index, end)
                    end
                }

                else -> index + 1
            }
        }
    }

    /** 여는 따옴표 위치에서 닫는 따옴표 다음 위치를 돌려준다. 닫히지 않았으면 끝. */
    private fun stringEnd(json: String, start: Int): Int {
        var index = start + 1
        while (index < json.length) {
            when (json[index]) {
                '\\' -> index += 2
                '"' -> return index + 1
                else -> index++
            }
        }
        return json.length
    }

    private fun isKey(json: String, afterString: Int): Boolean {
        var index = afterString
        while (index < json.length && json[index].isWhitespace()) index++
        return index < json.length && json[index] == ':'
    }

    private inline fun scan(json: String, start: Int, accept: (Char) -> Boolean): Int {
        var index = start + 1
        while (index < json.length && accept(json[index])) index++
        return index
    }

    private const val NUMBER_CHARS = ".eE+-"
}
