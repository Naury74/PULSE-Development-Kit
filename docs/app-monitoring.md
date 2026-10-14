# 앱 진단 — 로그·크래시·성능·마커

네트워크 말고 앱 자체의 상태도 같은 기록에 모읍니다. 모두 기기 안에만 저장하고([원격 보고](remote-reporting.md)를 켠 경우만 밖으로 보냄), 글자 속 비밀은 `RedactionPolicy`로 가립니다.

## 무엇을 모으나

| 종류 | 언제 | 켜는 설정(`AppMonitoring`) |
| --- | --- | --- |
| 크래시 | 처리되지 않은 예외로 죽기 직전. 스택·스레드·화면을 저장하고 원래 핸들러에 그대로 넘김 | `crashes`(기본 켬) |
| 종료 이유 | 다음 실행 때, 지난 실행이 끝난 이유(Android 11+ `ApplicationExitInfo`: 네이티브 크래시·ANR·메모리 부족·사용자 종료 등) | `exitReasons`(기본 켬) |
| 메인 스레드 멈춤 | 메인 스레드가 기준 시간보다 오래 멈추면 그때의 스택과 전체 길이. 디버거로 멈춘 것은 빼고 셈 | `mainThreadBlockMillis`(기본 2,000 ms, 0이면 끔) |
| 콜드 스타트·화면 로딩 | 프로세스 시작 → 첫 화면 첫 그리기, 화면 생성 → 첫 그리기 | `performance`(기본 켬) |
| 프레임 | 화면별 전체·느린(>16.7 ms)·멈춘(>700 ms) 프레임(Android 7+) | `performance` |
| 메모리 | 앱이 보이는 동안 일정 간격으로 사용량 | `memoryIntervalMillis`(기본 30초, 0이면 끔) |
| 실행(세션) | 시작 시각, 화면에 보인 시간, 마지막으로 살아 있던 시각, 크래시 여부 | 항상 |
| logcat | 이 앱 프로세스의 logcat(`--pid`라 권한 불필요, 다른 앱 로그는 읽지 않음) | `logcat`(기본 끔, `debugDefaults()`는 켬), `logcatMinLevel`, `logcatIgnoredTags` |
| 크래시 화면 캡처 | 크래시 직전의 앞 화면 | `screenshotOnCrash`(기본 끔. 화면에 개인정보가 보일 수 있음) |

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

`AppMonitoring.OFF`는 앱 쪽 수집을 모두 끕니다. 보관은 `RetentionPolicy.maxEvents`(기본 50,000개)와 기간·용량 상한을 함께 따릅니다.

## 코드에서 남기기

release(`pulse-noop`)에서는 모두 아무것도 하지 않으므로 지우지 않아도 됩니다.

```kotlin
Pulse.log(LogLevel.INFO, "Checkout", "결제 버튼 노출")
Pulse.recordError(error, "수량 파싱 실패")          // 잡아서 처리한 예외

Pulse.mark("결제 버튼 누름")                         // 한 시점
val span = Pulse.beginSpan("결제 흐름")              // 구간
// …
span?.let(Pulse::endSpan)
```

`Pulse.log`는 앱이 Logcat에 쓰지 않는 값을 남길 때 씁니다. 이미 `Log.d`로 쓰는 로그는 `logcat = true`로 모으면 됩니다.

## 행동·화면·플래그

```kotlin
Pulse.action("checkout.submit", mapOf("items" to "3"))   // 의미 있는 행동 ID. 입력값 원문은 넣지 않는다
Pulse.screen("Cart")                                       // Compose 경로처럼 Activity가 아닌 화면 이름
Pulse.setFlag("server", "staging")                         // 기능 플래그·환경 값. 바뀔 때만 이력이 남는다
```

- 행동은 `ACTION`(속성은 32개·값 256자까지, 비밀로 보이는 값은 가림), 화면 이동은 `NAVIGATION`(Activity 전환은 자동), 플래그 변경은 `CONFIG` 이벤트로 남습니다.
- `Pulse.screen`으로 알린 이름은 그 뒤 요청의 화면으로도 쓰입니다.
- 플래그 값은 마스킹하지 않으므로 비밀을 넣지 마세요. 설정 탭 → 환경과 플래그에서 지금 값과 앱·기기 정보를 봅니다.

## 통합 타임라인

타임라인 탭의 "전체 흐름"은 요청과 이벤트를 한 목록에 시간순으로 보여 줍니다. "주요"는 일반 로그를 뺀 흐름(요청·행동·화면·오류·성능), "요청"·"오류"·"행동"·"로그"·"성능"으로 좁힐 수 있고, 기간 칩에서 실행 하나를 고르면 그 실행만 봅니다.

