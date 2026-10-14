# 시작하기

## 1. 의존성

Maven Central에 배포되어 있습니다. 앱의 `settings.gradle.kts` 저장소 목록에 `mavenCentral()`만 있으면 됩니다.

```kotlin
dependencies {
    debugImplementation("com.naurylab:pulse-bundle:0.1.0-alpha01")
    debugImplementation("com.naurylab:pulse-ui-compose:0.1.0-alpha01") // 인스펙터 화면이 필요할 때
    releaseImplementation("com.naurylab:pulse-noop:0.1.0-alpha01")
}
```

- `pulse-bundle`과 `pulse-noop`은 패키지, 클래스 이름, 시그니처가 같습니다(`com.naury.pulse.api.Pulse`, `PulseHandle`). 따라서 같은 앱 코드로 debug와 release 빌드를 모두 컴파일할 수 있습니다.
- 요구 사항: minSdk 26, OkHttp 5.x, Kotlin 2.2 이상
- 저장소의 `sample-app`도 같은 구성을 사용합니다. 새 프로젝트에 위 의존성만 추가해 debug와 release가 모두 빌드되고, release APK에는 수집 코드와 인스펙터 Activity가 포함되지 않는 것을 확인했습니다.

## 2. 초기화

`Application.onCreate`에서 한 번 호출합니다. 디스크 정리는 백그라운드에서 하므로 메인 스레드에서 호출해도 됩니다.

```kotlin
val pulse = Pulse.initialize(this, PulseConfig.debugDefaults())
```

- 같은 설정으로 다시 호출하면 기존 핸들을 반환하고, 다른 설정으로 호출하면 `PulseConfigurationException`이 발생합니다.
- `PulseConfig.defaults()`는 본문을 저장하지 않습니다. `debugDefaults()`는 마스킹한 JSON 본문을 저장하고, 이 앱의 logcat과 탭, 뒤로 가기도 기록합니다.
- DI를 쓰는 앱은 반환된 `PulseHandle`을 그대로 주입하면 됩니다. 그 밖의 곳에서는 `Pulse.current()`로 가져올 수 있습니다.

## 3. OkHttpClient에 연결

```kotlin
OkHttpClient.Builder()
    .addInterceptor(pulse.interceptor())
    .eventListenerFactory(pulse.eventListenerFactory(existingFactory)) // 기존 팩토리가 없으면 인자 없이 호출
    .build()
```

- `interceptor()`를 다른 인터셉터보다 **먼저** 추가하면 앱이 보낸 요청을 그대로 기록하고, 나중에 추가하면 앞선 인터셉터가 수정한 요청을 기록합니다.
- `eventListenerFactory()`를 연결하지 않으면 단계별 타이밍만 빠지고 나머지는 모두 기록됩니다.
- 기록하려는 클라이언트마다 각각 연결해야 합니다. 연결하지 않은 클라이언트의 요청은 기록되지 않습니다.

## 4. 인스펙터 열기

```kotlin
Pulse.open(context)                                  // 별도 Activity로 열기(exported=false)
PulseInspectorScreen(inspector = pulse, onClose = {}) // 앱의 Compose 화면 안에 넣기
```

UI 모듈이 없거나 release(no-op) 빌드이면 `open`은 아무 동작 없이 `false`를 반환합니다.

앱에 별도 버튼을 만들지 않아도 `pulse-ui-compose`를 추가하면 debug 빌드에 진입점 두 개가 생깁니다.

- **런처 아이콘**: 앱 목록에 PULSE 아이콘이 따로 생깁니다. 누르면 인스펙터가 앱과 별도의 태스크로 열립니다.
- **알림**: 최근 1시간 동안의 요청 수와 오류 수, 최근 요청 5개를 보여 주며 일시 중지, 재개, 지우기 버튼을 제공합니다. 알림을 누르면 인스펙터가 열립니다.
  - Android 13 이상에서는 알림 권한이 필요합니다. 인스펙터의 트래픽 탭에서 한 번 권한을 요청하며, "나중에"를 누르면 다시 묻지 않습니다.
  - 잠금 화면에서는 요청 경로를 숨기고 개수만 표시합니다.

끄려면 앱의 `res/values`에 같은 이름의 리소스를 정의합니다. debug 소스셋에 두면 됩니다.

```xml
<bool name="pulse_launcher_icon_enabled">false</bool>
<bool name="pulse_notification_enabled">false</bool>
```

## 요청 공유

요청 상세 화면의 **공유**를 누르면 요청 하나를 Markdown 문서로 만들어 공유 시트로 보냅니다. 결과, URL, 소요 시간, 헤더, 본문(8KB까지), cURL 명령이 들어가며 모두 저장 전에 마스킹된 값입니다. QA가 이슈에 첨부하거나 메신저로 개발자에게 전달할 때 유용합니다.

## 5. 일시 중지, 삭제, 종료

- `pulse.pause()`, `pulse.resume()`: 기록을 일시 중지하거나 다시 시작합니다.
- `pulse.clear()`: 저장된 기록과 본문을 삭제합니다.
- `pulse.close()`: 설정을 바꿔 다시 초기화할 때 호출합니다.
