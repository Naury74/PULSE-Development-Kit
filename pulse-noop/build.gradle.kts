plugins {
    alias(libs.plugins.pulse.android.library)
}

android {
    namespace = "com.naury.pulse.noop"
}

dependencies {
    api(project(":pulse-api"))
    api(libs.okhttp)
}
