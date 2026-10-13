plugins {
    alias(libs.plugins.pulse.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.naury.pulse.storage"
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
