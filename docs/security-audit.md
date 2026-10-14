# 보안 점검

위협 모델은 [SECURITY.md](../SECURITY.md)에 있습니다. 이 문서는 각 대응을 어떤 자동 테스트가 확인하는지 정리합니다.

| 위협 | 대응 | 확인하는 테스트 |
| --- | --- | --- |
| 헤더에 담긴 토큰과 쿠키 | 헤더 이름 일치 또는 민감한 단어 포함 여부로 판단해 저장 전에 마스킹 | `RedactorTest`, `PulseInterceptorTest`(POST, Authorization) |
| URL 쿼리, 사용자 정보, fragment | URL 디코딩한 파라미터 이름 기준으로 마스킹하고 userinfo와 fragment는 제거 | `RedactorTest` |
| 중첩 JSON과 배열 속 비밀 | 저장 스레드에서 키 단위로 마스킹 | `BodySanitizerTest`, `CaptureEngineTest` |
| 잘못된 JSON, 너무 깊은 중첩, 잘못된 문자 인코딩 | 저장하지 않음(fail closed) | `BodySanitizerTest` |
| form, multipart, 바이너리 본문 | JSON 본문만 저장 | `BodySanitizerTest`, `PulseInterceptorTest` |
| 리다이렉트 Location 헤더의 토큰 | URL 값을 담는 헤더도 URL 규칙으로 마스킹 | `RedactorTest` |
| 실패 메시지에 포함된 URL과 비밀 | 메시지를 마스킹하고 길이를 제한 | `RedactorTest`, `CaptureEngineTest` |
| 저장소에 남는 원본 값 | 저장 타입은 마스킹 단계를 거쳐야만 생성됨 | `PulseStorageTest`(DB 덤프 검사) |
| 기기에 남는 파일과 내보낸 파일 속 비밀 | 위의 대응 전체에 더해, 내보낼 때 한 번 더 마스킹 | **`SecretLeakAuditTest`**: 가짜 비밀 14개를 헤더, 쿼리, 중첩 JSON, 문자열 속 JSON과 URL, form, 잘못된 JSON, Set-Cookie, Location에 넣고 DB, WAL, 본문 파일, HAR, PULSE JSON, cURL을 바이트 단위로 검사 |
| cURL로 자격 증명 복원 | 마스킹된 헤더는 cURL에 넣지 않음 | `CurlFormatterTest` |
| release 빌드에 포함 | no-op 모듈로 교체하고, release 런타임 classpath와 공개 API 일치 여부를 검사 | `verifyReleaseClasspath`, `verifyNoopApi` |
| 다른 앱이 인스펙터 화면을 실행 | 인스펙터 Activity는 `exported=false`입니다. debug 빌드의 런처 아이콘(activity-alias)만 `exported=true`이며, 화면을 띄울 뿐 데이터를 반환하지 않습니다. 알림 버튼 수신기는 `exported=false`입니다 | merged manifest 수동 확인. release 빌드에는 둘 다 없음 |
| 알림에 노출되는 요청 경로 | 기본적으로 알림 권한이 없으므로 알림을 띄우지 않음. 알림을 띄우더라도 잠금 화면에서는 개수만 표시(`VISIBILITY_PRIVATE`) | 수동 확인 |
| 재실행으로 운영 서버 데이터 변경이나 자격 증명 유출 | 기본값은 꺼짐. 허용 호스트, 사용자 확인, 마스킹된 헤더 제거, 리다이렉트마다 검사 | `ReplayEngineTest`, `PulseTest` |
| 모의 응답이 운영 환경에서 동작 | 명시적으로 추가해야만 동작하고, 기본값은 꺼짐이며, 메모리에만 저장 | `MockInterceptorTest` |
| 정규식 ReDoS | 입력 길이 제한과 실행 시간 예산 | `MockInterceptorTest` |
| 수집 실패로 앱 요청이 실패 | fail open으로 동작하고 버린 건수를 집계 | `CaptureEngineTest`, `CaptureQueueTest` |

## 남은 점검

- 실제 기기에서 백업(`adb backup`)과 기기 이전 시 PULSE 데이터가 제외되는지 확인해야 합니다.
- 스크린샷 차단(FLAG_SECURE) 옵션은 아직 없습니다.
- 저장 데이터는 암호화하지 않습니다(SECURITY.md의 알려진 한계).
