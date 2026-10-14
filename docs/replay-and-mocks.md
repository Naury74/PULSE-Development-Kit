# 재실행과 모의 응답

두 기능 모두 기본값은 꺼짐이며, release 빌드(no-op)에서는 동작하지 않습니다.

## 재실행

```kotlin
PulseConfig.debugDefaults().newBuilder()
    .replay(ReplayPolicy(setOf("dev-api.example.com", "*.staging.example.com")))
    .build()
```

- **허용 호스트**: 정확한 호스트 이름 또는 `*.도메인` 형식(하위 도메인만 해당)으로 지정합니다. 운영 서버는 넣지 마세요. 기본적으로 `https`만 허용합니다(`allowCleartext`).
- **초안**: 저장된 요청에서 마스킹된 헤더와 쿼리 값, 전송 관련 헤더(`Host`, `Content-Length` 등)를 뺀 상태로 시작합니다. 화면에서 메서드, URL, 헤더, 본문을 수정할 수 있습니다.
- **자격 증명**: PULSE는 원래의 인증 값을 보관하지 않습니다. 인증이 필요하면 `pulse.setReplayAuthorizer { draft -> listOf(HeaderField("Authorization", "Bearer ...")) }`로 앱이 직접 제공합니다. 이 헤더는 원래 호스트로 가는 요청에만 붙습니다.
- **확인**: POST, PUT, PATCH, DELETE처럼 서버 상태를 바꿀 수 있는 요청과 자격 증명을 붙이는 요청은 확인을 거쳐야 보낼 수 있습니다. 화면에서 확인란을 체크해야 보내기 버튼이 활성화됩니다.
- **리다이렉트**: PULSE가 직접 따라가면서 매 단계마다 호스트와 scheme을 다시 검사합니다. 허용하지 않은 곳으로 리다이렉트되거나 `maxRedirects`를 넘으면 중단합니다.
- **실행**: 한 번에 하나만, 제한 시간 안에서 실행합니다. 진행 중에 취소할 수 있습니다.
- **기록**: 앱의 클라이언트와 분리된 별도 클라이언트로 보내고, `REPLAY` 출처와 원본 ID를 붙여 기록하며, 통계와 진단에서는 제외합니다. 결과 상세 화면의 "원본과 비교"에서 상태, 쿼리, 헤더, JSON 경로별 차이를 볼 수 있습니다(마스킹된 값은 비교하지 않음).

## 모의 응답

```kotlin
OkHttpClient.Builder()
    .addInterceptor(pulse.interceptor())
    .addInterceptor(pulse.mockInterceptor()) // 반드시 PULSE 인터셉터 뒤에 추가
    .build()
```

- 모의 응답(목, mock)을 쓰려면 `mockInterceptor()`를 직접 추가해야 합니다. 전체 스위치(`pulse.mocks.setEnabled`)는 꺼진 상태로 시작하고, 규칙은 메모리에만 저장되므로 앱을 다시 시작하면 사라집니다.
- 규칙 항목: 메서드, 호스트(비워 두면 전체), 경로(정확히 일치, 접두사, 정규식), 우선순위, 사용 횟수, 만료 시각, 상태 코드, 헤더, 본문, 지연 시간.
- 적용 순서: 우선순위가 높은 규칙 → 정확히 일치, 접두사, 정규식 순 → 먼저 만든 규칙.
- 정규식은 256자 이하만 허용하며, 매칭에 50ms 넘게 걸리면 일치하지 않는 것으로 처리합니다(백트래킹 폭주 방지).
- 모의 응답에는 `X-Pulse-Mock: <규칙 ID>` 헤더가 붙고, `MOCK` 출처로 기록되어 통계에서 제외됩니다. 네트워크 단계는 기록되지 않습니다.
- 모의 응답이 켜져 있으면 트래픽 탭과 설정 탭에 경고가 표시됩니다.
