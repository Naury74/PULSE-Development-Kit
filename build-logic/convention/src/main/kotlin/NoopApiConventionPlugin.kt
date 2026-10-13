import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/** 루트 프로젝트에 `verifyNoopApi`를 등록하고 `check`에 붙인다. */
class NoopApiConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("base")
            // release(no-op)이 debug(pulse-bundle)와 같은 공개 API를 가져야 호스트가 같은 코드로 두 빌드를 컴파일할 수 있다.
            val verify = tasks.register<NoopApiAlignmentTask>("verifyNoopApi") {
                group = "verification"
                description = "pulse-noop과 pulse-bundle의 공개 시그니처가 같은지 확인한다."
                dependsOn(":pulse-bundle:compileReleaseKotlin", ":pulse-noop:compileReleaseKotlin")
                bundleClasses.set(layout.projectDirectory.dir(CLASSES.format("pulse-bundle")))
                noopClasses.set(layout.projectDirectory.dir(CLASSES.format("pulse-noop")))
                classNames.set(listOf("com.naury.pulse.api.Pulse", "com.naury.pulse.api.PulseHandle"))
                report.set(layout.buildDirectory.file("reports/noop-api.txt"))
            }
            tasks.named("check") { dependsOn(verify) }
        }
    }

    private companion object {
        const val CLASSES = "%s/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes"
    }
}
