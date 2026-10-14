# 앱 진단: 로그, 크래시, 성능, 마커

PULSE는 네트워크 요청뿐 아니라 앱 자체의 상태도 같은 저장소에 함께 기록합니다. 모든 데이터는 기기 안에만 저장되며, [원격 보고](remote-reporting.md)를 켠 경우에만 외부로 전송됩니다. 문자열 속 비밀 값은 `RedactionPolicy`에 따라 마스킹됩니다.

## 수집 항목

| 항목 | 수집 시점과 내용 | 설정(`AppMonitoring`) |
| --- | --- | --- |
| 크래시 | 처리되지 않은 예외로 앱이 종료되기 직전. 스택 트레이스, 스레드, 화면을 저장한 뒤 기존 핸들러에 그대로 넘깁니다 | `crashes`(기본 켬) |
| 종료 이유 | 다음 세션 시작 시, 이전 세션이 끝난 이유(Android 11+ `ApplicationExitInfo`: 네이티브 크래시, ANR, 메모리 부족, 사용자 종료 등) | `exitReasons`(기본 켬) |
| 메인 스레드 멈춤 | 메인 스레드가 기준 시간보다 오래 멈추면 그 시점의 스택과 멈춘 시간. 디버거로 일시 정지한 시간은 제외합니다 | `mainThreadBlockMillis`(기본 2,000 ms, 0이면 끔) |
| 콜드 스타트, 화면 로딩 | 프로세스 시작부터 첫 화면이 처음 그려질 때까지, 화면 생성부터 첫 그리기까지 | `performance`(기본 켬) |
| 프레임 | 화면별 전체 프레임, 느린 프레임(>16.7 ms), 멈춘 프레임(>700 ms)(Android 7+) | `performance` |
| 메모리 | 앱이 화면에 보이는 동안 일정 간격으로 측정한 사용량 | `memoryIntervalMillis`(기본 30초, 0이면 끔) |
| 세션 | 시작 시각, 화면에 표시된 시간, 마지막으로 살아 있던 시각, 크래시 여부 | 항상 |
| logcat | 이 앱 프로세스의 logcat. `--pid`로 읽으므로 별도 권한이 필요 없고, 다른 앱의 로그는 읽지 않습니다 | `logcat`(기본 끔, `debugDefaults()`에서는 켬), `logcatMinLevel`, `logcatIgnoredTags` |
| 탭, 뒤로 키 | 앱 화면에서 클릭 가능한 요소를 탭하거나 뒤로 키를 누를 때. 대상은 View id(`#login_button`)와 클래스, 또는 Compose testTag와 role만 기록하고 텍스트와 좌표는 기록하지 않습니다. 다이얼로그 같은 별도 창과 제스처 뒤로가기는 감지하지 못합니다 | `interactions`(기본 끔, `debugDefaults()`에서는 켬) |
| 크래시 화면 캡처 | 크래시 직전의 최상단 화면 | `screenshotOnCrash`(기본 끔. 화면에 개인정보가 보일 수 있습니다) |

```kotlin
PulseConfig.debugDefaults().newBuilder()
    .monitoring(
        AppMonitoring(
            logcat = true,
            logcatMinLevel = LogLevel.INFO,
            logcatIgnoredTags = setOf("chatty", "OpenGLRenderer"),
            mainThreadBlockMillis = 1_000,
            screenshotOnCrash = true,
        ),
    )
    .build()
```

`AppMonitoring.OFF`를 지정하면 앱 진단 수집이 모두 꺼집니다. 보관량은 `RetentionPolicy.maxEvents`(기본 50,000개)와 보관 기간, 용량 상한을 함께 따릅니다.

## 코드에서 기록하기

release 빌드(`pulse-noop`)에서는 아래 호출이 모두 아무 동작도 하지 않으므로 코드를 지울 필요가 없습니다.

```kotlin
Pulse.log(LogLevel.INFO, "Checkout", "결제 버튼 노출")
Pulse.recordError(error, "수량 파싱 실패")          // catch로 처리한 예외

Pulse.mark("결제 버튼 누름")                         // 특정 시점
val span = Pulse.beginSpan("결제 흐름")              // 스팬(구간)
// …
span?.let(Pulse::endSpan)
```

`Pulse.log`는 Logcat에 출력하지 않는 값을 기록할 때 사용합니다. 이미 `Log.d`로 출력하는 로그는 `logcat = true`로 수집하면 됩니다.

## 행동, 화면, 플래그

```kotlin
Pulse.action("checkout.submit", mapOf("items" to "3"))   // 의미 있는 행동 ID. 사용자 입력값 원문은 넣지 않는다
Pulse.screen("Cart")                                       // Compose 내비게이션 경로처럼 Activity가 아닌 화면의 이름
Pulse.setFlag("server", "staging")                         // 기능 플래그나 환경 값. 값이 바뀔 때만 이력이 남는다
```

