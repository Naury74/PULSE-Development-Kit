plugins {
    alias(libs.plugins.pulse.jvm.library)
    alias(libs.plugins.pulse.public.api)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
