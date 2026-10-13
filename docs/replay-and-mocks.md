# 재실행과 모의 응답

둘 다 기본으로 꺼져 있고, release(no-op)에서는 동작하지 않습니다.

## 재실행

```kotlin
PulseConfig.debugDefaults().newBuilder()
    .replay(ReplayPolicy(setOf("dev-api.example.com", "*.staging.example.com")))
    .build()
```

- **허용 호스트**: 정확한 이름 또는 `*.도메인`(하위 도메인만). 운영 서버는 넣지 마세요. 기본은 `https`만 허용합니다(`allowCleartext`).
- **초안**: 저장된 요청에서 값이 가려진 헤더와 쿼리 값, 전송 헤더(`Host`, `Content-Length` 등)를 빼고 시작합니다. 화면에서 메서드·URL·헤더·본문을 고칠 수 있습니다.
- **자격 증명**: PULSE는 원래 인증 값을 갖고 있지 않습니다. 필요하면 `pulse.setReplayAuthorizer { draft -> listOf(HeaderField("Authorization", "Bearer ...")) }`로 앱이 직접 줍니다. 이 헤더는 원래 호스트로 가는 요청에만 붙습니다.
- **확인**: POST·PUT·PATCH·DELETE 등 상태를 바꿀 수 있는 요청과 자격 증명을 붙이는 요청은 확인이 있어야 보냅니다. 화면에서는 확인란을 체크해야 보내기 버튼이 켜집니다.
- **리다이렉트**: 직접 따라가며 단계마다 호스트·scheme을 다시 검사합니다. 허용하지 않은 곳으로 향하면 멈추고, `maxRedirects`를 넘어도 멈춥니다.
- **실행**: 동시에 하나만, 제한 시간 안에서. 진행 중 취소할 수 있습니다.
- **기록**: 앱 클라이언트와 분리된 클라이언트로 보내 `REPLAY` 출처와 원본 ID로 기록하고, 통계·진단에서 뺍니다. 결과 상세의 "원본과 비교"에서 상태·쿼리·헤더·JSON 경로 차이를 볼 수 있습니다(가려진 값은 비교하지 않음).

## 모의 응답

```kotlin
OkHttpClient.Builder()
    .addInterceptor(pulse.interceptor())
    .addInterceptor(pulse.mockInterceptor()) // 반드시 PULSE 인터셉터 뒤에
    .build()
```

- `mockInterceptor()`를 직접 붙여야 동작합니다. 전체 스위치(`pulse.mocks.setEnabled`)는 꺼진 채 시작하고, 규칙은 메모리에만 있어 앱을 다시 시작하면 사라집니다.
- 규칙: 메서드·호스트(비우면 모두), 경로(정확·접두·정규식), 우선순위, 사용 횟수, 만료 시각, 상태 코드·헤더·본문·지연.
- 고르는 순서: 우선순위 높은 것 → 정확·접두·정규식 → 먼저 만든 것.
- 정규식은 256자 이하만 받고, 맞출 때 50ms를 넘으면 맞지 않는 것으로 봅니다(역추적 폭주 방지).
- 합성 응답에는 `X-Pulse-Mock: <규칙 ID>` 헤더가 붙고, `MOCK` 출처로 기록되어 통계에서 빠집니다. 네트워크 단계는 없습니다.
- 모의 응답이 켜져 있으면 트래픽 탭과 설정 탭에 경고가 보입니다.
