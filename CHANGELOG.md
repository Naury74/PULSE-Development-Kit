# Changelog

이 프로젝트는 [Semantic Versioning](https://semver.org/lang/ko/)을 따릅니다. 0.x 버전 동안에는 공개 API가 바뀔 수 있습니다.

## [Unreleased]

### 추가
- 인스펙터 UI 재디자인: 메서드별 색상 태그와 상태 표시 띠가 있는 목록, 결과와 핵심 수치를 모은 상세 화면 헤더, 구문 강조된 JSON, 단계별 색상과 "응답 대기" 구간을 보여 주는 타임라인, 카드형 개요·진단·설정 화면.
- 앱에 버튼을 추가하지 않고도 인스펙터를 여는 방법: debug 빌드 전용 런처 아이콘, 요청 수와 오류, 최근 요청을 보여 주는 알림(멈춤, 재개, 지우기). 리소스 값으로 끌 수 있습니다.
- 요청 하나를 Markdown으로 공유하는 기능(결과, 헤더, 본문, cURL 포함).
- `PulseInspectorRegistry.updates`: 초기화와 종료를 알리는 Flow.
- 수집 옵션: `CaptureFilter`(호스트·경로 필터링), `ContextCapture`(화면, 앱 상태, 네트워크 종류), `PulseLabel`(요청 라벨), `EndpointGrouping`(경로 템플릿), `BodyFormats`(form 및 텍스트 본문).
- 통계 확장: `StatsQuery`로 호스트, 화면, 라벨 필터링. 분당 요청 수, p75·p90·평균·최대값, 첫 응답 시간, 소요 시간 분포, 상태 코드와 실패 유형, DNS·연결·TLS 단계, 연결 재사용과 캐시, 엔드포인트별 통계(오류와 크기 포함), 기준별 분류.
- 대시보드: 필터 칩, 지표 8개, 요청 수와 응답 시간 추이, 분포, 결과 구성, 연결 단계, 정렬 가능한 엔드포인트 표, 기준별 분류(항목을 누르면 해당 값으로 필터링).
- 저장소 스키마 v4(컨텍스트 컬럼 추가, 자동 마이그레이션).
- OkHttp가 실제로 주고받은 헤더를 별도로 기록하고, 상세 화면에서 "앱"/"실제 전송" 헤더를 전환(스키마 v5).
- 요청 내보내기 옵션 `ExportOptions`(헤더, 본문, 재실행 기록 포함 여부), CSV와 Markdown 형식, 목록에서 선택한 요청만 내보내기, 파일로 저장하거나 바로 공유.
- 원형 차트(도넛, 링), 공통 바텀 시트와 확인 다이얼로그, 샘플 앱 테스트 요청 화면 개편(카테고리, 결과와 소요 시간, 모두 실행).
- 앱 진단 `AppMonitoring`: 크래시, 이전 세션의 종료 이유(`ApplicationExitInfo`), 메인 스레드 멈춤, 콜드 스타트와 화면 로딩 시간, 프레임, 메모리, 앱 자체의 logcat, 세션 시간.
- `Pulse.log`, `recordError`, `mark`, `beginSpan`/`endSpan`, 앱 이벤트 조회 API(`EventQuery`, `loadEvents`, `loadSessions`, `loadAppStatistics`). 스키마 v6.
- 로그 탭(그룹, 로그 레벨, 검색, 기간 필터, 전후 1분 또는 스팬 범위만 보기, 텍스트·JSON·CSV 내보내기), 이슈 탭(원인별 그룹과 네트워크 진단), 대시보드의 앱 화면.
- 날짜와 세션 단위로 보기: 오늘, 어제, 7일, 30일, 달력으로 범위 지정, 특정 세션 하나. `TransactionQuery`와 `StatsQuery`에 `fromEpochMillis`/`toEpochMillis` 추가.
- 앱 화면 위 플로팅 버튼(마커, 이름 붙인 마커, 스팬, 화면 캡처. 기본값은 꺼짐).
- 원격 오류 보고 `RemoteReporting`, `PulseRemote`: 저장 시점에 전송 대기열에 넣어 두므로 앱이 죽어도 다음 세션에서 전송하고, 실패하면 백오프로 간격을 늘려 재시도합니다. 설정 탭에서 기기별로 엔드포인트 주소, 토큰, 사용 여부를 바꿀 수 있습니다. 스키마 v7.
- 통합 타임라인(`loadTimeline`, `TimelineQuery`)과 관련 항목 조회(`loadRelated`, 확실한 연결·같은 화면·비슷한 시점을 등급으로 구분). 스팬 안에서 시작한 요청에 스팬 ID(`RequestContext.spanId`) 부여, `TransactionQuery`와 `EventQuery`에 `sessionId`, `spanId` 추가. 스키마 v8.
- `Pulse.action`, `Pulse.screen`, `Pulse.setFlag`와 이벤트 유형 `ACTION`, `NAVIGATION`, `CONFIG`, 환경 정보(`environment()`, `flags`). `PulseIgnore` 태그.
- 버그 보고(`PulseReports`): 작성 화면, 스크린샷 마스킹(픽셀 덮어쓰기)과 표시 편집기, 근거 추천, 초안 저장, 재현 패키지(`ReproPackageWriter`, `pulse.repro.v1`).
- 보고 목적지(`Reporting`, `ReportDestination`): 허용 호스트, 전송할 때마다 호출되는 `ReportHeaderProvider`, JSON 및 multipart 형식, 재시도와 중복 방지 헤더, 직접 전송(`ReportTransport`).
- `ScreenshotPolicy`와 `FLAG_SECURE`를 이용한 화면 캡처 차단.
- 네트워크 장애 시뮬레이션(`ChaosPolicy`, `ChaosRule`, `PulseChaos`).
- 세션 비교(`compareSessions`, `SessionDiff`), 화면 구조 보기(View 및 Compose Semantics), 로컬 데이터 읽기 전용 뷰어(`DataInspection`, `PulseLocalData`), WebView 기록(`PulseWebViewClient`, `PulseWebChromeClient`, no-op 포함), 메모리 샘플의 GC 횟수와 CPU 시간.
- 로컬 데이터 뷰어에서 SharedPreferences 편집(`DataInspection.allowPreferenceEdits`, 기본값은 꺼짐).
- 새 로고와 런처 아이콘(인스펙터와 샘플 앱 구분), README 스크린샷(다크, 라이트, 폴더블)과 대표 이미지.
- 재현 패키지 뷰어(설정 → 버그 보고 → 패키지 열기, SHA-256 무결성 검증).
- 탭과 뒤로 가기 자동 기록(`AppMonitoring.interactions`, `debugDefaults()`에서 켜짐), 인스펙터 화면 캡처 차단 리소스(`pulse_inspector_secure`).
- 샘플 앱: 앱 진단 시나리오(로그, 스팬, 처리된 오류, 원격 보고 왕복, 메인 스레드 멈춤, 크래시)와 데모 서버의 `/collect` 엔드포인트.

### 변경
- `loadStatistics(window)`는 재실행과 모의 응답 기록을 제외하고 집계합니다. 포함하려면 `StatsQuery(includeSynthetic = true)`를 사용하세요.
- 인스펙터 탭 구성 변경: 개요, 트래픽, 타임라인(전체 흐름, 앱 로그), 이슈, 설정. 진단은 이슈 탭의 "네트워크 진단"으로 이동했습니다.
- `RetentionPolicy`에 `maxEvents`(기본값 50,000) 추가.

## [0.1.0-alpha01] — 배포 준비 중

첫 공개 알파 버전입니다. 0.x 버전 동안에는 공개 API가 바뀔 수 있습니다.

### 추가
- 멀티 모듈 빌드(build-logic convention plugin, version catalog)와 CI: 단위 테스트, Android Lint, ktlint, detekt, release no-op 검증.
- `pulse-api`: `PulseConfig`, `CaptureLimits`, `RetentionPolicy`, `RedactionPolicy`, 트랜잭션 모델, `PulseInspector` 조회 계약.
- `pulse-security`: 헤더, 쿼리, JSON 키 마스킹(문자열 안의 JSON, URL, `name=value` 포함), URL의 사용자 정보와 fragment 제거, JSON이 아닌 본문은 저장하지 않음.
- `pulse-core`: 블로킹하지 않는 크기 제한 큐(넘치면 본문부터 버림), 저장 스레드, 버린 건수와 저장 실패 집계.
- `pulse-okhttp`: application 인터셉터와 `EventListener` 기반 단계별 타이밍, 호스트 앱이 읽은 바이트만 복사하는 본문 tee.
- `pulse-storage`: Room 스키마 v1, 본문 파일 저장, 건수·기간·용량 기준 보관 정책, keyset 페이징 조회.
- `pulse-bundle`, `pulse-noop`: 이름이 같은 `Pulse`, `PulseHandle` API.
- `pulse-ui-compose`: 트래픽 목록과 상세 화면, 한국어와 영어 지원.
- `sample-app`: 앱 내장 데모 서버로 다양한 상황을 재현.
- JSON 본문 트리 뷰, 캐시 응답과 연결 재사용 근거(Room 스키마 v2·v3, 마이그레이션 테스트).
- `pulse-analytics`: 기간별 통계(p50/p95/p99 등), 진단 규칙 6개, 원본 요청과 재실행 결과 비교. 개요, 진단, 설정 탭.
- `pulse-export`: HAR 1.2 및 PULSE JSON 스트리밍 내보내기(내보낼 때 다시 마스킹), cURL 복사.
- `pulse-replay`: 허용 호스트, 사용자 확인, 리다이렉트마다 검사하는 재실행 기능(기본값은 꺼짐).
- `pulse-mock`: 직접 추가해서 쓰는 모의 응답 인터셉터와 규칙 관리 화면(기본값은 꺼짐).
- Java용 `ReplayAuthorizer.blocking`, 공개 API 검사(`checkKotlinAbi`, `verifyNoopApi`), 비밀 유출 점검 테스트, JVM 오버헤드 측정.
- Maven 배포 설정(`com.naurylab:pulse-*`), 문서(`docs/`).

### 기기 테스트에서 수정한 문제
- 화면에서 재실행을 보내면 메인 스레드에서 응답을 읽다가 앱이 크래시되던 문제.
- `Pulse.initialize`가 메인 스레드에서 저장 폴더 조회·생성과 TLS 설정 생성을 하던 문제(이제 처음 사용할 때 백그라운드에서 엽니다).
- 다른 탭에 갔다가 돌아오면 트래픽 목록이 갱신되지 않던 문제, 목록 항목을 눌러도 키보드가 닫히지 않던 문제.
- HAR 파일이 `.har.json` 확장자로 저장되던 문제, 넓은 화면에서 항목을 선택하기 전에 상세 영역이 빈 칸으로 보이던 문제.
