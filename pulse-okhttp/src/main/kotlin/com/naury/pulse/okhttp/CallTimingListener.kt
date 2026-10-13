package com.naury.pulse.okhttp

import com.naury.pulse.api.ResponseSource
import com.naury.pulse.api.TimingPhase
import com.naury.pulse.api.TimingPhaseType
import com.naury.pulse.core.CaptureRecorder
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.WeakHashMap
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/**
 * `Call`과 그 `Call`의 타이밍 수집기를 잇는다. 키가 약한 참조라 끝나지 않은 `Call`이 있어도 쌓이지 않는다.
 */
public class CallTimingRegistry {
    private val listeners: MutableMap<Call, CallTimingListener> = Collections.synchronizedMap(WeakHashMap())

    internal fun register(call: Call, listener: CallTimingListener) {
        listeners[call] = listener
    }

    internal fun attach(call: Call, transactionId: String, startNanos: Long) {
        listeners[call]?.attach(transactionId, startNanos)
    }

    internal fun remove(call: Call) {
        listeners.remove(call)
    }
}

/**
 * [PulseInterceptor]와 함께 쓰는 `EventListener.Factory`. 호스트의 기존 팩토리가 있으면 [delegate]로 넘겨 두 리스너가 모두 이벤트를 받게 한다.
 */
public class PulseEventListenerFactory(
    private val recorder: CaptureRecorder,
    private val registry: CallTimingRegistry,
    private val delegate: EventListener.Factory? = null,
) : EventListener.Factory {
    override fun create(call: Call): EventListener {
        val own = CallTimingListener(recorder, registry, call)
        registry.register(call, own)
        val hostListener = delegate?.create(call) ?: return own
        return own + hostListener
    }
}

/**
 * 한 `Call`의 네트워크 단계를 단조 시계로 기록한다. 관찰한 이벤트만 단계로 만들고, 끝 이벤트가 없으면 길이를 비워 둔다.
 * 연결 재사용·캐시 응답에서는 DNS·연결·TLS 이벤트가 오지 않으므로 그 단계는 목록에 없다.
 */
internal class CallTimingListener(
    private val recorder: CaptureRecorder,
    private val registry: CallTimingRegistry,
    private val call: Call,
) : EventListener() {
    private val callStartNanos = recorder.clock.monoNanos()
    private val phases = ArrayList<OpenPhase>()
    private var exchangeIndex = 0
    private var exchangeHasRequest = false
    private var transactionId: String? = null
    private var interceptorStartNanos = callStartNanos
    private var reported = false
    private var responseSource: ResponseSource? = null
    private var connectionAcquired = false
    private var connectionOpened = false

    @Synchronized
    fun attach(id: String, startNanos: Long) {
        transactionId = id
        interceptorStartNanos = startNanos
    }

    override fun dnsStart(call: Call, domainName: String) = start(TimingPhaseType.DNS, newConnection = true)

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = end(TimingPhaseType.DNS)

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        start(TimingPhaseType.CONNECT, newConnection = true)

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
        end(TimingPhaseType.CONNECT)

    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) =
        end(TimingPhaseType.CONNECT)

    override fun secureConnectStart(call: Call) = start(TimingPhaseType.TLS, newConnection = true)

    override fun secureConnectEnd(call: Call, handshake: Handshake?) = end(TimingPhaseType.TLS)

    @Synchronized
    override fun connectionAcquired(call: Call, connection: Connection) {
        connectionAcquired = true
    }

    @Synchronized
    override fun cacheHit(call: Call, response: Response) {
        responseSource = ResponseSource.CACHE
    }

    @Synchronized
    override fun cacheConditionalHit(call: Call, cachedResponse: Response) {
        responseSource = ResponseSource.CONDITIONAL_CACHE
    }

    @Synchronized
    override fun cacheMiss(call: Call) {
        if (responseSource == null) responseSource = ResponseSource.NETWORK
    }

    override fun requestHeadersStart(call: Call) = start(TimingPhaseType.REQUEST_HEADERS, newConnection = false)

    override fun requestHeadersEnd(call: Call, request: Request) = end(TimingPhaseType.REQUEST_HEADERS)

    override fun requestBodyStart(call: Call) = start(TimingPhaseType.REQUEST_BODY, newConnection = false)

    override fun requestBodyEnd(call: Call, byteCount: Long) = end(TimingPhaseType.REQUEST_BODY)

    override fun responseHeadersStart(call: Call) = start(TimingPhaseType.RESPONSE_HEADERS, newConnection = false)

    override fun responseHeadersEnd(call: Call, response: Response) = end(TimingPhaseType.RESPONSE_HEADERS)

    override fun responseBodyStart(call: Call) = start(TimingPhaseType.RESPONSE_BODY, newConnection = false)

    override fun responseBodyEnd(call: Call, byteCount: Long) = end(TimingPhaseType.RESPONSE_BODY)

    override fun callEnd(call: Call) = report()

    override fun callFailed(call: Call, ioe: IOException) = report()

    @Synchronized
    private fun start(type: TimingPhaseType, newConnection: Boolean) {
        if (phases.size >= MAX_PHASES) return
        val beginsNextExchange = exchangeHasRequest && (newConnection || type == TimingPhaseType.REQUEST_HEADERS)
        if (beginsNextExchange) {
            exchangeIndex++
            exchangeHasRequest = false
        }
        if (type == TimingPhaseType.REQUEST_HEADERS) exchangeHasRequest = true
        if (type == TimingPhaseType.CONNECT) connectionOpened = true
        phases += OpenPhase(type, exchangeIndex, recorder.clock.monoNanos())
    }

    @Synchronized
    private fun end(type: TimingPhaseType) {
        phases.lastOrNull { it.type == type && it.endNanos == null }?.endNanos = recorder.clock.monoNanos()
    }

    private fun report() {
        val snapshot = synchronized(this) {
            if (reported) return
            reported = true
            val reused = if (connectionAcquired) !connectionOpened else null
            transactionId?.let { Report(it, phases.map { phase -> phase.toTimingPhase(interceptorStartNanos) }, responseSource, reused) }
        }
        registry.remove(call)
        snapshot?.let { recorder.timings(it.transactionId, it.phases, it.responseSource, it.connectionReused) }
    }

    private class Report(
        val transactionId: String,
        val phases: List<TimingPhase>,
        val responseSource: ResponseSource?,
        val connectionReused: Boolean?,
    )

    private class OpenPhase(val type: TimingPhaseType, val exchangeIndex: Int, val startNanos: Long) {
        var endNanos: Long? = null

        fun toTimingPhase(originNanos: Long) = TimingPhase(type, exchangeIndex, startNanos - originNanos, endNanos?.let { it - startNanos })
    }

    private companion object {
        const val MAX_PHASES = 256
    }
}
