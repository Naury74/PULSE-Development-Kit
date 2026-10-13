package com.naury.pulse.ui

/**
 * 해석하지 않고 문자 단위로 들여쓰기만 한다. 저장된 본문은 이미 검증·마스킹된 JSON이므로 구조를 다시 만들 필요가 없고,
 * 입력 길이에 비례하는 시간만 쓴다. 문자열 안의 괄호·쉼표와 이스케이프는 건드리지 않는다.
 */
internal object JsonPrettyPrinter {
    fun format(json: String): String = Printer(json).print()

    private class Printer(private val json: String) {
        private val out = StringBuilder(json.length + json.length / 2)
        private var depth = 0
        private var inString = false
        private var escaped = false

        fun print(): String {
            var index = 0
            while (index < json.length) {
                index = if (inString) appendStringChar(index) else appendStructural(index)
            }
            return out.toString()
        }

        private fun appendStringChar(index: Int): Int {
            val char = json[index]
            out.append(char)
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            return index + 1
        }

        private fun appendStructural(index: Int): Int {
            when (val char = json[index]) {
                '"' -> {
                    out.append(char)
                    inString = true
                }

                '{', '[' -> return open(index, close = if (char == '{') '}' else ']')

                '}', ']' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    newLine().append(char)
                }

                ',' -> {
                    out.append(char)
                    newLine()
                }

                ':' -> out.append(": ")

                else -> if (!char.isWhitespace()) out.append(char)
            }
            return index + 1
        }

        /** 빈 컨테이너는 `{}`·`[]` 한 줄로 쓰고, 아니면 한 단계 들여쓴다. */
        private fun open(index: Int, close: Char): Int {
            out.append(json[index])
            var next = index + 1
            while (next < json.length && json[next].isWhitespace()) next++
            if (next < json.length && json[next] == close) {
                out.append(close)
                return next + 1
            }
            depth++
            newLine()
            return index + 1
        }

        private fun newLine(): StringBuilder = out.append('\n').apply { repeat(depth) { append(INDENT) } }
    }

    private const val INDENT = "  "
}
