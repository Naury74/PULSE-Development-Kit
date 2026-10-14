package com.naury.pulse.mock

import com.naury.pulse.api.ChaosPolicy
import com.naury.pulse.api.ChaosRule
import com.naury.pulse.api.PulseChaos
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request

/** 메모리에만 있는 [PulseChaos]. 전체 스위치는 꺼진 채 시작하고, 허용 호스트는 넣을 때와 적용할 때 모두 검사한다. */
public class ChaosRegistry(override val policy: ChaosPolicy, private val random: Random = Random.Default) : PulseChaos {
    private val mutableEnabled = MutableStateFlow(false)
    private val mutableRules = MutableStateFlow<List<ChaosRule>>(emptyList())

    override val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    override val rules: StateFlow<List<ChaosRule>> = mutableRules.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        mutableEnabled.value = enabled
    }

    @Synchronized
    override fun upsert(rule: ChaosRule) {
        require(policy.allowsHost(rule.host)) { "장애 모사를 허용하지 않은 호스트입니다: ${rule.host}" }
        mutableRules.value = mutableRules.value.filterNot { it.id == rule.id } + rule
    }

    @Synchronized
    override fun remove(ruleId: String) {
        mutableRules.value = mutableRules.value.filterNot { it.id == ruleId }
    }

    /** 요청에 적용할 규칙. 꺼져 있거나 맞는 규칙이 없거나 확률에 걸리지 않으면 null. */
    internal fun pick(request: Request): ChaosRule? {
        if (!mutableEnabled.value) return null
        val host = request.url.host
        if (!policy.allowsHost(host)) return null
        val path = request.url.encodedPath
        val rule =
            mutableRules.value.firstOrNull { it.enabled && it.host == host.lowercase() && path.startsWith(it.pathPrefix) } ?: return null
        return rule.takeIf { random.nextFloat() < it.probability }
    }
}
