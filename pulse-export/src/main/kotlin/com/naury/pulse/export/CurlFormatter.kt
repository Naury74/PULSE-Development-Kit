package com.naury.pulse.export

import com.naury.pulse.api.RedactionPolicy
import com.naury.pulse.api.TransactionDetail

/**
 * 저장된 요청을 POSIX 셸용 cURL 명령으로 만든다. 값이 가려진 헤더는 넣지 않고 주석으로 알려 주며, 가려진 자격 증명을 되살리지 않는다.
 * 문자열은 작은따옴표로 감싸고 안의 작은따옴표는 `'\''`로 바꾼다.
 */
public class CurlFormatter(policy: RedactionPolicy) {
    private val sanitizer = ExportSanitizer(policy)

    public fun format(detail: TransactionDetail, requestBody: String?): String {
        val summary = detail.summary
        val headers = sanitizer.headers(detail.request.headers)
        val (omitted, kept) = headers.partition { it.value == RedactionPolicy.REDACTED }
        val body = sanitizer.body(requestBody)
        val lines = mutableListOf<String>()
        if (summary.method !in safeMethods) lines += "# PULSE: 이 요청은 서버 상태를 바꿀 수 있습니다. 실행 전에 대상 서버를 확인하세요."
        if (omitted.isNotEmpty()) lines += "# PULSE: 가려진 헤더는 넣지 않았습니다: ${omitted.joinToString { it.name }}"
        if (requestBody == null && detail.request.bodyState.name != "EMPTY") {
            lines += "# PULSE: 요청 본문은 저장되지 않았습니다(${detail.request.bodyState.name})."
        }
        if (requestBody != null && body == null) lines += "# PULSE: 요청 본문을 다시 마스킹할 수 없어 넣지 않았습니다."
        val head = listOfNotNull(
            "curl",
            if (summary.method != "GET" || body != null) "-X ${quote(summary.method)}" else null,
            quote(sanitizer.url(summary.url)),
        ).joinToString(" ")
        val options = kept.map { "-H ${quote("${it.name}: ${it.value}")}" } + listOfNotNull(body?.let { "--data-raw ${quote(it)}" })
        lines += (listOf(head) + options).joinToString(" \\\n  ")
        return lines.joinToString("\n")
    }

    public companion object {
        private val safeMethods = setOf("GET", "HEAD", "OPTIONS")

        /** POSIX 셸 작은따옴표 인용. */
        @JvmStatic
        public fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
