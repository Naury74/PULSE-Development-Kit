# Java에서 쓰기

PULSE의 진입점은 Java에서도 그대로 쓸 수 있습니다. `pulse-bundle`(debug)과 `pulse-noop`(release)은 같은 클래스·메서드를 제공하므로 한 코드로 두 빌드를 컴파일합니다. 실제로 컴파일되는 예제는 [`JavaIntegration.java`](../sample-app/src/main/java/com/naury/pulse/sample/JavaIntegration.java)입니다.

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
        .addInterceptor(pulse.mockInterceptor())   // 모의 응답을 쓸 때만, PULSE 인터셉터 뒤에
        .eventListenerFactory(pulse.eventListenerFactory())
        .build();

Pulse.open(context); // 화면 모듈이 없거나 release면 false
```

- `CaptureLimits`, `RetentionPolicy`, `InsightThresholds`, `ReplayPolicy`, `MockRule`, `MockResponseSpec`은 `@JvmOverloads` 생성자라 앞쪽 인자만 넘겨도 됩니다.
- `RedactionPolicy.strict()`, `PulseConfig.defaults()`, `PulseConfig.debugDefaults()`, `Pulse.initialize/open/current()`는 정적 메서드입니다.
- 기존 `EventListener.Factory`가 있으면 `pulse.eventListenerFactory(existing)`로 넘깁니다.

## 재실행 자격 증명

`ReplayAuthorizer`는 suspend 함수라 Java에서는 `ReplayAuthorizer.blocking(...)`으로 감쌉니다. 콜백은 I/O 스레드에서 불립니다.

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

`PulseInspector`의 조회 함수(`loadTransactions` 등)는 Kotlin suspend 함수라 Java에서 직접 부르기 불편합니다. 화면은 `pulse-ui-compose`가 제공하므로 Java 앱은 보통 조회 API를 쓸 필요가 없습니다.
