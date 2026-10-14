<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/logo-dark.png">
    <img src="docs/images/logo-light.png" alt="PULSE" width="420">
  </picture>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/com.naurylab/pulse-bundle"><img src="https://img.shields.io/maven-central/v/com.naurylab/pulse-bundle?label=Maven%20Central&color=3B5BDB" alt="Maven Central"></a>
  <img src="https://img.shields.io/badge/minSdk-26-3DDC84" alt="minSdk 26">
  <img src="https://img.shields.io/badge/OkHttp-5-1971C2" alt="OkHttp 5">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue" alt="License"></a>
</p>

**PULSE**는 개발 중인 Android 앱 안에서 바로 쓰는 디버깅 SDK입니다.
OkHttp 요청·응답, 앱 로그, 크래시와 멈춤, 화면 성능을 한 타임라인에 모아 보여 주고, 테스트하다 문제를 보면 화면 캡처와 근거를 붙여 버그 보고까지 그 자리에서 끝낼 수 있습니다.
토큰·비밀번호 같은 값은 **기기에 저장하기 전에** 가리고, release 빌드에서는 같은 API의 no-op으로 바뀌어 수집 코드가 남지 않습니다.

![PULSE](docs/images/hero.png)

## 기능

- **네트워크**: 연결한 `OkHttpClient`의 요청·응답·실패, 단계별 시간(DNS·연결·TLS·응답 대기), 실제로 오간 헤더, JSON 본문(트리·원문). 결과를 바꾸지 않고 기록만 함
- **통합 타임라인**: 요청·로그·행동·화면 이동·오류·성능을 시간순 한 목록으로. 상세의 "연결된 근거"는 같은 구간·같은 화면·시간상 근접을 등급으로 구분해 보여 줌
- **앱 진단**: 크래시(죽기 직전 저장), 지난 실행의 종료 이유(ANR·네이티브 크래시·메모리 부족), 메인 스레드 멈춤과 스택, 콜드 스타트·화면 로딩·프레임·메모리, 이 앱의 logcat
- **행동 기록**: 탭·뒤로 키 자동 기록(View id·Compose testTag만, 글자는 남기지 않음), `Pulse.action`·`Pulse.mark`·`Pulse.beginSpan`으로 의미 있는 행동과 구간 표시. 화면 위 플로팅 버튼으로도 마커·구간·캡처
- **버그 보고**: 지금 화면을 붙여 보고를 쓰고, 개인정보를 픽셀째 가리고(흐림이 아님), 추천 근거를 골라 재현 패키지(ZIP)로 내보내거나 팀 서버로 전송. 목적지·인증·재시도는 앱이 정함
- **대시보드·이슈**: 성공률·지연 백분위·엔드포인트별 오류, 크래시 없는 실행 비율·날짜별 실행 시간, 원인별로 묶은 이슈, 진단 규칙(느린 요청·오류 몰림·N+1 추정 등), 두 실행 비교
- **날짜·실행으로 보기**: 오늘·어제·7일·30일, 달력 범위, 실행 하나를 골라 모든 탭에 적용(야간 자동 테스트 결과 확인)
- **도구**: 재실행(허용 호스트만), 모의 응답, 네트워크 장애 모사(지연·실패·429/503), 화면 구조(View·Compose), 로컬 데이터(등록한 SharedPreferences·DB, 민감 값 가림), 환경·기능 플래그, WebView 기록
- **내보내기·공유**: HAR 1.2·JSON·CSV·Markdown, cURL, 로그(텍스트·JSON·CSV), 원격 오류 보고. 모두 내보내기 직전에 다시 마스킹
- **release 빌드**: `pulse-noop`이 같은 API를 제공해 코드를 바꾸지 않아도 수집·저장·화면이 빠짐(release APK 증가 약 2KB)

## 화면

인스펙터는 다크·라이트 테마를 따르며, 폴드·태블릿에서는 목록과 상세를 두 칸으로 보여 줍니다.

### 다크 모드

<table>
  <tr>
    <td align="center" width="25%"><img src="docs/images/screen-traffic.png" width="200" alt="트래픽"><br><b>트래픽</b><br>메서드·결과·소요 시간</td>
    <td align="center" width="25%"><img src="docs/images/screen-request.png" width="200" alt="요청 상세"><br><b>요청 상세</b><br>요약·헤더·본문·타이밍</td>
    <td align="center" width="25%"><img src="docs/images/screen-timeline.png" width="200" alt="통합 타임라인"><br><b>통합 타임라인</b><br>요청·행동·오류를 한 목록</td>
    <td align="center" width="25%"><img src="docs/images/screen-overview.png" width="200" alt="네트워크 대시보드"><br><b>네트워크 대시보드</b><br>성공률·지연·엔드포인트</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/screen-app.png" width="200" alt="앱 대시보드"><br><b>앱 대시보드</b><br>크래시 없는 실행·성능</td>
    <td align="center"><img src="docs/images/screen-issues.png" width="200" alt="이슈"><br><b>이슈</b><br>원인별 묶음·네트워크 진단</td>
    <td align="center"><img src="docs/images/screen-report.png" width="200" alt="버그 보고"><br><b>버그 보고</b><br>재현 단계·근거·전송</td>
    <td align="center"><img src="docs/images/screen-editor.png" width="200" alt="화면 가리기"><br><b>화면 가리기</b><br>가리기·화살표·번호</td>
  </tr>
