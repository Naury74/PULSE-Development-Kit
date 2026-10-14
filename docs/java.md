# Java에서 사용하기

PULSE의 진입점은 Java에서도 그대로 사용할 수 있습니다. `pulse-bundle`(debug)과 `pulse-noop`(release)은 같은 클래스와 메서드를 제공하므로, 하나의 코드로 두 빌드를 모두 컴파일할 수 있습니다. 실제로 컴파일되는 예제는 [`JavaIntegration.java`](../sample-app/src/main/java/com/naury/pulse/sample/JavaIntegration.java)를 참고하세요.

## 초기화와 OkHttp 연결

```java
PulseConfig config = new PulseConfig.Builder()
        .captureMode(CaptureMode.METADATA_AND_SAFE_BODIES)
        .limits(new CaptureLimits(64 * 1024, 256 * 1024))
        .retention(new RetentionPolicy(5_000))
        .redaction(RedactionPolicy.strict().withHeaders("X-Tenant-Id"))
        .build();
PulseHandle pulse = Pulse.initialize(context, config);

OkHttpClient client = new OkHttpClient.Builder()
        .addInterceptor(pulse.interceptor())
        .addInterceptor(pulse.mockInterceptor())   // 모의 응답을 쓸 때만, PULSE 인터셉터 뒤에 추가
        .eventListenerFactory(pulse.eventListenerFactory())
        .build();

Pulse.open(context); // UI 모듈이 없거나 release 빌드면 false
```

- `CaptureLimits`, `RetentionPolicy`, `InsightThresholds`, `ReplayPolicy`, `MockRule`, `MockResponseSpec`은 생성자에 `@JvmOverloads`가 붙어 있어 앞쪽 인자만 넘겨도 됩니다.
- `RedactionPolicy.strict()`, `PulseConfig.defaults()`, `PulseConfig.debugDefaults()`, `Pulse.initialize/open/current()`는 정적 메서드입니다.
- 이미 사용 중인 `EventListener.Factory`가 있다면 `pulse.eventListenerFactory(existing)`로 넘기면 됩니다.

## 재실행 자격 증명

`ReplayAuthorizer`는 suspend 함수이므로 Java에서는 `ReplayAuthorizer.blocking(...)`으로 감싸서 사용합니다. 콜백은 I/O 스레드에서 호출됩니다.

```java
pulse.setReplayAuthorizer(ReplayAuthorizer.blocking(draft ->
        Collections.singletonList(new HeaderField("Authorization", "Bearer " + tokenStore.devToken()))));
```

## 모의 응답

```java
pulse.getMocks().upsert(new MockRule("/v1/", new MockResponseSpec(503, "{\"error\":\"maintenance\"}"), PathMatch.PREFIX));
pulse.getMocks().setEnabled(true);
```

## 조회 API

`PulseInspector`의 조회 함수(`loadTransactions` 등)는 Kotlin suspend 함수라서 Java에서 직접 호출하기 불편합니다. 화면은 `pulse-ui-compose`가 제공하므로, Java 앱에서는 대개 조회 API를 직접 쓸 일이 없습니다.
