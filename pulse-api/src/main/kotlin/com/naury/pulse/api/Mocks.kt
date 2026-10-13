package com.naury.pulse.api

import java.util.UUID
import java.util.regex.PatternSyntaxException
import kotlinx.coroutines.flow.StateFlow

/** 경로를 맞추는 방식. 같은 우선순위면 [EXACT] → [PREFIX] → [REGEX] 순으로 먼저 고른다. */
public enum class PathMatch { EXACT, PREFIX, REGEX }

/**
 * 모의 응답 규칙. 만들 때 값을 검사하고 범위를 벗어나면 [IllegalArgumentException]을 던진다.
 *
 * @property method 맞출 메서드. null이면 모든 메서드.
 * @property host 맞출 호스트(대소문자 무시). null이면 모든 호스트.
 * @property path 경로(쿼리 제외). 길이 1..[MAX_PATH_LENGTH]. [PathMatch.REGEX]면 정규식이며 길이 [MAX_REGEX_LENGTH] 이하.
 * @property priority 클수록 먼저 맞춘다.
 * @property remainingUses 남은 사용 횟수. null이면 제한 없음, 1 이상.
 * @property expiresAtEpochMillis 이 시각 이후 쓰지 않는다. null이면 만료 없음.
 */
public class MockRule @JvmOverloads constructor(
    public val path: String,
    public val response: MockResponseSpec,
    public val pathMatch: PathMatch = PathMatch.EXACT,
    method: String? = null,
    host: String? = null,
    public val priority: Int = 0,
    public val remainingUses: Int? = null,
    public val expiresAtEpochMillis: Long? = null,
    public val enabled: Boolean = true,
    public val id: String = UUID.randomUUID().toString(),
    public val createdAtEpochMillis: Long = System.currentTimeMillis(),
) {
    public val method: String? = method?.trim()?.uppercase()?.ifEmpty { null }
    public val host: String? = host?.trim()?.lowercase()?.ifEmpty { null }

    init {
        require(path.isNotEmpty() && path.length <= MAX_PATH_LENGTH) { "path 길이는 1..$MAX_PATH_LENGTH 이어야 합니다." }
        require(remainingUses == null || remainingUses >= 1) { "remainingUses는 1 이상이어야 합니다." }
        if (pathMatch == PathMatch.REGEX) {
            require(path.length <= MAX_REGEX_LENGTH) { "정규식은 $MAX_REGEX_LENGTH 자 이하여야 합니다." }
            try {
                Regex(path)
            } catch (error: PatternSyntaxException) {
                throw IllegalArgumentException("정규식을 해석할 수 없습니다: ${error.description}", error)
            }
        }
    }

    /** 바꾼 값으로 새 규칙을 만든다. ID와 생성 시각은 그대로다. */
    @JvmOverloads
    public fun copy(enabled: Boolean = this.enabled, remainingUses: Int? = this.remainingUses): MockRule =
        MockRule(path, response, pathMatch, method, host, priority, remainingUses, expiresAtEpochMillis, enabled, id, createdAtEpochMillis)

    public companion object {
        public const val MAX_PATH_LENGTH: Int = 2_048
        public const val MAX_REGEX_LENGTH: Int = 256
    }
}

/**
 * 모의 응답 내용.
 *
 * @property statusCode 100..599.
 * @property delayMillis 응답 전 지연(ms), 0..[MAX_DELAY_MILLIS]. 지연 중 취소하면 바로 끝난다.
 * @property body UTF-8 바이트 기준 [MAX_BODY_BYTES] 이하.
 */
public class MockResponseSpec @JvmOverloads constructor(
    public val statusCode: Int = HTTP_OK,
    public val body: String = "",
    public val contentType: String = "application/json; charset=utf-8",
    public val headers: List<HeaderField> = emptyList(),
    public val delayMillis: Long = 0,
) {
    init {
        require(statusCode in MIN_STATUS..MAX_STATUS) { "statusCode는 $MIN_STATUS..$MAX_STATUS 범위여야 합니다." }
        require(delayMillis in 0..MAX_DELAY_MILLIS) { "delayMillis는 0..$MAX_DELAY_MILLIS 범위여야 합니다." }
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) { "body는 $MAX_BODY_BYTES 바이트 이하여야 합니다." }
    }

    public companion object {
        public const val MAX_DELAY_MILLIS: Long = 60_000
        public const val MAX_BODY_BYTES: Int = 1_024 * 1_024
        private const val HTTP_OK = 200
        private const val MIN_STATUS = 100
        private const val MAX_STATUS = 599
    }
}

/**
 * 모의 응답 규칙 목록과 전체 스위치. 규칙은 메모리에만 있어 앱을 다시 시작하면 사라지고, 전체 스위치는 기본으로 꺼져 있다.
 * 규칙은 `PulseHandle.mockInterceptor()`를 OkHttpClient에 직접 붙인 경우에만 적용된다.
 */
public interface PulseMocks {
    public val enabled: StateFlow<Boolean>

    /** 우선순위가 높은 순. */
    public val rules: StateFlow<List<MockRule>>

    public fun setEnabled(enabled: Boolean)

    /** 같은 ID가 있으면 바꾸고, 없으면 더한다. */
    public fun upsert(rule: MockRule)

    public fun remove(ruleId: String)

    /** 전체 스위치를 끈다. 규칙은 남겨 둔다. */
    public fun disableAll()
}
