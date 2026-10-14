# ADR-0004: 정적 분석 도구 실행 방식과 규칙 조정

상태: 채택

## 실행 방식

ktlint와 detekt는 Gradle 플러그인 대신 CLI를 `JavaExec`로 실행합니다(`build-logic`의 `pulse.quality`). AGP 9의 built-in Kotlin과 두 플러그인이 맞물리는 방식이 버전마다 달라서, 모듈 소스를 직접 넘기는 쪽이 더 안정적입니다. detekt CLI는 detekt가 빌드된 Kotlin 컴파일러 버전(2.0.21)으로 고정해 실행합니다.

## 규칙 조정 (`config/detekt/detekt.yml`)

| 규칙 | 조정 | 이유 |
| --- | --- | --- |
| `ReturnCount` | 최대 3, 가드 절 제외 | 조기 반환하는 가드 절은 읽기 쉽습니다 |
| `LongParameterList` | 생성자 16, `@Composable`과 Room `@Query` 제외, 테스트 제외 | 공개 모델은 바이너리 호환성을 위해 data class 대신 일반 클래스로 만들었고, Room 쿼리 인자는 SQL 바인딩과 일대일로 대응합니다 |
| `TooManyFunctions` | 오버라이드 함수 제외, `@Dao` 제외, 인터페이스 16 | `EventListener` 콜백 수와 DAO 쿼리 수는 계약으로 정해집니다. `PulseInspector`는 UI가 사용하는 단일 계약입니다 |
| `FunctionNaming` | `@Composable` 제외 | Compose 관례(PascalCase) |
| `MagicNumber` | 프로퍼티와 이름 있는 인자 제외, 테스트 제외 | 상수 선언 자체와 테스트 데이터이기 때문입니다 |

ktlint는 `android_studio` 스타일을 사용합니다. `ktlint_official`의 체인 줄바꿈 규칙을 적용하면 Gradle 스크립트가 읽기 어려워지기 때문입니다.

개별 `@Suppress`는 이유를 적은 주석과 함께 사용합니다(호스트 앱의 예외를 기록한 뒤 다시 던지는 `catch (Throwable)`, no-op 구현의 고정 반환값 등).
