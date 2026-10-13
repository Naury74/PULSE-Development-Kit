# 시작하기

## 1. 의존성

배포 전에는 이 저장소에서 `./gradlew publishToMavenLocal`을 실행해 로컬 Maven(`~/.m2`)에 올리고, 앱의 `settings.gradle.kts` 저장소 목록에 `mavenLocal()`을 더합니다.

```kotlin
dependencies {
    debugImplementation("com.naurylab:pulse-bundle:0.1.0-alpha01")
    debugImplementation("com.naurylab:pulse-ui-compose:0.1.0-alpha01") // 화면이 필요할 때
    releaseImplementation("com.naurylab:pulse-noop:0.1.0-alpha01")
}
```

- `pulse-bundle`과 `pulse-noop`은 같은 패키지·이름·시그니처(`com.naury.pulse.api.Pulse`, `PulseHandle`)를 가집니다. 앱 코드는 한 벌로 두 빌드를 컴파일합니다.
- 요구 사항: minSdk 26, OkHttp 5.x, Kotlin 2.2 이상으로 빌드하는 앱.
- 저장소의 `sample-app`이 같은 구성을 씁니다. 새 프로젝트에서 위 좌표만으로 debug·release가 빌드되고, release APK에 수집 코드와 인스펙터 Activity가 들어가지 않는 것을 확인했습니다.

## 2. 초기화

`Application.onCreate`에서 한 번 부릅니다. 메인 스레드에서 불러도 됩니다(디스크 정리는 백그라운드).

```kotlin
val pulse = Pulse.initialize(this, PulseConfig.debugDefaults())
```

- 같은 설정으로 다시 부르면 기존 핸들을, 다른 설정이면 `PulseConfigurationException`을 돌려줍니다.
- `PulseConfig.defaults()`는 본문을 저장하지 않습니다. `debugDefaults()`는 마스킹한 JSON 본문을 저장합니다.
- DI를 쓰는 앱은 돌려받은 `PulseHandle`을 그대로 주입하면 됩니다. 다른 곳에서는 `Pulse.current()`로 찾을 수 있습니다.

## 3. OkHttpClient 연결

```kotlin
OkHttpClient.Builder()
    .addInterceptor(pulse.interceptor())
    .eventListenerFactory(pulse.eventListenerFactory(existingFactory)) // 기존 팩토리가 없으면 인자 없이
    .build()
```

- `interceptor()`를 다른 interceptor보다 **먼저** 넣으면 앱이 보낸 그대로를, 나중에 넣으면 앞선 interceptor가 바꾼 요청을 기록합니다.
- `eventListenerFactory()`를 붙이지 않으면 단계 타이밍만 빠지고 나머지는 기록됩니다.
- 기록할 클라이언트마다 붙입니다. 붙이지 않은 클라이언트의 요청은 기록되지 않습니다.

## 4. 화면 열기

```kotlin
Pulse.open(context)                                  // 별도 Activity(exported=false)
PulseInspectorScreen(inspector = pulse, onClose = {}) // 앱의 Compose 화면 안에
```

화면 모듈이 없거나 release(no-op)면 `open`은 `false`를 돌려주고 아무것도 하지 않습니다.

## 5. 멈추기·지우기·닫기

`pulse.pause()`, `pulse.resume()`, `pulse.clear()`(저장된 기록과 본문 삭제), `pulse.close()`(설정을 바꿔 다시 초기화할 때).
