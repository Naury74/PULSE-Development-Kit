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
