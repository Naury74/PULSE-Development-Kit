# Changelog

이 프로젝트는 [Semantic Versioning](https://semver.org/lang/ko/)을 따릅니다. 0.x 동안 공개 API는 바뀔 수 있습니다.

## [Unreleased]

## [0.1.0-alpha01] — 배포 준비 중

첫 공개 알파. 공개 API는 0.x 동안 바뀔 수 있습니다.

### 추가
- 멀티 모듈 빌드(build-logic convention plugin, version catalog)와 CI: 단위 테스트, Android Lint, ktlint, detekt, release no-op 확인.
- `pulse-api`: `PulseConfig`·`CaptureLimits`·`RetentionPolicy`·`RedactionPolicy`, 트랜잭션 모델, `PulseInspector` 조회 계약.
- `pulse-security`: 헤더·쿼리·JSON 키 마스킹, URL 사용자 정보·fragment 제거, JSON 외 본문 저장 거부.
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
