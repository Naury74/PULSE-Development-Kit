package com.naury.pulse.security

import com.naury.pulse.api.RedactionPolicy

internal class SensitiveNameMatcher(policy: RedactionPolicy) {
    private val exactHeaders = policy.headers
    private val exactQueryParameters = policy.queryParameters
    private val sensitiveWords: List<List<String>> = policy.sensitiveNames.map(::words).filter { it.isNotEmpty() }

    fun isSensitiveHeader(name: String): Boolean = name.lowercase() in exactHeaders || containsSensitiveWords(name)

    fun isSensitiveQueryParameter(name: String): Boolean = name.lowercase() in exactQueryParameters || containsSensitiveWords(name)

    fun isSensitiveKey(name: String): Boolean = containsSensitiveWords(name)

    private fun containsSensitiveWords(name: String): Boolean {
        val nameWords = words(name)
        return sensitiveWords.any { candidate -> nameWords.containsRun(candidate) }
    }

    private fun List<String>.containsRun(run: List<String>): Boolean {
        if (run.size > size) return false
        return (0..size - run.size).any { start -> run.indices.all { this[start + it] == run[it] } }
    }

    private companion object {
        private val camelBoundary = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
        private val separators = Regex("[^A-Za-z0-9]+")

        fun words(name: String): List<String> = name.split(separators)
            .flatMap { it.split(camelBoundary) }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
    }
}
