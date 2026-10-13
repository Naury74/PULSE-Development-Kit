# Changelog

이 프로젝트는 [Semantic Versioning](https://semver.org/lang/ko/)을 따릅니다. 0.x 동안 공개 API는 바뀔 수 있습니다.

## [Unreleased]

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
