// no-op은 pulse-bundle과 시그니처를 맞추려고 인자를 쓰지 않고 고정값을 돌려준다.
@file:Suppress("FunctionOnlyReturningConstant", "UnusedParameter")

package com.naury.pulse.api

import android.content.Context

/**
 * release 빌드용 PULSE 진입점. `pulse-bundle`과 같은 이름·시그니처를 갖지만 아무것도 기록하지 않는다.
 * 데이터베이스·파일·스레드·화면을 만들지 않는다.
 */
public object Pulse {
    /** 기록하지 않는 [PulseHandle]을 돌려준다. 설정은 검사하지 않고 보관만 한다. */
    @JvmStatic
    @JvmOverloads
    public fun initialize(context: Context, config: PulseConfig = PulseConfig.defaults()): PulseHandle = PulseHandle(config)

    /** no-op에서는 항상 null. */
    @JvmStatic
    public fun current(): PulseHandle? = null

    /** no-op에서는 항상 `false`. */
    @JvmStatic
    public fun open(context: Context): Boolean = false

    @JvmStatic
    @JvmOverloads
    public fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null): Unit = Unit

    @JvmStatic
    @JvmOverloads
    public fun recordError(throwable: Throwable, message: String? = null): Unit = Unit

    @JvmStatic
    public fun mark(name: String): Unit = Unit

    /** no-op에서는 항상 null. */
    @JvmStatic
    public fun beginSpan(name: String): String? = null

    @JvmStatic
    public fun endSpan(spanId: String): Unit = Unit
}
