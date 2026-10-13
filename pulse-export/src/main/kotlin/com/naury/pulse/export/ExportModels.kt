package com.naury.pulse.export

import com.naury.pulse.api.TransactionDetail

/** 내보내기 형식. */
public enum class ExportFormat(public val fileExtension: String, public val mimeType: String) {
    /** PULSE 자체 JSON(`pulse.capture.v1`). 본문 상태·마스킹 수 같은 PULSE 정보를 모두 담는다. */
    PULSE_JSON("json", "application/json"),

    /** HAR 1.2. 관찰하지 못한 타이밍은 -1이고, PULSE 정보는 `_pulse` 확장 필드에 둔다. */
    HAR("har", "application/json"),
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
