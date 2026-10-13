package com.naury.pulse.okhttp

import com.naury.pulse.api.MessageKind
import com.naury.pulse.api.PulseConfig
import com.naury.pulse.core.BodyRecord
import com.naury.pulse.core.CaptureEngine
import com.naury.pulse.core.CaptureRecord
import com.naury.pulse.core.CaptureSink
import com.naury.pulse.core.FinishRecord
import com.naury.pulse.core.RequestRecord
import com.naury.pulse.core.ResponseRecord
import com.naury.pulse.core.TimingRecord
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.EventListener
import okhttp3.OkHttpClient

/** 실제 저장 파이프라인에 메모리 sink를 붙여 인터셉터가 남긴 기록을 모은다. */
class CaptureHarness(config: PulseConfig = PulseConfig.debugDefaults()) {
    private val records = CopyOnWriteArrayList<CaptureRecord>()
    val engine = CaptureEngine.start(config, CaptureSink { records += it })
    private val registry = CallTimingRegistry()

    fun client(builder: OkHttpClient.Builder = OkHttpClient.Builder(), hostListener: EventListener.Factory? = null): OkHttpClient = builder
        .addInterceptor(PulseInterceptor(engine.recorder, registry))
        .eventListenerFactory(PulseEventListenerFactory(engine.recorder, registry, hostListener))
        .build()

    /** 남은 기록을 모두 저장한 뒤 결과를 돌려준다. 이후에는 기록하지 않는다. */
    fun finish(): Captured {
        engine.close()
        return Captured(records.toList())
    }
}

class Captured(val records: List<CaptureRecord>) {
    val requests = records.filterIsInstance<RequestRecord>()
    val responses = records.filterIsInstance<ResponseRecord>()
    val finishes = records.filterIsInstance<FinishRecord>()
    val timings = records.filterIsInstance<TimingRecord>()

    fun single(): String = requests.single().transactionId

    fun request(id: String = single()) = requests.single { it.transactionId == id }

    fun response(id: String = single()) = responses.single { it.transactionId == id }

    fun finish(id: String = single()) = finishes.single { it.transactionId == id }

    fun body(kind: MessageKind, id: String = single()) =
        records.filterIsInstance<BodyRecord>().single { it.transactionId == id && it.kind == kind }

    fun timing(id: String = single()) = timings.singleOrNull { it.transactionId == id }

    /** 저장될 모든 문자열. 비밀 문자열이 남지 않았는지 확인할 때 쓴다. */
    fun storedText(): String = records.joinToString("\n") { record ->
        when (record) {
            is RequestRecord -> listOf(record.url, record.path, record.headers.joinToString()).joinToString()
            is ResponseRecord -> record.headers.joinToString()
            is BodyRecord -> record.text.orEmpty()
            is FinishRecord -> listOfNotNull(record.failureType, record.failureMessage).joinToString()
            is TimingRecord -> ""
        }
    }
}
