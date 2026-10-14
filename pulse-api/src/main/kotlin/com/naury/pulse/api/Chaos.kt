package com.naury.pulse.api

import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

/**
 * 네트워크 장애 모사를 허용할 호스트. 설정([PulseConfig.Builder.chaos])에 넣어야만 켤 수 있고, 목록에 없는 호스트에는 어떤 규칙도 적용하지 않는다.
 * 운영 서버를 넣지 않는다. `*.example.com`은 하위 도메인만 허용한다.
 */
public class ChaosPolicy(allowedHosts: Set<String>) {
    public val allowedHosts: Set<String> = allowedHosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    init {
        require(this.allowedHosts.isNotEmpty()) { "allowedHosts가 비어 있습니다." }
    }

    public fun allowsHost(host: String): Boolean {
        val target = host.lowercase()
        return allowedHosts.any { pattern ->
            if (pattern.startsWith("*.")) target.endsWith(pattern.substring(1)) && target.length > pattern.length - 1 else target == pattern
        }
    }

    override fun equals(other: Any?): Boolean = other is ChaosPolicy && allowedHosts == other.allowedHosts

    override fun hashCode(): Int = allowedHosts.hashCode()

    override fun toString(): String = "ChaosPolicy(allowedHosts=$allowedHosts)"
}

/** 모사할 장애. */
public enum class ChaosAction {
    /** [ChaosRule.delayMillis]만큼 늦춘 뒤 진짜로 보낸다. */
    DELAY,

    /** 보내지 않고 연결 실패(IOException)로 끝낸다. */
    FAIL_IO,

    /** [ChaosRule.delayMillis]만큼 기다린 뒤 시간 초과(SocketTimeoutException)로 끝낸다. */
    TIMEOUT,

    /** 보내지 않고 429 응답(Retry-After 포함)을 돌려준다. */
    STATUS_429,

    /** 보내지 않고 500 응답을 돌려준다. */
    STATUS_500,

    /** 보내지 않고 503 응답(Retry-After 포함)을 돌려준다. */
    STATUS_503,
}

/**
 * 장애 모사 규칙. [host]가 [ChaosPolicy]에 있어야 만들 수 있다.
 *
 * @property pathPrefix 경로가 이것으로 시작하는 요청에만. `/`로 시작해야 한다.
 * @property probability 맞는 요청 중 이 비율(0..1)에만 적용한다.
 * @property delayMillis [ChaosAction.DELAY]·[ChaosAction.TIMEOUT]의 지연(ms), 0..[MockResponseSpec.MAX_DELAY_MILLIS].
 */
public class ChaosRule @JvmOverloads constructor(
    host: String,
    public val action: ChaosAction,
    public val pathPrefix: String = "/",
    public val probability: Float = 1f,
    public val delayMillis: Long = DEFAULT_DELAY_MILLIS,
    public val enabled: Boolean = true,
    public val id: String = UUID.randomUUID().toString(),
) {
    public val host: String = host.trim().lowercase()

    init {
        require(this.host.isNotEmpty()) { "host가 비어 있습니다." }
        require(pathPrefix.startsWith("/")) { "pathPrefix는 /로 시작해야 합니다." }
        require(probability in 0f..1f) { "probability는 0..1 범위여야 합니다." }
        require(delayMillis in 0..MockResponseSpec.MAX_DELAY_MILLIS) { "delayMillis는 0..${MockResponseSpec.MAX_DELAY_MILLIS} 범위여야 합니다." }
    }

    public fun withEnabled(enabled: Boolean): ChaosRule = ChaosRule(host, action, pathPrefix, probability, delayMillis, enabled, id)

    override fun equals(other: Any?): Boolean = other is ChaosRule && id == other.id && host == other.host && action == other.action &&
        pathPrefix == other.pathPrefix && probability == other.probability && delayMillis == other.delayMillis && enabled == other.enabled

    override fun hashCode(): Int = listOf(id, host, action, pathPrefix, probability, delayMillis, enabled).hashCode()

    public companion object {
        public const val DEFAULT_DELAY_MILLIS: Long = 3_000
    }
}

/**
 * 장애 모사 규칙과 전체 스위치. 규칙은 메모리에만 있고 전체 스위치는 꺼진 채 시작한다.
 * `PulseHandle.mockInterceptor()`를 붙인 클라이언트에만 적용되며, 모의 응답 규칙이 먼저 맞으면 그것을 쓴다.
 */
public interface PulseChaos {
    public val policy: ChaosPolicy

    public val enabled: StateFlow<Boolean>

    public val rules: StateFlow<List<ChaosRule>>

    public fun setEnabled(enabled: Boolean)

    /** 같은 ID가 있으면 바꾼다. 허용하지 않은 호스트면 [IllegalArgumentException]. */
    public fun upsert(rule: ChaosRule)

    public fun remove(ruleId: String)
}
