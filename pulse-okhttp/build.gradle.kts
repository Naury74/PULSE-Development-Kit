plugins {
    alias(libs.plugins.pulse.jvm.library)
}

dependencies {
    api(project(":pulse-api"))
    api(libs.okhttp)
    implementation(project(":pulse-core"))
    implementation(project(":pulse-security"))

    testImplementation(libs.okhttp.mockwebserver)
}
