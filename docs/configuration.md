# 설정

`PulseConfig.Builder()`로 만들며 만든 뒤에는 바뀌지 않습니다. 잘못된 값은 `build()`나 각 설정 클래스 생성자에서 `IllegalArgumentException`을 던집니다.

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `captureMode` | `METADATA_ONLY` | `METADATA_AND_SAFE_BODIES`면 마스킹한 JSON 본문도 저장 |
| `limits` | 요청 128 KB, 응답 512 KB | 본문 상한(최대 4 MB). 넘으면 본문 전체를 저장하지 않음 |
| `retention` | 10,000건, 7일, 본문 100 MB | 셋 다 지키도록 오래된 것부터 지움 |
| `redaction` | `RedactionPolicy.strict()` | 가릴 헤더·쿼리·민감 단어. 아래 참고 |
| `queueCapacity` | 2,000 | 저장을 기다리는 이벤트 수(16..100,000). 넘치면 본문부터 버림 |
| `startRecording` | `true` | `false`면 멈춘 상태로 시작 |
| `insights` | `InsightThresholds()` | 진단 규칙 임계값 |
| `replay` | `null`(꺼짐) | `ReplayPolicy`를 넣어야 재실행 가능 |
| `filter` | `CaptureFilter.ALL` | 기록할 호스트·경로. 걸러진 요청은 그대로 보내고 기록하지 않음 |
| `context` | 화면·앱 상태·네트워크 모두 켬 | 요청을 시작할 때의 맥락을 함께 기록 |
| `endpoints` | 자동 템플릿 켬 | 통계에서 `/users/42`를 `/users/{id}`로 묶는 방법 |
| `bodyFormats` | `BodyFormats.JSON_ONLY` | JSON 말고 form·텍스트 본문도 저장할지 |

## 기록할 요청 거르기

```kotlin
CaptureFilter(
    onlyHosts = setOf("*.example.com"),          // 비어 있지 않으면 여기에 맞는 호스트만
    ignoredHosts = setOf("analytics.example.com"), // onlyHosts보다 우선
    ignoredPathPrefixes = setOf("/health", "/metrics"),
)
```

호스트 패턴은 정확한 이름이나 `*.도메인`(그 도메인 자신 포함)입니다. 재실행·모의 응답처럼 PULSE가 만든 요청은 필터와 상관없이 기록합니다.

## 요청 맥락과 라벨

`ContextCapture(screen = true, appState = true, networkType = true)`

- **screen**: 요청을 시작할 때 앞에 있던 Activity의 짧은 이름. Compose 내비게이션 안의 화면 이름은 알 수 없습니다.
- **appState**: 앱이 화면에 보였는지(포그라운드) 백그라운드였는지. 초기화 전에 이미 열린 화면은 다음 화면 전환부터 알 수 있습니다.
- **networkType**: Wi-Fi·셀룰러·VPN 등. `pulse-bundle`이 debug 빌드에 `ACCESS_NETWORK_STATE` 권한을 더합니다.
- 값은 콜백으로 미리 알아 두므로 요청마다 시스템 서비스를 부르지 않습니다.

기능 단위로 나눠 보려면 요청에 라벨을 붙입니다. `PulseLabel`은 `pulse-noop`에도 있어 출시 코드에 남겨도 됩니다. 라벨은 마스킹하지 않으니 비밀을 넣지 마세요.

```kotlin
Request.Builder().url(url).tag(PulseLabel::class.java, PulseLabel("checkout")).build()
```

## 엔드포인트 묶기

`EndpointGrouping(autoTemplates = true, templates = listOf("/shops/{shop}/items"))`

직접 정한 템플릿을 먼저 쓰고(`{이름}`은 경로 조각 하나), 맞는 것이 없으면 숫자·UUID·긴 16진수 조각을 `{id}`로 묶습니다. 기록은 바꾸지 않고 통계에서만 묶습니다.

## 본문 형식

`BodyFormats(forms = false, text = false)` — `captureMode`가 `METADATA_AND_SAFE_BODIES`일 때만 의미가 있습니다.

- **forms**: `application/x-www-form-urlencoded`. 민감한 이름의 값을 가리고, 나머지 값 안의 URL 쿼리와 `name=value`도 가립니다.
- **text**: `text/*`와 XML. 구조를 모르므로 URL 쿼리와 `name=value` 형태의 비밀만 가립니다. 문장 속 비밀이나 `<password>` 같은 XML 요소는 찾지 못하니 비밀을 담지 않는 API에만 켜세요.

## 마스킹

```kotlin
RedactionPolicy.strict()
    .withHeaders("X-Tenant-Id")          // 이름이 정확히 같은 헤더
    .withQueryParameters("ticket")       // 이름이 정확히 같은 쿼리 파라미터
    .withSensitiveNames("card_number")   // 헤더·쿼리·JSON 키에서 단어로 찾음
```

기본 정책: 헤더 `authorization`, `proxy-authorization`, `cookie`, `set-cookie`, `x-api-key` / 쿼리 `code`, `sig` / 민감 단어 token, password, passwd, pwd, secret, api_key, apikey, session, auth, authorization, cookie, credential(s), private_key, signature, otp, pin, ssn.
민감 단어는 이름을 `_`, `-`, `.`, 공백, camelCase 경계로 나눈 단어 묶음과 비교합니다(`token`은 `refresh_token`, `X-Auth-Token`, `accessToken`에 해당).

## 진단 임계값

`InsightThresholds(slowRequestMillis = 1_500, errorBurstCount = 5, errorBurstWindowMillis = 60_000, oversizedResponseBytes = 1 MiB, nPlusOneCount = 8, nPlusOneWindowMillis = 2_000, cacheRepeatCount = 5, cacheWindowMillis = 5분)`

## 재실행

`ReplayPolicy(allowedHosts, allowCleartext = false, maxRequestBodyBytes = 256 KB, timeoutMillis = 15_000, maxRedirects = 5)` — [replay-and-mocks.md](replay-and-mocks.md).