</table>

### 라이트 모드

<table>
  <tr>
    <td align="center" width="25%"><img src="docs/images/screen-traffic-light.png" width="200" alt="트래픽"><br><b>트래픽</b><br>메서드·결과·소요 시간</td>
    <td align="center" width="25%"><img src="docs/images/screen-request-light.png" width="200" alt="요청 상세"><br><b>요청 상세</b><br>요약·헤더·본문·타이밍</td>
    <td align="center" width="25%"><img src="docs/images/screen-timeline-light.png" width="200" alt="통합 타임라인"><br><b>통합 타임라인</b><br>요청·행동·오류를 한 목록</td>
    <td align="center" width="25%"><img src="docs/images/screen-overview-light.png" width="200" alt="네트워크 대시보드"><br><b>네트워크 대시보드</b><br>성공률·지연·엔드포인트</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/screen-app-light.png" width="200" alt="앱 대시보드"><br><b>앱 대시보드</b><br>크래시 없는 실행·성능</td>
    <td align="center"><img src="docs/images/screen-issues-light.png" width="200" alt="이슈"><br><b>이슈</b><br>원인별 묶음·네트워크 진단</td>
    <td align="center"><img src="docs/images/screen-report-light.png" width="200" alt="버그 보고"><br><b>버그 보고</b><br>재현 단계·근거·전송</td>
    <td align="center"><img src="docs/images/screen-editor-light.png" width="200" alt="화면 가리기"><br><b>화면 가리기</b><br>가리기·화살표·번호</td>
  </tr>
</table>

### 폴드·태블릿

<table>
  <tr>
    <td align="center" width="33%"><img src="docs/images/screen-fold-traffic.png" width="280" alt="펼친 폴드에서 트래픽"><br><b>트래픽</b><br>목록과 상세를 두 칸으로</td>
    <td align="center" width="33%"><img src="docs/images/screen-fold-timeline.png" width="280" alt="펼친 폴드에서 타임라인"><br><b>타임라인</b><br>옆 레일 탐색</td>
    <td align="center" width="33%"><img src="docs/images/screen-fold-overview.png" width="280" alt="펼친 폴드에서 대시보드"><br><b>대시보드</b><br>넓은 화면에 맞춘 카드</td>
  </tr>
</table>

화면은 `sample-app` debug 빌드를 Android 16(API 36) 폴더블 에뮬레이터에서 실행한 실제 화면입니다.

## 샘플 앱

