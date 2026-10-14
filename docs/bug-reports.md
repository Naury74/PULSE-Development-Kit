# 버그 보고, 재현 패키지, 전송

테스트 중 문제를 발견하면 그 자리에서 버그 보고를 작성할 수 있습니다. 화면 캡처의 개인정보를 마스킹하고, 관련 요청과 오류, 사용자 행동을 증거로 첨부한 뒤 팀 서버로 전송하거나 ZIP 파일로 내보냅니다.

## 보고 작성하기

| 여는 위치 | 자동으로 첨부되는 항목 |
| --- | --- |
| 플로팅 PULSE 버튼 → 버그 보고 | 현재 화면 캡처(메모리에만 보관), 캡처 시각 5분 전부터 30초 후까지의 관련 항목 |
| 이벤트 상세(로그, 이슈, 타임라인) → 이걸로 보고 | 해당 이벤트와 그 시각 앞뒤의 관련 항목. 크래시나 종료 이유에서 열면 보고 종류가 CRASH가 됩니다 |
| 설정 → 버그 보고 → 새 버그 보고 | 현재 시각 기준의 관련 항목 |

작성 화면에서 다음 순서로 진행합니다.

1. 제목, 심각도(치명, 중요, 보통, 사소), 설명, 재현 단계, 기대한 결과, 실제 결과를 입력합니다. 재현 단계는 증거에 포함된 행동(`Pulse.action`), 화면 이동, 마커로 초안이 채워집니다.
2. **화면 캡처 편집**: 가리기, 사각형, 화살표, 펜, 번호 도구를 쓸 수 있습니다. 가리기는 저장되는 이미지의 픽셀을 검은색으로 덮어쓰므로 원래 내용이 남지 않습니다(블러 처리가 아닙니다). 편집 전의 원본 캡처는 디스크에 저장하지 않습니다.
3. **붙일 근거**: 오류, 크래시, 메인 스레드 멈춤, 종료 이유, WARN 이상 로그, 4xx·5xx 응답이나 실패하거나 느린 요청, 행동, 화면 이동, 마커를 추천 항목으로 보여 줍니다. 항목별로 빼거나 넣을 수 있습니다.
4. 개인정보 확인 체크박스("…개인정보가 없는지 확인했습니다.")를 선택해야 내보내기와 보내기가 활성화됩니다.
5. 초안 저장, 패키지 내보내기, 보내기 중 하나를 선택합니다. 보고는 기록 지우기나 보관 기간 정리로 삭제되지 않으며, 설정 → 버그 보고에서 다시 열거나 다시 보내거나 삭제할 수 있습니다.

보고의 텍스트(제목, 설명, 재현 단계, 증거 제목)는 저장하기 전에 현재 마스킹 정책으로 마스킹됩니다.

## 화면 캡처 규칙

- 앱이 캡처를 막은 화면(`FLAG_SECURE`)은 플로팅 버튼, 크래시 캡처, 버그 보고 어디에서도 캡처하지 않습니다. 다만 앱 창이 아닌 별도 창(일부 다이얼로그나 팝업)의 보안 설정은 확인할 수 없습니다.
- `ScreenshotPolicy(blockedScreens = setOf("PaymentActivity"))`처럼 화면 이름(Activity의 짧은 이름)을 지정해 캡처를 막을 수 있습니다.

## 목적지는 호스트 앱이 정합니다

```kotlin
val qa = ReportDestination(
    id = "qa",
    endpoint = "https://qa-collector.example.com/reports",
    allowedHosts = setOf("qa-collector.example.com"),
    encoding = ReportEncoding.MULTIPART,
    maxAttempts = 10,
    headerProvider = { reportId, kind -> mapOf("Authorization" to "Bearer ${tokenStore.freshToken()}") },
)
PulseConfig.debugDefaults().newBuilder()
    .reporting(Reporting(manual = qa, crash = qa))
    .build()
```

