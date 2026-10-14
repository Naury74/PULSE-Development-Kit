# 설정

`PulseConfig`는 `PulseConfig.Builder()`로 만들며, 한 번 만든 뒤에는 변경할 수 없습니다. 잘못된 값을 넣으면 `build()`나 각 설정 클래스의 생성자에서 `IllegalArgumentException`이 발생합니다.

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `captureMode` | `METADATA_ONLY` | `METADATA_AND_SAFE_BODIES`로 설정하면 마스킹한 JSON 본문도 저장 |
| `limits` | 요청 128 KB, 응답 512 KB | 본문 크기 상한(최대 4 MB). 상한을 넘으면 본문을 아예 저장하지 않음 |
| `retention` | 10,000건, 이벤트 50,000개, 7일, 본문 100 MB | 모든 한도를 지키도록 오래된 기록부터 삭제 |
| `redaction` | `RedactionPolicy.strict()` | 마스킹할 헤더, 쿼리 파라미터, 민감 단어. 아래 참고 |
| `queueCapacity` | 2,000 | 저장 대기열에 쌓을 수 있는 이벤트 수(16..100,000). 가득 차면 본문부터 버림 |
| `startRecording` | `true` | `false`면 기록을 일시 중지한 상태로 시작 |
| `insights` | `InsightThresholds()` | 진단 규칙의 임계값 |
| `replay` | `null`(꺼짐) | `ReplayPolicy`를 지정해야 재실행을 쓸 수 있음 |
| `filter` | `CaptureFilter.ALL` | 기록할 호스트와 경로. 필터에서 제외된 요청은 정상적으로 전송되고 기록만 하지 않음 |
| `context` | 화면, 앱 상태, 네트워크 모두 켬 | 요청이 시작될 때의 컨텍스트를 함께 기록 |
| `endpoints` | 자동 템플릿 켬 | 통계에서 `/users/42`를 `/users/{id}`로 묶는 방식 |
| `bodyFormats` | `BodyFormats.JSON_ONLY` | JSON 외에 form, 텍스트 본문도 저장할지 여부 |
| `monitoring` | 크래시, 종료 이유, 메인 스레드 멈춤(2초), 성능, 메모리 켬. logcat 끔 | 앱 진단 수집. `debugDefaults()`는 logcat도 켬. [app-monitoring.md](app-monitoring.md) |
| `remote` | `null`(꺼짐) | 크래시와 오류를 서버로 전송. [remote-reporting.md](remote-reporting.md) |
| `reporting` | `null`(내보내기만) | 버그 보고 종류별 전송 대상. [bug-reports.md](bug-reports.md) |
| `screenshots` | `ScreenshotPolicy()` | 스크린샷을 찍지 않을 화면. `FLAG_SECURE` 화면은 항상 제외 |
| `chaos` | `null`(꺼짐) | 네트워크 장애 모사를 허용할 호스트 |
| `dataInspection` | `null`(숨김) | 인스펙터에서 읽기 전용으로 볼 SharedPreferences와 DB. [app-monitoring.md](app-monitoring.md) |

## 기록할 요청 필터링

```kotlin
CaptureFilter(
    onlyHosts = setOf("*.example.com"),          // 비어 있지 않으면 여기에 해당하는 호스트만 기록
    ignoredHosts = setOf("analytics.example.com"), // onlyHosts보다 우선 적용
    ignoredPathPrefixes = setOf("/health", "/metrics"),
)
```

호스트 패턴에는 정확한 호스트 이름이나 `*.도메인` 형식을 쓸 수 있고, `*.도메인`은 해당 도메인 자체도 포함합니다. 재실행이나 모의 응답처럼 PULSE가 만든 요청은 필터와 관계없이 기록합니다.

## 요청 컨텍스트와 라벨

`ContextCapture(screen = true, appState = true, networkType = true)`

- **screen**: 요청이 시작될 때 포그라운드에 있던 Activity의 짧은 이름입니다. Compose 내비게이션 안의 개별 화면 이름은 알 수 없습니다.
- **appState**: 앱이 포그라운드였는지 백그라운드였는지를 기록합니다. 초기화 전에 이미 열려 있던 화면은 다음 화면 전환부터 상태를 알 수 있습니다.
- **networkType**: Wi-Fi, 셀룰러, VPN 등 네트워크 종류입니다. 이를 위해 `pulse-bundle`이 debug 빌드에 `ACCESS_NETWORK_STATE` 권한을 추가합니다.
- 이 값들은 콜백으로 미리 받아 두므로 요청마다 시스템 서비스를 호출하지 않습니다.

기능 단위로 나눠 보려면 요청에 라벨을 붙입니다. `PulseLabel`은 `pulse-noop`에도 있으므로 release 코드에 그대로 남겨 둬도 됩니다. 라벨은 마스킹하지 않으니 민감한 값을 넣지 마세요.

