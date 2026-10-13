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

// 성능 측정은 시간이 걸리고 기기마다 달라 check에 넣지 않는다.
val testSourceSet = extensions.getByType<SourceSetContainer>().getByName("test")
tasks.register<Test>("benchmark") {
    group = "verification"
    description = "요청당 수집 오버헤드를 잰다(JVM, 루프백). 결과는 build/reports/benchmark.md."
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    filter { includeTestsMatching("*CaptureOverheadBenchmark") }
    systemProperty("pulse.benchmark", "true")
    systemProperty("pulse.benchmark.report", layout.buildDirectory.file("reports/benchmark.md").get().asFile.path)
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
