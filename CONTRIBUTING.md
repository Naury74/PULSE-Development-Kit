# Contributing

## 빌드

JDK 25(Android Studio에 포함된 JBR)를 씁니다.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew check :sample-app:assembleDebug :sample-app:assembleRelease
```

PR 전에 위 명령이 통과해야 합니다. 같은 명령이 CI(`.github/workflows/ci.yml`)에서 실행됩니다. 형식은 `./gradlew ktlintFormat`으로 고칩니다.

## 작업 흐름

1. `main`에서 티켓 단위 branch를 만듭니다: `feat/PULSE-102-capture-interceptor`, `fix/PULSE-127-truncated-body`
2. 커밋 하나에 의도 하나. [Conventional Commits](https://www.conventionalcommits.org/)를 쓰고 제목은 한국어, scope는 모듈입니다.

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

- 호스트 앱의 요청 결과와 본문 소비를 바꾸지 않습니다. `ResponseBody.string()`처럼 본문을 미리 읽는 코드를 넣지 않습니다.
- 마스킹은 저장 전에 합니다. 저장소는 `pulse-core`의 `CaptureRecord`만 받습니다.
- 네트워크 콜백 스레드에서 디스크 I/O나 JSON 해석을 하지 않습니다.
- 공개 API는 `com.naury.pulse.api` 패키지에 두고, `pulse-bundle`과 `pulse-noop`의 `Pulse`·`PulseHandle` 시그니처를 함께 바꿉니다.
- 주석과 KDoc은 한국어로 씁니다. KDoc은 공개 API 계약(단위·범위·실패 조건)에만, 그 밖의 주석은 코드가 설명하지 못하는 이유만 씁니다.
- 티켓 번호와 제거 기준 없는 `TODO`/`FIXME`를 남기지 않습니다.
- 버전은 `gradle/libs.versions.toml`에서만 관리하고 버전 변경은 기능 변경과 섞지 않습니다.
- detekt·lint 규칙을 끄거나 완화하려면 `docs/adr`에 이유를 남깁니다.
- 실제 토큰·개인정보를 테스트 데이터·스크린샷·로그에 넣지 않습니다.
- 기기에서 확인하지 않은 동작을 "검증 완료"로 적지 않습니다.
