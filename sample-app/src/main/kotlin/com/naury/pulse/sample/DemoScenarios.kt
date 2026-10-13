package com.naury.pulse.sample

import androidx.annotation.StringRes
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** PULSE가 기록하는 여러 경우를 재현하는 요청 묶음. */
enum class DemoScenario(@StringRes val title: Int, @StringRes val description: Int) {
    GET_JSON(R.string.scenario_get_json, R.string.scenario_get_json_detail),
    POST_LOGIN(R.string.scenario_post_login, R.string.scenario_post_login_detail),
    NOT_FOUND(R.string.scenario_not_found, R.string.scenario_not_found_detail),
    SERVER_ERROR(R.string.scenario_server_error, R.string.scenario_server_error_detail),
    REDIRECT(R.string.scenario_redirect, R.string.scenario_redirect_detail),
    GZIP(R.string.scenario_gzip, R.string.scenario_gzip_detail),
    LARGE(R.string.scenario_large, R.string.scenario_large_detail),
    IMAGE(R.string.scenario_image, R.string.scenario_image_detail),
    TIMEOUT(R.string.scenario_timeout, R.string.scenario_timeout_detail),
    CANCEL(R.string.scenario_cancel, R.string.scenario_cancel_detail),
    BURST(R.string.scenario_burst, R.string.scenario_burst_detail),
    ERROR_BURST(R.string.scenario_error_burst, R.string.scenario_error_burst_detail),
    REPEATED_GET(R.string.scenario_repeated_get, R.string.scenario_repeated_get_detail),
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

    private suspend fun execute(scenario: DemoScenario, base: HttpUrl): String = when (scenario) {
        DemoScenario.GET_JSON -> call(get(base, "users/1?api_key=demo-key"))

        DemoScenario.POST_LOGIN -> call(
            Request.Builder()
                .url(base.resolve("login")!!)
                .header("Authorization", "Bearer demo-bearer-token")
                .post("""{"email":"kim@example.com","password":"demo-password"}""".toRequestBody(json))
                .build(),
        )

        DemoScenario.NOT_FOUND -> call(get(base, "missing"))

        DemoScenario.SERVER_ERROR -> call(get(base, "error"))

        DemoScenario.REDIRECT -> call(get(base, "redirect"))

        DemoScenario.GZIP -> call(get(base, "gzip"))

        DemoScenario.LARGE -> call(get(base, "large"))

        DemoScenario.IMAGE -> call(get(base, "image"))

        DemoScenario.TIMEOUT -> call(get(base, "slow"), client.newBuilder().readTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).build())

        DemoScenario.CANCEL -> cancelAfterStart(get(base, "slow"))

        DemoScenario.BURST -> burst(base)

        DemoScenario.ERROR_BURST -> repeatCall(base, "error", REPEAT_COUNT)

        DemoScenario.REPEATED_GET -> repeatCall(base, "config", REPEAT_COUNT)
    }

    private fun get(base: HttpUrl, path: String) = Request.Builder().url(base.resolve(path)!!).build()

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
        val codes = (1..BURST_SIZE).map { index -> async(Dispatchers.IO) { call(get(base, "items/$index")) } }.awaitAll()
        "${codes.size} requests"
    }

    private fun repeatCall(base: HttpUrl, path: String, times: Int): String =
        (1..times).map { call(get(base, path)) }.last().let { "$times × $it" }

    private companion object {
        const val REPEAT_COUNT = 6
        const val TIMEOUT_MILLIS = 300L
        const val CANCEL_AFTER_MILLIS = 200L
        const val BURST_SIZE = 20
    }
}
