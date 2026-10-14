# 원격 오류 보고

테스트 기기나 야간 자동 테스트에서 난 크래시·비정상 종료·처리한 오류·메인 스레드 멈춤을 팀 서버로 보냅니다.
debug 빌드에서 쓰는 개발용 기능입니다. 운영 앱의 크래시 수집 서비스를 대신하지 않습니다.

## 동작

1. 보낼 이벤트는 **기기에 저장할 때** 보낼 목록(outbox, Room 표)에도 함께 넣습니다. 앱이 바로 죽어도 다음 실행 때 보냅니다.
2. 새 보고가 쌓이면 약 1초 동안 더 모았다가 묶어서 `POST`합니다. 보내지 못한 것은 `flushIntervalMillis`(기본 30초)마다 다시 확인합니다.
3. 실패하면 15초부터 두 배씩 늘려 최대 1시간 간격으로 다시 보내고, 20번 넘게 실패한 보고는 버립니다.
4. 보내는 값은 저장 전에 이미 마스킹한 값입니다. 요청은 PULSE가 기록하지 않는 별도 OkHttp 클라이언트로 보냅니다.
5. 꺼져 있으면 보낼 목록에 넣지 않습니다. 쌓아 두는 개수는 `maxPending`(기본 5,000)까지이고 넘치면 오래된 것부터 버립니다.

## 설정 — 어디에 무엇을 두나

| 값 | 둘 곳 | 이유 |
| --- | --- | --- |
| 받는 주소, 보낼 종류, 묶음 크기 | 코드(`PulseConfig.remote`) | 팀이 같이 쓰는 기본값이고 비밀이 아님. 리뷰로 바뀐 내역이 남음 |
| 인증 토큰 | `local.properties` 또는 CI 환경 변수 → debug `BuildConfig` | 저장소에 넣지 않음. debug 빌드에만 들어감 |
| 기기마다 다른 주소·토큰, 잠깐 끄기 | 인스펙터 설정 탭 → 원격 오류 보고 | 다시 빌드하지 않고 현장에서 바꿈. 이 기기의 앱 전용 저장소에만 남음 |
| release 빌드 | 아무것도 하지 않음 | `pulse-noop`은 보고를 모으지도 보내지도 않음 |

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
            minLogLevel = LogLevel.ERROR,             // ERROR 이상 로그도 보냄(기본은 보내지 않음)
            attributes = mapOf("run" to (System.getenv("CI_RUN_ID") ?: "local")),
        ),
    )
    .build()
```

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `endpoint` | 필수 | `https://`만. `allowCleartext = true`면 `http://`도(로컬 개발 서버용) |
| `headers` | 없음 | 요청마다 붙일 헤더. PULSE 저장소·화면·내보내기에 남기지 않음 |
| `kinds` | CRASH, EXIT, ERROR, MAIN_THREAD_BLOCKED | 보낼 이벤트 종류 |
| `minLogLevel` | `null` | 이 수준 이상의 로그도 보냄. `null`이면 로그는 보내지 않음 |
| `networkFailures` | `true` | 5xx 응답과 응답 없이 실패한 요청의 요약 |
| `batchSize` | 50 | 한 요청에 담을 최대 보고 수(1..500) |
| `flushIntervalMillis` | 30,000 | 보내지 못한 보고를 다시 확인하는 간격(5,000 이상) |
| `wifiOnly` | `false` | 요금이 드는 네트워크에서는 보내지 않음 |
| `attributes` | 없음 | 모든 요청의 `app.attributes`에 붙일 값(빌드 이름, 테스트 실행 ID) |
| `maxPending` | 5,000 | 쌓아 둘 최대 개수 |

코드에 `remote`가 없어도 설정 탭에서 주소를 넣으면 그 기기에서만 켤 수 있습니다. 이때 주소는 `https://`만 받습니다.

## 받는 쪽 형식 `pulse.remote.v1`

`POST <endpoint>`, `Content-Type: application/json; charset=utf-8`. 2xx로 답하면 보낸 것으로 보고 목록에서 지웁니다.

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

값이 없는 필드는 빠집니다. 같은 보고가 두 번 갈 수 있으니(응답을 받기 전에 연결이 끊긴 경우) 받는 쪽은 `id`로 중복을 거르세요.

## 샘플 앱으로 확인하기

`sample-app` debug 빌드는 앱 안 데모 서버(`http://127.0.0.1:8787/collect`)로 보냅니다. "앱 진단 → 원격 보고 왕복"을 누르면 오류를 하나 기록하고 서버가 받은 보고 수를 보여 줍니다. 인스펙터 설정 탭의 "원격 오류 보고"에서 대기·보낸 수와 마지막 오류를 볼 수 있습니다.

## 개인정보

보고에는 마스킹한 URL·로그·스택 트레이스와 기기 모델이 들어갑니다. 마스킹은 이름 규칙으로 찾으므로 로그 문장 속 개인정보는 남을 수 있습니다. 받는 서버는 테스트 데이터를 다루는 곳만 쓰고, 보관 기간을 정해 두세요.
