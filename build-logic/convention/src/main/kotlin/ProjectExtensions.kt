import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.library(alias: String) = findLibrary(alias).get()

// 호스트 앱이 더 낮은 Kotlin(2.2 이상)으로 빌드해도 SDK를 읽을 수 있게 언어·API 버전과 표준 라이브러리 버전을 낮춰 둔다.
internal fun Project.configureSdkKotlin() {
    extensions.configure<KotlinProjectExtension>("kotlin") {
        explicitApi()
        coreLibrariesVersion = "2.2.0"
    }
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            languageVersion.set(KotlinVersion.KOTLIN_2_2)
            apiVersion.set(KotlinVersion.KOTLIN_2_2)
        }
    }
}
