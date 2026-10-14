package com.naury.pulse.storage

import java.io.File
import java.io.IOException

/**
 * 마스킹된 본문을 앱 전용 디렉터리의 파일로 둔다. 임시 파일에 쓴 뒤 이름을 바꿔 반쯤 쓴 파일이 남지 않게 한다.
 * 파일 이름은 저장소가 만든 값만 받으며 경로 구분자를 허용하지 않는다.
 */
internal class BlobStore(private val directory: File) {
    fun write(name: String, text: String): Long {
        val target = file(name)
        directory.mkdirs()
        val temporary = File(directory, "$name$TEMPORARY_SUFFIX")
        val bytes = text.toByteArray(Charsets.UTF_8)
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("본문 파일을 저장하지 못했습니다.")
        }
        return bytes.size.toLong()
    }

    fun read(name: String): String? = file(name).takeIf { it.isFile }?.readText(Charsets.UTF_8)

    /** 이진 파일(화면 캡처). 텍스트와 같은 방식으로 임시 파일을 거친다. */
    fun writeBytes(name: String, bytes: ByteArray): Long {
        val target = file(name)
        directory.mkdirs()
        val temporary = File(directory, "$name$TEMPORARY_SUFFIX")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("첨부 파일을 저장하지 못했습니다.")
        }
        return bytes.size.toLong()
    }

    fun readBytes(name: String): ByteArray? = file(name).takeIf { it.isFile }?.readBytes()

    fun delete(names: Collection<String>) {
        names.forEach { file(it).delete() }
    }

    /** [referenced]에 없는 파일과 남은 임시 파일을 지운다. */
    fun deleteUnreferenced(referenced: Set<String>) {
        directory.listFiles()?.forEach { file -> if (file.name !in referenced) file.delete() }
    }

    fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun file(name: String): File {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") { "잘못된 본문 파일 이름입니다." }
        return File(directory, name)
    }

    private companion object {
        const val TEMPORARY_SUFFIX = ".partial"
    }
}
