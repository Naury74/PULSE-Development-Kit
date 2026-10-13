# PULSE 평가 가이드

PULSE가 무엇을 하는지, SDK로 쓸 만한지 직접 확인하기 위한 안내입니다. 순서대로 하면 1시간 안팎이 걸립니다.

## PULSE는 무엇인가

Android 앱의 **OkHttp 요청을 기기 안에서 기록하고 살펴보는 개발용 도구**입니다. 앱의 `OkHttpClient`에 interceptor를 하나 붙이면, 앱이 보낸 요청과 받은 응답을 기록하고 앱 안의 화면으로 보여 줍니다.

| 할 수 있는 것 | 하지 않는 것 |
| --- | --- |
| 연결한 OkHttpClient의 요청·응답·실패 기록 | 다른 앱, WebView, Cronet, `HttpURLConnection`의 트래픽 |
| 비밀 값을 **저장하기 전에** 가림 | 원래 비밀 값을 다시 보여 주기 |
| 단계별 시간(DNS·연결·TLS 등), 통계, 진단 | 서버 내부 처리 시간 추정 |
| HAR·JSON 내보내기, cURL 복사 | 외부 서버로 자동 업로드 |
| 재실행·모의 응답(직접 켰을 때만) | 운영 빌드에서 동작 |
| release 빌드에서 완전히 빠짐(no-op) | 기기 전체 패킷 캡처·HTTPS 가로채기 |

## 준비물

`평가/` 폴더(작업문서)에 준비해 두었습니다.

| 파일 | 내용 |
| --- | --- |
| `apk/pulse-sample-debug.apk` | 샘플 앱(debug): 앱 안 데모 서버로 여러 경우를 재현, 인스펙터 포함 |
| `apk/pulse-sample-release.apk` | 같은 샘플 앱의 release: PULSE가 no-op으로 바뀐 빌드 |
| `apk/pulse-consumer-debug.apk` / `-release.apk` | 연동 예제(아래 B)를 미리 빌드한 것. 인터넷(httpbin.org) 필요 |
| `연동예제/` | 빈 Android 앱에 PULSE를 붙인 최소 프로젝트(Android Studio로 열기) |

설치: `adb install -r apk/pulse-sample-debug.apk` (같은 앱의 debug·release를 바꿔 설치할 때는 먼저 `adb uninstall com.naury.pulse.sample`)

연동 예제를 직접 빌드하려면 저장소에서 `./gradlew publishToMavenLocal`을 한 번 실행해 두어야 합니다(이미 해 두었습니다). Maven Central 배포 전이라 `mavenLocal()`에서 받습니다.

## A. 기능 둘러보기 — 샘플 앱 (20분)

`pulse-sample-debug.apk`를 설치하고 실행합니다. 각 카드를 누른 뒤 **인스펙터 열기**로 확인합니다.

| 해 볼 것 | 기대 결과 | 확인 포인트 |
| --- | --- | --- |
| 처음 열기 | 트래픽 위에 알림 켜기 안내 | "켜기" 후 알림에 요청 수·오류 수·최근 요청, 알림을 누르면 인스펙터. 홈 화면 앱 목록에 PULSE 아이콘 |
| GET JSON | 트래픽에 `GET /users/1`과 `200` | 상세 → 응답의 `accessToken`이 `[REDACTED]`, URL의 `api_key` 값도 가려짐 |
| POST 로그인 | `200 POST /login` | 요청 탭: `Authorization` 헤더와 본문 `password`가 가려짐 |
| 404, 500 | 상태가 색과 글자로 구분 | 실패 필터에는 안 나오고 4xx·5xx 필터에 나옴 |
| 읽기 시간 초과, 취소 | `실패`, `취소`로 따로 표시 | 앱이 받은 예외는 그대로(카드에 `SocketTimeoutException`) |
| 리다이렉트 302 → 200 | 요청 하나로 기록 | 타임라인에 교환 두 개 |
| gzip 응답 | 풀린 JSON이 보이고 가려짐 | 앱이 받은 본문은 PULSE가 없을 때와 같음 |
| 큰 스트리밍 JSON | 본문 "저장 안 함: 수집 상한보다 큼" | 앱은 약 1MB를 끝까지 받음 |
| 바이너리 이미지 | 본문 "JSON만 저장" | |
| 동시 요청 20개 | 20개가 각자 자기 응답과 함께 기록 | 섞이지 않음 |
| 오류 몰림, 같은 GET 반복 | 진단 탭에 "오류 몰림", "캐시 적용 후보" | 기준값·관찰값·근거 요청이 함께 나옴, 추정은 "추정"으로 표시 |
| 개요 탭 | 요청 수·오류율·p95·응답 크기, 시간별 막대 | 오류 타일을 누르면 거른 목록으로 이동 |
| 상세 → 공유 | 공유 시트에 Markdown 글 | 결과·URL·헤더·본문·cURL이 들어 있고 비밀 값은 `[REDACTED]` |
| 상세 → 타임라인 | 단계별 색 막대와 "응답 대기" | 관찰하지 못한 연결 단계는 따로 알림 |
| 요청 탭 → 재실행 | 허용 호스트 표시, 가려진 헤더는 빠진 채 시작 | POST는 확인란을 체크해야 보내짐, 결과는 "재실행" 표시와 원본 비교 |
| 설정 → 모의 응답 규칙 추가·켜기 → GET JSON | 규칙의 상태 코드가 돌아옴 | 트래픽에 경고 띠와 "모의 응답" 표시 |
| 트래픽 → 내보내기(공유 아이콘) | 파일 선택기로 `.har` 저장 | 파일에 비밀 값이 없는지, 관찰 못 한 타이밍이 -1인지 |
| 기록 멈춤 후 요청 | 저장되지 않음 | |
| 지우기 | 확인 후 모두 삭제 | |
| 기기 회전·다크 모드·영어 | 상태 유지, 화면 정상 | 폴드·태블릿에서는 왼쪽 레일 + 두 칸 |