- 목적지는 코드에서만 설정할 수 있습니다. 목적지가 설정되지 않은 종류의 보고는 기기에 저장하고 내보내기만 할 수 있으며, 어떤 서버로도 전송되지 않습니다.
- `endpoint`의 호스트는 `allowedHosts`에 포함되어야 하며(생성 시점과 전송 시점에 모두 검사), 리다이렉트는 따라가지 않습니다. 기본적으로 `https`만 허용하고, `allowCleartext = true`이면 `http`도 허용합니다.
- `headerProvider`는 **전송할 때마다** 호출됩니다. 따라서 수명이 짧은 토큰을 PULSE가 따로 저장하지 않습니다. Java에서는 `ReportHeaderProvider.blocking { … }`을 사용합니다.
- 요청에는 `X-Pulse-Report-Id`와 `Idempotency-Key` 헤더(값은 보고 ID)가 붙습니다. 응답을 받기 전에 연결이 끊기면 같은 보고가 다시 전송될 수 있으므로, 받는 쪽에서는 ID로 중복을 걸러 주세요.
- 2xx 응답은 전송 성공으로 처리합니다. 408, 425, 429, 5xx 응답과 네트워크 오류는 15초부터 두 배씩 늘어나는 백오프(최대 1시간)로 재시도하고, `maxAttempts`를 넘기면 실패로 처리합니다. 그 밖의 4xx 응답은 즉시 실패로 처리합니다. 전송 중에 앱이 종료되면 다음 세션에서 다시 전송합니다.
- 내장 전송은 PULSE 인터셉터가 없는 별도 클라이언트를 사용하므로 업로드 요청이 기록에 남지 않습니다.

### 직접 전송하기(Retrofit, 사내 모듈)

```kotlin
val custom = ReportDestination(id = "jira", transport = { upload, onProgress ->
    val response = api.uploadReport(upload.json, upload.attachments)   // 이 클라이언트에 PULSE 인터셉터를 붙였다면 PulseIgnore 태그를 지정한다
    if (response.isSuccessful) TransportResult.Sent(response.code()) else TransportResult.Failed("HTTP ${response.code()}", retryable = response.code() >= 500)
})
```

`PulseIgnore`: `Request.Builder().tag(PulseIgnore::class.java, PulseIgnore)`를 지정한 요청은 PULSE가 기록하지 않습니다.

### 받는 쪽 형식

`MULTIPART`(기본값): `report` 파트(JSON)와 `attachment` 파트(화면 캡처 `screenshot.png`, 재현 패키지 `pulse-report-<id>.zip`)로 구성됩니다. `JSON`을 선택하면 `report` JSON만 본문으로 전송합니다.

```json
{
  "schema": "pulse.report.v1",
  "id": "…", "kind": "MANUAL", "severity": "BLOCKER",
  "title": "결제 실패", "description": "…", "expected": "…", "actual": "…",
  "steps": ["장바구니 열기", "결제 누르기"],
  "sessionId": "…", "createdAt": 1760400000000,
  "evidence": [{ "kind": "REQUEST", "id": "…", "title": "POST /pay 503" }],
  "environment": { "app": {…}, "device": {…}, "flags": {…} }
}
```

## 재현 패키지(ZIP)

보고 작성 화면의 "패키지 내보내기"를 누르거나 전송 시 첨부 파일로 생성됩니다. 코드에서는 `ReproPackageWriter(policy).write(inspector, draft, sessionId, environment, maskedPng, out)`를 사용합니다.

```
manifest.json                       pulse.repro.v1, 파일 목록과 SHA-256
issue.json                          보고 내용
session/timeline.json               해당 세션의 요청과 이벤트 통합 타임라인(최대 20,000개)
network/requests.har                증거로 선택한 요청(없으면 해당 세션의 요청, 최대 2,000개)
app/events.json                     해당 세션의 앱 이벤트
environment.json                    앱·기기 환경 정보와 플래그
attachments/screenshot-redacted.png 마스킹한 화면 캡처
```

모든 텍스트는 파일에 쓰기 직전에 다시 마스킹합니다.

**다시 열기**: 설정 → 버그 보고 → 패키지 열기에서 ZIP 파일을 선택하면 보고 내용, 환경 정보, 마스킹한 캡처, 타임라인, 요청(HAR)을 읽기 전용으로 볼 수 있습니다. 각 파일을 manifest의 SHA-256과 비교해, 패키지 생성 후 변경된 파일이 있으면 알려 줍니다. 다른 기기에서 만든 패키지도 열 수 있습니다.

## 네트워크 장애 시뮬레이션

```kotlin
PulseConfig.debugDefaults().newBuilder()
    .chaos(ChaosPolicy(setOf("staging-api.example.com", "*.dev.example.com")))
```

설정 탭 → 네트워크 장애 모사에서 규칙을 추가하고 켭니다. 규칙에는 호스트, 경로 접두사, 장애 유형(지연, 연결 실패, 시간 초과, 429, 500, 503), 적용 비율, 지연 시간(ms)을 지정합니다. 허용 목록에 없는 호스트로는 규칙을 만들 수도, 적용할 수도 없습니다. 규칙은 `mockInterceptor()`를 추가한 클라이언트에만 적용되며, 메모리에만 보관되므로 앱을 다시 시작하면 사라집니다. 상태 코드를 반환하는 규칙의 응답은 MOCK 출처와 `X-Pulse-Chaos` 헤더로 구분됩니다.
