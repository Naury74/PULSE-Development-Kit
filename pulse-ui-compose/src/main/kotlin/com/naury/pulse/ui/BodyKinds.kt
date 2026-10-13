package com.naury.pulse.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.naury.pulse.api.HeaderField
import com.naury.pulse.api.NetworkType
import java.net.URLDecoder

/** 저장된 본문의 형식을 화면에서 구분한다. 저장할 때 이미 마스킹했으므로 여기서는 보여 주는 방법만 고른다. */
internal object BodyKinds {
    /** 형식을 모르면 예전 기록처럼 JSON으로 본다. */
    fun isJson(contentType: String?): Boolean {
        val subtype = mediaType(contentType)?.substringAfter('/', "") ?: return true
        return subtype == "json" || subtype.endsWith("+json")
    }

    fun isForm(contentType: String?): Boolean = mediaType(contentType) == "application/x-www-form-urlencoded"

    /** `a=1&b=2`를 이름·값 목록으로 푼다. 풀 수 없는 조각은 원래 글자 그대로 둔다. */
    fun formFields(text: String): List<HeaderField> = text.split('&').filter { it.isNotEmpty() }.map { pair ->
        HeaderField(decode(pair.substringBefore('=')), decode(pair.substringAfter('=', "")))
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        value
    }

    private fun mediaType(contentType: String?): String? = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf {
        it.isNotEmpty()
    }
}

@StringRes
private fun NetworkType.labelRes(): Int = when (this) {
    NetworkType.WIFI -> R.string.pulse_network_wifi
    NetworkType.CELLULAR -> R.string.pulse_network_cellular
    NetworkType.ETHERNET -> R.string.pulse_network_ethernet
    NetworkType.VPN -> R.string.pulse_network_vpn
    NetworkType.OTHER -> R.string.pulse_network_other
    NetworkType.NONE -> R.string.pulse_network_none
}

@Composable
internal fun networkLabel(type: NetworkType): String = stringResource(type.labelRes())