- 행동은 `ACTION` 이벤트(속성 32개, 값 256자까지, 비밀로 보이는 값은 마스킹), 화면 이동은 `NAVIGATION` 이벤트(Activity 전환은 자동 기록), 플래그 변경은 `CONFIG` 이벤트로 기록됩니다.
- `Pulse.screen`으로 알린 화면 이름은 이후 요청의 화면 정보로도 사용됩니다.
- 플래그 값은 마스킹하지 않으므로 비밀 값을 넣지 마세요. 현재 플래그 값과 앱, 기기 정보는 설정 탭 → 환경과 플래그에서 확인할 수 있습니다.

## 통합 타임라인

타임라인 탭의 "전체 흐름"은 요청과 이벤트를 하나의 목록에 시간순으로 보여 줍니다. "주요"는 일반 로그를 제외한 흐름(요청, 행동, 화면, 오류, 성능)이고, "요청", "오류", "행동", "로그", "성능"으로 범위를 좁힐 수 있습니다. 기간 칩에서 세션 하나를 고르면 해당 세션만 표시됩니다.

- [`Pulse.beginSpan`]으로 시작한 스팬 안에서 보낸 요청에는 스팬 ID(`RequestContext.spanId`)가 붙으므로 스팬과 정확히 연결됩니다.
- 이벤트와 요청 상세의 **연결된 근거**에는 관련 항목이 연결 강도와 함께 표시됩니다. 같은 스팬, 재실행, 크래시와 종료 이유처럼 기록으로 확실히 연결된 항목이 가장 강하고, 1분 안에 같은 화면에서 일어난 항목, 앞뒤 5초 안에 일어났을 뿐인 항목 순입니다. 시간상 가깝다고 해서 원인이라는 뜻은 아닙니다.
- API: `loadTimeline(TimelineQuery(sessionId = …), after, limit)`, `loadRelated(id, kind)`, `TransactionQuery(sessionId = …, spanId = …)`.

## 테스트 중 마커 남기기: 플로팅 버튼

설정 탭에서 "플로팅 PULSE 버튼"을 켜면(또는 `res/values`에 `<bool name="pulse_overlay_enabled">true</bool>` 지정) 앱 화면 위에 드래그로 옮길 수 있는 버튼이 나타납니다. 다른 앱 위에 그리기 권한은 사용하지 않으며, 앱 화면 안에만 표시됩니다.

- **마커 찍기 / 이름 붙여 마커 찍기**: 현재 시각에 마커를 남깁니다.
- **구간 시작 / 구간 끝내기**: 스팬을 기록하는 동안 버튼에 빨간 점이 표시되고, 메뉴에 경과 시간이 나옵니다.
- **버그 보고**: 현재 화면을 첨부해 보고 작성 화면을 엽니다([bug-reports.md](bug-reports.md)).
- **화면 캡처**: 최상단 화면을 PNG로 저장합니다(플로팅 버튼은 제외하고 캡처). 앱이 캡처를 막은 화면(`FLAG_SECURE`)과 `ScreenshotPolicy.blockedScreens`에 지정한 화면은 캡처하지 않습니다.
- **PULSE 열기 / 이 버튼 숨기기**

## 기록 확인하기

- **타임라인 탭 → 앱 로그**: 로그, 마커, 스팬, 오류, 성능, 앱 상태 이벤트를 시간순으로 보여 줍니다. 분류(로그, 마커, 오류, 성능, 앱 상태), 최소 수준(V~A), 텍스트 검색, 기간으로 필터링할 수 있습니다. 이벤트를 누르면 전체 내용과 스택 트레이스, 캡처 이미지가 표시됩니다.
  - **이 시점 앞뒤 1분**: 모든 탭(트래픽, 로그, 대시보드)의 기간을 해당 시점 앞뒤 1분으로 맞춥니다.
  - **이 구간만 보기**: 기간을 스팬의 시작부터 끝까지로 맞춥니다. 트래픽 탭에는 그 사이의 요청만 표시됩니다.
- **이슈 탭**: 크래시, 종료 이유, 오류, 메인 스레드 멈춤을 같은 원인(종류와 메시지 첫 줄)별로 묶어 발생 횟수와 최초, 최근 발생 시각을 보여 줍니다. 상단 전환 버튼으로 네트워크 진단을 볼 수 있습니다.
- **대시보드 → 앱**: 크래시 없는 세션 비율, 세션 수, 총 실행 시간, 화면 표시 시간, 날짜별 실행 시간과 크래시, 콜드 스타트와 화면 로딩 p50/p95, 화면별 느린 프레임, 로그 수준별 개수, 종료 이유, 최근 세션 목록을 보여 줍니다.

## 세션 비교

개요 → 앱 → 최근 앱 실행 아래의 "두 실행 비교"에서 기준 실행(정상)과 확인할 실행(문제)을 고르면 두 세션의 차이를 보여 줍니다. 확인할 실행에만 있는 오류와 크래시, 값이 다른 플래그, 한쪽 세션에서만 방문한 화면, 엔드포인트별 요청 수, 오류 수, 중앙값 응답 시간의 변화(변화가 큰 순), 이벤트 종류별 개수가 표시됩니다. 세션마다 기록 20,000개까지 비교합니다. API: `compareSessions(base, target)`.

