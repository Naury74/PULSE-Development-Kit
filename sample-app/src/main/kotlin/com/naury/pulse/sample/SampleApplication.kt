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

    override fun onCreate() {
        super.onCreate()
        pulse = Pulse.initialize(this, PulseConfig.debugDefaults())
        client = OkHttpClient.Builder()
            .addInterceptor(pulse.interceptor())
            .eventListenerFactory(pulse.eventListenerFactory())
            .build()
    }
}
