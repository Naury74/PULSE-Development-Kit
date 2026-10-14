# 원격 오류 보고

테스트 기기나 야간 자동 테스트에서 발생한 크래시, 비정상 종료, 처리한 오류, 메인 스레드 멈춤을 팀 서버로 전송합니다.
debug 빌드에서 사용하는 개발용 기능이며, 운영 앱의 크래시 수집 서비스를 대체하지 않습니다.

## 동작 방식

1. 전송 대상 이벤트는 **기기에 저장할 때** 전송 대기열(outbox, Room 테이블)에도 함께 넣습니다. 따라서 앱이 곧바로 종료되더라도 다음 세션에서 전송됩니다.
2. 새 보고가 쌓이면 약 1초 동안 더 모은 뒤 한 번에 `POST`합니다. 전송하지 못한 보고는 `flushIntervalMillis`(기본 30초)마다 다시 확인합니다.
3. 전송에 실패하면 15초부터 두 배씩 늘어나는 백오프(최대 1시간 간격)로 재시도하고, 20회를 넘게 실패한 보고는 폐기합니다.
4. 전송하는 값은 저장 단계에서 이미 마스킹된 값입니다. 요청은 PULSE가 기록하지 않는 별도의 OkHttp 클라이언트로 보냅니다.
5. 기능이 꺼져 있으면 대기열에 넣지 않습니다. 대기열에는 `maxPending`(기본 5,000)개까지 쌓이며, 초과하면 오래된 보고부터 폐기합니다.

## 설정 값을 어디에 둘까

| 값 | 위치 | 이유 |
| --- | --- | --- |
| 수신 주소, 전송할 종류, 배치 크기 | 코드(`PulseConfig.remote`) | 팀이 함께 쓰는 기본값이며 비밀 값이 아닙니다. 변경 내역이 코드 리뷰로 남습니다 |
| 인증 토큰 | `local.properties` 또는 CI 환경 변수 → debug `BuildConfig` | 저장소에 커밋하지 않으며 debug 빌드에만 포함됩니다 |
| 기기별 주소와 토큰, 일시 중지 | 인스펙터 설정 탭 → 원격 오류 보고 | 다시 빌드하지 않고 현장에서 바꿀 수 있습니다. 해당 기기의 앱 전용 저장소에만 저장됩니다 |
| release 빌드 | 설정 불필요 | `pulse-noop`은 보고를 수집하지도 전송하지도 않습니다 |

```kotlin
// app/build.gradle.kts
android {
    buildFeatures { buildConfig = true }
    defaultConfig {
        val token = providers.gradleProperty("pulseRemoteToken")
            .orElse(providers.environmentVariable("PULSE_REMOTE_TOKEN"))
            .getOrElse("")
        buildConfigField("String", "PULSE_REMOTE_TOKEN", "\"$token\"")
    }
}
```

```kotlin
val config = PulseConfig.debugDefaults().newBuilder()
    .remote(
        RemoteReporting(
            endpoint = "https://qa-collector.example.com/pulse",
            headers = mapOf("Authorization" to "Bearer ${BuildConfig.PULSE_REMOTE_TOKEN}"),
            minLogLevel = LogLevel.ERROR,             // ERROR 이상 로그도 전송(기본값은 로그를 보내지 않음)
            attributes = mapOf("run" to (System.getenv("CI_RUN_ID") ?: "local")),
        ),
    )
    .build()
```

| 옵션 | 기본값 | 설명 |
| --- | --- | --- |
| `endpoint` | 필수 | `https://`만 허용합니다. `allowCleartext = true`이면 `http://`도 허용합니다(로컬 개발 서버용) |
| `headers` | 없음 | 모든 요청에 붙일 헤더. PULSE 저장소, 화면, 내보내기 파일에는 남기지 않습니다 |
| `kinds` | CRASH, EXIT, ERROR, MAIN_THREAD_BLOCKED | 전송할 이벤트 종류 |
| `minLogLevel` | `null` | 이 수준 이상의 로그도 전송합니다. `null`이면 로그는 전송하지 않습니다 |
| `networkFailures` | `true` | 5xx 응답과 응답 없이 실패한 요청의 요약을 전송합니다 |
| `batchSize` | 50 | 요청 하나에 담을 최대 보고 수(1..500) |
| `flushIntervalMillis` | 30,000 | 전송하지 못한 보고를 다시 확인하는 간격(5,000 이상) |
| `wifiOnly` | `false` | 데이터 요금이 부과되는 네트워크에서는 전송하지 않습니다 |
| `attributes` | 없음 | 모든 요청의 `app.attributes`에 붙일 값(빌드 이름, 테스트 실행 ID 등) |
| `maxPending` | 5,000 | 대기열에 쌓아 둘 최대 개수 |

코드에 `remote` 설정이 없어도 설정 탭에서 주소를 입력하면 해당 기기에서만 기능을 켤 수 있습니다. 이때는 `https://` 주소만 허용합니다.

## 수신 형식 `pulse.remote.v1`

`POST <endpoint>`, `Content-Type: application/json; charset=utf-8`로 전송합니다. 서버가 2xx로 응답하면 전송된 것으로 보고 대기열에서 삭제합니다.

```json
{
  "schema": "pulse.remote.v1",
  "sentAt": 1760400000000,
  "app": {
    "package": "com.example.app",
    "versionName": "1.4.0",
    "versionCode": 140,
    "sdkInt": 36,
    "device": "Google Pixel 9",
    "attributes": { "run": "nightly-1023" }
  },
  "reports": [
    {
      "type": "event",
      "id": "…", "sessionId": "…", "time": 1760399990000,
      "kind": "CRASH", "level": "ASSERT", "tag": "main",
      "message": "java.lang.IllegalStateException: …",
      "detail": "스택 트레이스(마스킹됨)",
      "screen": "CheckoutActivity"
    },
    {
      "type": "network",
      "id": "…", "sessionId": "…", "time": 1760399980000,
      "method": "GET", "url": "https://api.example.com/orders?token=█",
      "state": "COMPLETED", "statusCode": 503, "durationNanos": 812000000,
      "screen": "OrdersActivity", "label": "orders"
    }
  ]
}
```

값이 없는 필드는 생략됩니다. 응답을 받기 전에 연결이 끊기면 같은 보고가 두 번 전송될 수 있으므로, 받는 쪽에서는 `id`로 중복을 걸러 주세요.

## 샘플 앱으로 확인하기

`sample-app` debug 빌드는 앱에 내장된 데모 서버(`http://127.0.0.1:8787/collect`)로 보고를 전송합니다. "앱 진단 → 원격 보고 왕복"을 누르면 오류를 하나 기록하고, 서버가 받은 보고 수를 보여 줍니다. 인스펙터 설정 탭의 "원격 오류 보고"에서 대기 중인 보고 수, 전송한 보고 수, 마지막 오류를 확인할 수 있습니다.

## 개인정보

보고에는 마스킹된 URL, 로그, 스택 트레이스와 기기 모델명이 포함됩니다. 마스킹은 이름 규칙을 기준으로 값을 찾기 때문에 로그 문장 안의 개인정보는 남을 수 있습니다. 수신 서버는 테스트 데이터를 다루는 서버만 사용하고, 보관 기간을 정해 두세요.
