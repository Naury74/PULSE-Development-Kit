package com.naury.pulse.export

import com.naury.pulse.api.TransactionDetail

/** 내보내기 형식. */
public enum class ExportFormat(public val fileExtension: String, public val mimeType: String) {
    /** PULSE 자체 JSON(`pulse.capture.v1`). 본문 상태·마스킹 수 같은 PULSE 정보를 모두 담는다. */
    PULSE_JSON("json", "application/json"),

    /** HAR 1.2. 관찰하지 못한 타이밍은 -1이고, PULSE 정보는 `_pulse` 확장 필드에 둔다. */
    HAR("har", "application/json"),

    /** 요청마다 한 줄인 표(RFC 4180). 헤더·본문 없이 결과·시간·크기·맥락만 담아 스프레드시트로 분석한다. */
    CSV("csv", "text/csv"),

    /** 사람이 읽는 보고서. 요청마다 결과·URL·헤더·본문(앞부분)을 이슈나 문서에 붙일 수 있는 Markdown으로 쓴다. */
    MARKDOWN("md", "text/markdown"),
}

/**
 * 무엇을 내보낼지 고르는 선택지.
 *
 * @property includeHeaders 헤더를 넣을지. 끄면 헤더 목록이 비어 있다.
 * @property preferNetworkHeaders HAR·Markdown에서 실제로 오간 헤더가 있으면 앱 단계 헤더 대신 쓴다. PULSE JSON은 둘 다 쓴다.
 * @property includeRequestBodies 저장된 요청 본문을 넣을지.
 * @property includeResponseBodies 저장된 응답 본문을 넣을지.
 * @property includeSynthetic 재실행·모의 응답 기록도 넣을지.
 * @property sinceEpochMillis 이 시각 이후에 시작한 요청만. null이면 기간 제한 없음.
 * @property transactionIds 이 ID들만 내보낸다(검색 조건은 무시). null이면 검색 조건에 맞는 전체.
 * @property maxTransactions 최대 개수, 1 이상.
 */
public class ExportOptions @JvmOverloads constructor(
    public val includeHeaders: Boolean = true,
    public val preferNetworkHeaders: Boolean = true,
    public val includeRequestBodies: Boolean = true,
    public val includeResponseBodies: Boolean = true,
    public val includeSynthetic: Boolean = true,
    public val sinceEpochMillis: Long? = null,
    public val transactionIds: List<String>? = null,
    public val maxTransactions: Int = PulseExporter.DEFAULT_MAX_TRANSACTIONS,
) {
    init {
        require(maxTransactions >= 1) { "maxTransactions는 1 이상이어야 합니다." }
    }
}

/** 내보낼 트랜잭션 하나와 저장된(마스킹된) 본문. */
public class ExportEntry(public val detail: TransactionDetail, public val requestBody: String?, public val responseBody: String?)

/**
 * 내보내기 결과.
 *
 * @property transactionCount 쓴 트랜잭션 수.
 * @property droppedBodyCount 다시 마스킹할 수 없어 빼고 쓴 본문 수.
 * @property truncated 상한 때문에 일부만 썼는지.
 */
public class ExportResult(public val transactionCount: Int, public val droppedBodyCount: Int, public val truncated: Boolean)
