# 기록 방식

## 트랜잭션

OkHttp `Call` 하나(논리적인 요청 하나)를 트랜잭션 하나로 기록합니다. 리다이렉트와 재시도는 같은 트랜잭션 안의 교환(exchange)으로 처리되며, 타이밍에만 드러납니다.

| 상태 | 뜻 |
| --- | --- |
| `IN_FLIGHT` | 요청을 보냈고 응답 본문을 아직 다 받지 않음 |
| `COMPLETED` | HTTP 응답을 받음(4xx, 5xx 포함) |
| `FAILED` | I/O 예외 발생(연결 실패, 타임아웃 등). 본문을 읽다가 실패하면 상태 코드와 실패 정보가 함께 남음 |
| `CANCELED` | 앱이 `Call`을 취소함 |
| `INTERRUPTED` | 앱 프로세스가 종료되어 결과를 알 수 없음(다음 실행 때 표시) |

소요 시간은 인터셉터에 진입한 시점부터 응답 본문이 끝나거나(EOF 또는 닫힘) 실패한 시점까지를 단조 시계로 잰 값입니다. HEAD, 1xx, 204, 304 응답과 길이가 0인 응답은 헤더를 받은 시점에 끝난 것으로 봅니다.

## 본문

본문은 앱이 실제로 주고받은 바이트만 복사합니다. PULSE가 본문을 미리 읽거나 다시 쓰지 않습니다.

| 상태 | 뜻 |
| --- | --- |
| `CAPTURED` | 마스킹한 JSON을 저장함 |
| `EMPTY` | 본문이 없음 |
| `OMITTED_BY_POLICY` | 메타데이터 모드라서 저장하지 않음 |
| `UNSUPPORTED_CONTENT` | JSON이 아니거나, 앱이 직접 압축을 요청해서 압축된 상태로 받음 |
| `TOO_LARGE` | 크기 상한을 넘음(잘린 JSON은 마스킹할 수 없어 저장하지 않음) |
| `ONE_SHOT_UNSUPPORTED` / `DUPLEX_UNSUPPORTED` | 다시 읽을 수 없는 요청 본문 |
| `INCOMPLETE_READ` | 앱이 본문을 끝까지 읽지 않음 |
| `REDACTION_FAILED` / `DECODE_FAILED` | JSON 또는 문자 인코딩 해석에 실패함 |
| `DROPPED` | 수집 큐가 가득 참 |
| `PENDING` | 아직 보내거나 받지 않음 |

크기: `requestBodyBytes`와 `responseBodyBytes`는 앱이 실제로 주고받은 바이트 수입니다(응답은 OkHttp가 압축을 푼 뒤 기준). `Content-Length` 값은 `declaredContentLength`에 따로 저장합니다.

## 타이밍

`EventListener`가 알려 준 단계(DNS, CONNECT, TLS, REQUEST_HEADERS, REQUEST_BODY, RESPONSE_HEADERS, RESPONSE_BODY)만 기록하며, 각 단계의 시작은 요청 시작 시점으로부터의 오프셋입니다. 종료 이벤트가 없으면 길이를 비워 둡니다. 연결을 재사용했거나 캐시에서 응답한 경우에는 연결 단계가 없으며, 이를 0으로 채우지 않습니다. 캐시 이벤트로 응답 출처(NETWORK, CACHE, CONDITIONAL_CACHE)를, 연결 이벤트로 연결 재사용 여부를 함께 기록합니다. 서버 처리 시간은 따로 추정하지 않습니다.

## 실패 시 동작

수집에 실패해도 앱의 요청은 그대로 진행됩니다(fail open). 저장 큐가 넘치면 본문부터 버리고, 버린 개수는 화면의 경고와 설정 탭의 수집 상태에 표시됩니다. 앱에서 발생한 예외와 취소는 바꾸지 않고 그대로 전달합니다.
