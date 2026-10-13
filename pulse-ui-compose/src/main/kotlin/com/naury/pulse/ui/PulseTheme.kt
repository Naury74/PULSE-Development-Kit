package com.naury.pulse.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naury.pulse.api.TimingPhaseType

/** HTTP 결과 색. 색만으로 상태를 전달하지 않도록 항상 글자와 함께 쓴다. */
@Immutable
internal data class StatusColors(
    val success: Color,
    val redirect: Color,
    val clientError: Color,
    val serverError: Color,
    val failure: Color,
    val pending: Color,
    val slow: Color,
)

/** 메서드·타이밍 단계·JSON 값처럼 상태가 아닌 구분에 쓰는 색. 상태 색과 겹치지 않게 고른다. */
@Immutable
internal data class AccentColors(
    val get: Color,
    val post: Color,
    val put: Color,
    val patch: Color,
    val delete: Color,
    val otherMethod: Color,
    val phases: Map<TimingPhaseType, Color>,
    val waiting: Color,
    val jsonKey: Color,
    val jsonString: Color,
    val jsonNumber: Color,
    val jsonLiteral: Color,
    val codeBackground: Color,
) {
    fun method(method: String): Color = when (method.uppercase()) {
        "GET", "HEAD" -> get
        "POST" -> post
        "PUT" -> put
        "PATCH" -> patch
        "DELETE" -> delete
        else -> otherMethod
    }
}

internal object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}

internal val CardShape = RoundedCornerShape(14.dp)
internal val PillShape = RoundedCornerShape(6.dp)

internal val MonospaceStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)

private val LightStatus = StatusColors(
    success = Color(0xFF1E7F3E),
    redirect = Color(0xFF1864AB),
    clientError = Color(0xFFB45309),
    serverError = Color(0xFFC92A2A),
    failure = Color(0xFF9B1C1C),
    pending = Color(0xFF6B7280),
    slow = Color(0xFFB45309),
)

private val DarkStatus = StatusColors(
    success = Color(0xFF69DB7C),
    redirect = Color(0xFF74C0FC),
    clientError = Color(0xFFFFC078),
    serverError = Color(0xFFFF8787),
    failure = Color(0xFFFFA8A8),
    pending = Color(0xFFADB5BD),
    slow = Color(0xFFFFC078),
)

private val LightAccents = AccentColors(
    get = Color(0xFF1971C2),
    post = Color(0xFF2B8A3E),
    put = Color(0xFFD9480F),
    patch = Color(0xFF6741D9),
    delete = Color(0xFFC92A2A),
    otherMethod = Color(0xFF5C6370),
    phases = mapOf(
        TimingPhaseType.DNS to Color(0xFF0C8599),
        TimingPhaseType.CONNECT to Color(0xFFE67700),
        TimingPhaseType.TLS to Color(0xFF9C36B5),
        TimingPhaseType.REQUEST_HEADERS to Color(0xFF4263EB),
        TimingPhaseType.REQUEST_BODY to Color(0xFF748FFC),
        TimingPhaseType.RESPONSE_HEADERS to Color(0xFF2F9E44),
        TimingPhaseType.RESPONSE_BODY to Color(0xFF8CE99A),
    ),
    waiting = Color(0xFFADB5BD),
    jsonKey = Color(0xFF6741D9),
    jsonString = Color(0xFF2B8A3E),
    jsonNumber = Color(0xFF1971C2),
    jsonLiteral = Color(0xFFD9480F),
    codeBackground = Color(0xFFF6F7F9),
)

private val DarkAccents = AccentColors(
    get = Color(0xFF74C0FC),
    post = Color(0xFF8CE99A),
    put = Color(0xFFFFA94D),
    patch = Color(0xFFB197FC),
    delete = Color(0xFFFF8787),
    otherMethod = Color(0xFFADB5BD),
    phases = mapOf(
        TimingPhaseType.DNS to Color(0xFF3BC9DB),
        TimingPhaseType.CONNECT to Color(0xFFFFC078),
        TimingPhaseType.TLS to Color(0xFFDA77F2),
        TimingPhaseType.REQUEST_HEADERS to Color(0xFF748FFC),
        TimingPhaseType.REQUEST_BODY to Color(0xFFBAC8FF),
        TimingPhaseType.RESPONSE_HEADERS to Color(0xFF51CF66),
        TimingPhaseType.RESPONSE_BODY to Color(0xFFB2F2BB),
    ),
    waiting = Color(0xFF868E96),
    jsonKey = Color(0xFFB197FC),
    jsonString = Color(0xFF8CE99A),
    jsonNumber = Color(0xFF74C0FC),
    jsonLiteral = Color(0xFFFFA94D),
    codeBackground = Color(0xFF11141A),
)

internal val LocalStatusColors = staticCompositionLocalOf { LightStatus }
internal val LocalAccentColors = staticCompositionLocalOf { LightAccents }

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E9FF),
    onPrimaryContainer = Color(0xFF1C2D7A),
    secondaryContainer = Color(0xFFE3E9FF),
    onSecondaryContainer = Color(0xFF1C2D7A),
    background = Color(0xFFF3F4F7),
    onBackground = Color(0xFF14171D),
    surface = Color(0xFFF3F4F7),
    onSurface = Color(0xFF14171D),
    surfaceVariant = Color(0xFFE9EBF0),
    onSurfaceVariant = Color(0xFF596171),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF7F8FA),
    surfaceContainerHighest = Color(0xFFE9EBF0),
    outline = Color(0xFFC5CAD3),
    outlineVariant = Color(0xFFE2E5EA),
    error = Color(0xFFC92A2A),
    errorContainer = Color(0xFFFFE3E3),
    onErrorContainer = Color(0xFF7A1414),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF91A7FF),
    onPrimary = Color(0xFF0F1D5C),
    primaryContainer = Color(0xFF26336E),
    onPrimaryContainer = Color(0xFFDBE4FF),
    secondaryContainer = Color(0xFF26336E),
    onSecondaryContainer = Color(0xFFDBE4FF),
    background = Color(0xFF0C0E12),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF0C0E12),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF1E222B),
    onSurfaceVariant = Color(0xFFA2AAB8),
    surfaceContainerLowest = Color(0xFF15181F),
    surfaceContainerLow = Color(0xFF15181F),
    surfaceContainer = Color(0xFF15181F),
    surfaceContainerHigh = Color(0xFF1B1F27),
    surfaceContainerHighest = Color(0xFF242934),
    outline = Color(0xFF3A404C),
    outlineVariant = Color(0xFF262B35),
    error = Color(0xFFFF8787),
    errorContainer = Color(0xFF4A1515),
    onErrorContainer = Color(0xFFFFD8D8),
)

private val PulseShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
)

@Composable
internal fun PulseTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalStatusColors provides if (dark) DarkStatus else LightStatus,
        LocalAccentColors provides if (dark) DarkAccents else LightAccents,
    ) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, shapes = PulseShapes, content = content)
    }
}
