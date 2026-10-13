import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.pulse.quality)
}

android {
    namespace = "com.naury.pulse.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.naury.pulse.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            // release(no-op) 동작을 별도 키 없이 설치해 확인할 수 있도록 debug 서명을 사용한다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    debugImplementation(project(":pulse-bundle"))
    debugImplementation(project(":pulse-ui-compose"))
    releaseImplementation(project(":pulse-noop"))

    implementation(libs.okhttp)
    // 인터넷 없이 시연하도록 앱 안에서 로컬 서버를 띄운다. 샘플 전용이며 SDK는 의존하지 않는다.
    implementation(libs.okhttp.mockwebserver)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
}

// release 런타임에 수집 엔진·저장소·UI가 들어가지 않았는지 확인한다(no-op 교체 보장).
fun Configuration.projectDependencyNames(): Provider<Set<String>> = incoming.resolutionResult.rootComponent.map { root ->
    val seen = mutableSetOf<String>()
    val pending = ArrayDeque(listOf(root))
    while (pending.isNotEmpty()) {
        pending
            .removeFirst()
            .dependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .map { it.selected }
            .forEach { component ->
                val id = component.id
                if (id is ProjectComponentIdentifier && seen.add(id.projectName)) pending.add(component)
            }
    }
    seen
}

tasks.register("verifyReleaseClasspath") {
    group = "verification"
    description = "release 런타임 classpath에 pulse-noop만 있는지 확인한다."
    val projects = configurations.named("releaseRuntimeClasspath").flatMap { it.projectDependencyNames() }
    val forbidden = setOf("pulse-bundle", "pulse-core", "pulse-okhttp", "pulse-security", "pulse-storage", "pulse-ui-compose")
    doLast {
        val names = projects.get()
        val leaked = names.intersect(forbidden)
        check(leaked.isEmpty()) { "release 런타임에 수집 모듈이 들어갔습니다: $leaked" }
        check("pulse-noop" in names) { "release 런타임에 pulse-noop이 없습니다." }
    }
}
tasks.named("check") { dependsOn("verifyReleaseClasspath") }
