package com.naury.pulse.api

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import com.naury.pulse.core.AppContextSource

/**
 * 앞에 있는 Activity와 기본 네트워크 종류를 콜백으로 미리 알아 둔다. 요청마다 시스템 서비스를 부르지 않는다.
 * 네트워크 상태 권한이 없거나 콜백 등록에 실패하면 네트워크 종류는 null로 둔다.
 */
internal class AndroidAppContext(context: Context, options: ContextCapture) : AppContextSource {
    private val app = context.applicationContext as? Application

    @Volatile
    override var screen: String? = null
        private set

    @Volatile
    private var startedActivities = 0

    @Volatile
    private var lifecycleKnown = false

    override val foreground: Boolean?
        get() = if (lifecycleKnown) startedActivities > 0 else null

    @Volatile
    override var networkType: NetworkType? = null
        private set

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            screen = activity.javaClass.simpleName
        }

        override fun onActivityStarted(activity: Activity) {
            lifecycleKnown = true
            startedActivities++
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            networkType = capabilities.toNetworkType()
        }

        override fun onLost(network: Network) {
            networkType = NetworkType.NONE
        }
    }

    private var connectivity: ConnectivityManager? = null

    init {
        // 초기화 전에 이미 열린 화면은 다음 전환 때부터 알 수 있다. 그 전에는 앱 상태를 모름(null)으로 둔다.
        if (options.screen || options.appState) app?.registerActivityLifecycleCallbacks(lifecycle)
        if (options.networkType) registerNetworkCallback(context)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun registerNetworkCallback(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) return
        try {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
            manager.registerDefaultNetworkCallback(network)
            connectivity = manager
        } catch (_: RuntimeException) {
            // 콜백 수 제한 등으로 등록하지 못하면 네트워크 종류만 기록하지 않는다.
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun close() {
        app?.unregisterActivityLifecycleCallbacks(lifecycle)
        try {
            connectivity?.unregisterNetworkCallback(network)
        } catch (_: RuntimeException) {
            // 이미 해제됐다.
        }
    }

    private fun NetworkCapabilities.toNetworkType(): NetworkType = when {
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
        else -> NetworkType.OTHER
    }
}
