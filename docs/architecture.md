# 구조

## 모듈

| 모듈 | 종류 | 책임 | 의존 |
| --- | --- | --- | --- |
| `pulse-api` | Kotlin JVM | 설정·모델·`PulseInspector`·재실행·모의 응답 계약 | coroutines |
| `pulse-security` | Kotlin JVM | 헤더·URL·JSON 마스킹 | api |
| `pulse-core` | Kotlin JVM | 상한 큐, 저장 스레드, 기록 상태, `CaptureRecord` | api, security |
| `pulse-okhttp` | Kotlin JVM | interceptor, EventListener 타이밍, 본문 tee | api, core, security, OkHttp |
| `pulse-storage` | Android | Room, 본문 파일, 보관 정책, 조회 | api, core, analytics |
| `pulse-analytics` | Kotlin JVM | 통계, 진단 규칙, 비교 | api |
| `pulse-export` | Kotlin JVM | HAR·JSON·cURL | api, security |
| `pulse-replay` | Kotlin JVM | 재실행 엔진 | api, core, OkHttp |
| `pulse-mock` | Kotlin JVM | 모의 응답 interceptor·규칙 | api, core, OkHttp |
| `pulse-bundle` | Android | `Pulse`·`PulseHandle` 조립(debug) | 위 전부 |
| `pulse-noop` | Android | 같은 API의 빈 구현(release) | api, OkHttp |
| `pulse-ui-compose` | Android Compose | 인스펙터 화면 | api, export, analytics |

UI는 `PulseInspector`만 쓰고 저장소를 직접 보지 않습니다. DI 프레임워크 없이 `PulseHandle`이 생성자로 조립합니다.

## 데이터 흐름

```
앱 OkHttp ─► PulseInterceptor ──(헤더·URL 마스킹)──► CaptureQueue(상한, 본문부터 버림)
          └► EventListener ─(단계·근거)─────────────┘          │
                                                     저장 스레드: 본문 JSON 마스킹
                                                               │
                                                   PulseStorage(Room + 본문 파일)
                                                               │
                           PulseInspector(조회·통계·진단) ─► Compose 화면 / 내보내기
```

- 네트워크 스레드에서는 디스크 I/O와 JSON 해석을 하지 않습니다.
- 저장 타입(`CaptureRecord`)은 `pulse-core` 안에서만 만들 수 있어, 마스킹을 거치지 않은 값이 저장될 경로가 없습니다.
- 재실행·모의 응답은 같은 파이프라인에 `REPLAY`·`MOCK` 출처로 기록됩니다.

## 결정 기록

[adr/](adr) 참고: 모듈 분리(0001), 저장 전 마스킹(0002), 본문 tee(0003), 정적 분석 설정(0004).
