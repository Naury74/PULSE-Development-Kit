package com.naury.pulse.monitor

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.naury.pulse.api.PulseRemote
import com.naury.pulse.api.RemoteReporting
import com.naury.pulse.api.RemoteStatus
import com.naury.pulse.storage.PendingReport
import com.naury.pulse.storage.PulseStorage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 보낼 목록의 보고를 모아 원격 서버로 보낸다. 코드 설정([RemoteReporting])에 이 기기에서 바꾼 값을 덮어써 쓴다.
 * 요청은 PULSE가 기록하지 않는 별도 OkHttp 클라이언트로 보내고, 실패하면 간격을 늘려 다시 보낸다.
 */
internal class RemoteReporter(
    private val context: Context,
    private val config: RemoteReporting?,
    private val storage: () -> PulseStorage,
    private val scope: CoroutineScope,
) : PulseRemote {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val client by lazy {
        OkHttpClient.Builder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }
    private val state = MutableStateFlow(RemoteStatus.NONE)
    private var sent = 0L
    private var lastSuccess: Long? = null
    private var lastError: String? = null

    override val status: StateFlow<RemoteStatus> = state.asStateFlow()

    /** 지금 보낼 수 있는 상태인지. 저장 스레드가 보낼 목록에 넣을지 정할 때 읽는다. */
    fun isActive(): Boolean = effective() != null

    fun start() {
        refreshStatus()
        // 새 보고가 쌓이면 [COLLECT_MILLIS] 동안 더 모은 뒤 보낸다. 로그가 계속 쌓여도 그 간격으로는 보낸다.
        // 보내고 지운 변경에도 깨지만 보낼 차례인 것이 없으면 요청하지 않는다.
        scope.launch {
            delay(FIRST_FLUSH_DELAY_MILLIS)
            storage().observeReports().conflate().collect {
                delay(COLLECT_MILLIS)
                if (isActive()) flushNow()
            }
        }
        scope.launch {
            delay(FIRST_FLUSH_DELAY_MILLIS)
            while (isActive) {
                flush()
                val interval = config?.flushIntervalMillis ?: RemoteReporting.DEFAULT_FLUSH_INTERVAL_MILLIS
                select {
                    wake.onReceive { }
                    onTimeout(interval) { }
                }
            }
        }
    }

    override fun setOverride(enabled: Boolean?, endpoint: String?, bearerToken: String?) {
        endpoint?.takeIf { it.isNotBlank() }?.let {
            require(RemoteReporting.isAllowedEndpoint(it, config?.allowCleartext ?: false)) { "https 주소가 필요합니다: $it" }
        }
        preferences.edit().apply {
            enabled?.let { putBoolean(KEY_ENABLED, it) }
            endpoint?.let { if (it.isBlank()) remove(KEY_ENDPOINT) else putString(KEY_ENDPOINT, it.trim()) }
            bearerToken?.let { if (it.isBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, it.trim()) }
        }.apply()
        refreshStatus()
        flushNow()
    }

    override fun clearOverride() {
        preferences.edit().clear().apply()
        refreshStatus()
    }

    /** 보내기를 멈춘다. 보내지 못한 보고는 보낼 목록에 남아 다음 실행 때 보낸다. */
    fun close() {
        scope.cancel()
    }

    override fun flushNow() {
        wake.trySend(Unit)
    }

    /** 코드 설정과 이 기기에서 바꾼 값을 합친다. 끄여 있거나 주소가 없으면 null. */
    private fun effective(): Target? {
        val enabled = if (preferences.contains(KEY_ENABLED)) preferences.getBoolean(KEY_ENABLED, false) else config != null
        val endpoint = preferences.getString(KEY_ENDPOINT, null) ?: config?.endpoint
        if (!enabled || endpoint == null) return null
        val headers = config?.headers.orEmpty().toMutableMap()
        preferences.getString(KEY_TOKEN, null)?.let { headers["Authorization"] = "Bearer $it" }
        return Target(endpoint, headers)
    }

    private suspend fun flush() {
        val target = effective()
        if (target == null || !networkAllowed()) {
            refreshStatus()
            return
        }
        val batchSize = config?.batchSize ?: RemoteReporting.DEFAULT_BATCH_SIZE
        withContext(Dispatchers.IO) {
            do {
                val due = storage().dueReports(System.currentTimeMillis(), batchSize)
            } while (due.isNotEmpty() && send(target, due))
        }
        refreshStatus()
    }

    /** 한 묶음을 보내고 결과를 보낼 목록에 반영한다. 실패하면 다음 시도 시각을 미루고 false. */
    @Suppress("TooGenericExceptionCaught")
    private fun send(target: Target, due: List<PendingReport>): Boolean {
        val ok = try {
            post(target, due.map { JSONObject(it.payload) })
        } catch (error: Exception) {
            lastError = error.javaClass.simpleName + (error.message?.let { ": $it" } ?: "")
            false
        }
        if (ok) {
            storage().reportsSent(due.map { it.id })
            sent += due.size
            lastSuccess = System.currentTimeMillis()
            lastError = null
        } else {
            val attempts = due.maxOf { it.attempts } + 1
            val backoff = (BASE_BACKOFF_MILLIS shl attempts.coerceAtMost(MAX_BACKOFF_SHIFT)).coerceAtMost(MAX_BACKOFF_MILLIS)
            storage().reportsFailed(due.map { it.id }, System.currentTimeMillis() + backoff, MAX_ATTEMPTS)
        }
        return ok
    }

    private fun post(target: Target, reports: List<JSONObject>): Boolean {
        val body = JSONObject()
            .put("schema", SCHEMA)
            .put("sentAt", System.currentTimeMillis())
            .put("app", appInfo())
            .put("reports", JSONArray(reports))
            .toString()
        val request = Request.Builder()
            .url(target.endpoint)
            .post(body.toRequestBody(JSON))
            .apply { target.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) lastError = "HTTP ${response.code}"
            return response.isSuccessful
        }
    }

    @Suppress("DEPRECATION")
    private fun appInfo(): JSONObject {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val version = info?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong() }
        return JSONObject()
            .put("package", context.packageName)
            .putOpt("versionName", info?.versionName)
            .putOpt("versionCode", version)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("attributes", JSONObject(config?.attributes.orEmpty()))
    }

    private fun networkAllowed(): Boolean {
        if (config?.wifiOnly != true) return true
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun refreshStatus() {
        val target = effective()
        scope.launch(Dispatchers.IO) {
            val pending = try {
                storage().pendingReports()
            } catch (_: RuntimeException) {
                0
            }
            state.value = RemoteStatus(
                configured = (preferences.getString(KEY_ENDPOINT, null) ?: config?.endpoint) != null,
                enabled = target != null,
                endpoint = target?.endpoint ?: preferences.getString(KEY_ENDPOINT, null) ?: config?.endpoint,
                overridden = preferences.all.isNotEmpty(),
                pending = pending,
                sent = sent,
                lastSuccessEpochMillis = lastSuccess,
                lastError = lastError,
            )
        }
    }

    private class Target(val endpoint: String, val headers: Map<String, String>)

    private companion object {
        const val PREFERENCES = "com.naury.pulse.remote"
        const val KEY_ENABLED = "enabled"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_TOKEN = "token"
        const val SCHEMA = "pulse.remote.v1"
        const val TIMEOUT_SECONDS = 15L
        const val FIRST_FLUSH_DELAY_MILLIS = 2_000L
        const val COLLECT_MILLIS = 1_000L
        const val BASE_BACKOFF_MILLIS = 15_000L
        const val MAX_BACKOFF_SHIFT = 8
        const val MAX_BACKOFF_MILLIS = 60L * 60 * 1000
        const val MAX_ATTEMPTS = 20
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
