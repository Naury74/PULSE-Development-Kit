package com.naury.pulse.analytics

import com.naury.pulse.api.ResponseSource
import com.naury.pulse.api.TransactionState

internal fun sample(
    id: String,
    at: Long,
    path: String = "/items",
    method: String = "GET",
    host: String = "api.test",
    durationMillis: Long? = 100,
    status: Int? = 200,
    state: TransactionState = if (status == null) TransactionState.FAILED else TransactionState.COMPLETED,
    responseBytes: Long? = 10,
    credentials: Boolean = false,
    source: ResponseSource? = ResponseSource.NETWORK,
) = TrafficSample(
    id = id,
    method = method,
    url = "https://$host$path",
    host = host,
    path = path,
    startedAtEpochMillis = at,
    durationNanos = durationMillis?.let { it * 1_000_000 },
    statusCode = status,
    state = state,
    requestBodyBytes = 0,
    responseBodyBytes = responseBytes,
    hasCredentials = credentials,
    responseSource = source,
)
