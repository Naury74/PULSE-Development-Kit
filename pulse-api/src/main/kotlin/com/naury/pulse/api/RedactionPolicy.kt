package com.naury.pulse.api

/**
 * 저장 전에 값을 가릴 이름 목록. 이름 비교는 대소문자를 구분하지 않는다.
 *
 * - [headers]: 이름이 정확히 같은 헤더 값을 가린다.
 * - [queryParameters]: 이름이 정확히 같은 URL 쿼리 파라미터 값을 가린다. 이름은 URL 디코딩한 뒤 비교한다.
 * - [sensitiveNames]: 헤더·쿼리 파라미터·JSON 키 이름을 단어로 나눈 뒤(`_`, `-`, `.`, 공백, camelCase 경계)
 *   이 목록의 단어 묶음을 연속으로 포함하면 값을 가린다. 예: `token`은 `refresh_token`, `X-Auth-Token`, `accessToken`에 해당한다.
 *
 * 가린 값은 원본을 남기지 않고 [REDACTED]로 바꾼다. 원본 값을 다시 볼 수 있는 기능은 없다.
 */
public class RedactionPolicy private constructor(
    public val headers: Set<String>,
    public val queryParameters: Set<String>,
    public val sensitiveNames: Set<String>,
) {
    /** [names]를 정확히 일치로 가릴 헤더에 더한 새 정책을 돌려준다. */
    public fun withHeaders(vararg names: String): RedactionPolicy =
        RedactionPolicy(headers + names.map(::normalize), queryParameters, sensitiveNames)

    /** [names]를 정확히 일치로 가릴 쿼리 파라미터에 더한 새 정책을 돌려준다. */
    public fun withQueryParameters(vararg names: String): RedactionPolicy =
        RedactionPolicy(headers, queryParameters + names.map(::normalize), sensitiveNames)

    /** [names]를 헤더·쿼리·JSON 키에서 단어로 찾아 가릴 이름에 더한 새 정책을 돌려준다. */
    public fun withSensitiveNames(vararg names: String): RedactionPolicy =
        RedactionPolicy(headers, queryParameters, sensitiveNames + names.map(::normalize))

    override fun equals(other: Any?): Boolean = other is RedactionPolicy &&
        headers == other.headers &&
        queryParameters == other.queryParameters &&
        sensitiveNames == other.sensitiveNames

    override fun hashCode(): Int = listOf(headers, queryParameters, sensitiveNames).hashCode()

    override fun toString(): String = "RedactionPolicy(headers=$headers, queryParameters=$queryParameters, sensitiveNames=$sensitiveNames)"

    public companion object {
        /** 가린 값 자리에 저장하는 문자열. */
        public const val REDACTED: String = "[REDACTED]"

        /**
         * 기본 정책. 인증·쿠키 헤더, OAuth 인가 코드 쿼리(`code`), 흔한 비밀 이름(token, password, secret, api key, session,
         * auth 등)을 가린다. 넓게 가리는 쪽을 택해 `session_count`처럼 비밀이 아닌 값도 가려질 수 있다.
         */
        @JvmStatic
        public fun strict(): RedactionPolicy = RedactionPolicy(
            headers = setOf("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key"),
            queryParameters = setOf("code", "sig"),
            sensitiveNames = setOf(
                "token", "password", "passwd", "pwd", "secret", "api_key", "apikey", "session", "auth",
                "authorization", "cookie", "credential", "credentials", "private_key", "signature",
                "otp", "pin", "ssn",
            ).mapTo(LinkedHashSet(), ::normalize),
        )

        private fun normalize(name: String): String {
            require(name.isNotBlank()) { "가릴 이름은 비어 있을 수 없습니다." }
            return name.trim().lowercase()
        }
    }
}
