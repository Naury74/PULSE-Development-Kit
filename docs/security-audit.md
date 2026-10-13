# 보안 점검

위협 모델은 [SECURITY.md](../SECURITY.md)에 있습니다. 이 문서는 각 대응이 어떤 자동 테스트로 확인되는지 적습니다.

| 위협 | 대응 | 확인하는 테스트 |
| --- | --- | --- |
| 헤더의 토큰·쿠키 | 이름 일치·민감 단어로 저장 전 마스킹 | `RedactorTest`, `PulseInterceptorTest`(POST·Authorization) |
| URL 쿼리·사용자 정보·fragment | URL 디코딩한 이름으로 마스킹, userinfo·fragment 제거 | `RedactorTest` |
| 중첩 JSON·배열의 비밀 | 키 단위로 저장 스레드에서 마스킹 | `BodySanitizerTest`, `CaptureEngineTest` |
| 잘못된 JSON·깊은 중첩·잘못된 문자 인코딩 | 저장하지 않음(fail closed) | `BodySanitizerTest` |
| form·multipart·바이너리 | JSON만 저장 | `BodySanitizerTest`, `PulseInterceptorTest` |
| 리다이렉트 Location의 토큰 | URL 값 헤더도 URL 규칙으로 마스킹 | `RedactorTest` |
| 실패 메시지 속 URL·비밀 | 메시지 마스킹·길이 제한 | `RedactorTest`, `CaptureEngineTest` |
| 저장소에 남는 원본 | 저장 타입이 마스킹 단계를 거쳐야만 생성됨 | `PulseStorageTest`(DB 덤프 검사) |
| 기기 전체 파일·내보내기에 남는 비밀 | 위 대응 전체 + 내보낼 때 다시 마스킹 | **`SecretLeakAuditTest`**: 가짜 비밀 14개를 헤더·쿼리·중첩 JSON·문자열 속 JSON과 URL·form·잘못된 JSON·Set-Cookie·Location에 심고, DB·WAL·본문 파일·HAR·PULSE JSON·cURL을 바이트 단위로 검사 |
| cURL로 자격 증명 재구성 | 가려진 헤더는 넣지 않음 | `CurlFormatterTest` |
| release 빌드 유입 | no-op 교체, release 런타임 classpath 검사, 공개 API 일치 검사 | `verifyReleaseClasspath`, `verifyNoopApi` |
| 다른 앱이 화면을 엶 | `exported=false` | merged manifest 확인(수동, README 참고) |
| 재실행으로 운영 서버 변경·자격 증명 유출 | 기본 꺼짐, 허용 호스트, 확인, 가려진 헤더 제거, 리다이렉트 단계별 검사 | `ReplayEngineTest`, `PulseTest` |
| 모의 응답이 운영에서 동작 | 명시적으로 붙여야 동작, 기본 꺼짐, 메모리에만 저장 | `MockInterceptorTest` |
| 정규식 ReDoS | 길이 제한·시간 예산 | `MockInterceptorTest` |
| 수집 실패로 앱 요청 실패 | fail open, 버린 수 집계 | `CaptureEngineTest`, `CaptureQueueTest` |

## 남은 점검

- 실제 기기에서 백업(`adb backup`)·기기 이전에 PULSE 데이터가 빠지는지 확인.
- 스크린샷 차단(FLAG_SECURE) 선택 기능은 아직 없다.
- 저장 데이터 암호화는 하지 않는다(SECURITY.md의 알려진 한계).
