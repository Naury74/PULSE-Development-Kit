package com.naury.pulse.security

import com.naury.pulse.api.BodyState
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 본문 마스킹 결과. 마스킹에 성공한 경우에만 저장할 텍스트가 있다. */
public sealed interface BodySanitizeResult {
    public class Captured(public val text: String, public val redactedValueCount: Int) : BodySanitizeResult

    public class Rejected(public val state: BodyState) : BodySanitizeResult
}

/**
 * 원본 본문 바이트를 저장 가능한 텍스트로 바꾼다. JSON만 받아들이고, 해석하거나 마스킹할 수 없으면 실패로 닫는다(fail closed).
 *
 * JSON 해석은 비용이 크므로 네트워크 스레드가 아닌 저장 스레드에서 불러야 한다.
 */
public class BodySanitizer(private val redactor: Redactor) {
    public fun sanitize(bytes: ByteArray, contentType: String?): BodySanitizeResult {
        if (bytes.isEmpty()) return BodySanitizeResult.Rejected(BodyState.EMPTY)
        if (!isJson(contentType)) return BodySanitizeResult.Rejected(BodyState.UNSUPPORTED_CONTENT)
        val text = decode(bytes, charsetOf(contentType)) ?: return BodySanitizeResult.Rejected(BodyState.DECODE_FAILED)
        return redactJson(text)
    }

    internal fun redactJson(text: String): BodySanitizeResult {
        if (nestingDepth(text) > MAX_JSON_DEPTH) return BodySanitizeResult.Rejected(BodyState.REDACTION_FAILED)
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return BodySanitizeResult.Rejected(BodyState.REDACTION_FAILED)
        } catch (_: IllegalArgumentException) {
            return BodySanitizeResult.Rejected(BodyState.REDACTION_FAILED)
        }
        var redacted = 0
        fun redact(node: JsonElement): JsonElement = when (node) {
            is JsonObject -> JsonObject(
                node.mapValues { (key, value) ->
                    if (redactor.matcher.isSensitiveKey(key)) {
                        redacted++
                        JsonPrimitive(REDACTED)
                    } else {
                        redact(value)
                    }
                },
            )

            is JsonArray -> JsonArray(node.map(::redact))

            else -> node
        }
        val safe = redact(element)
        return BodySanitizeResult.Captured(json.encodeToString(JsonElement.serializer(), safe), redacted)
    }

    public companion object {
        /** 이보다 깊게 중첩된 JSON은 해석하지 않는다(스택 소모 방지). */
        public const val MAX_JSON_DEPTH: Int = 64

        private val json = Json { prettyPrint = false }
        private val BYTE_ORDER_MARK = Char(0xFEFF).toString()

        /** `application/json`, `text/json`과 `+json`으로 끝나는 형식(`application/problem+json` 등)만 JSON으로 본다. */
        @JvmStatic
        public fun isJson(contentType: String?): Boolean {
            val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
            val subtype = mediaType.substringAfter('/', missingDelimiterValue = "")
            return subtype == "json" || subtype.endsWith("+json")
        }

        private fun charsetOf(contentType: String?): Charset {
            val name = contentType?.split(';')
                ?.drop(1)
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim('"', ' ')
                ?: return Charsets.UTF_8
            return try {
                Charset.forName(name)
            } catch (_: IllegalCharsetNameException) {
                Charsets.UTF_8
            } catch (_: UnsupportedCharsetException) {
                Charsets.UTF_8
            }
        }

        private fun decode(bytes: ByteArray, charset: Charset): String? {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                return null
            }
            return text.removePrefix(BYTE_ORDER_MARK)
        }

        private fun nestingDepth(text: String): Int {
            var depth = 0
            var maxDepth = 0
            var inString = false
            var escaped = false
            for (char in text) {
                when {
                    escaped -> escaped = false

                    inString && char == '\\' -> escaped = true

                    char == '"' -> inString = !inString

                    inString -> Unit

                    char == '{' || char == '[' -> {
                        depth++
                        if (depth > maxDepth) maxDepth = depth
                        if (maxDepth > MAX_JSON_DEPTH) return maxDepth
                    }

                    char == '}' || char == ']' -> depth--
                }
            }
            return maxDepth
        }
    }
}
