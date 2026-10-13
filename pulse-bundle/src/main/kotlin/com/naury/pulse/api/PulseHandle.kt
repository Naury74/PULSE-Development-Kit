package com.naury.pulse.api

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.naury.pulse.core.CaptureEngine
import com.naury.pulse.okhttp.CallTimingRegistry
import com.naury.pulse.okhttp.PulseEventListenerFactory
import com.naury.pulse.okhttp.PulseInterceptor
import com.naury.pulse.storage.PulseStorage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.EventListener
import okhttp3.Interceptor

/**
 * 초기화된 PULSE 인스턴스. OkHttpClient에 붙일 [interceptor]·[eventListenerFactory]를 주고, 저장된 기록을 읽는다.
 * 모든 메서드는 스레드 안전하다.
 */
public class PulseHandle private constructor(
    public val config: PulseConfig,
    private val storage: PulseStorage,
    private val engine: CaptureEngine,
) : PulseInspector {
    // 디스크 정리·삭제 실패는 수집만 못 하게 할 뿐 호스트 앱을 멈추면 안 된다.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val timingRegistry = CallTimingRegistry()
    private val interceptor = PulseInterceptor(engine.recorder, timingRegistry)

    override val recordingState: StateFlow<RecordingState> = engine.recorder.controller.recordingState

    override val captureHealth: StateFlow<CaptureHealth> = engine.health

    /**
     * `OkHttpClient.Builder.addInterceptor`에 넣는 application interceptor. 응답 캐시까지 포함한 논리 요청 하나를 한 번 기록한다.
     * 다른 interceptor보다 먼저 넣으면 호스트가 보낸 그대로를, 나중에 넣으면 앞선 interceptor가 바꾼 요청을 기록한다.
     */
    public fun interceptor(): Interceptor = interceptor

    /**
     * `OkHttpClient.Builder.eventListenerFactory`에 넣는 팩토리. DNS·연결·TLS·요청·응답 단계 시간을 기록한다.
     * 이미 쓰는 팩토리가 있으면 [delegate]로 넘기면 두 리스너가 모두 이벤트를 받는다.
     */
    @JvmOverloads
    public fun eventListenerFactory(delegate: EventListener.Factory? = null): EventListener.Factory =
        PulseEventListenerFactory(engine.recorder, timingRegistry, delegate)

    /** 인스펙터 화면을 연다. 화면 모듈이 없거나 닫힌 핸들이면 `false`. */
    public fun open(context: Context): Boolean {
        if (recordingState.value == RecordingState.DISABLED) return false
        val component = ComponentName(context.packageName, INSPECTOR_ACTIVITY)
        if (!isDeclared(context, component)) return false
        val intent = Intent().setComponent(component)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    override suspend fun loadTransactions(query: TransactionQuery, after: PageCursor?, limit: Int): TransactionPage =
        storage.loadTransactions(query, after, limit)

    override fun observeChanges(): Flow<Unit> = storage.observeChanges()

    override suspend fun loadTransaction(id: String): TransactionDetail? = storage.loadTransaction(id)

    override suspend fun loadBody(id: String, kind: MessageKind): String? = storage.loadBody(id, kind)

    override fun pause() {
        engine.recorder.controller.pause()
    }

    override fun resume() {
        engine.recorder.controller.resume()
    }

    override fun clear() {
        scope.launch { storage.clear() }
    }

    /** 기록을 멈추고 남은 기록을 저장한 뒤 자원을 닫는다. 닫은 뒤에는 [Pulse.initialize]로 새 핸들을 만들 수 있다. */
    public fun close() {
        Pulse.release(this)
        engine.close()
        scope.cancel()
        storage.close()
    }

    private fun isDeclared(context: Context, component: ComponentName): Boolean = try {
        context.packageManager.getActivityInfo(component, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    internal companion object {
        private const val INSPECTOR_ACTIVITY = "com.naury.pulse.ui.PulseInspectorActivity"

        fun create(context: Context, config: PulseConfig): PulseHandle {
            val storage = PulseStorage.open(context, config.retention)
            val engine = CaptureEngine.start(config, storage.sink)
            return PulseHandle(config, storage, engine).also { handle ->
                handle.scope.launch { storage.startSession(engine.recorder.sessionId, System.currentTimeMillis()) }
            }
        }
    }
}
