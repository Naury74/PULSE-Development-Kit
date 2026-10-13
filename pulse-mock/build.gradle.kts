plugins {
    alias(libs.plugins.pulse.jvm.library)
}

dependencies {
    api(project(":pulse-api"))
    api(libs.okhttp)
    implementation(project(":pulse-core"))

    testImplementation(project(":pulse-okhttp"))
    testImplementation(libs.okhttp.mockwebserver)
}
