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
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
}
