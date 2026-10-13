package com.naury.pulse.okhttp

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Sink
import okio.Source
import okio.buffer

/** 지나가는 바이트를 [limit]까지만 복사한다. 상한을 넘으면 복사본을 버리고 개수만 센다. */
internal class CappedCopy(private val limit: Long) {
    private var copy: Buffer? = if (limit > 0) Buffer() else null

    var total: Long = 0
        private set

    val overflowed: Boolean
        get() = limit > 0 && copy == null

    fun append(source: Buffer, offset: Long, byteCount: Long) {
        total += byteCount
        val target = copy ?: return
        if (target.size + byteCount > limit) {
            target.clear()
            copy = null
        } else {
            source.copyTo(target, offset, byteCount)
        }
    }

    fun bytesOrNull(): ByteArray? = copy?.readByteArray()
}

/**
 * 호스트가 요청 본문을 네트워크로 쓸 때 같은 바이트를 복사한다. 본문을 따로 한 번 더 쓰지 않는다.
 * 리다이렉트·재시도로 여러 번 쓰여도 첫 번째로 끝까지 쓴 결과만 알린다.
 */
internal class TeeRequestBody(
    private val delegate: RequestBody,
    private val limit: Long,
    private val onWritten: (copy: CappedCopy) -> Unit,
) : RequestBody() {
    private val reported = AtomicBoolean()

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = delegate.isOneShot()

    override fun isDuplex(): Boolean = delegate.isDuplex()

    override fun writeTo(sink: BufferedSink) {
        if (reported.get()) {
            delegate.writeTo(sink)
            return
        }
        val copy = CappedCopy(limit)
        val tee = CopyingSink(sink, copy).buffer()
        delegate.writeTo(tee)
        tee.emit()
        if (reported.compareAndSet(false, true)) onWritten(copy)
    }

    private class CopyingSink(delegate: Sink, private val copy: CappedCopy) : ForwardingSink(delegate) {
        override fun write(source: Buffer, byteCount: Long) {
            copy.append(source, 0, byteCount)
            super.write(source, byteCount)
        }
    }
}

/** 응답 본문을 호스트가 읽은 만큼만 복사하는 쪽에서 알리는 결과. 각 콜백은 많아야 한 번, 셋 중 하나만 불린다. */
internal interface ResponseBodyObserver {
    /** 호스트가 본문 끝(EOF)까지 읽었다. */
    fun onComplete(copy: CappedCopy)

    /** 끝까지 읽기 전에 닫았다. */
    fun onClosedEarly(bytesRead: Long)

    /** 읽는 중 I/O 예외가 났다. 예외는 호스트에 그대로 전달된다. */
    fun onReadFailed(bytesRead: Long, error: IOException)
}

/**
 * 호스트가 읽는 바이트를 그대로 넘기면서 [limit]까지 복사하는 응답 본문. 미리 읽거나 버퍼에 모아 두지 않으므로
 * 스트리밍·부분 읽기·취소 동작이 원래 본문과 같다.
 */
internal class TeeResponseBody(private val delegate: ResponseBody, private val limit: Long, private val observer: ResponseBodyObserver) :
    ResponseBody() {
    private val teeSource: BufferedSource by lazy { TeeSource(delegate.source(), CappedCopy(limit), observer).buffer() }

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = teeSource

    private class TeeSource(delegate: Source, private val copy: CappedCopy, private val observer: ResponseBodyObserver) :
        ForwardingSource(delegate) {
        private val finished = AtomicBoolean()

        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = try {
                super.read(sink, byteCount)
            } catch (error: IOException) {
                if (finished.compareAndSet(false, true)) observer.onReadFailed(copy.total, error)
                throw error
            }
            if (read == -1L) {
                if (finished.compareAndSet(false, true)) observer.onComplete(copy)
            } else {
                copy.append(sink, sink.size - read, read)
            }
            return read
        }

        override fun close() {
            try {
                super.close()
            } finally {
                if (finished.compareAndSet(false, true)) observer.onClosedEarly(copy.total)
            }
        }
    }
}
