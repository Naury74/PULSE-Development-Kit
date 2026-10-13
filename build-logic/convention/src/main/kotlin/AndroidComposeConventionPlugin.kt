import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.getByType<CommonExtension>().buildFeatures.compose = true
            dependencies {
                // 버전 없이 쓰는 Compose 의존성의 버전을 POM에서도 알 수 있게 BOM을 직접 선언한다.
                "implementation"(platform(libs.library("androidx-compose-bom")))
            }
        }
    }
}
