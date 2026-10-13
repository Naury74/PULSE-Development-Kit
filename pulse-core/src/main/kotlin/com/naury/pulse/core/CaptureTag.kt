package com.naury.pulse.core

import com.naury.pulse.api.CaptureSource

/**
 * PULSE가 직접 보내는 요청(재실행 등)에 붙이는 OkHttp 요청 태그. 인터셉터가 이 값으로 트랜잭션 ID·출처·원본을 정한다.
 * 호스트 앱 요청에는 붙지 않는다.
 */
public class CaptureTag(public val transactionId: String, public val source: CaptureSource, public val parentTransactionId: String?)
