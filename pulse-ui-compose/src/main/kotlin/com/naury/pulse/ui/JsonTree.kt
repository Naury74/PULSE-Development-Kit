package com.naury.pulse.ui

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 화면에 그릴 JSON 노드. [path]는 펼침 상태를 기억하는 키다. */
internal class JsonNode(
    val path: String,
    val label: String?,
    val kind: Kind,
    /** 원시 값이면 표시할 값, 컨테이너면 요약(`{3}`, `[10]`). */
    val summary: String,
    val children: List<JsonNode>,
    val depth: Int,
) {
    enum class Kind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

    val isContainer: Boolean
        get() = kind == Kind.OBJECT || kind == Kind.ARRAY
}

/** 해석 결과. [truncated]이면 노드 상한 때문에 일부 자식을 만들지 않았다. */
internal class JsonTree(val root: JsonNode, val nodeCount: Int, val truncated: Boolean)

/**
 * 저장된(마스킹된) JSON을 노드 상한 안에서 트리로 만든다. 상한을 넘는 부분은 만들지 않고 [JsonTree.truncated]로 알린다.
 * 해석할 수 없으면 null이며 화면은 원문 보기로 돌아간다. 비용이 크므로 메인 스레드에서 부르지 않는다.
 */
internal object JsonTreeBuilder {
    const val MAX_NODES = 5_000
    private const val MAX_DEPTH = 64
    private val json = Json

    fun build(text: String, maxNodes: Int = MAX_NODES): JsonTree? {
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val counter = Counter(maxNodes)
        val root = convert(element, label = null, path = "$", depth = 0, counter = counter)
        return JsonTree(root, counter.count, counter.truncated)
    }

    private class Counter(val max: Int) {
        var count = 0
        var truncated = false

        fun take(): Boolean {
            if (count >= max) {
                truncated = true
                return false
            }
            count++
            return true
        }
    }

    private fun convert(element: JsonElement, label: String?, path: String, depth: Int, counter: Counter): JsonNode {
        counter.take()
        return when (element) {
            is JsonObject -> JsonNode(
                path,
                label,
                JsonNode.Kind.OBJECT,
                "{${element.size}}",
                children(element.entries.map { it.key to it.value }, depth, counter) { "$path.$it" },
                depth,
            )

            is JsonArray -> JsonNode(
                path,
                label,
                JsonNode.Kind.ARRAY,
                "[${element.size}]",
                children(element.mapIndexed { index, value -> index.toString() to value }, depth, counter) { "$path[$it]" },
                depth,
            )

            is JsonNull -> JsonNode(path, label, JsonNode.Kind.NULL, "null", emptyList(), depth)

            is JsonPrimitive -> {
                val kind = when {
                    element.isString -> JsonNode.Kind.STRING
                    element.content == "true" || element.content == "false" -> JsonNode.Kind.BOOLEAN
                    else -> JsonNode.Kind.NUMBER
                }
                JsonNode(path, label, kind, if (element.isString) "\"${element.content}\"" else element.content, emptyList(), depth)
            }
        }
    }

    private inline fun children(
        entries: List<Pair<String, JsonElement>>,
        depth: Int,
        counter: Counter,
        childPath: (String) -> String,
    ): List<JsonNode> {
        if (depth >= MAX_DEPTH) {
            if (entries.isNotEmpty()) counter.truncated = true
            return emptyList()
        }
        val result = ArrayList<JsonNode>()
        for ((key, value) in entries) {
            if (counter.count >= counter.max) {
                counter.truncated = true
                break
            }
            result += convert(value, key, childPath(key.ifEmpty { "\"\"" }), depth + 1, counter)
        }
        return result
    }
}
