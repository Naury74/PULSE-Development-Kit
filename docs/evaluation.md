# PULSE 평가 가이드

PULSE가 무엇을 하는지, SDK로 쓸 만한지 직접 확인하기 위한 안내입니다. 순서대로 하면 1시간 안팎이 걸립니다.

## PULSE는 무엇인가

Android 앱의 **OkHttp 요청과 앱 상태(로그·크래시·멈춤·성능)를 기기 안에서 기록하고 살펴보는 개발용 진단 도구**입니다. 앱의 `OkHttpClient`에 interceptor를 하나 붙이고 `Pulse.initialize`를 부르면, 요청·응답과 앱 이벤트를 기록하고 앱 안의 화면으로 보여 줍니다.

| 할 수 있는 것 | 하지 않는 것 |
| --- | --- |
| 연결한 OkHttpClient의 요청·응답·실패 기록 | 다른 앱, WebView, Cronet, `HttpURLConnection`의 트래픽 |
| 비밀 값을 **저장하기 전에** 가림 | 원래 비밀 값을 다시 보여 주기 |
| 단계별 시간(DNS·연결·TLS 등), 통계, 진단 | 서버 내부 처리 시간 추정 |
| 크래시·종료 이유·멈춤·로그·성능, 마커·구간, 날짜·실행별 보기 | 네이티브 크래시를 그 자리에서 잡기(다음 실행 때 시스템 기록으로 남김) |
| HAR·JSON·CSV·Markdown·로그 내보내기, cURL 복사 | 직접 켜지 않은 외부 업로드 |
| 원격 오류 보고(주소를 넣었을 때만) | 운영 앱 크래시 수집 서비스 대체 |
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
| 오류 몰림, 같은 GET 반복 | 이슈 탭 → 네트워크 진단에 "오류 몰림", "캐시 적용 후보" | 기준값·관찰값·근거 요청이 함께 나옴, 추정은 "추정"으로 표시 |
| 개요(대시보드) | 지표 8개, 요청 수·응답 시간 추이, 분포, 결과, 연결 단계, 엔드포인트, 기준별 분류 | 엔드포인트가 `/items/{itemId}`로 묶이는지, 분류에서 화면·라벨을 누르면 위 필터가 걸리는지, "재실행·모의 포함"으로 수가 바뀌는지 |
| form 로그인 | 요청 본문에 이름·값 표, `password`는 가려짐 | 라벨 `auth`가 상세 "요청 맥락"에 보임 |
| 상태 확인(기록 제외) | 앱 결과는 HTTP 200 | 트래픽에 `/health`가 없음(필터) |
| 상세 → 공유 | 공유 시트에 Markdown 글 | 결과·URL·헤더·본문·cURL이 들어 있고 비밀 값은 `[REDACTED]` |
| 상세 → 타임라인 | 단계별 색 막대와 "응답 대기" | 관찰하지 못한 연결 단계는 따로 알림 |
| 요청 탭 → 재실행 | 허용 호스트 표시, 가려진 헤더는 빠진 채 시작 | POST는 확인란을 체크해야 보내짐, 결과는 "재실행" 표시와 원본 비교 |
| 설정 → 모의 응답 규칙 추가·켜기 → GET JSON | 규칙의 상태 코드가 돌아옴 | 트래픽에 경고 띠와 "모의 응답" 표시 |
| 트래픽 → 내보내기(공유 아이콘) | 파일 선택기로 `.har` 저장 | 파일에 비밀 값이 없는지, 관찰 못 한 타이밍이 -1인지 |
| 앱 진단 → 디버그 로그 | 로그 탭에 V·D·I·W·E 5줄 | 최소 수준 W로 거르면 2줄, 검색·묶음 칩, ERROR 줄을 누르면 스택 |
| 앱 진단 → 구간 안의 요청 | 로그 탭에 MARK, SPAN ▶, SPAN ■ | SPAN을 열고 "구간만 보기" → 트래픽에 그 구간의 요청 2개만, 위 기간 칩에 구간 이름 |
| 앱 진단 → 처리한 오류 | 이슈 탭에 오류 묶음과 횟수 | 다시 누르면 같은 묶음의 횟수가 늘어남 |
| 앱 진단 → 원격 보고 왕복 | 카드에 "server received N" | 설정 → 원격 오류 보고에 보냄 수·마지막으로 보낸 시각, 주소 바꾸기·끄기 |
| 앱 진단 → 메인 스레드 멈춤 | 화면이 3초 멈춤 | 로그 탭 JANK에 멈춘 시간과 메인 스레드 스택 |
| 앱 진단 → 앱 강제 종료 | 확인 후 앱이 닫힘 | 다시 열면 로그·이슈 탭에 CRASH(스택·화면), 대시보드 → 앱에서 크래시 없는 실행 비율이 내려감 |
| 설정 → 플로팅 PULSE 버튼 켜기 | 앱 화면 위에 둥근 버튼 | 끌어서 옮기기, 마커·구간 시작/끝·화면 캡처. 캡처는 로그 탭에서 이미지로 보임 |
| 기간 칩 → 오늘·어제·달력·실행 | 모든 탭이 그 기간으로 바뀜 | 실행 하나를 고르면 그 실행의 요청·로그만 |
| 로그 탭 → 공유 | 텍스트·JSON·CSV, 지금 조건/전부, 상세 포함, 파일/공유 | 파일에 비밀 값이 없는지 |
| 개요 → 앱 | 크래시 없는 실행 도넛, 실행 수·시간, 날짜별 실행 시간, 화면 성능, 로그·종료 | 크래시를 낸 날짜 막대에 표시 |
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
| release APK 크기 증가 | **약 +2KB** (211,022 → 213,375 bytes) | no-op만 들어감, R8 적용. 연동 예제와, PULSE만 뺀 같은 앱 비교 |
| debug APK 크기 증가 | 약 +12MB (1,605,580 → 13,597,997 bytes) | Compose·Room 등 포함, 축소 안 함. Compose를 이미 쓰는 앱은 더 적음 |
| 요청당 추가 시간 | 중앙값 약 7~34µs | Mac JVM 루프백 측정, **기기 측정 아님**([benchmarks.md](benchmarks.md)) |
| 메인 스레드 디스크·네트워크 | 0건 | 에뮬레이터 StrictMode 점검 |
| 자동 테스트 | 189개 통과 | 단위·Robolectric·UI, CI 동일 |
| 공개 API 보호 | ABI 덤프, no-op·debug 시그니처 비교 | CI에서 깨지면 실패 |

