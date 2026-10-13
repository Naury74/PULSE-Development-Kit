plugins {
    alias(libs.plugins.pulse.android.library)
    alias(libs.plugins.pulse.android.compose)
}

android {
    namespace = "com.naury.pulse.ui"
}

dependencies {
    api(project(":pulse-api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