## 화면 구조 보기

플로팅 버튼 → 화면 구조 보기는 현재 화면의 View 계층과 Compose Semantics 계층(테스트용 공개 API로 읽음)을 가져와 노드 목록과 속성(영역, 표시 여부, 클릭 가능 여부, 패딩, testTag, role 등)을 보여 주고, 선택한 노드의 영역을 캡처 이미지 위에 표시합니다. 텍스트는 내용 없이 길이만 기록합니다. 노드는 최대 3,000개까지 표시하며, Compose 버전이 바뀌어 Semantics를 읽지 못하면 해당 View까지만 보입니다.

## 로컬 데이터(읽기 전용)

```kotlin
.dataInspection(DataInspection(sharedPreferences = setOf("settings"), databases = setOf("app.db"), maxRows = 200))
```

등록한 SharedPreferences와 SQLite, Room DB만 설정 탭에서 읽기 전용으로 볼 수 있습니다. DB는 읽기 전용으로 열고 테이블, 컬럼, 행을 페이지 단위로 읽습니다. 이름에 민감한 단어가 들어간 키와 컬럼은 값을 마스킹하고(`RedactionPolicy.withSensitiveNames`로 단어 추가), 그 밖의 값도 문자열 속 비밀 값을 마스킹합니다. BLOB은 크기만 표시합니다. DB는 항상 읽기 전용입니다. `allowPreferenceEdits = true`로 설정하면 SharedPreferences 값을 확인 절차를 거쳐 기존 타입(Boolean, Int, Long, Float, String)으로 수정하거나 삭제할 수 있으며, 변경 내역(키 이름만)은 타임라인에 `CONFIG` 이벤트로 남습니다. 되돌리기 기능은 없습니다.

## WebView

```kotlin
webView.webViewClient = object : PulseWebViewClient() { /* 재정의할 때는 super를 호출 */ }
webView.webChromeClient = PulseWebChromeClient(minLevel = LogLevel.WARN)
```

페이지 로딩 시작과 끝(소요 시간), 로딩 실패, HTTP 4xx·5xx 응답, `console.*`(기본 WARN 이상)를 `WebView`, `WebView.console` 태그의 로그로 기록합니다. release(`pulse-noop`)에도 같은 이름의 클래스가 있어 그대로 컴파일되며, 기록만 하지 않습니다. JS↔Native 브리지 호출은 `Pulse.action("bridge.pay")`처럼 기록하면 타임라인에 함께 표시됩니다.

## 날짜와 세션으로 필터링하기

모든 탭 상단의 기간 칩에서 **오늘, 어제, 최근 7일, 최근 30일**, **달력에서 고른 날짜 범위**, **앱 실행(세션) 하나**를 선택할 수 있습니다. 선택한 기간은 트래픽, 로그, 이슈, 대시보드에 함께 적용되고 화면을 회전해도 유지됩니다. 야간 자동 테스트 결과는 다음 날 "어제"나 해당 세션을 선택해 확인하면 됩니다.

API로는 `TransactionQuery`, `StatsQuery`, `EventQuery`의 `fromEpochMillis`/`toEpochMillis`와 `loadSessions(from, to, limit)`, `loadAppStatistics(from, to)`를 사용합니다.

## 내보내기

타임라인 탭 → 앱 로그의 공유 버튼에서 형식과 범위를 선택합니다.

| 옵션 | 선택지 |
| --- | --- |
| 형식 | 텍스트(한 줄에 이벤트 하나, 스택은 들여쓰기), JSON(`pulse.events.v1`), CSV(스프레드시트용, 상세 제외) |
| 범위 | 현재 화면의 필터 조건(분류, 수준, 검색어, 기간) 그대로, 또는 전체 |
| 상세 | 스택 트레이스 등 상세 정보 포함 여부 |
| 저장 위치 | 파일로 저장(Storage Access Framework) 또는 바로 공유 |

파일을 쓰기 직전에 현재 정책으로 다시 마스킹하며, 최대 100,000개(초과하면 최신 항목 우선)까지 씁니다. 코드에서는 `EventExporter(policy).export(inspector, query, format, out, includeDetails)`를 사용합니다.

## 제한 사항

- 크래시는 JVM(Kotlin, Java) 예외만 발생 시점에 잡습니다. 네이티브 크래시와 ANR은 다음 세션에서 시스템 기록(Android 11+)으로만 확인할 수 있습니다.
- 프로세스가 강제 종료되면 마지막 몇 초(최대 15초)의 실행 시간이 누락될 수 있습니다.
- logcat은 수집을 시작한 이후의 로그만 읽으며, 기기의 logcat 버퍼가 넘치면 일부 줄이 누락될 수 있습니다.
- 성능 수치는 기기 상태(디버그 빌드, 에뮬레이터 여부)에 크게 좌우되므로 추이를 보거나 비교하는 용도로 사용하세요.
