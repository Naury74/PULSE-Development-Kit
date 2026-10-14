# 버그 보고·재현 패키지·전송

테스트하다 문제를 보면 그 자리에서 보고를 쓰고, 화면 캡처의 개인정보를 가리고, 관련 요청·오류·행동을 근거로 붙여 팀 서버로 보내거나 ZIP으로 내보냅니다.

## 보고 쓰기

| 여는 곳 | 붙는 것 |
| --- | --- |
| 플로팅 PULSE 버튼 → 버그 보고 | 지금 화면 캡처(메모리에만), 캡처 시각 앞 5분~뒤 30초의 근거 |
| 이벤트 상세(로그·이슈·타임라인) → 이걸로 보고 | 그 이벤트, 그 시각 앞뒤 근거. 크래시·종료 이유면 종류가 CRASH |
| 설정 → 버그 보고 → 새 버그 보고 | 지금 시각 근거 |

작성기에서:

1. 제목·심각도(치명·중요·보통·사소)·설명·재현 단계·기대 결과·실제 결과를 씁니다. 재현 단계는 근거의 행동(`Pulse.action`)·화면 이동·마커로 초안을 채웁니다.
2. **화면 캡처 편집**: 가리기·사각형·화살표·펜·번호. 가리기는 저장되는 이미지의 픽셀을 검은색으로 덮어써 원래 내용이 남지 않습니다(흐림 처리가 아님). 편집 전 캡처는 디스크에 쓰지 않습니다.
3. **근거**: 오류·크래시·멈춤·종료, WARN 이상 로그, 4xx·5xx·실패·느린 요청, 행동·화면 이동·마커를 추천합니다. 항목마다 빼거나 넣습니다.
4. "개인정보가 없는지 확인했습니다"를 체크해야 내보내기·보내기를 할 수 있습니다.
5. 초안 저장 / 패키지 내보내기 / 보내기. 보고는 기록 지우기와 보관 정리에 지워지지 않고, 설정 → 버그 보고에서 다시 열거나 다시 보내거나 지웁니다.

보고의 글자(제목·설명·단계·근거 제목)는 저장 전에 현재 마스킹 정책으로 가립니다.

## 화면 캡처 규칙

- 앱이 캡처를 막은 화면(`FLAG_SECURE`)은 플로팅 버튼·크래시 캡처·보고 모두 찍지 않습니다. 앱 창이 아닌 별도 창(일부 다이얼로그·팝업)의 보안 설정은 알 수 없습니다.
- `ScreenshotPolicy(blockedScreens = setOf("PaymentActivity"))`로 화면 이름(Activity 짧은 이름)을 막을 수 있습니다.

## 목적지 — 호스트가 정한다

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

- 목적지는 코드 설정으로만 정합니다. 설정이 없는 종류의 보고는 기기에 저장하고 내보내기만 하며, 어떤 서버로도 보내지 않습니다.
- `endpoint`의 호스트는 `allowedHosts`에 있어야 하고(만들 때와 보낼 때 모두 검사), 리다이렉트는 따르지 않습니다. `https`만 받고 `allowCleartext = true`면 `http`도 받습니다.
- `headerProvider`는 **보낼 때마다** 불립니다. 짧게 쓰는 토큰을 PULSE가 저장하지 않습니다. Java는 `ReportHeaderProvider.blocking { … }`.
- 요청에 `X-Pulse-Report-Id`·`Idempotency-Key`(보고 ID)를 붙입니다. 응답 전에 연결이 끊기면 같은 보고가 다시 갈 수 있으니 받는 쪽은 ID로 중복을 거릅니다.
- 2xx는 보냄, 408·425·429·5xx와 네트워크 오류는 15초부터 두 배씩(최대 1시간) 늘려 다시 보내고 `maxAttempts`를 넘기면 실패, 그 밖의 4xx는 바로 실패입니다. 보내던 중 앱이 끝나면 다음 실행 때 다시 보냅니다.
- 내장 전송은 PULSE 인터셉터가 없는 별도 클라이언트를 쓰므로 업로드가 기록에 남지 않습니다.

### 직접 보내기(Retrofit·사내 모듈)

```kotlin
val custom = ReportDestination(id = "jira", transport = { upload, onProgress ->
    val response = api.uploadReport(upload.json, upload.attachments)   // 이 클라이언트에 PULSE를 붙였다면 PulseIgnore 태그를 붙인다
    if (response.isSuccessful) TransportResult.Sent(response.code()) else TransportResult.Failed("HTTP ${response.code()}", retryable = response.code() >= 500)
})
```

`PulseIgnore`: `Request.Builder().tag(PulseIgnore::class.java, PulseIgnore)`를 붙인 요청은 PULSE가 기록하지 않습니다.

### 받는 쪽 형식

`MULTIPART`(기본): 파트 `report`(JSON) + `attachment`(화면 캡처 `screenshot.png`, 재현 패키지 `pulse-report-<id>.zip`). `JSON`이면 `report` JSON만 본문으로 보냅니다.

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

보고 작성기의 "패키지 내보내기"나 전송 첨부로 만들어집니다. 코드로는 `ReproPackageWriter(policy).write(inspector, draft, sessionId, environment, maskedPng, out)`.

```
manifest.json                       pulse.repro.v1, 파일 목록과 SHA-256
issue.json                          보고 내용
session/timeline.json               그 실행의 요청·이벤트 통합 타임라인(최대 20,000개)
network/requests.har                근거로 고른 요청(없으면 그 실행의 요청, 최대 2,000개)
app/events.json                     그 실행의 앱 이벤트
environment.json                    앱·기기 환경과 플래그
attachments/screenshot-redacted.png 가린 화면 캡처
```

모든 글자는 쓰기 직전에 다시 마스킹합니다. 패키지를 다시 여는 뷰어는 아직 없습니다(JSON·HAR 도구로 엽니다).

## 네트워크 장애 모사

```kotlin
PulseConfig.debugDefaults().newBuilder()
    .chaos(ChaosPolicy(setOf("staging-api.example.com", "*.dev.example.com")))
```

설정 탭 → 네트워크 장애 모사에서 규칙(호스트·경로 접두사·지연/연결 실패/시간 초과/429/500/503·적용 비율·지연 ms)을 더하고 켭니다. 허용 목록에 없는 호스트는 규칙을 만들 수도, 적용할 수도 없습니다. `mockInterceptor()`를 붙인 클라이언트에만 적용되고, 규칙은 메모리에만 있어 앱을 다시 시작하면 사라집니다. 상태 코드 응답은 MOCK 출처와 `X-Pulse-Chaos` 헤더로 표시됩니다.
