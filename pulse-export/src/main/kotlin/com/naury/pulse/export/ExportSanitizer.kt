package com.naury.pulse.export

import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.RedactionPolicy
import com.naury.pulse.security.BodySanitizeResult
import com.naury.pulse.security.BodySanitizer
import com.naury.pulse.security.Redactor

/**
 * 내보내기 직전에 현재 정책으로 다시 마스킹한다. 저장된 값은 이미 마스킹돼 있지만, 정책이 넓어졌거나 파일이 기기 밖으로
 * 나가므로 한 번 더 적용한다. 본문을 다시 마스킹할 수 없으면 본문을 빼고 쓴다.
 */
internal class ExportSanitizer(policy: RedactionPolicy) {
    private val redactor = Redactor(policy)
    private val bodies = BodySanitizer(redactor)

    fun url(url: String): String = redactor.sanitizeUrl(url)

    fun headers(headers: List<HeaderField>): List<HeaderField> = redactor.redactHeaders(headers.map { it.name to it.value })

    fun message(message: String?): String? = redactor.sanitizeMessage(message)

    /** 다시 마스킹한 본문. 마스킹할 수 없으면 null. */
    fun body(text: String?): String? {
        if (text == null) return null
        return (bodies.sanitize(text.toByteArray(Charsets.UTF_8), JSON) as? BodySanitizeResult.Captured)?.text
    }

    private companion object {
        const val JSON = "application/json"
    }
}
