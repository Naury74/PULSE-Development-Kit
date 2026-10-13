plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.pulse.noop.api)
}


// SDK 모듈은 같은 좌표·POM으로 배포한다. 저장소의 app(Showcase)은 배포하지 않는다.
val sdkModules = mapOf(
    "pulse-api" to "PULSE 공개 설정·모델·조회 계약 (Kotlin JVM)",
    "pulse-security" to "PULSE 헤더·URL·JSON 마스킹",
    "pulse-core" to "PULSE 상한 있는 수집 큐와 저장 파이프라인",
    "pulse-okhttp" to "PULSE OkHttp 인터셉터·EventListener 수집",
    "pulse-storage" to "PULSE Room 저장소·보관 정책",
    "pulse-analytics" to "PULSE 트래픽 통계·진단 규칙·비교",
    "pulse-export" to "PULSE HAR·JSON·cURL 내보내기",
    "pulse-replay" to "PULSE 허용 호스트 기반 요청 재실행",
    "pulse-mock" to "PULSE 모의 응답 interceptor",
    "pulse-bundle" to "PULSE 진입점(Pulse, PulseHandle) — debug용",
    "pulse-noop" to "PULSE 같은 API의 빈 구현 — release용",
    "pulse-ui-compose" to "PULSE 인스펙터 Compose 화면",
)

// Maven Central 업로드 묶음을 만들 임시 저장소. 서명·체크섬이 붙은 배포 파일이 모듈별로 모인다.
val centralStaging = layout.buildDirectory.dir("central-staging")

subprojects {
    val summary = sdkModules[name] ?: return@subprojects
    apply(plugin = "maven-publish")
    apply(plugin = "signing")
    group = providers.gradleProperty("PULSE_GROUP").get()
    version = providers.gradleProperty("PULSE_VERSION").get()

    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> { withSourcesJar() }
    }

    // Maven Central은 javadoc jar를 요구한다. API 문서는 소스 jar와 README·docs로 제공하므로 안내 파일만 담은 jar를 붙인다.
    val javadocJar = tasks.register<Jar>("centralJavadocJar") {
        archiveClassifier.set("javadoc")
        from(rootProject.file("README.md"))
    }

    // AGP는 평가가 끝난 뒤에 컴포넌트를 만들므로, 컴포넌트가 생길 때 publication을 만든다.
    components.configureEach {
        val component = this
        val wanted = if (plugins.hasPlugin("com.android.library")) "release" else "java"
        if (component.name != wanted) return@configureEach
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("release") {
                    from(component)
                    artifact(javadocJar)
                    artifactId = project.name
                    pom {
                        name.set(project.name)
                        description.set(summary)
                        url.set("https://github.com/Naury74/PULSE-Development-Kit")
                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                distribution.set("repo")
                            }
                        }
                        developers {
                            developer {
                                id.set("Naury74")
                                name.set("Naury74")
                            }
                        }
                        scm {
                            url.set("https://github.com/Naury74/PULSE-Development-Kit")
                            connection.set("scm:git:https://github.com/Naury74/PULSE-Development-Kit.git")
                            developerConnection.set("scm:git:ssh://git@github.com/Naury74/PULSE-Development-Kit.git")
                        }
                        issueManagement {
                            system.set("GitHub")
                            url.set("https://github.com/Naury74/PULSE-Development-Kit/issues")
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "CentralStaging"
                    url = uri(centralStaging)
                }
            }
        }
        configureSigning()
    }
}

/**
 * 서명 키가 있으면 배포 파일에 서명한다. 키는 저장소에 두지 않고 `~/.gradle/gradle.properties`나 환경 변수에서 읽는다.
 *
 * - `signingInMemoryKey` / `signingInMemoryKeyPassword`: ASCII armor 비밀 키와 비밀번호(CI에 알맞음).
 * - `signing.gnupg.keyName` (+ `signing.gnupg.passphrase`): 설치된 gpg로 서명(개인 컴퓨터에 알맞음).
 *
 * `-PpulseSign=true`를 주지 않거나 키가 없으면 서명하지 않으므로 `publishToMavenLocal`은 그대로 동작한다. Central 묶음은 서명이 필수다.
 */
