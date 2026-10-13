package com.naury.pulse.security

import com.naury.pulse.api.BodyFormats
import com.naury.pulse.api.BodyState
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import java.net.URLDecoder
import java.net.URLEncoder
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
 * 원본 본문 바이트를 저장 가능한 텍스트로 바꾼다. JSON과 [formats]로 켠 형식만 받아들이고,
 * 해석하거나 마스킹할 수 없으면 실패로 닫는다(fail closed).
 *
 * JSON 해석은 비용이 크므로 네트워크 스레드가 아닌 저장 스레드에서 불러야 한다.
 */
public class BodySanitizer @JvmOverloads constructor(
    private val redactor: Redactor,
    private val formats: BodyFormats = BodyFormats.JSON_ONLY,
) {
    /** [contentType] 본문을 저장할 수 있는 형식인지. 네트워크 스레드에서 불러도 된다. */
    public fun supports(contentType: String?): Boolean = formatOf(contentType) != null

    public fun sanitize(bytes: ByteArray, contentType: String?): BodySanitizeResult {
        if (bytes.isEmpty()) return BodySanitizeResult.Rejected(BodyState.EMPTY)
        val format = formatOf(contentType) ?: return BodySanitizeResult.Rejected(BodyState.UNSUPPORTED_CONTENT)
        val text = decode(bytes, charsetOf(contentType)) ?: return BodySanitizeResult.Rejected(BodyState.DECODE_FAILED)
        return when (format) {
            Format.JSON -> redactJson(text)
            Format.FORM -> redactForm(text)
            Format.TEXT -> redactText(text)
        }
    }

    private fun formatOf(contentType: String?): Format? = when {
        isJson(contentType) -> Format.JSON
        formats.forms && isForm(contentType) -> Format.FORM
        formats.text && isText(contentType) -> Format.TEXT
        else -> null
    }

    /** `a=1&b=2` 형태. 민감한 이름의 값은 가리고, 나머지 값 안의 URL·`name=value`도 가린다. 인코딩은 원래대로 둔다. */
    internal fun redactForm(text: String): BodySanitizeResult {
        var redacted = 0
        val safe = text.split('&').joinToString("&") { pair ->
            if ('=' !in pair) return@joinToString pair
            val rawName = pair.substringBefore('=')
            val rawValue = pair.substringAfter('=')
            val name = decodeForm(rawName)
            val value = decodeForm(rawValue)
            when {
                name == null || value == null -> {
                    redacted++
                    "$rawName=$REDACTED"
                }

                redactor.matcher.isSensitiveKey(name) || redactor.matcher.isSensitiveQueryParameter(name) -> {
                    redacted++
                    "$rawName=$REDACTED"
                }

                else -> {
                    val sanitized = redactor.sanitizeText(value)
                    if (sanitized == value) {
                        pair
                    } else {
                        redacted++
                        "$rawName=" + URLEncoder.encode(sanitized, Charsets.UTF_8.name())
                    }
                }
            }
        }
        return BodySanitizeResult.Captured(safe, redacted)
    }

    /** 구조를 모르는 텍스트. URL 쿼리와 `name=value` 형태의 비밀만 가린다. */
    internal fun redactText(text: String): BodySanitizeResult {
        val safe = redactor.sanitizeText(text)
        return BodySanitizeResult.Captured(safe, if (safe == text) 0 else 1)
    }

    private fun decodeForm(value: String): String? = try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        null
    }

    private enum class Format { JSON, FORM, TEXT }

    internal fun redactJson(text: String): BodySanitizeResult {
        val element = parseOrNull(text) ?: return BodySanitizeResult.Rejected(BodyState.REDACTION_FAILED)
        val pass = RedactionPass()
        val safe = pass.redact(element, embeddingDepth = 0)
        return BodySanitizeResult.Captured(encode(safe), pass.count)
    }

    /** 한 본문을 마스킹하는 동안 가린 값의 수를 센다. */
    private inner class RedactionPass {
        var count = 0
            private set

        fun redact(node: JsonElement, embeddingDepth: Int): JsonElement = when (node) {
            is JsonObject -> JsonObject(
                node.mapValues { (key, value) ->
                    if (redactor.matcher.isSensitiveKey(key)) {
                        count++
                        JsonPrimitive(REDACTED)
                    } else {
                        redact(value, embeddingDepth)
                    }
                },
            )

            is JsonArray -> JsonArray(node.map { redact(it, embeddingDepth) })

            is JsonPrimitive -> if (node.isString) redactString(node, embeddingDepth) else node
        }

        // 서버가 받은 요청을 되돌려 주거나 로그를 담으면 비밀이 문자열 안에 JSON·URL·name=value로 들어 있다.
        private fun redactString(node: JsonPrimitive, embeddingDepth: Int): JsonPrimitive {
            val original = node.content
            val embedded = embeddedJson(original, embeddingDepth)
            if (embedded != null) {
                val before = count
                val safe = redact(embedded, embeddingDepth + 1)
                // 가린 것이 없으면 원래 공백·순서를 그대로 둔다.
                return if (count == before) node else JsonPrimitive(encode(safe))
            }
            val safe = redactor.sanitizeText(original)
            if (safe == original) return node
            count++
            return JsonPrimitive(safe)
        }

        private fun embeddedJson(text: String, embeddingDepth: Int): JsonElement? {
            if (embeddingDepth >= MAX_EMBEDDED_DEPTH) return null
            val trimmed = text.trimStart()
            if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return null
            return parseOrNull(trimmed)
        }
    }

    private fun encode(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

    private fun parseOrNull(text: String): JsonElement? {
        if (nestingDepth(text) > MAX_JSON_DEPTH) return null
        return try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    public companion object {
        /** 이보다 깊게 중첩된 JSON은 해석하지 않는다(스택 소모 방지). */
        public const val MAX_JSON_DEPTH: Int = 64

        /** 문자열 안에 든 JSON을 이 단계까지만 풀어 본다. */
        private const val MAX_EMBEDDED_DEPTH = 2

        private val json = Json { prettyPrint = false }
        private val BYTE_ORDER_MARK = Char(0xFEFF).toString()

        /** `application/json`, `text/json`과 `+json`으로 끝나는 형식(`application/problem+json` 등)만 JSON으로 본다. */
        @JvmStatic
        public fun isForm(contentType: String?): Boolean = mediaType(contentType) == "application/x-www-form-urlencoded"

        /** `text/`로 시작하는 형식과 XML(`application/xml`, `+xml`). */
        @JvmStatic
        public fun isText(contentType: String?): Boolean {
            val mediaType = mediaType(contentType) ?: return false
            return mediaType.startsWith("text/") || mediaType == "application/xml" || mediaType.endsWith("+xml")
        }

        private fun mediaType(contentType: String?): String? = contentType?.substringBefore(';')?.trim()?.lowercase()

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