- [`Pulse.beginSpan`]으로 연 구간 안에서 시작한 요청에는 구간 ID가 붙어(`RequestContext.spanId`) 구간과 확실하게 이어집니다.
- 이벤트·요청 상세의 **연결된 근거**는 연결 등급을 함께 보여 줍니다: 같은 구간·재실행·크래시와 종료 이유(기록된 연결), 같은 화면(1분 안), 근접(앞뒤 5초). 시간상 가깝다는 것은 원인이라는 뜻이 아닙니다.
- API: `loadTimeline(TimelineQuery(sessionId = …), after, limit)`, `loadRelated(id, kind)`, `TransactionQuery(sessionId = …, spanId = …)`.

## 테스트하면서 마커 찍기 — 플로팅 버튼

설정 탭에서 "플로팅 PULSE 버튼"을 켜면(또는 `res/values`에 `<bool name="pulse_overlay_enabled">true</bool>`) 앱 화면 위에 끌어 옮길 수 있는 버튼이 생깁니다. 다른 앱 위에 그리는 권한은 쓰지 않고 앱의 화면 안에만 붙습니다.

- **마커 / 이름 붙인 마커**: 지금 시각에 표시
- **구간 시작 / 구간 끝**: 기록 중에는 버튼에 빨간 점이 보이고 메뉴에 경과 시간이 나옴
- **버그 보고**: 지금 화면을 붙여 보고 작성기를 연다([bug-reports.md](bug-reports.md))
- **화면 캡처**: 앞 화면을 PNG로 저장(버튼은 빼고 찍음). 앱이 캡처를 막은 화면(`FLAG_SECURE`)과 `ScreenshotPolicy.blockedScreens`는 찍지 않음
- **PULSE 열기 / 버튼 숨기기**

## 보기

- **타임라인 탭 → 앱 로그**: 로그·마커·구간·오류·성능·앱 상태 이벤트를 시간순으로. 묶음(로그·마커·이슈·성능·앱 상태), 최소 수준(V~A), 글자 검색, 기간으로 거릅니다. 이벤트를 누르면 전체 내용과 스택, 캡처 이미지가 나옵니다.
  - **앞뒤 1분 보기**: 그 시점 앞뒤 1분으로 모든 탭(트래픽·로그·대시보드)의 기간을 맞춥니다.
  - **구간만 보기**: 구간 시작부터 끝까지로 맞춥니다. 그 사이의 요청만 트래픽 탭에 나옵니다.
- **이슈 탭**: 크래시·종료 이유·오류·멈춤을 같은 원인(종류와 메시지 첫 줄)끼리 묶어 횟수와 처음·마지막 시각을 보여 줍니다. 위쪽 전환으로 네트워크 진단을 봅니다.
- **대시보드 → 앱**: 크래시 없는 실행 비율, 실행 수·전체 실행 시간·화면에 보인 시간, 날짜별 실행 시간과 크래시, 콜드 스타트·화면 로딩 p50/p95, 화면별 느린 프레임, 로그 수준별 개수, 종료 이유, 최근 실행 목록.

## 날짜·실행으로 거르기

모든 탭 위의 기간 칩에서 **오늘·어제·최근 7일·최근 30일**, **달력에서 날짜 범위**, **앱 실행(세션) 하나**를 고릅니다. 고른 기간은 트래픽·로그·이슈·대시보드에 함께 적용되고 화면을 돌려도 유지됩니다. 야간 자동 테스트 결과는 다음 날 "어제"나 그 실행을 골라 보면 됩니다.

API로는 `TransactionQuery`·`StatsQuery`·`EventQuery`의 `fromEpochMillis`/`toEpochMillis`, `loadSessions(from, to, limit)`, `loadAppStatistics(from, to)`를 씁니다.

## 내보내기

타임라인 탭 → 앱 로그의 공유 버튼에서 형식과 범위를 고릅니다.

| 선택 | 값 |
| --- | --- |
| 형식 | 텍스트(한 줄에 하나, 스택은 들여쓰기), JSON(`pulse.events.v1`), CSV(표 프로그램용, 상세 제외) |
| 범위 | 지금 화면의 조건(묶음·수준·검색·기간) 그대로, 또는 전부 |
| 상세 | 스택 트레이스 등 상세 포함 여부 |
| 보낼 곳 | 파일로 저장(Storage Access Framework) 또는 바로 공유 |

쓰기 직전에 현재 정책으로 다시 마스킹하고, 최대 100,000개(넘으면 최근 것)까지 씁니다. 코드로는 `EventExporter(policy).export(inspector, query, format, out, includeDetails)`.

## 한계

- 크래시는 JVM(Kotlin·Java) 예외만 그 자리에서 잡습니다. 네이티브 크래시와 ANR은 다음 실행 때 시스템 기록(Android 11+)으로만 알 수 있습니다.
- 프로세스가 강제로 끝나면 마지막 몇 초(최대 15초)의 실행 시간은 빠질 수 있습니다.
- logcat은 시작한 뒤의 로그만 읽고, 기기 logcat 버퍼가 넘치면 빠지는 줄이 있습니다.
- 성능 값은 기기 상태(디버그 빌드, 에뮬레이터)에 크게 좌우됩니다. 추이와 비교에 쓰세요.
