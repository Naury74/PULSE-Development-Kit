plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = "pulse.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("androidLibrary") {
            id = "pulse.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("publicApi") {
            id = "pulse.public.api"
            implementationClass = "PublicApiConventionPlugin"
        }
        register("noopApi") {
            id = "pulse.noop.api"
            implementationClass = "NoopApiConventionPlugin"
        }
        register("quality") {
            id = "pulse.quality"
            implementationClass = "QualityConventionPlugin"
        }
        register("androidCompose") {
            id = "pulse.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
    }
}
