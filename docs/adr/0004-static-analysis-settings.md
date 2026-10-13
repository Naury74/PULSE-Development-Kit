# ADR-0004: 정적 분석 도구 실행 방식과 규칙 조정

상태: 채택

## 실행 방식

ktlint·detekt는 Gradle plugin 대신 CLI를 `JavaExec`로 실행한다(`build-logic`의 `pulse.quality`). AGP 9의 built-in Kotlin과 두 plugin이 맞물리는 방식이 버전마다 달라 모듈 소스를 직접 넘기는 쪽이 안정적이다. detekt CLI는 자신이 빌드된 Kotlin 컴파일러 버전(2.0.21)으로 고정해 실행한다.

## 규칙 조정 (`config/detekt/detekt.yml`)

| 규칙 | 조정 | 이유 |
| --- | --- | --- |
| `ReturnCount` | 최대 3, 가드 절 제외 | 조기 반환 가드 절은 읽기 쉽다 |
| `LongParameterList` | 생성자 16, `@Composable`·Room `@Query` 제외, 테스트 제외 | 공개 모델은 이진 호환을 위해 data class 대신 일반 클래스이고, Room 쿼리 인자는 SQL 바인딩과 일대일이다 |
| `TooManyFunctions` | 재정의 함수 제외, `@Dao` 제외 | `EventListener` 콜백 수와 DAO 쿼리 수는 계약이 정한다 |
| `FunctionNaming` | `@Composable` 제외 | Compose 관례(PascalCase) |
| `MagicNumber` | 속성·이름 있는 인자 제외, 테스트 제외 | 상수 선언 자체와 테스트 데이터 |

ktlint는 `android_studio` 스타일을 쓴다. `ktlint_official`의 체인 줄바꿈 규칙이 Gradle 스크립트를 읽기 어렵게 만든다.

개별 `@Suppress`는 이유 주석과 함께 쓴다(호스트 예외를 기록 후 다시 던지는 `catch (Throwable)`, no-op의 고정 반환값 등).
