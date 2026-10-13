package com.naury.pulse.sample

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.naury.pulse.api.Pulse
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.PulseHandle
import com.naury.pulse.api.ReplayPolicy
import okhttp3.OkHttpClient

class SampleApplication : Application() {
    lateinit var pulse: PulseHandle
        private set

    /** TLS 설정을 만드는 비용이 있어 첫 요청 때(백그라운드) 만든다. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(pulse.interceptor())
            // 모의 응답은 PULSE 인터셉터 뒤에 붙여야 MOCK으로 기록된다. 설정 탭에서 켜기 전에는 요청을 그대로 넘긴다.
            .addInterceptor(pulse.mockInterceptor())
            .eventListenerFactory(pulse.eventListenerFactory())
            .build()
    }

    /** 프로세스가 사는 동안 하나만 둔다. 첫 요청 때 백그라운드에서 시작한다. */
    val demoServer: DemoServer by lazy { DemoServer() }

    override fun onCreate() {
        super.onCreate()
        // debug 빌드에서 메인 스레드의 디스크·네트워크 작업을 로그로 남겨 SDK가 메인 스레드를 막지 않는지 확인한다.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        }
        // 재실행은 앱 안 데모 서버(127.0.0.1)에만 허용한다.
        val config = PulseConfig.debugDefaults().newBuilder()
            .replay(ReplayPolicy(setOf("127.0.0.1", "localhost"), allowCleartext = true))
            .build()
        pulse = Pulse.initialize(this, config)
    }
}
