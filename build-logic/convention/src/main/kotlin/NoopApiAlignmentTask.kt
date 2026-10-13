import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * release no-op이 debug 수집 artifact와 같은 공개 시그니처를 갖는지 `javap -public`으로 비교한다.
 * 생성자와 Kotlin `internal` 멤버(이름에 `$`가 붙는다)는 두 구현이 달라도 되므로 뺀다.
 */
abstract class NoopApiAlignmentTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundleClasses: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noopClasses: DirectoryProperty

    @get:Input
    abstract val classNames: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun compare() {
        val javap = File(System.getProperty("java.home"), "bin/javap").path
        val differences = classNames.get().flatMap { name ->
            val bundle = signatures(javap, bundleClasses.get().asFile, name)
            val noop = signatures(javap, noopClasses.get().asFile, name)
            (bundle - noop).map { "no-op에 없음: $it" } + (noop - bundle).map { "no-op에만 있음: $it" }
        }
        report.get().asFile.writeText(differences.joinToString("\n"))
        if (differences.isNotEmpty()) throw GradleException("pulse-noop과 pulse-bundle의 공개 API가 다릅니다:\n" + differences.joinToString("\n"))
    }

    private fun signatures(javap: String, classes: File, name: String): Set<String> {
        val process = ProcessBuilder(javap, "-public", "-cp", classes.path, name).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readLines()
        if (process.waitFor() != 0) throw GradleException("javap 실패($name): ${output.joinToString("\n")}")
        val simpleName = name.substringAfterLast('.')
        return output.map { it.trim() }
            .filter { it.endsWith(";") }
            .filterNot { '$' in it || it.contains(" $name(") || it.contains("$simpleName(") && !it.contains(" $simpleName ") }
            .toSet()
    }
}
