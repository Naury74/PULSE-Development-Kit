# 기록 방식

## 트랜잭션

OkHttp `Call` 하나(논리 요청)가 트랜잭션 하나입니다. 리다이렉트·재시도는 같은 트랜잭션 안의 교환(exchange)으로 타이밍에만 나타납니다.

| 상태 | 뜻 |
| --- | --- |
| `IN_FLIGHT` | 보냈고 아직 응답 본문이 끝나지 않음 |
| `COMPLETED` | HTTP 응답을 받음(4xx·5xx 포함) |
| `FAILED` | I/O 예외(연결 실패, 시간 초과 등). 본문을 읽다 실패하면 상태 코드와 실패가 함께 있음 |
| `CANCELED` | 앱이 `Call`을 취소 |
| `INTERRUPTED` | 앱 프로세스가 끝나 결과를 모름(다음 실행 때 표시) |

소요 시간은 interceptor 진입부터 응답 본문이 끝나거나(EOF·닫힘) 실패할 때까지의 단조 시계 값입니다. HEAD·1xx·204·304와 길이 0 응답은 헤더를 받을 때 끝납니다.

## 본문

본문은 호스트가 실제로 주고받은 바이트만 복사합니다. 미리 읽거나 다시 쓰지 않습니다.

| 상태 | 뜻 |
| --- | --- |
| `CAPTURED` | 마스킹한 JSON 저장 |
| `EMPTY` | 본문 없음 |
| `OMITTED_BY_POLICY` | 메타데이터 모드 |
| `UNSUPPORTED_CONTENT` | JSON이 아님, 또는 호스트가 직접 압축을 요청해 압축된 채로 받음 |
| `TOO_LARGE` | 상한 초과(잘린 JSON은 마스킹할 수 없어 저장 안 함) |
| `ONE_SHOT_UNSUPPORTED` / `DUPLEX_UNSUPPORTED` | 다시 쓸 수 없는 요청 본문 |
| `INCOMPLETE_READ` | 호스트가 끝까지 읽지 않음 |
| `REDACTION_FAILED` / `DECODE_FAILED` | JSON·문자 인코딩 해석 실패 |
| `DROPPED` | 수집 큐가 가득 참 |
| `PENDING` | 아직 보내거나 받지 않음 |

크기: `requestBodyBytes`·`responseBodyBytes`는 호스트가 주고받은 바이트(응답은 OkHttp가 압축을 푼 뒤)입니다. `Content-Length`는 따로 `declaredContentLength`로 둡니다.

## 타이밍

`EventListener`가 알린 단계(DNS, CONNECT, TLS, REQUEST_HEADERS, REQUEST_BODY, RESPONSE_HEADERS, RESPONSE_BODY)만 기록하며, 시작은 요청 시작 기준 오프셋입니다. 끝 이벤트가 없으면 길이를 비워 둡니다. 연결을 재사용하거나 캐시에서 응답하면 연결 단계가 없고, 이를 0으로 채우지 않습니다. 캐시 이벤트로 응답 출처(NETWORK·CACHE·CONDITIONAL_CACHE)를, 연결 이벤트로 연결 재사용 여부를 함께 기록합니다. 서버 처리 시간은 따로 추정하지 않습니다.

## 실패 시 동작

수집이 실패해도 앱의 요청은 그대로 진행합니다(fail open). 저장 큐가 넘치면 본문부터 버리고, 버린 수는 화면의 경고와 설정 탭의 수집 상태에 나옵니다. 앱의 예외·취소는 바꾸지 않고 그대로 전달합니다.