fun Project.configureSigning() {
    // 로컬 확인(publishToMavenLocal)에서는 서명하지 않는다. Central 묶음을 만들 때만 -PpulseSign=true로 서명한다.
    if (providers.gradleProperty("pulseSign").orNull != "true") return
    val inMemoryKey = providers.gradleProperty("signingInMemoryKey").orNull?.trim()?.takeIf { it.isNotEmpty() }
    val useGpg = providers.gradleProperty("signing.gnupg.keyName").isPresent
    if (inMemoryKey == null && !useGpg) return
    extensions.configure<SigningExtension> {
        if (inMemoryKey != null) {
            useInMemoryPgpKeys(inMemoryKey, providers.gradleProperty("signingInMemoryKeyPassword").orNull?.trim())
        } else {
            useGpgCmd()
        }
        sign(extensions.getByType<PublishingExtension>().publications)
    }
}

// ---- Maven Central(Central Portal) 배포 ----

val sdkProjects = subprojects.filter { it.name in sdkModules }

/** 모든 SDK 모듈을 서명해 임시 저장소에 모으고 Central Portal 업로드용 zip을 만든다. */
val centralBundle = tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Maven Central 업로드 묶음(build/central-bundle.zip)을 만든다."
    dependsOn(sdkProjects.map { "${it.path}:publishReleasePublicationToCentralStagingRepository" })
    doFirst {
        val root = centralStaging.get().asFile
        val unsigned = root.walkTopDown().filter { it.isFile && it.extension in setOf("aar", "jar", "pom", "module") }
            .filter { !java.io.File(it.path + ".asc").exists() }.toList()
        check(unsigned.isEmpty()) { "서명되지 않은 파일이 있습니다. 서명 키 설정(~/.gradle/gradle.properties)을 확인하세요: ${unsigned.take(3)}" }
    }
    from(centralStaging) { exclude("**/maven-metadata*") }
    archiveFileName.set("central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
    notCompatibleWithConfigurationCache("임시 저장소 내용을 실행 시점에 검사한다")
}

tasks.register("cleanCentralStaging") {
    group = "publishing"
    description = "이전 버전이 섞이지 않도록 Central 임시 저장소를 비운다."
    val dir = centralStaging
    doLast { dir.get().asFile.deleteRecursively() }
}

/**
 * 묶음을 Central Portal에 올린다. 기본은 검증 후 대기(USER_MANAGED)라 포털에서 내용을 확인하고 직접 Publish를 누른다.
 * 자격 증명은 Central Portal에서 만든 사용자 토큰(`centralPortalUsername` / `centralPortalPassword`)이다.
 * `-PcentralAutoPublish=true`를 주면 검증이 끝나는 대로 바로 공개한다(되돌릴 수 없다).
 */
tasks.register("publishToCentral") {
    group = "publishing"
    description = "build/central-bundle.zip을 Maven Central(Central Portal)에 업로드한다."
    dependsOn(centralBundle)
    notCompatibleWithConfigurationCache("업로드는 네트워크 작업이다")
    doLast {
        // 복사해 붙여 넣으며 생긴 앞뒤 공백이 토큰에 섞이면 인증이 실패하므로 잘라 낸다.
        val user = providers.gradleProperty("centralPortalUsername").orNull?.trim()?.takeIf { it.isNotEmpty() }
        val password = providers.gradleProperty("centralPortalPassword").orNull?.trim()?.takeIf { it.isNotEmpty() }
        check(user != null && password != null) { "centralPortalUsername / centralPortalPassword가 없습니다. ~/.gradle/gradle.properties에 넣으세요." }
        val bundle = layout.buildDirectory.file("central-bundle.zip").get().asFile
        val auto = providers.gradleProperty("centralAutoPublish").orNull == "true"
        val token = java.util.Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        val boundary = "----Pulse${System.currentTimeMillis()}"
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"${bundle.name}\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        val body = head.toByteArray() + bundle.readBytes() + "\r\n--$boundary--\r\n".toByteArray()
        val name = java.net.URLEncoder.encode("${project.property("PULSE_GROUP")}:pulse-bundle:${project.property("PULSE_VERSION")}", "UTF-8")
        val type = if (auto) "AUTOMATIC" else "USER_MANAGED"
        val request = java.net.http.HttpRequest.newBuilder(
            uri("https://central.sonatype.com/api/v1/publisher/upload?publishingType=$type&name=$name"),
        )
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "업로드 실패(${response.statusCode()}): ${response.body()}" }
        logger.lifecycle("업로드 완료. 배포 ID: ${response.body()}")
        logger.lifecycle(if (auto) "검증이 끝나면 자동으로 공개됩니다." else "https://central.sonatype.com/publishing/deployments 에서 확인 후 Publish를 누르세요.")
    }
}
