package com.naury.pulse.ui

import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.EnvironmentSnapshot
import com.naury.pulse.api.ReportDraft
import com.naury.pulse.api.Severity
import com.naury.pulse.export.ReproPackageWriter
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class ReproPackageReaderTest {
    private fun pack(): ByteArray {
        val inspector = FakeInspector()
        val draft = ReportDraft(id = "r1", title = "결제 실패", steps = listOf("장바구니", "결제"), severity = Severity.BLOCKER)
        val environment = EnvironmentSnapshot(mapOf("package" to "com.example"), mapOf("model" to "Pixel"), mapOf("server" to "staging"), 0)
        return ByteArrayOutputStream().also {
            runBlocking {
                ReproPackageWriter(inspector.config.redaction).write(inspector, draft, null, environment, byteArrayOf(1, 2, 3), it)
            }
        }.toByteArray()
    }

    @Test
    fun `작성기가 만든 패키지를 읽고 체크섬이 맞으면 무결하다고 본다`() {
        val read = ReproPackageReader.read(pack().inputStream())

        assertThat(read.schema).isEqualTo("pulse.repro.v1")
        assertThat(read.integrity).isTrue()
        assertThat(read.issue["title"]).isEqualTo("결제 실패")
        assertThat(read.issue["severity"]).isEqualTo("BLOCKER")
        assertThat(read.steps).containsExactly("장바구니", "결제").inOrder()
        assertThat(read.environment.getValue("flags")).containsEntry("server", "staging")
        assertThat(read.screenshot).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `만든 뒤에 바뀐 파일을 찾아낸다`() {
        val tampered = ByteArrayOutputStream()
        ZipOutputStream(tampered).use { out ->
            ZipInputStream(pack().inputStream()).use { input ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    val bytes = input.readBytes()
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(
                        if (entry.name ==
                            "issue.json"
                        ) {
                            bytes.toString(Charsets.UTF_8).replace("결제 실패", "다른 제목").toByteArray()
                        } else {
                            bytes
                        },
                    )
                    out.closeEntry()
                }
            }
        }

        val read = ReproPackageReader.read(tampered.toByteArray().inputStream())

        assertThat(read.integrity).isFalse()
        assertThat(read.mismatched).containsExactly("issue.json")
    }

    @Test
    fun `manifest가 없으면 읽지 않는다`() {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { out ->
                out.putNextEntry(ZipEntry("issue.json"))
                out.write("{}".toByteArray())
                out.closeEntry()
            }
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) { ReproPackageReader.read(zip.inputStream()) }
    }
}