소스를 받지 않고도 써 볼 수 있도록 샘플 앱(APK)을 [Releases](https://github.com/Naury74/PULSE-Development-Kit/releases/latest)에 올려 두었습니다. 앱 안의 데모 서버로 성공·오류·시간 초과·큰 본문 같은 요청과 로그·구간·오류·멈춤·크래시를 재현하고, 인스펙터로 바로 확인할 수 있습니다. 인터넷 연결 없이 동작합니다.

- [샘플 앱 내려받기 (pulse-sample.apk)](https://github.com/Naury74/PULSE-Development-Kit/releases/latest/download/pulse-sample.apk)
- Android 8.0(API 26) 이상에서 설치할 수 있습니다. 처음 설치할 때 "출처를 알 수 없는 앱" 설치를 허용해야 합니다.
- 설치하면 런처에 **PULSE 샘플**(데모 앱)과 **PULSE**(인스펙터 바로 열기) 아이콘이 생깁니다.

## 설치

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    debugImplementation("com.naurylab:pulse-bundle:0.1.0-alpha01")      // 수집·저장
    debugImplementation("com.naurylab:pulse-ui-compose:0.1.0-alpha01")  // 인스펙터 화면
    releaseImplementation("com.naurylab:pulse-noop:0.1.0-alpha01")      // 같은 API, 아무것도 하지 않음
}
```

- minSdk 26, OkHttp 5, Kotlin 2.2 이상이 필요합니다. 앱이 Compose를 쓰지 않아도 인스펙터를 쓸 수 있습니다.
- R8/ProGuard 규칙을 따로 추가할 필요가 없습니다.

| 모듈 | 내용 |
| --- | --- |
| `pulse-bundle` | 수집·저장·분석 전체와 `Pulse`·`PulseHandle` 진입점(debug용) |
| `pulse-ui-compose` | 인스펙터 화면, 런처 아이콘·알림·플로팅 버튼 |
| `pulse-noop` | release용. `pulse-bundle`과 같은 API, 아무것도 하지 않음 |
| `pulse-export` | (선택) HAR·JSON·CSV·재현 패키지를 코드에서 직접 만들 때 |

## 사용법

### 붙이기

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
```

이것만으로 요청 기록, 크래시·종료 이유·멈춤, 성능, 이 앱의 logcat, 탭·뒤로 키가 기록됩니다. 인스펙터는 런처의 **PULSE** 아이콘, 알림, 또는 코드로 엽니다.

```kotlin
Pulse.open(context)   // 화면 모듈이 없거나 release(no-op)면 false
```

### 행동·구간·로그 남기기

```kotlin
Pulse.action("checkout.submit", mapOf("items" to "3"))   // 의미 있는 행동. 버그 보고의 재현 단계에 쓰임
val span = Pulse.beginSpan("결제 흐름")                   // 이 사이에 시작한 요청은 구간과 이어짐
// …
span?.let(Pulse::endSpan)
Pulse.log(LogLevel.WARN, "Checkout", "쿠폰 만료 임박")
Pulse.recordError(error, "수량 파싱 실패")
Pulse.setFlag("server", "staging")                        // 환경·실험 값. 바뀐 이력이 남음
```

release에서는 모두 아무것도 하지 않으므로 지우지 않아도 됩니다.

### 버그 보고 보내기

```kotlin
val qa = ReportDestination(
    id = "qa",
    endpoint = "https://qa-collector.example.com/reports",
    allowedHosts = setOf("qa-collector.example.com"),
    headerProvider = { _, _ -> mapOf("Authorization" to "Bearer ${tokenStore.freshToken()}") },
)
PulseConfig.debugDefaults().newBuilder()
    .reporting(Reporting(manual = qa, crash = qa))
    .build()
```

목적지를 정하지 않으면 보고는 기기에 저장하고 ZIP으로 내보내기만 합니다. Retrofit 등 앱의 통신 모듈로 보내려면 `ReportTransport`를 넘깁니다. 자세한 내용은 [docs/bug-reports.md](docs/bug-reports.md).

Compose 화면 안에 넣으려면 `PulseInspectorScreen(inspector = pulse, onClose = { … })`, Java는 [docs/java.md](docs/java.md)를 보세요.

## 문서

| 문서 | 내용 |
| --- | --- |
| [getting-started.md](docs/getting-started.md) | 설치, 초기화, 기존 EventListener와 함께 쓰기, 화면 열기 |
| [configuration.md](docs/configuration.md) | `PulseConfig`의 모든 설정과 기본값, 화면 리소스 |
| [app-monitoring.md](docs/app-monitoring.md) | 로그·크래시·성능·행동, 타임라인, 실행 비교, 화면 구조, 로컬 데이터, WebView |
| [bug-reports.md](docs/bug-reports.md) | 버그 보고·화면 가리기, 재현 패키지와 뷰어, 보고 목적지, 장애 모사 |
| [remote-reporting.md](docs/remote-reporting.md) | 원격 오류 보고 설정과 받는 쪽 형식 |
| [capture-semantics.md](docs/capture-semantics.md) | 무엇을 언제 기록하는지, 본문 상태, 타이밍의 의미 |
| [privacy-and-export.md](docs/privacy-and-export.md) | 마스킹 규칙, 저장 위치, 내보내기 |
| [replay-and-mocks.md](docs/replay-and-mocks.md) | 재실행과 모의 응답의 안전 장치 |
| [java.md](docs/java.md) | Java에서 쓰기 |
| [architecture.md](docs/architecture.md) | 모듈 구성과 데이터 흐름 |
| [security-audit.md](docs/security-audit.md) | 위협별 대응과 확인하는 테스트 |

## 알려진 제한

- 연결한 `OkHttpClient`의 요청만 기록합니다. WebView 네트워크, Cronet, `HttpURLConnection`, 다른 앱의 트래픽은 기록하지 않습니다(WebView는 페이지 이동·오류·콘솔만).
- 본문은 기본으로 JSON만 저장합니다(form·텍스트는 `BodyFormats`로 켬). multipart·바이너리와 WebSocket 프레임은 기록하지 않습니다.
- 마스킹은 이름 규칙(헤더·쿼리·JSON 키) 기반입니다. 이름이 평범한 필드의 개인정보는 `withSensitiveNames`로 더해야 합니다.
- 네이티브 크래시와 ANR은 그 자리에서 잡지 못하고 다음 실행 때 시스템 기록(Android 11+)으로 남깁니다.
- 저장 데이터는 암호화하지 않습니다(앱 전용·백업 제외 폴더에 저장). 성능은 JVM에서만 쟀고 Android 기기 측정은 아직입니다.
- 0.x 동안 공개 API가 바뀔 수 있습니다.

## 빌드

```bash
./gradlew check :sample-app:assembleDebug :sample-app:assembleRelease
```

`check`는 단위·Robolectric 테스트, Android Lint, ktlint, detekt, 공개 API 검사(`checkKotlinAbi`, `verifyNoopApi`), release 런타임 검사(`verifyReleaseClasspath`)를 실행합니다. 기여 방법은 [CONTRIBUTING.md](CONTRIBUTING.md), 변경 내역은 [CHANGELOG.md](CHANGELOG.md), 보안 신고는 [SECURITY.md](SECURITY.md).

## 라이선스

[Apache License 2.0](LICENSE)