## 준비하면서 확인한 것 (2026-10-13, Android 16 에뮬레이터)

연동 예제(debug)로 httpbin.org에 실제 요청을 보내 확인했습니다.

- 11종 요청이 모두 기록되고, 앱이 받은 응답 크기는 release(PULSE 없음)와 같았습니다.
- 진단 탭에 "오류 몰림"(500 6번)과 "느린 요청"이 근거 요청과 함께 나왔습니다.
- POST 재실행: 허용 호스트 표시, 가려진 값 경고, 확인란을 체크한 뒤 전송, 결과가 "재실행"으로 기록됐습니다.
- release: 요청은 정상이고 인스펙터 대신 안내가 떴으며, 크래시는 0건이었습니다.
- **찾아서 고친 누출**: httpbin처럼 받은 요청을 되돌려 주는 응답에서, 문자열 값 안에 든 JSON(`"data":"{\"password\":...}"`)과 URL 쿼리(`"url":"...?token=..."`)가 가려지지 않고 기기에 저장됐습니다. 이제 문자열 속 JSON·URL·`name=value`도 저장 전에 가립니다. 고친 뒤 기기 저장소(DB·WAL·본문 파일)에서 심은 비밀이 나오지 않는 것을 확인했고, `SecretLeakAuditTest`에도 이 경우를 넣었습니다.

### 앱 진단 (2026-10-14, Android 16 에뮬레이터, 샘플 앱 debug)