```kotlin
Request.Builder().url(url).tag(PulseLabel::class.java, PulseLabel("checkout")).build()
```

## 엔드포인트 그룹화

`EndpointGrouping(autoTemplates = true, templates = listOf("/shops/{shop}/items"))`

직접 지정한 템플릿을 먼저 적용합니다. 템플릿의 `{이름}`은 경로 세그먼트 하나에 대응합니다. 맞는 템플릿이 없으면 숫자, UUID, 긴 16진수 세그먼트를 `{id}`로 묶습니다. 저장된 기록은 그대로 두고 통계에서만 묶어서 보여 줍니다.

## 본문 형식

`BodyFormats(forms = false, text = false)`는 `captureMode`가 `METADATA_AND_SAFE_BODIES`일 때만 적용됩니다.

- **forms**: `application/x-www-form-urlencoded` 본문입니다. 이름이 민감한 필드의 값을 마스킹하고, 나머지 값 안에 들어 있는 URL 쿼리와 `name=value` 형태도 마스킹합니다.
- **text**: `text/*`와 XML 본문입니다. 구조를 해석하지 않으므로 URL 쿼리와 `name=value` 형태의 민감한 값만 마스킹합니다. 문장 안에 섞인 민감한 값이나 `<password>` 같은 XML 요소는 찾지 못하므로, 민감한 값을 주고받지 않는 API에만 켜세요.

## 마스킹

```kotlin
RedactionPolicy.strict()
    .withHeaders("X-Tenant-Id")          // 이름이 정확히 같은 헤더
    .withQueryParameters("ticket")       // 이름이 정확히 같은 쿼리 파라미터
    .withSensitiveNames("card_number")   // 헤더, 쿼리, JSON 키 이름에서 단어 단위로 찾음
```

기본 정책은 다음 항목을 마스킹합니다.

- 헤더: `authorization`, `proxy-authorization`, `cookie`, `set-cookie`, `x-api-key`
- 쿼리 파라미터: `code`, `sig`
- 민감 단어: token, password, passwd, pwd, secret, api_key, apikey, session, auth, authorization, cookie, credential(s), private_key, signature, otp, pin, ssn

민감 단어는 이름을 `_`, `-`, `.`, 공백, camelCase 경계로 나눈 단어들과 비교합니다. 예를 들어 `token`은 `refresh_token`, `X-Auth-Token`, `accessToken`에 모두 걸립니다.

## 진단 임계값

`InsightThresholds(slowRequestMillis = 1_500, errorBurstCount = 5, errorBurstWindowMillis = 60_000, oversizedResponseBytes = 1 MiB, nPlusOneCount = 8, nPlusOneWindowMillis = 2_000, cacheRepeatCount = 5, cacheWindowMillis = 5분)`

## 재실행

`ReplayPolicy(allowedHosts, allowCleartext = false, maxRequestBodyBytes = 256 KB, timeoutMillis = 15_000, maxRedirects = 5)`

자세한 내용은 [replay-and-mocks.md](replay-and-mocks.md)를 참고하세요.

## 앱 진단

`AppMonitoring(crashes = true, exitReasons = true, logcat = false, logcatMinLevel = DEBUG, logcatIgnoredTags = emptySet(), mainThreadBlockMillis = 2_000, performance = true, memoryIntervalMillis = 30_000, screenshotOnCrash = false, interactions = false)`. `debugDefaults()`는 여기서 `logcat`과 `interactions`를 추가로 켭니다. 자세한 내용은 [app-monitoring.md](app-monitoring.md)를 참고하세요.

## 원격 오류 보고

`RemoteReporting(endpoint, headers = emptyMap(), kinds = CRASH·EXIT·ERROR·MAIN_THREAD_BLOCKED, minLogLevel = null, networkFailures = true, batchSize = 50, flushIntervalMillis = 30_000, wifiOnly = false, allowCleartext = false, attributes = emptyMap(), maxPending = 5_000)`

각 값을 어디에 설정할지와 수신 서버가 받는 형식은 [remote-reporting.md](remote-reporting.md)를 참고하세요.

## 화면 리소스

호스트 앱의 `res/values`에 같은 이름의 리소스를 정의하면 값을 바꿀 수 있습니다.

| 리소스 | 기본값 | 설명 |
| --- | --- | --- |
| `pulse_launcher_icon_enabled` | `true` | debug 빌드에 PULSE 런처 아이콘 표시 |
| `pulse_notification_enabled` | `true` | 요청 수와 오류 수를 보여 주는 알림 표시 |
| `pulse_inspector_secure` | `false` | 인스펙터 화면이 스크린샷, 화면 녹화, 최근 앱 미리보기에 나오지 않도록 함(`FLAG_SECURE`) |
| `pulse_overlay_enabled` | `false` | 앱 화면 위에 플로팅 버튼 표시(마커, 스팬, 화면 캡처). 설정 탭에서 기기별로 바꿀 수 있음 |
