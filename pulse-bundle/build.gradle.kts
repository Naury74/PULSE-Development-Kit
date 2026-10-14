plugins {
    alias(libs.plugins.pulse.android.library)
}

android {
    namespace = "com.naury.pulse.bundle"
}

dependencies {
    api(project(":pulse-api"))
    api(libs.okhttp)
    implementation(project(":pulse-core"))
    implementation(project(":pulse-security"))
    implementation(project(":pulse-okhttp"))
    implementation(project(":pulse-storage"))
    implementation(project(":pulse-analytics"))
    implementation(project(":pulse-replay"))
    implementation(project(":pulse-mock"))
    implementation(project(":pulse-export"))
    implementation(libs.kotlinx.coroutines.android)
    // 탭한 Compose 노드를 찾을 때만 쓴다. Compose가 없는 앱에서는 이 코드에 들어가지 않는다.
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.ui)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
}
