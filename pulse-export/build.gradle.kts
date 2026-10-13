plugins {
    alias(libs.plugins.pulse.jvm.library)
}

dependencies {
    api(project(":pulse-api"))
    implementation(project(":pulse-security"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
