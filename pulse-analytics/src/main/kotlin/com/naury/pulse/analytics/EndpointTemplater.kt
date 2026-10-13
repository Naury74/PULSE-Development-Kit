package com.naury.pulse.analytics

import com.naury.pulse.api.EndpointGrouping

/** [EndpointGrouping]에 따라 경로를 템플릿으로 바꾼다. 상태가 없어 여러 스레드에서 함께 써도 된다. */
public class EndpointTemplater(grouping: EndpointGrouping) {
    private val auto = grouping.autoTemplates
    private val templates = grouping.templates.map { template -> template to template.trimEnd('/').split('/') }

    public fun template(path: String): String {
        val segments = path.trimEnd('/').split('/')
        templates.firstOrNull { (_, parts) -> matches(parts, segments) }?.let { return it.first }
        return if (auto) autoTemplate(path) else path
    }

    private fun matches(template: List<String>, segments: List<String>): Boolean = template.size == segments.size &&
        template.indices.all { index -> isPlaceholder(template[index]) || template[index] == segments[index] }

    private fun isPlaceholder(part: String) = part.length > 2 && part.startsWith('{') && part.endsWith('}')

    public companion object {
        public const val ID_PLACEHOLDER: String = "{id}"

        private val idSegment =
            Regex("^(\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{24,})$")

        /** 숫자·UUID·긴 16진수 조각을 [ID_PLACEHOLDER]로 바꾼다. */
        @JvmStatic
        public fun autoTemplate(path: String): String =
            path.split('/').joinToString("/") { segment -> if (idSegment.matches(segment)) ID_PLACEHOLDER else segment }
    }
}
