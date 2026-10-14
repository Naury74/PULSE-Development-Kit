# Changelog

이 프로젝트는 [Semantic Versioning](https://semver.org/lang/ko/)을 따릅니다. 0.x 동안 공개 API는 바뀔 수 있습니다.

## [Unreleased]

### 추가
- 인스펙터 화면을 다시 디자인: 메서드 색 태그·상태 띠가 있는 목록, 결과와 핵심 수치를 모은 상세 머리말, 색을 입힌 JSON, 단계별 색과 "응답 대기" 구간이 있는 타임라인, 카드형 개요·진단·설정.
- 앱에 버튼 없이 쓰는 진입점: debug 빌드의 런처 아이콘과 요청 수·오류·최근 요청을 보여 주는 알림(멈춤·재개·지우기). 리소스 값으로 끌 수 있음.
- 요청 하나를 Markdown 글로 공유(결과·헤더·본문·cURL).
- `PulseInspectorRegistry.updates`: 초기화·종료를 알리는 흐름.
- 수집 옵션: `CaptureFilter`(호스트·경로 거르기), `ContextCapture`(화면·앱 상태·네트워크 종류), `PulseLabel`(요청 라벨), `EndpointGrouping`(경로 템플릿), `BodyFormats`(form·텍스트 본문).
- 통계 확장: `StatsQuery`로 호스트·화면·라벨 거르기, 분당 요청, p75·p90·평균·최대, 첫 응답 시간, 소요 시간 분포, 상태 코드·실패 종류, DNS·연결·TLS 단계, 연결 재사용·캐시, 엔드포인트(오류·크기 포함), 기준별 분류.
- 대시보드: 필터 칩, 지표 8개, 요청 수·응답 시간 추이, 분포, 결과 구성, 연결 단계, 정렬 가능한 엔드포인트 표, 기준별 분류(누르면 그 값으로 거름).
- 저장소 스키마 v4(맥락 열 추가, 자동 마이그레이션).
- OkHttp가 실제로 보낸·받은 헤더를 따로 기록하고 상세에서 "앱/실제 전송" 전환(스키마 v5).
- 요청 내보내기 옵션 `ExportOptions`(헤더·본문·재실행 포함 여부), CSV·Markdown 형식, 목록에서 골라 내보내기, 파일 저장 또는 바로 공유.
- 원형 지표(도넛·링), 공통 시트·확인 대화상자, 샘플 앱 테스트 요청 화면 개편(범주, 결과·시간, 모두 실행).
- 앱 진단 `AppMonitoring`: 크래시, 지난 실행의 종료 이유(`ApplicationExitInfo`), 메인 스레드 멈춤, 콜드 스타트·화면 로딩·프레임·메모리, 이 앱의 logcat, 실행(세션) 시간.
- `Pulse.log`·`recordError`·`mark`·`beginSpan`/`endSpan`, 앱 이벤트 조회 API(`EventQuery`, `loadEvents`, `loadSessions`, `loadAppStatistics`). 스키마 v6.
- 로그 탭(묶음·수준·검색·기간, 앞뒤 1분·구간만 보기, 텍스트·JSON·CSV 내보내기), 이슈 탭(원인별 묶음 + 네트워크 진단), 대시보드의 앱 화면.
- 날짜·실행으로 보기: 오늘·어제·7일·30일, 달력 범위, 실행 하나. `TransactionQuery`·`StatsQuery`에 `fromEpochMillis`/`toEpochMillis`.
- 앱 화면 위 플로팅 버튼(마커·이름 마커·구간·화면 캡처, 기본 꺼짐).
- 원격 오류 보고 `RemoteReporting`·`PulseRemote`: 저장할 때 보낼 목록에 넣어 앱이 죽어도 다음 실행 때 보내고, 실패하면 간격을 늘려 다시 보냄. 설정 탭에서 기기마다 주소·토큰·켜기를 바꿈. 스키마 v7.
- 통합 타임라인(`loadTimeline`, `TimelineQuery`)과 연결된 근거(`loadRelated`, 기록된 연결·같은 화면·근접 등급). 구간 안에서 시작한 요청에 구간 ID(`RequestContext.spanId`), `TransactionQuery`·`EventQuery`에 `sessionId`·`spanId`. 스키마 v8.
- `Pulse.action`·`Pulse.screen`·`Pulse.setFlag`와 이벤트 종류 `ACTION`·`NAVIGATION`·`CONFIG`, 환경 정보(`environment()`, `flags`). `PulseIgnore` 태그.
- 버그 보고(`PulseReports`): 작성기, 화면 캡처 가리기(픽셀 덮어쓰기)·표시 편집기, 근거 추천, 초안 저장, 재현 패키지(`ReproPackageWriter`, `pulse.repro.v1`).
- 보고 목적지(`Reporting`, `ReportDestination`): 허용 호스트, 보낼 때마다 부르는 `ReportHeaderProvider`, JSON·multipart, 재시도·중복 방지 헤더, 직접 전송(`ReportTransport`).
- `ScreenshotPolicy`와 `FLAG_SECURE` 화면 캡처 차단.
- 네트워크 장애 모사(`ChaosPolicy`, `ChaosRule`, `PulseChaos`).
- 샘플 앱: 앱 진단 시나리오(로그·구간·처리 오류·원격 보고 왕복·멈춤·크래시)와 데모 서버의 `/collect`.

### 바뀜
- `loadStatistics(window)`는 재실행·모의 응답 기록을 빼고 셉니다. 넣으려면 `StatsQuery(includeSynthetic = true)`.
- 인스펙터 탭 구성: 개요·트래픽·타임라인(전체 흐름·앱 로그)·이슈·설정. 진단은 이슈 탭의 "네트워크 진단"으로 옮김.
- `RetentionPolicy`에 `maxEvents`(기본 50,000) 추가.

## [0.1.0-alpha01] — 배포 준비 중

첫 공개 알파. 공개 API는 0.x 동안 바뀔 수 있습니다.

### 추가
- 멀티 모듈 빌드(build-logic convention plugin, version catalog)와 CI: 단위 테스트, Android Lint, ktlint, detekt, release no-op 확인.
- `pulse-api`: `PulseConfig`·`CaptureLimits`·`RetentionPolicy`·`RedactionPolicy`, 트랜잭션 모델, `PulseInspector` 조회 계약.
- `pulse-security`: 헤더·쿼리·JSON 키 마스킹(문자열 속 JSON·URL·`name=value`도 포함), URL 사용자 정보·fragment 제거, JSON 외 본문 저장 거부.
- `pulse-core`: 막지 않는 상한 큐(본문부터 버림), 저장 스레드, 버린 수·저장 실패 집계.
- `pulse-okhttp`: application interceptor와 `EventListener` 단계 타이밍, 호스트가 읽은 바이트만 복사하는 본문 tee.
- `pulse-storage`: Room 스키마 v1, 본문 파일, 건수·기간·용량 보관 정책, keyset 페이지 조회.
- `pulse-bundle`·`pulse-noop`: 같은 이름의 `Pulse`·`PulseHandle` API.
- `pulse-ui-compose`: 트래픽 목록·상세 화면, 한국어·영어.
- `sample-app`: 앱 안 데모 서버로 여러 경우를 재현.
- JSON 본문 트리 보기, 캐시 응답·연결 재사용 근거(Room 스키마 v2·v3, 마이그레이션 테스트).
- `pulse-analytics`: 기간별 통계(p50/p95/p99 등), 진단 규칙 6개, 원본·재실행 비교. 개요·진단·설정 탭.
- `pulse-export`: HAR 1.2·PULSE JSON 스트리밍 내보내기(다시 마스킹), cURL 복사.
- `pulse-replay`: 허용 호스트·확인·리다이렉트 단계별 검사가 있는 재실행(기본 꺼짐).
- `pulse-mock`: 직접 붙이는 모의 응답 interceptor와 규칙 화면(기본 꺼짐).
- Java용 `ReplayAuthorizer.blocking`, 공개 API 검사(`checkKotlinAbi`, `verifyNoopApi`), 비밀 유출 점검 테스트, JVM 오버헤드 측정.
- Maven 배포 설정(`com.naurylab:pulse-*`), 문서(`docs/`).

### 기기 점검에서 고친 것
- 재실행을 화면에서 보내면 메인 스레드에서 응답을 읽어 앱이 죽던 문제.
- `Pulse.initialize`가 메인 스레드에서 저장 폴더 조회·생성과 TLS 설정 생성을 하던 문제(이제 처음 쓸 때 백그라운드에서 연다).
- 다른 탭에 있다 돌아오면 트래픽 목록이 갱신되지 않던 문제, 목록을 눌러도 키보드가 남던 문제.
- HAR 파일이 `.har.json`으로 저장되던 문제, 넓은 화면에서 선택 전 빈 상세 칸.
