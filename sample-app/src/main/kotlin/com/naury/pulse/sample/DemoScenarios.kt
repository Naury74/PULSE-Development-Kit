package com.naury.pulse.sample

import androidx.annotation.StringRes
import com.naury.pulse.api.PulseLabel
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 화면에서 시나리오를 묶는 범주. */
enum class ScenarioGroup(@StringRes val title: Int) {
    BASIC(R.string.group_basic),
    ERRORS(R.string.group_errors),
    BODIES(R.string.group_bodies),
    DIAGNOSTICS(R.string.group_diagnostics),
    OPTIONS(R.string.group_options),
}

/** PULSE가 기록하는 여러 경우를 재현하는 요청 묶음. [endpoint]는 화면에 보여 줄 메서드와 경로다. */
enum class DemoScenario(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val group: ScenarioGroup,
    val method: String,
    val endpoint: String,
) {
    GET_JSON(R.string.scenario_get_json, R.string.scenario_get_json_detail, ScenarioGroup.BASIC, "GET", "/users/1"),
    POST_LOGIN(R.string.scenario_post_login, R.string.scenario_post_login_detail, ScenarioGroup.BASIC, "POST", "/login"),
    FORM_LOGIN(R.string.scenario_form_login, R.string.scenario_form_login_detail, ScenarioGroup.BASIC, "POST", "/oauth/token"),
    REDIRECT(R.string.scenario_redirect, R.string.scenario_redirect_detail, ScenarioGroup.BASIC, "GET", "/redirect"),
    GZIP(R.string.scenario_gzip, R.string.scenario_gzip_detail, ScenarioGroup.BASIC, "GET", "/gzip"),
    NOT_FOUND(R.string.scenario_not_found, R.string.scenario_not_found_detail, ScenarioGroup.ERRORS, "GET", "/missing"),
    SERVER_ERROR(R.string.scenario_server_error, R.string.scenario_server_error_detail, ScenarioGroup.ERRORS, "GET", "/error"),
    TIMEOUT(R.string.scenario_timeout, R.string.scenario_timeout_detail, ScenarioGroup.ERRORS, "GET", "/slow"),
    CANCEL(R.string.scenario_cancel, R.string.scenario_cancel_detail, ScenarioGroup.ERRORS, "GET", "/slow"),
    LARGE(R.string.scenario_large, R.string.scenario_large_detail, ScenarioGroup.BODIES, "GET", "/large"),
    IMAGE(R.string.scenario_image, R.string.scenario_image_detail, ScenarioGroup.BODIES, "GET", "/image"),
    BURST(R.string.scenario_burst, R.string.scenario_burst_detail, ScenarioGroup.DIAGNOSTICS, "GET", "/items/{1..20}"),
    ERROR_BURST(R.string.scenario_error_burst, R.string.scenario_error_burst_detail, ScenarioGroup.DIAGNOSTICS, "GET", "/error ×6"),
    REPEATED_GET(R.string.scenario_repeated_get, R.string.scenario_repeated_get_detail, ScenarioGroup.DIAGNOSTICS, "GET", "/config ×6"),
    HEALTH_CHECK(R.string.scenario_health, R.string.scenario_health_detail, ScenarioGroup.OPTIONS, "GET", "/health"),
}

/** [clientFactory]는 첫 시나리오를 실행할 때 I/O 스레드에서 부른다. */
class DemoRunner(clientFactory: () -> OkHttpClient, private val server: DemoServer) {
    private val client by lazy(clientFactory)

    private val json = "application/json; charset=utf-8".toMediaType()

    /** 시나리오를 실행하고 앱이 받은 결과를 한 줄로 돌려준다. 실패도 결과로 돌려준다. */
    suspend fun run(scenario: DemoScenario): String = withContext(Dispatchers.IO) {
        val base = server.start()
        try {
            execute(scenario, base)
        } catch (error: IOException) {
            "${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private suspend fun execute(scenario: DemoScenario, base: HttpUrl): String = simplePaths[scenario]?.let { call(get(base, it)) }
        ?: when (scenario) {
            DemoScenario.GET_JSON -> call(get(base, "users/1?api_key=demo-key", "profile"))
            DemoScenario.POST_LOGIN, DemoScenario.FORM_LOGIN -> login(scenario, base)
            DemoScenario.TIMEOUT -> call(get(base, "slow"), client.newBuilder().readTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).build())
            DemoScenario.CANCEL -> cancelAfterStart(get(base, "slow"))
            DemoScenario.BURST -> burst(base)
            DemoScenario.ERROR_BURST -> repeatCall(base, "error", REPEAT_COUNT)
            DemoScenario.REPEATED_GET -> repeatCall(base, "config", REPEAT_COUNT)
            else -> error("simplePaths에 있는 시나리오입니다: $scenario")
        }

    /** 인증 요청은 같은 라벨(auth)로 묶는다. */
    private fun login(scenario: DemoScenario, base: HttpUrl): String {
        val builder = Request.Builder().tag(PulseLabel::class.java, PulseLabel("auth"))
        val request = if (scenario == DemoScenario.FORM_LOGIN) {
            builder.url(base.resolve("oauth/token")!!)
                .post(FormBody.Builder().add("grant_type", "password").add("username", "kim").add("password", "demo-password").build())
        } else {
            builder.url(base.resolve("login")!!)
                .header("Authorization", "Bearer demo-bearer-token")
                .post("""{"email":"kim@example.com","password":"demo-password"}""".toRequestBody(json))
        }
        return call(request.build())
    }

    private fun get(base: HttpUrl, path: String, label: String? = null) = Request.Builder()
        .url(base.resolve(path)!!)
        .apply { label?.let { tag(PulseLabel::class.java, PulseLabel(it)) } }
        .build()

    private fun call(request: Request, httpClient: OkHttpClient = client): String = httpClient.newCall(request).execute().use { response ->
        "HTTP ${response.code} · ${response.body.bytes().size} bytes"
    }

    private fun cancelAfterStart(request: Request): String {
        val call = client.newCall(request)
        Thread {
            Thread.sleep(CANCEL_AFTER_MILLIS)
            call.cancel()
        }.start()
        return call.execute().use { "HTTP ${it.code}" }
    }

    private suspend fun burst(base: HttpUrl): String = coroutineScope {
        val codes = (1..BURST_SIZE).map { index -> async(Dispatchers.IO) { call(get(base, "items/$index", "catalog")) } }.awaitAll()
        "${codes.size} requests"
    }

    private fun repeatCall(base: HttpUrl, path: String, times: Int): String =
        (1..times).map { call(get(base, path)) }.last().let { "$times × $it" }

    private companion object {
        /** 경로 하나에 GET만 보내는 시나리오. */
        val simplePaths = mapOf(
            DemoScenario.NOT_FOUND to "missing",
            DemoScenario.SERVER_ERROR to "error",
            DemoScenario.REDIRECT to "redirect",
            DemoScenario.GZIP to "gzip",
            DemoScenario.LARGE to "large",
            DemoScenario.IMAGE to "image",
            DemoScenario.HEALTH_CHECK to "health",
        )
        const val REPEAT_COUNT = 6
        const val TIMEOUT_MILLIS = 300L
        const val CANCEL_AFTER_MILLIS = 200L
        const val BURST_SIZE = 20
    }
}
