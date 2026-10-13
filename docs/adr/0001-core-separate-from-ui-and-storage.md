# ADR-0001: 수집 엔진을 화면·저장소와 분리

상태: 채택

## 배경

SDK는 호스트 앱의 DI·내비게이션을 건드리지 않아야 하고, interceptor 콜백은 성능에 민감하다. release 빌드에서는 수집 코드가 전혀 들어가지 않아야 한다.

## 결정

- `pulse-api`(Kotlin JVM)에 설정·모델·`PulseInspector` 계약만 둔다. Android·OkHttp·Room에 의존하지 않는다.
- `pulse-core`가 큐와 저장 스레드를, `pulse-okhttp`가 OkHttp 연결을, `pulse-security`가 마스킹을, `pulse-storage`가 Room·파일을 맡는다.
- `pulse-bundle`이 이들을 직접 조립하는 진입점(`Pulse`, `PulseHandle`)이다. DI 프레임워크를 쓰지 않는다.
- `pulse-noop`은 같은 패키지·이름·시그니처의 `Pulse`·`PulseHandle`을 제공한다. 호스트는 `debugImplementation`/`releaseImplementation`으로 바꿔 끼운다.
- 화면(`pulse-ui-compose`)은 `PulseInspector`만 쓰고, 초기화된 인스턴스는 `PulseInspectorRegistry`로 찾는다. 등록 함수는 `@PulseInternalApi`로 막는다.

## 결과

- 화면 없이 수집만 붙일 수 있고, 테스트에서 Activity 없이 엔진을 검증할 수 있다.
- 모듈이 많아 의존 방향을 지켜야 한다. release 런타임 classpath는 CI의 `verifyReleaseClasspath`가 확인한다.
- `pulse-api`와 `pulse-bundle`·`pulse-noop`이 같은 패키지를 나눠 쓴다(split package). Android·JVM classpath에서는 문제가 없다.

## 버린 대안

Compose·Room·OkHttp를 한 AAR에 묶는 방식: 처음엔 편하지만 no-op 교체와 화면 없는 연동이 어렵다.