release(`pulse-sample-release.apk`)로 바꿔 설치하면 상태가 `DISABLED`, 요청은 정상, 인스펙터 열기는 "이 빌드에는 인스펙터가 없습니다"가 떠야 합니다.

## B. 내 앱에 붙여 보기 (5~10분)

`연동예제/`가 빈 앱에 PULSE를 붙인 결과입니다. 바뀐 곳은 `settings.gradle.kts`의 `mavenLocal()`, 의존성 세 줄, `Application` 초기화, 화면을 여는 버튼입니다. 예제는 재실행 허용 호스트(`httpbin.org`)와 모의 응답 interceptor도 켜 둡니다.

```kotlin
// app/build.gradle.kts
debugImplementation("com.naurylab:pulse-bundle:0.1.0-alpha01")
debugImplementation("com.naurylab:pulse-ui-compose:0.1.0-alpha01")
releaseImplementation("com.naurylab:pulse-noop:0.1.0-alpha01")
```

```kotlin
// Application
val pulse = Pulse.initialize(this, PulseConfig.debugDefaults())
val client = OkHttpClient.Builder()
    .addInterceptor(pulse.interceptor())
    .eventListenerFactory(pulse.eventListenerFactory())
    .build()
// 아무 화면에서
Pulse.open(context)
```

실제 앱에 붙일 때 확인할 것:

- [ ] Gradle 동기화·debug·release 빌드가 그대로 되는가(minSdk 26, OkHttp 5. 라이브러리를 Kotlin 2.4.21로 빌드했으므로 그보다 오래된 Kotlin을 쓰는 앱은 메타데이터 호환 오류가 없는지 확인)
- [ ] 기존 interceptor·`EventListener.Factory`와 같이 써도 되는가(`eventListenerFactory(기존팩토리)`)
- [ ] 화면·API 응답이 PULSE를 붙이기 전과 같은가(본문, 오류, 스트리밍, 파일 다운로드)
- [ ] 우리 API의 민감한 필드가 기본 규칙으로 가려지는가. 아니면 `RedactionPolicy.strict().withSensitiveNames(...)`로 더할 수 있는가
- [ ] 앱 시작 시간·스크롤이 체감상 달라지지 않는가
- [ ] release APK에 PULSE가 남지 않는가(아래 C)

## C. 안전성 직접 확인하기

```bash
# 1) 기기에 저장된 PULSE 파일에서 비밀 값 찾기(debug 앱, 예: 샘플)
adb shell run-as com.naury.pulse.sample ls -R no_backup/pulse
adb exec-out run-as com.naury.pulse.sample tar cf - no_backup/pulse > pulse.tar && tar xf pulse.tar
grep -r "demo-password\|demo-bearer-token\|demo-access-token\|demo-key" no_backup/ || echo "없음"

# 2) release APK에 수집 코드가 없는지
unzip -o -q pulse-sample-release.apk 'classes*.dex' -d rel && \
  LC_ALL=C grep -a -c "com/naury/pulse/storage" rel/classes*.dex   # 0이어야 함
```

