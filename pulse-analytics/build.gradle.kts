plugins {
    alias(libs.plugins.pulse.jvm.library)
}

dependencies {
    api(project(":pulse-api"))
}
