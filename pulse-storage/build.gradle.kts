plugins {
    alias(libs.plugins.pulse.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "com.naury.pulse.storage"
    // 마이그레이션 테스트가 내보낸 스키마(schemas/)를 읽을 수 있게 테스트 asset에 넣는다.
    sourceSets {
        named("test") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":pulse-api"))
    implementation(project(":pulse-core"))
    implementation(project(":pulse-security"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}

// 스키마 JSON은 KSP가 만들므로, 테스트 asset을 모으기 전에 KSP가 먼저 돌아야 새 버전 스키마가 들어간다.
tasks.matching { it.name.matches(Regex("merge(Debug|Release)UnitTestAssets")) }.configureEach {
    dependsOn(tasks.matching { it.name.matches(Regex("ksp(Debug|Release)Kotlin")) })
}
