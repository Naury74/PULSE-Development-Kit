package com.naury.pulse.export

import java.io.Writer
import java.util.Locale

/** 의존성 없이 JSON을 바로 [out]에 쓴다. 쉼표와 이스케이프만 책임지며 구조 검증은 하지 않는다. */
internal class JsonStreamWriter(private val out: Writer) {
    private val needsComma = ArrayDeque<Boolean>()
    private var afterName = false

    fun beginObject() = bracket('{', opening = true)

    fun endObject() = bracket('}', opening = false)

    fun beginArray() = bracket('[', opening = true)

    fun endArray() = bracket(']', opening = false)

    fun name(name: String): JsonStreamWriter {
        separate()
        out.writeJsonString(name)
        out.write(":")
        afterName = true
        return this
    }

    fun value(value: String?): JsonStreamWriter {
        separate()
        if (value == null) out.write("null") else out.writeJsonString(value)
        return this
    }

    fun value(value: Long?): JsonStreamWriter {
        separate()
        out.write(value?.toString() ?: "null")
        return this
    }

    fun value(value: Boolean?): JsonStreamWriter {
        separate()
        out.write(value?.toString() ?: "null")
        return this
    }

    private fun bracket(bracket: Char, opening: Boolean): JsonStreamWriter {
        if (opening) {
            separate()
            needsComma.addLast(false)
        } else {
            needsComma.removeLast()
        }
        out.write(bracket.code)
        return this
    }

    private fun separate() {
        if (afterName) {
            afterName = false
            return
        }
        if (needsComma.isEmpty()) return
        if (needsComma.last()) out.write(",") else needsComma[needsComma.lastIndex] = true
    }
}

private fun Writer.writeJsonString(value: String) {
    write("\"")
    value.forEach { char ->
        when {
            char == '"' -> write("\\\"")
            char == '\\' -> write("\\\\")
            char == '\n' -> write("\\n")
            char == '\r' -> write("\\r")
            char == '\t' -> write("\\t")
            char < ' ' -> write(String.format(Locale.ROOT, "\\u%04x", char.code))
            else -> write(char.code)
        }
    }
    write("\"")
}