- 로그 5개, 마커·구간, 처리한 오류, 메인 스레드 3초 멈춤이 로그 탭에 나오고, "구간만 보기"를 누르면 트래픽 탭에 그 구간의 요청 2개만 남았습니다.
- 크래시 버튼으로 앱을 죽인 뒤 다시 열자 CRASH(스택·화면)와 지난 실행의 종료 이유(CRASH)가 로그·이슈 탭에 나왔고, 크래시 보고는 다음 실행 때 데모 서버로 전송됐습니다(설정 탭 "보냄" 수 증가).
- 플로팅 버튼의 마커·화면 캡처가 기록되고, 캡처에는 버튼이 찍히지 않았습니다.
- **찾아서 고친 것**: 같은 크래시를 앱 기록과 종료 이유로 두 번 세던 것, 백그라운드에서 시스템이 앱을 얼린 시간과 감시 스레드 지연을 메인 스레드 멈춤으로 기록하던 것(46초·1.5초 오탐), 에뮬레이터에서 모든 프레임이 느린 프레임으로 잡히던 것(이제 화면 주사율에 맞춘 마감 시각 기준), 지난 실행의 종료 이유에 지금 화면이 붙던 것, Android 14의 "앱 업데이트로 종료"가 `OTHER(16)`로 보이던 것, 날짜를 고른 빈 목록에 연결 안내가 뜨던 것, 골라 내보내기에서 날짜 조건이 빠지던 것.

## 알려진 한계 (평가 때 감안할 것)

- **배포 전 알파**입니다. Maven Central에 아직 없고, 공개 API는 0.x 동안 바뀔 수 있습니다.
- 본문은 기본으로 **JSON만** 저장합니다. form·텍스트는 `BodyFormats`로 켜야 하고, multipart·이미지는 메타데이터만 남습니다.
- application interceptor 위치라 OkHttp가 나중에 붙이는 헤더(`Host`, `Accept-Encoding`, 쿠키 저장소 쿠키)는 안 보입니다.
- 기기 성능(Macrobenchmark)은 아직 재지 않았습니다. 기기 테스트는 Android 16 에뮬레이터(폴드)에서만 했고 API 26 실기기·저사양 기기는 확인하지 않았습니다.
- 저장 데이터 암호화, 스크린샷 차단, WebSocket 프레임 기록은 없습니다.
- 마스킹은 이름 규칙 기반입니다. 키 이름이 평범한 값(예: `"memo": "비밀번호는 1234"`)이나 응답의 IP 주소 같은 개인정보는 가리지 않습니다. 우리 API의 필드 이름을 `withSensitiveNames`로 더해야 합니다.
- 진단 규칙 임계값은 일반적인 기본값입니다(`InsightThresholds`로 조정).
- 로그 문장 속 개인정보와 화면 캡처는 가리지 못합니다. `logcat`·`screenshotOnCrash`·플로팅 버튼 캡처는 이 점을 감안해 켜세요.
- 원격 보고는 개발·QA용입니다. 같은 보고가 두 번 갈 수 있어 받는 쪽에서 `id`로 걸러야 합니다.

## 평가 기록 양식

| 항목 | 결과(O/△/X) | 메모 |
| --- | --- | --- |
| 5분 안에 연동되는가 | | |
| 앱 동작이 바뀌지 않는가 | | |
| 우리 API의 비밀이 가려지는가 | | |
| 문제 원인을 찾는 데 도움이 되는가(상세·타임라인·진단) | | |
| 크래시·멈춤·로그를 같은 화면에서 보는 것이 도움이 되는가 | | |
| 마커·구간·날짜로 테스트 구간을 다시 찾기 쉬운가 | | |
| 원격 보고 설정 방식(코드·빌드 값·설정 탭)이 팀에 맞는가 | | |
| 공유(HAR·cURL)가 실무에 쓸 만한가 | | |
| 재실행·모의 응답이 쓸 만한가 | | |
| release에서 완전히 빠지는가 | | |
| 문서만 보고 쓸 수 있는가 | | |
| 알림·런처 아이콘·공유가 팀 작업 흐름에 맞는가 | | |
| 꼭 필요한데 없는 기능 | | |
