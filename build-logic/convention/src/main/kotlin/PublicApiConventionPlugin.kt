import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

/**
 * 호스트 앱이 직접 쓰는 모듈의 공개 ABI를 `api/` 덤프로 고정한다. 공개 API가 바뀌면 `checkKotlinAbi`(check에 포함)가 실패하고,
 * 의도한 변경이면 `updateKotlinAbi`로 덤프를 갱신해 커밋한다.
 */
class PublicApiConventionPlugin : Plugin<Project> {
    @OptIn(ExperimentalAbiValidation::class)
    override fun apply(target: Project) {
        with(target) {
            extensions.configure<KotlinProjectExtension>("kotlin") {
                abiValidation()
            }
            tasks.matching { it.name == "check" }.configureEach { dependsOn("checkKotlinAbi") }
        }
    }
}
