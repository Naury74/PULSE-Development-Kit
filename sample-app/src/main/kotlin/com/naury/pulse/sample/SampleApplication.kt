package com.naury.pulse.sample

import android.app.Application
import com.naury.pulse.api.Pulse
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.PulseHandle
import com.naury.pulse.api.ReplayPolicy
import okhttp3.OkHttpClient

class SampleApplication : Application() {
    lateinit var pulse: PulseHandle
        private set

    lateinit var client: OkHttpClient
        private set

    /** 프로세스가 사는 동안 하나만 둔다. 첫 요청 때 백그라운드에서 시작한다. */
    val demoServer: DemoServer by lazy { DemoServer() }

    override fun onCreate() {
        super.onCreate()
        // 재실행은 앱 안 데모 서버(127.0.0.1)에만 허용한다.
        val config = PulseConfig.debugDefaults().newBuilder()
            .replay(ReplayPolicy(setOf("127.0.0.1", "localhost"), allowCleartext = true))
            .build()
        pulse = Pulse.initialize(this, config)
        client = OkHttpClient.Builder()
            .addInterceptor(pulse.interceptor())
            // 모의 응답은 PULSE 인터셉터 뒤에 붙여야 MOCK으로 기록된다. 설정 탭에서 켜기 전에는 요청을 그대로 넘긴다.
            .addInterceptor(pulse.mockInterceptor())
            .eventListenerFactory(pulse.eventListenerFactory())
            .build()
    }
}
