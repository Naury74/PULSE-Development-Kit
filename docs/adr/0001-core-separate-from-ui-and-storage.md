# ADR-0001: 수집 엔진을 UI 및 저장소와 분리

상태: 채택

## 배경

SDK는 호스트 앱의 DI와 내비게이션 구성에 영향을 주면 안 되고, 인터셉터 콜백은 성능에 민감합니다. 또한 release 빌드에는 수집 코드가 전혀 포함되지 않아야 합니다.

## 결정

- `pulse-api`(Kotlin JVM)에는 설정, 모델, `PulseInspector` 계약만 둡니다. Android, OkHttp, Room에 의존하지 않습니다.
- `pulse-core`는 큐와 저장 스레드, `pulse-okhttp`는 OkHttp 연동, `pulse-security`는 마스킹, `pulse-storage`는 Room과 파일 저장을 담당합니다.
- `pulse-bundle`은 이 모듈들을 직접 조립하는 진입점(`Pulse`, `PulseHandle`)입니다. DI 프레임워크는 사용하지 않습니다.
- `pulse-noop`은 패키지, 이름, 시그니처가 같은 `Pulse`와 `PulseHandle`을 제공합니다. 호스트 앱은 `debugImplementation`/`releaseImplementation`으로 둘을 바꿔 끼웁니다.
- UI 모듈(`pulse-ui-compose`)은 `PulseInspector`만 사용하고, 초기화된 인스턴스는 `PulseInspectorRegistry`에서 찾습니다. 등록 함수는 `@PulseInternalApi`로 외부 사용을 막습니다.

## 결과

- UI 없이 수집 기능만 붙일 수 있고, 테스트에서 Activity 없이 엔진을 검증할 수 있습니다.
- 모듈이 많아서 의존성 방향을 지켜야 합니다. release 런타임 classpath는 CI의 `verifyReleaseClasspath`가 확인합니다.
- `pulse-api`와 `pulse-bundle`, `pulse-noop`이 같은 패키지를 나눠 씁니다(split package). Android와 JVM classpath에서는 문제가 되지 않습니다.

## 채택하지 않은 대안

Compose, Room, OkHttp를 하나의 AAR로 묶는 방식: 처음에는 편하지만 no-op 교체와 UI 없는 연동이 어려워집니다.
