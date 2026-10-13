package com.naury.pulse.mock

import com.naury.pulse.api.MockRule
import com.naury.pulse.api.PulseMocks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 메모리에만 있는 [PulseMocks]. 전체 스위치는 꺼진 채 시작한다. */
public class MockRegistry : PulseMocks {
    private val mutableEnabled = MutableStateFlow(false)
    private val mutableRules = MutableStateFlow<List<MockRule>>(emptyList())

    override val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    override val rules: StateFlow<List<MockRule>> = mutableRules.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        mutableEnabled.value = enabled
    }

    @Synchronized
    override fun upsert(rule: MockRule) {
        mutableRules.value = (mutableRules.value.filterNot { it.id == rule.id } + rule).sortedWith(order)
    }

    @Synchronized
    override fun remove(ruleId: String) {
        mutableRules.value = mutableRules.value.filterNot { it.id == ruleId }
    }

    override fun disableAll() {
        mutableEnabled.value = false
    }

    /** 요청에 맞는 첫 규칙을 고르고 사용 횟수를 하나 줄인다. 마지막 사용이면 규칙을 끈다. */
    @Synchronized
    internal fun take(match: (MockRule) -> Boolean): MockRule? {
        if (!mutableEnabled.value) return null
        val rule = mutableRules.value.firstOrNull { it.enabled && match(it) } ?: return null
        val uses = rule.remainingUses
        if (uses != null) upsert(if (uses == 1) rule.copy(enabled = false) else rule.copy(remainingUses = uses - 1))
        return rule
    }

    private companion object {
        val order = compareByDescending<MockRule> { it.priority }.thenBy { it.pathMatch.ordinal }.thenBy { it.createdAtEpochMillis }
    }
}
