package com.naury.pulse.api

import android.content.Context

/**
 * PULSE 진입점. 앱 프로세스마다 하나의 [PulseHandle]을 만든다.
 *
 * `debugImplementation`에는 이 수집 artifact(`pulse-bundle`)를, `releaseImplementation`에는 같은 이름의 API를 가진
 * `pulse-noop`을 두면 release 빌드에는 수집·저장·화면 코드가 들어가지 않는다.
 */
@Suppress("TooManyFunctions")
public object Pulse {
    private val lock = Any()
    private var current: PulseHandle? = null

    /**
     * 수집을 준비하고 [PulseHandle]을 돌려준다. 디스크 정리는 백그라운드에서 하므로 메인 스레드에서 불러도 된다.
     *
     * 같은 설정으로 다시 부르면 기존 핸들을 돌려준다. 다른 설정으로 부르면 [PulseConfigurationException]을 던진다.
     * 설정을 바꾸려면 [PulseHandle.close] 후 다시 초기화한다.
     */
    @JvmStatic
    @JvmOverloads
    public fun initialize(context: Context, config: PulseConfig = PulseConfig.defaults()): PulseHandle = synchronized(lock) {
        current?.let { existing ->
            if (existing.config == config) return existing
            throw PulseConfigurationException("PULSE가 이미 다른 설정으로 초기화되었습니다. close() 후 다시 초기화하세요.")
        }
        PulseHandle.create(context.applicationContext, config).also { handle ->
            current = handle
            @OptIn(PulseInternalApi::class)
            PulseInspectorRegistry.register(handle)
        }
    }

    /** 초기화된 핸들. 초기화 전이거나 닫힌 뒤에는 null. */
    @JvmStatic
    public fun current(): PulseHandle? = synchronized(lock) { current }

    /**
     * 인스펙터 화면을 연다. 초기화 전이거나 화면 모듈(`pulse-ui-compose`)이 없으면 아무것도 하지 않고 `false`를 돌려준다.
     */
    @JvmStatic
    public fun open(context: Context): Boolean = current()?.open(context) ?: false

    /** [PulseHandle.log]. 초기화 전이면 아무것도 하지 않는다. */
    @JvmStatic
    @JvmOverloads
    public fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        current()?.log(level, tag, message, throwable)
    }

    /** [PulseHandle.recordError]. 초기화 전이면 아무것도 하지 않는다. */
    @JvmStatic
    @JvmOverloads
    public fun recordError(throwable: Throwable, message: String? = null) {
        current()?.recordError(throwable, message)
    }

    /** [PulseHandle.mark]. 초기화 전이면 아무것도 하지 않는다. */
    @JvmStatic
    public fun mark(name: String) {
        current()?.mark(name)
    }

    /** [PulseHandle.beginSpan]. 초기화 전이면 null. */
    @JvmStatic
    public fun beginSpan(name: String): String? = current()?.beginSpan(name)

    /** [PulseHandle.endSpan]. */
    @JvmStatic
    public fun endSpan(spanId: String) {
        current()?.endSpan(spanId)
    }

    /** [PulseHandle.action]. 초기화 전이면 아무것도 하지 않는다. */
    @JvmStatic
    @JvmOverloads
    public fun action(id: String, attributes: Map<String, String> = emptyMap()) {
        current()?.action(id, attributes)
    }

    /** [PulseHandle.screen]. */
    @JvmStatic
    public fun screen(name: String) {
        current()?.screen(name)
    }

    /** [PulseHandle.setFlag]. */
    @JvmStatic
    public fun setFlag(name: String, value: String?) {
        current()?.setFlag(name, value)
    }

    internal fun release(handle: PulseHandle) = synchronized(lock) {
        if (current === handle) {
            current = null
            @OptIn(PulseInternalApi::class)
            PulseInspectorRegistry.register(null)
        }
    }
}
