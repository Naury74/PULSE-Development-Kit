package com.naury.pulse.security

import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.RedactionPolicy
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import java.net.URLDecoder

/** 헤더·URL·실패 메시지를 저장 전에 마스킹한다. 상태가 없어 여러 스레드에서 함께 써도 된다. */
public class Redactor(policy: RedactionPolicy) {
    internal val matcher = SensitiveNameMatcher(policy)

    public fun redactHeaders(headers: List<Pair<String, String>>): List<HeaderField> = headers.map { (name, value) ->
        val lowerName = name.lowercase()
        val safeValue = when {
            matcher.isSensitiveHeader(name) -> REDACTED
            lowerName in urlValuedHeaders -> sanitizeUrl(value)
            else -> value
        }
        HeaderField(name, safeValue)
    }

    /**
     * URL의 사용자 정보와 fragment를 지우고, 민감한 쿼리 파라미터 값을 가린다. 파라미터 이름을 디코딩할 수 없으면 값을 가린다.
     * 경로 안의 비밀은 알아낼 수 없어 그대로 둔다.
     */
    public fun sanitizeUrl(url: String): String {
        val withoutFragment = url.substringBefore('#')
        val base = withoutFragment.substringBefore('?')
        val query = if ('?' in withoutFragment) withoutFragment.substringAfter('?') else null
        val safeBase = stripUserInfo(base)
        if (query == null) return safeBase
        val safeQuery = query.split('&').joinToString("&") { parameter ->
            val rawName = parameter.substringBefore('=')
            val hasValue = '=' in parameter
            val decodedName = decodeOrNull(rawName)
            if (hasValue && (decodedName == null || matcher.isSensitiveQueryParameter(decodedName))) "$rawName=$REDACTED" else parameter
        }
        return "$safeBase?$safeQuery"
    }

    /** 실패 메시지 안의 URL과 `name=value` 형태의 민감 값을 가리고 [MAX_MESSAGE_LENGTH]자로 자른다. */
    public fun sanitizeMessage(message: String?): String? {
        if (message.isNullOrEmpty()) return message
        val sanitized = sanitizeText(message)
        return if (sanitized.length > MAX_MESSAGE_LENGTH) sanitized.take(MAX_MESSAGE_LENGTH) + "…" else sanitized
    }

    /** 자유 텍스트(로그 등) 안의 URL과 `name=value` 형태의 민감 값을 가린다. 길이는 자르지 않는다. */
    public fun sanitizeTextPublic(text: String): String = sanitizeText(text)

    /** 자유 텍스트 안의 URL과 `name=value` 형태의 민감 값을 가린다. 길이는 자르지 않는다. */
    internal fun sanitizeText(text: String): String {
        val urlsSanitized = urlPattern.replace(text) { sanitizeUrl(it.value) }
        return assignmentPattern.replace(urlsSanitized) { match ->
            val name = match.groupValues[1]
            if (matcher.isSensitiveKey(name) || matcher.isSensitiveQueryParameter(name)) "$name=$REDACTED" else match.value
        }
    }

    private fun stripUserInfo(base: String): String {
        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) return base
        val authorityStart = schemeEnd + SCHEME_SEPARATOR_LENGTH
        val authorityEnd = base.indexOf('/', authorityStart).let { if (it < 0) base.length else it }
        val authority = base.substring(authorityStart, authorityEnd)
        if ('@' !in authority) return base
        return base.substring(0, authorityStart) + authority.substringAfterLast('@') + base.substring(authorityEnd)
    }

    private fun decodeOrNull(value: String): String? = try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        null
    }

    public companion object {
        public const val MAX_MESSAGE_LENGTH: Int = 300
        private const val SCHEME_SEPARATOR_LENGTH = 3
        private val urlValuedHeaders = setOf("location", "content-location", "referer")
        private val urlPattern = Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^\s"'<>]+""")
        private val assignmentPattern = Regex("""([A-Za-z0-9_.-]+)=([^\s&,;]+)""")
    }
}
