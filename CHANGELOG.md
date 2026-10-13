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

### 바뀜
- `loadStatistics(window)`는 재실행·모의 응답 기록을 빼고 셉니다. 넣으려면 `StatsQuery(includeSynthetic = true)`.

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
