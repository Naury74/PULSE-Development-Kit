# PULSE

> **WIP** — 아직 배포 전입니다. Maven Central에 올라가 있지 않고, 공개 API는 0.x 동안 바뀔 수 있습니다.

**PULSE**는 Android 앱의 OkHttp 요청을 기기 안에서 안전하게 기록하고 살펴보는 네트워크 인스펙터 SDK입니다.
연결한 `OkHttpClient`를 지나는 요청만 기록하며, 비밀 값은 **저장하기 전에** 가립니다. 호스트 앱이 받는 응답·예외·스트리밍 동작은 바뀌지 않습니다.

## 지금 되는 것 (0.1.0-SNAPSHOT)

- **기록**: 메서드, 마스킹한 URL·헤더, 상태 코드, 실패 종류(I/O 실패·취소·시간 초과와 4xx·5xx 응답을 구분), 크기, 소요 시간
- **본문**(선택): JSON 본문만, 상한(요청 128 KB·응답 512 KB 기본) 안에서, 호스트가 실제로 읽거나 쓴 바이트만 복사해 마스킹한 뒤 저장
- **단계 타이밍**: OkHttp `EventListener`가 관찰한 DNS·연결·TLS·요청·응답 단계만. 관찰하지 못한 단계는 0으로 추정하지 않고 비워 둡니다
- **보관**: Room DB와 앱 전용 파일(`noBackupFilesDir`), 건수·기간·본문 용량 상한, 앱이 죽어 끝나지 않은 요청은 다음 실행 때 "중단"으로 표시
- **화면**: 검색·상태 필터·페이지 목록, 요약·요청·응답·타임라인 상세, 넓은 화면 두 칸 보기, 한국어·영어
- **release 빌드**: 같은 API의 `pulse-noop`으로 바꿔 수집·저장·화면 코드가 들어가지 않음

아직 없는 것: 대시보드·통계, 진단 규칙, HAR/cURL 내보내기, 재실행(replay), 모킹, Maven Central 배포. 순서는 [기획 문서의 백로그](#로드맵)를 따릅니다.

## 연동

배포 전이라 지금은 이 저장소의 모듈을 직접 포함해 씁니다.

```kotlin
// app/build.gradle.kts
dependencies {
    debugImplementation(project(":pulse-bundle"))      // 수집·저장
    debugImplementation(project(":pulse-ui-compose"))  // 인스펙터 화면(선택)
    releaseImplementation(project(":pulse-noop"))      // 같은 API, 아무것도 하지 않음
}
```

```kotlin
class App : Application() {
    lateinit var client: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        val pulse = Pulse.initialize(this, PulseConfig.debugDefaults())
        client = OkHttpClient.Builder()
            .addInterceptor(pulse.interceptor())
            .eventListenerFactory(pulse.eventListenerFactory())
            .build()
    }
}

// 인스펙터 열기. 화면 모듈이 없거나 release(no-op)면 false
Pulse.open(context)
```

- 이미 `EventListener.Factory`를 쓰고 있다면 `pulse.eventListenerFactory(existingFactory)`로 넘기면 두 리스너 모두 이벤트를 받습니다.
- Compose 화면 안에 넣으려면 `PulseInspectorScreen(inspector = pulse, onClose = { ... })`를 씁니다.
- Java에서는 `new PulseConfig.Builder()...build()`와 `Pulse.initialize(context, config)`를 씁니다. 예제: [`JavaIntegration.java`](sample-app/src/main/java/com/naury/pulse/sample/JavaIntegration.java).

### 설정

```kotlin
PulseConfig.Builder()
    .captureMode(CaptureMode.METADATA_AND_SAFE_BODIES)       // 기본은 METADATA_ONLY(본문 저장 안 함)
    .limits(CaptureLimits(maxRequestBodyBytes = 64 * 1024, maxResponseBodyBytes = 256 * 1024))
    .retention(RetentionPolicy(maxTransactions = 5_000, maxAgeMillis = 3L * 24 * 60 * 60 * 1000))
    .redaction(RedactionPolicy.strict().withHeaders("X-Tenant-Id").withSensitiveNames("card_number"))
    .build()
```

같은 설정으로 다시 `initialize`하면 기존 핸들을 돌려주고, 다른 설정이면 `PulseConfigurationException`을 던집니다. 설정을 바꾸려면 `close()` 후 다시 초기화합니다.

## 개인정보와 안전

- 기본으로 `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key` 헤더와 이름에 token·password·secret·api key·session·auth 등이 들어간 헤더·쿼리·JSON 키의 값을 가립니다. 원래 값은 저장하지 않으므로 다시 볼 수 없습니다.
- JSON이 아니거나, 해석할 수 없거나, 상한을 넘거나, 끝까지 읽지 않은 본문은 저장하지 않고 이유만 남깁니다(fail closed).
- 수집이 실패해도 요청은 그대로 진행됩니다(fail open). 큐가 넘치면 본문부터 버리고 버린 수를 화면에 보여 줍니다.
- 인스펙터 Activity는 `exported=false`라 다른 앱이 열 수 없습니다.
- 디버깅 권한이 있는 앱·계정에서만 쓰세요. 자세한 내용은 [SECURITY.md](SECURITY.md).

## 알려진 제한

- 연결한 `OkHttpClient`의 요청만 기록합니다. WebView, Cronet, `HttpURLConnection`, 다른 앱의 트래픽은 기록하지 않습니다.
- application interceptor 위치에서 기록하므로 OkHttp가 나중에 붙이는 헤더(`Host`, `Accept-Encoding`, 쿠키 저장소의 쿠키)는 요청 헤더에 보이지 않습니다.
- 본문은 JSON만 저장합니다. 호스트가 직접 `Accept-Encoding`을 지정해 압축된 채로 받는 응답은 저장하지 않습니다.
- WebSocket 프레임, 진단 규칙, 내보내기, 재실행, 모킹은 아직 없습니다.
- 성능 목표(요청당 오버헤드 등)는 아직 측정하지 않았습니다.

## 저장소 구성

| 모듈 | 내용 |
| --- | --- |
| `pulse-api` | 설정·모델·`PulseInspector` 계약 (Kotlin JVM, Android·OkHttp 의존 없음) |
| `pulse-security` | 헤더·URL·JSON 마스킹 |
| `pulse-core` | 상한 있는 수집 큐, 저장 스레드, 기록 상태 |
| `pulse-okhttp` | `PulseInterceptor`, `EventListener` 타이밍, 본문 tee |
| `pulse-storage` | Room 스키마, 본문 파일, 보관 정책, 페이지 조회 |
| `pulse-bundle` | `Pulse` 진입점과 `PulseHandle` (debug용) |
| `pulse-noop` | 같은 API의 빈 구현 (release용) |
| `pulse-ui-compose` | 인스펙터 화면 |
| `sample-app` | 앱 안 데모 서버로 여러 경우를 재현하는 예제 |

## 빌드

```bash
./gradlew check :sample-app:assembleDebug :sample-app:assembleRelease
```

`check`는 단위 테스트, Android Lint, ktlint, detekt와 release 런타임에 수집 모듈이 섞이지 않았는지 확인하는 `verifyReleaseClasspath`를 실행합니다. JDK 25(Android Studio 포함 JBR)를 씁니다.

## 로드맵

capture → security → storage → UI(여기까지 기본 흐름) → timing·통계 → 진단 → 내보내기 → 재실행 → 모킹 → 배포 순서로 진행합니다. 변경 내역은 [CHANGELOG.md](CHANGELOG.md), 설계 결정은 [docs/adr](docs/adr)에 있습니다.
