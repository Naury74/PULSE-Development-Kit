package com.naury.pulse.sample

import android.app.Application
import com.naury.pulse.api.Pulse
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.api.PulseHandle
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
        pulse = Pulse.initialize(this, PulseConfig.debugDefaults())
        client = OkHttpClient.Builder()
            .addInterceptor(pulse.interceptor())
            .eventListenerFactory(pulse.eventListenerFactory())
            .build()
    }
}
