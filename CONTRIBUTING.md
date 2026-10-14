# 기여 가이드

## 빌드

JDK 25(Android Studio에 포함된 JBR)를 사용합니다.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew check :sample-app:assembleDebug :sample-app:assembleRelease
```

PR을 올리기 전에 위 명령이 통과해야 합니다. `check`에는 다음과 같은 공개 API 검사가 포함되어 있습니다.

- `pulse-api`의 공개 ABI는 `pulse-api/api/pulse-api.api`에 고정되어 있습니다. 의도한 변경이라면 `./gradlew :pulse-api:updateKotlinAbi`로 파일을 갱신해 함께 커밋합니다. 0.x 버전이라도 변경 내역은 CHANGELOG에 기록합니다.
- `verifyNoopApi`는 `pulse-noop`의 `Pulse`, `PulseHandle`이 `pulse-bundle`과 같은 공개 시그니처를 갖는지 확인합니다. 한쪽에 공개 멤버를 추가하면 다른 쪽에도 추가해야 합니다.

CI(`.github/workflows/ci.yml`)에서도 같은 명령을 실행합니다. 코드 포맷은 `./gradlew ktlintFormat`으로 자동 정리할 수 있습니다.

## 작업 흐름

1. `main`에서 티켓 단위로 브랜치를 만듭니다. 예: `feat/PULSE-102-capture-interceptor`, `fix/PULSE-127-truncated-body`
2. 커밋 하나에는 하나의 변경 의도만 담습니다. [Conventional Commits](https://www.conventionalcommits.org/) 형식을 따르고, 제목은 한국어로, scope는 모듈 이름으로 씁니다.

```text
feat(okhttp): 응답 본문 tee에 부분 읽기 처리 추가
fix(security): 잘못된 JSON 본문 저장 차단
test(storage): 보관 기간 정책 확인
build: Kotlin 2.4.21로 올림
```

| scope | 모듈 |
| --- | --- |
| `api` | pulse-api |
| `security` | pulse-security |
| `core` | pulse-core |
| `okhttp` | pulse-okhttp |
| `storage` | pulse-storage |
| `bundle` / `noop` | pulse-bundle / pulse-noop |
| `ui` | pulse-ui-compose |
| `sample` | sample-app |

## 규칙

- 호스트 앱의 요청 결과나 응답 본문을 읽는 방식에 영향을 주지 않아야 합니다. `ResponseBody.string()`처럼 본문을 미리 소비하는 코드를 넣지 마세요.
- 마스킹은 반드시 저장 전에 합니다. 저장소 계층은 `pulse-core`의 `CaptureRecord`만 받습니다.
- 네트워크 콜백 스레드에서는 디스크 I/O나 JSON 파싱을 하지 않습니다.
- 공개 API는 `com.naury.pulse.api` 패키지에 두고, `Pulse`와 `PulseHandle`의 시그니처를 바꿀 때는 `pulse-bundle`과 `pulse-noop`을 함께 수정합니다.
- 주석과 KDoc은 한국어로 씁니다. KDoc은 공개 API 계약에만 달고 단위, 범위, 실패 조건을 적습니다. 그 밖의 주석에는 코드만 봐서는 알 수 없는 이유만 적습니다.
- 티켓 번호와 제거 기준이 없는 `TODO`/`FIXME`는 남기지 않습니다.
- 의존성 버전은 `gradle/libs.versions.toml`에서만 관리하고, 버전 변경은 기능 변경과 다른 커밋으로 분리합니다.
- detekt나 lint 규칙을 끄거나 완화하려면 `docs/adr`에 이유를 기록합니다.
- 실제 토큰이나 개인정보를 테스트 데이터, 스크린샷, 로그에 넣지 않습니다.
- 실제 기기에서 확인하지 않은 동작을 "검증 완료"라고 적지 않습니다.