자동 테스트로도 확인합니다: `SecretLeakAuditTest`가 가짜 비밀 14개를 심고 DB·WAL·본문 파일·HAR·JSON·cURL을 검사합니다([security-audit.md](security-audit.md)).

## 판단에 쓸 수 있는 수치 (2026-10-13 측정)

| 항목 | 값 | 비고 |
| --- | --- | --- |
| release APK 크기 증가 | **약 +2KB** (211,022 → 212,951 bytes) | no-op만 들어감, R8 적용. 연동 예제와, PULSE만 뺀 같은 앱 비교 |
| debug APK 크기 증가 | 약 +12MB (1,605,580 → 13,597,997 bytes) | Compose·Room 등 포함, 축소 안 함. Compose를 이미 쓰는 앱은 더 적음 |
| 요청당 추가 시간 | 중앙값 약 7~34µs | Mac JVM 루프백 측정, **기기 측정 아님**([benchmarks.md](benchmarks.md)) |
| 메인 스레드 디스크·네트워크 | 0건 | 에뮬레이터 StrictMode 점검 |
| 자동 테스트 | 156개 통과 | 단위·Robolectric·UI, CI 동일 |
| 공개 API 보호 | ABI 덤프, no-op·debug 시그니처 비교 | CI에서 깨지면 실패 |

## 준비하면서 확인한 것 (2026-10-13, Android 16 에뮬레이터)

연동 예제(debug)로 httpbin.org에 실제 요청을 보내 확인했습니다.

- 11종 요청이 모두 기록되고, 앱이 받은 응답 크기는 release(PULSE 없음)와 같았습니다.
- 진단 탭에 "오류 몰림"(500 6번)과 "느린 요청"이 근거 요청과 함께 나왔습니다.
- POST 재실행: 허용 호스트 표시, 가려진 값 경고, 확인란을 체크한 뒤 전송, 결과가 "재실행"으로 기록됐습니다.
- release: 요청은 정상이고 인스펙터 대신 안내가 떴으며, 크래시는 0건이었습니다.
- **찾아서 고친 누출**: httpbin처럼 받은 요청을 되돌려 주는 응답에서, 문자열 값 안에 든 JSON(`"data":"{\"password\":...}"`)과 URL 쿼리(`"url":"...?token=..."`)가 가려지지 않고 기기에 저장됐습니다. 이제 문자열 속 JSON·URL·`name=value`도 저장 전에 가립니다. 고친 뒤 기기 저장소(DB·WAL·본문 파일)에서 심은 비밀이 나오지 않는 것을 확인했고, `SecretLeakAuditTest`에도 이 경우를 넣었습니다.

## 알려진 한계 (평가 때 감안할 것)

- **배포 전 알파**입니다. Maven Central에 아직 없고, 공개 API는 0.x 동안 바뀔 수 있습니다.
- 본문은 **JSON만** 저장합니다. form·multipart·텍스트·이미지 본문은 메타데이터만 남습니다.
- application interceptor 위치라 OkHttp가 나중에 붙이는 헤더(`Host`, `Accept-Encoding`, 쿠키 저장소 쿠키)는 안 보입니다.
- 기기 성능(Macrobenchmark)은 아직 재지 않았습니다. 기기 테스트는 Android 16 에뮬레이터(폴드)에서만 했고 API 26 실기기·저사양 기기는 확인하지 않았습니다.
- 저장 데이터 암호화, 스크린샷 차단, WebSocket 프레임 기록은 없습니다.
- 마스킹은 이름 규칙 기반입니다. 키 이름이 평범한 값(예: `"memo": "비밀번호는 1234"`)이나 응답의 IP 주소 같은 개인정보는 가리지 않습니다. 우리 API의 필드 이름을 `withSensitiveNames`로 더해야 합니다.
- 진단 규칙 임계값은 일반적인 기본값입니다(`InsightThresholds`로 조정).

## 평가 기록 양식

| 항목 | 결과(O/△/X) | 메모 |
| --- | --- | --- |
| 5분 안에 연동되는가 | | |
| 앱 동작이 바뀌지 않는가 | | |
| 우리 API의 비밀이 가려지는가 | | |
| 문제 원인을 찾는 데 도움이 되는가(상세·타임라인·진단) | | |
| 공유(HAR·cURL)가 실무에 쓸 만한가 | | |
| 재실행·모의 응답이 쓸 만한가 | | |
| release에서 완전히 빠지는가 | | |
| 문서만 보고 쓸 수 있는가 | | |
| 알림·런처 아이콘·공유가 팀 작업 흐름에 맞는가 | | |
| 꼭 필요한데 없는 기능 | | |
