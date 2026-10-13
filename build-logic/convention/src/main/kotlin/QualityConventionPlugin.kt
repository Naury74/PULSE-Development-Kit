import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.register

/**
 * ktlint·detekt를 CLI로 실행한다. 두 도구의 Gradle plugin은 AGP built-in Kotlin과 맞물리는 방식이 버전마다 달라
 * 모듈 소스를 직접 넘기는 쪽이 안정적이다.
 */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val ktlint = configurations.create("ktlint")
            val detekt = configurations.create("detekt") {
                resolutionStrategy.eachDependency {
                    // detekt CLI는 자신이 빌드된 Kotlin 컴파일러 버전으로만 동작한다.
                    if (requested.group == "org.jetbrains.kotlin") useVersion(DETEKT_KOTLIN_VERSION)
                }
            }
            dependencies.add(ktlint.name, libs.library("ktlint-cli"))
            dependencies.add(detekt.name, libs.library("detekt-cli"))

            val sources = fileTree("src") { include("**/*.kt") }
            val scripts = fileTree(projectDir) { include("*.kts") }
            val editorConfig = rootProject.file(".editorconfig")
            val detektConfig = rootProject.file("config/detekt/detekt.yml")
            val reports = layout.buildDirectory.dir("reports/quality")

            val ktlintCheck = tasks.register<JavaExec>("ktlintCheck") {
                group = "verification"
                description = "ktlint로 Kotlin 코드 형식을 검사한다."
                classpath = ktlint
                mainClass.set("com.pinterest.ktlint.Main")
                workingDir = projectDir
                onlyIf { !sources.isEmpty }
                inputs.files(sources, scripts, editorConfig)
                outputs.file(reports.map { it.file("ktlint.txt") })
                args("--relative", "src/**/*.kt", "*.kts", "--reporter=plain,output=${reports.get().file("ktlint.txt").asFile}")
                args("--reporter=plain")
            }
            tasks.register<JavaExec>("ktlintFormat") {
                group = "formatting"
                description = "ktlint 규칙에 맞게 Kotlin 코드 형식을 고친다."
                classpath = ktlint
                mainClass.set("com.pinterest.ktlint.Main")
                workingDir = projectDir
                args("-F", "src/**/*.kt", "*.kts")
            }
            val detektCheck = tasks.register<JavaExec>("detekt") {
                group = "verification"
                description = "detekt로 Kotlin 코드 복잡도와 위험 패턴을 검사한다."
                classpath = detekt
                mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
                workingDir = projectDir
                onlyIf { !sources.isEmpty }
                inputs.files(sources, detektConfig)
                outputs.file(reports.map { it.file("detekt.txt") })
                args(
                    "--input", "src",
                    "--config", detektConfig.absolutePath,
                    "--build-upon-default-config",
                    "--report", "txt:${reports.get().file("detekt.txt").asFile}",
                )
            }
            tasks.matching { it.name == "check" }.configureEach {
                dependsOn(ktlintCheck, detektCheck)
            }
        }
    }

    private companion object {
        const val DETEKT_KOTLIN_VERSION = "2.0.21"
    }
}
