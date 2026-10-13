package com.naury.pulse.analytics

import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import com.naury.pulse.api.TransactionDetail
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 비교한 부분. */
public enum class DiffArea { STATUS, METHOD, URL, QUERY, REQUEST_HEADER, RESPONSE_HEADER, REQUEST_BODY, RESPONSE_BODY }

/** 차이 종류. 배열은 같은 값이 순서만 바뀌었으면 [REORDERED]다. */
public enum class DiffKind { ADDED, REMOVED, CHANGED, TYPE_CHANGED, REORDERED }

/**
 * 차이 하나. [path]는 헤더·쿼리 이름이거나 JSON 경로(`$.user.name`, `$.items[0]`)다.
 * 가려진 값([REDACTED])이 한쪽이라도 있으면 비교하지 않으므로 결과에 나오지 않는다.
 */
public class DiffEntry(
    public val area: DiffArea,
    public val path: String,
    public val kind: DiffKind,
    public val left: String?,
    public val right: String?,
)

/** 비교할 한쪽. 본문은 저장된(마스킹된) 텍스트다. */
public class DiffSide(public val detail: TransactionDetail, public val requestBody: String?, public val responseBody: String?)

/** 두 트랜잭션(보통 원본과 재실행)의 차이를 구조 단위로 구한다. */
public object TransactionDiff {
    /** 값이 매번 바뀌는 응답 헤더. 비교하지 않는다. */
    private val volatileHeaders = setOf("date", "age", "expires")
    private const val MAX_ENTRIES = 500
    private val json = Json

    @JvmStatic
    public fun compare(left: DiffSide, right: DiffSide): List<DiffEntry> {
        val out = ArrayList<DiffEntry>()
        val l = left.detail
        val r = right.detail
        value(out, DiffArea.METHOD, "method", l.summary.method, r.summary.method)
        value(out, DiffArea.URL, "path", l.summary.path, r.summary.path)
        value(out, DiffArea.STATUS, "status", l.summary.statusCode?.toString(), r.summary.statusCode?.toString())
        pairs(out, DiffArea.QUERY, query(l.summary.url), query(r.summary.url))
        pairs(out, DiffArea.REQUEST_HEADER, l.request.headers.lowerNames(), r.request.headers.lowerNames())
        pairs(
            out,
            DiffArea.RESPONSE_HEADER,
            l.response?.headers.orEmpty().lowerNames().filterKeys { it !in volatileHeaders },
            r.response?.headers.orEmpty().lowerNames().filterKeys { it !in volatileHeaders },
        )
        body(out, DiffArea.REQUEST_BODY, left.requestBody, right.requestBody)
        body(out, DiffArea.RESPONSE_BODY, left.responseBody, right.responseBody)
        return out.take(MAX_ENTRIES)
    }

    private fun List<HeaderField>.lowerNames(): Map<String, String> =
        groupBy { it.name.lowercase() }.mapValues { (_, fields) -> fields.joinToString(", ") { it.value } }

    private fun query(url: String): Map<String, String> = url.substringAfter('?', missingDelimiterValue = "")
        .split('&')
        .filter { it.isNotEmpty() }
        .associate { it.substringBefore('=') to it.substringAfter('=', missingDelimiterValue = "") }

    private fun value(out: MutableList<DiffEntry>, area: DiffArea, path: String, left: String?, right: String?) {
        if (left == right || left == REDACTED || right == REDACTED) return
        val kind = when {
            left == null -> DiffKind.ADDED
            right == null -> DiffKind.REMOVED
            else -> DiffKind.CHANGED
        }
        out += DiffEntry(area, path, kind, left, right)
    }

    private fun pairs(out: MutableList<DiffEntry>, area: DiffArea, left: Map<String, String>, right: Map<String, String>) {
        (left.keys + right.keys).sorted().forEach { key -> value(out, area, key, left[key], right[key]) }
    }

    private fun body(out: MutableList<DiffEntry>, area: DiffArea, left: String?, right: String?) {
        if (left == right) return
        val leftJson = left?.let(::parse)
        val rightJson = right?.let(::parse)
        if (leftJson == null || rightJson == null) {
            value(out, area, "$", left, right)
            return
        }
        element(out, area, "$", leftJson, rightJson)
    }

    private fun parse(text: String): JsonElement? = try {
        json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun element(out: MutableList<DiffEntry>, area: DiffArea, path: String, left: JsonElement, right: JsonElement) {
        if (out.size >= MAX_ENTRIES || left == right) return
        when {
            left is JsonObject && right is JsonObject -> (left.keys + right.keys).sorted().forEach { key ->
                val l = left[key]
                val r = right[key]
                when {
                    l == null -> out += DiffEntry(area, "$path.$key", DiffKind.ADDED, null, r.toString())
                    r == null -> out += DiffEntry(area, "$path.$key", DiffKind.REMOVED, l.toString(), null)
                    else -> element(out, area, "$path.$key", l, r)
                }
            }

            left is JsonArray && right is JsonArray -> array(out, area, path, left, right)

            kind(left) != kind(right) -> out += DiffEntry(area, path, DiffKind.TYPE_CHANGED, left.toString(), right.toString())

            else -> value(out, area, path, (left as? JsonPrimitive)?.content, (right as? JsonPrimitive)?.content)
        }
    }

    private fun array(out: MutableList<DiffEntry>, area: DiffArea, path: String, left: JsonArray, right: JsonArray) {
        if (left.size == right.size && left.sortedBy { it.toString() } == right.sortedBy { it.toString() }) {
            out += DiffEntry(area, path, DiffKind.REORDERED, null, null)
            return
        }
        for (index in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrNull(index)
            val r = right.getOrNull(index)
            when {
                l == null -> out += DiffEntry(area, "$path[$index]", DiffKind.ADDED, null, r.toString())
                r == null -> out += DiffEntry(area, "$path[$index]", DiffKind.REMOVED, l.toString(), null)
                else -> element(out, area, "$path[$index]", l, r)
            }
        }
    }

    private fun kind(element: JsonElement): String = when (element) {
        is JsonObject -> "object"

        is JsonArray -> "array"

        JsonNull -> "null"

        is JsonPrimitive -> when {
            element.isString -> "string"
            element.content == "true" || element.content == "false" -> "boolean"
            else -> "number"
        }
    }
}
