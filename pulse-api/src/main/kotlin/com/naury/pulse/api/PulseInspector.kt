package com.naury.pulse.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 저장된 트랜잭션을 읽고 수집을 제어하는 계약. UI 모듈은 이 인터페이스만 사용한다.
 *
 * 모든 메서드는 어느 스레드에서 불러도 된다. suspend 함수는 내부에서 I/O 디스패처로 옮겨 실행한다.
 */
public interface PulseInspector {
    public val recordingState: StateFlow<RecordingState>

    public val captureHealth: StateFlow<CaptureHealth>

    /**
     * [query]에 맞는 트랜잭션을 최신순으로 [limit]개까지 읽는다.
     *
     * @param after 이전 페이지의 [TransactionPage.next]. null이면 가장 최신부터 읽는다.
     * @param limit 1..500.
     */
    public suspend fun loadTransactions(query: TransactionQuery, after: PageCursor?, limit: Int): TransactionPage

    /** 저장된 트랜잭션이 바뀔 때마다 값을 낸다. 바뀐 내용은 담지 않으므로 필요한 쪽이 다시 읽는다. */
    public fun observeChanges(): Flow<Unit>

    /** 트랜잭션 상세. 없거나 지워졌으면 null. */
    public suspend fun loadTransaction(id: String): TransactionDetail?

    /** 저장한(마스킹된) 본문. [BodyState.CAPTURED]가 아니거나 파일이 지워졌으면 null. */
    public suspend fun loadBody(id: String, kind: MessageKind): String?

    /** 기록을 멈춘다. 이미 진행 중인 요청은 끝까지 기록한다. */
    public fun pause()

    /** 기록을 다시 시작한다. */
    public fun resume()

    /** 저장된 트랜잭션과 본문을 모두 지운다. 지우는 작업은 백그라운드에서 진행한다. */
    public fun clear()
}

/** 목록 조회 조건. */
public class TransactionQuery @JvmOverloads constructor(
    /** 마스킹한 URL에서 찾을 문자열. 빈 문자열이면 조건 없음. 대소문자를 구분하지 않는다. */
    public val text: String = "",
    public val status: StatusFilter = StatusFilter.ALL,
) {
    override fun equals(other: Any?): Boolean = other is TransactionQuery && text == other.text && status == other.status

    override fun hashCode(): Int = 31 * text.hashCode() + status.hashCode()

    override fun toString(): String = "TransactionQuery(text=$text, status=$status)"
}

/** 상태 필터. */
public enum class StatusFilter {
    ALL,

    /** 응답 코드 1xx·2xx·3xx. */
    SUCCESS,

    /** 응답 코드 4xx. */
    CLIENT_ERROR,

    /** 응답 코드 5xx. */
    SERVER_ERROR,

    /** 응답 없이 실패·취소·중단된 요청. */
    FAILED,

    /** 아직 끝나지 않은 요청. */
    IN_FLIGHT,
}

/** 다음 페이지 위치. 내용은 구현에 속하므로 값을 만들거나 해석하지 않는다. */
public class PageCursor(public val startedAtEpochMillis: Long, public val id: String) {
    override fun equals(other: Any?): Boolean = other is PageCursor && startedAtEpochMillis == other.startedAtEpochMillis && id == other.id

    override fun hashCode(): Int = 31 * startedAtEpochMillis.hashCode() + id.hashCode()
}

/** 한 페이지. [next]가 null이면 마지막 페이지다. */
public class TransactionPage(public val items: List<TransactionSummary>, public val next: PageCursor?)

/** 설정이 맞지 않거나 잘못된 상태에서 API를 부르면 던진다. 네트워크 호출 경로에서는 던지지 않는다. */
public class PulseConfigurationException(message: String) : IllegalStateException(message)
