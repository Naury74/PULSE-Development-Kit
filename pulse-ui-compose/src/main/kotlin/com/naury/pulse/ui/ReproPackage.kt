package com.naury.pulse.ui

import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 타임라인·요청 목록의 한 줄. */
internal class PackageLine(val time: String, val badge: String, val text: String, val detail: String?)

/**
 * 읽어 들인 재현 패키지(`pulse.repro.v1`). 화면에서 읽기 전용으로 보여 준다.
 *
 * @property integrity 파일마다 manifest의 SHA-256과 맞는지. 맞지 않거나 manifest에 없는 파일이 있으면 false.
 */
internal class ReproPackage(
    val schema: String?,
    val createdAt: String?,
    val issue: Map<String, String>,
    val steps: List<String>,
    val evidence: List<String>,
    val environment: Map<String, Map<String, String>>,
    val timeline: List<PackageLine>,
    val requests: List<PackageLine>,
    val screenshot: ByteArray?,
    val integrity: Boolean,
    val mismatched: List<String>,
)

/** 재현 패키지 ZIP을 읽는다. 크기·개수 상한을 넘거나 manifest가 없으면 [IllegalArgumentException]. */
internal object ReproPackageReader {
    private const val MAX_ENTRIES = 32
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_LINES = 5_000
    private val json = Json { ignoreUnknownKeys = true }

    fun read(input: InputStream): ReproPackage {
        val files = unzip(input)
        val manifest = files["manifest.json"]?.let(::parse)?.jsonObject ?: throw IllegalArgumentException("manifest.json이 없습니다.")
        val expected = manifest["files"]?.jsonArray.orEmpty().associate { entry ->
            entry.jsonObject.string("path").orEmpty() to entry.jsonObject.string("sha256").orEmpty()
        }
        val mismatched = files.keys.filter { it != "manifest.json" }.filter { path -> expected[path] != sha256(files.getValue(path)) }
        val issue = files["issue.json"]?.let(::parse)?.jsonObject
        return ReproPackage(
            schema = manifest.string("schema"),
            createdAt = manifest.string("createdAt"),
            issue = issue?.filterValues { it is JsonPrimitive && it !is JsonNull }?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
            steps = issue?.get("steps")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            evidence = issue?.get("evidence")?.jsonArray?.map { it.jsonObject.string("title").orEmpty() }.orEmpty(),
            environment = files["environment.json"]?.let(::parse)?.jsonObject?.filterValues {
                it is JsonObject
            }?.mapValues { (_, section) ->
                section.jsonObject.mapValues { it.value.jsonPrimitive.content }
            }.orEmpty(),
            timeline = files["session/timeline.json"]?.let(
                ::parse,
            )?.jsonObject?.get("items")?.jsonArray.orEmpty().take(MAX_LINES).map(::timelineLine),
            requests = files["network/requests.har"]?.let(::parse)?.jsonObject?.get("log")?.jsonObject?.get("entries")?.jsonArray.orEmpty()
                .take(MAX_LINES).map(::harLine),
            screenshot = files["attachments/screenshot-redacted.png"],
            integrity = mismatched.isEmpty(),
            mismatched = mismatched,
        )
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { entry ->
                require(files.size < MAX_ENTRIES) { "파일이 너무 많습니다." }
                require(!entry.name.contains("..")) { "잘못된 경로입니다: ${entry.name}" }
                val bytes = zip.readBytes()
                total += bytes.size
                require(total <= MAX_TOTAL_BYTES) { "패키지가 너무 큽니다." }
                files[entry.name] = bytes
            }
        }
        return files
    }

    private fun timelineLine(element: JsonElement): PackageLine {
        val item = element.jsonObject
        val time = item.string("time")?.substringAfter('T')?.take(TIME_CHARS).orEmpty()
        return if (item.string("type") == "REQUEST") {
            PackageLine(
                time,
                item.string("method") ?: "REQ",
                "${item.string("url").orEmpty()} ${item.string("status") ?: item.string("state").orEmpty()}",
                item.string("screen"),
            )
        } else {
            PackageLine(
                time,
                item.string("kind") ?: "EVENT",
                item.string("message").orEmpty(),
                listOfNotNull(item.string("tag"), item.string("screen")).joinToString(" · ").ifEmpty {
                    null
                },
            )
        }
    }

    private fun harLine(element: JsonElement): PackageLine {
        val entry = element.jsonObject
        val request = entry["request"]?.jsonObject
        val response = entry["response"]?.jsonObject
        return PackageLine(
            time = entry.string("startedDateTime")?.substringAfter('T')?.take(TIME_CHARS).orEmpty(),
            badge = request?.string("method") ?: "REQ",
            text = "${request?.string("url").orEmpty()} ${response?.string("status").orEmpty()}",
            detail = entry.string("time")?.let { "$it ms" },
        )
    }

    private fun parse(bytes: ByteArray): JsonElement = json.parseToJsonElement(bytes.toString(Charsets.UTF_8))

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private const val TIME_CHARS = 12
}
