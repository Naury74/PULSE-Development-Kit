package com.naury.pulse.core

import com.naury.pulse.api.NetworkType

/**
 * 요청을 시작하는 순간의 앱 상태를 알려 준다. 네트워크 스레드에서 매 요청마다 부르므로 미리 구해 둔 값을 돌려줘야 하며
 * 시스템 서비스 호출 같은 느린 작업을 하면 안 된다. 모르는 값은 null.
 */
public interface AppContextSource {
    public val screen: String?
    public val foreground: Boolean?
    public val networkType: NetworkType?

    /** 지금 열려 있는 가장 최근 구간의 ID. */
    public val spanId: String?
        get() = null
}
