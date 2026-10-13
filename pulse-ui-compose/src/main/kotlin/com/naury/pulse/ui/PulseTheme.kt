package com.naury.pulse.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** HTTP 결과 색. 색만으로 상태를 전달하지 않도록 항상 글자와 함께 쓴다. */
@Immutable
internal data class StatusColors(
    val success: Color,
    val redirect: Color,
    val clientError: Color,
    val serverError: Color,
    val failure: Color,
    val pending: Color,
)

internal object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}

internal val MonospaceStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)

private val LightStatus = StatusColors(
    success = Color(0xFF1B7F3B),
    redirect = Color(0xFF1F5FBF),
    clientError = Color(0xFF9A5B00),
    serverError = Color(0xFFC62828),
    failure = Color(0xFF8E1B1B),
    pending = Color(0xFF5F6368),
)

private val DarkStatus = StatusColors(
    success = Color(0xFF7BD88F),
    redirect = Color(0xFF8AB4F8),
    clientError = Color(0xFFF6C26B),
    serverError = Color(0xFFF28B82),
    failure = Color(0xFFFF8A80),
    pending = Color(0xFFBDC1C6),
)

internal val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val LightColors = lightColorScheme(
    primary = Color(0xFF2D5BA8),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFDDE5F4),
    onSecondaryContainer = Color(0xFF15233B),
    background = Color(0xFFF8F9FB),
    surface = Color(0xFFF8F9FB),
    surfaceVariant = Color(0xFFE8EBF0),
    onSurfaceVariant = Color(0xFF444A55),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C3F0),
    onPrimary = Color(0xFF0E2A57),
    secondaryContainer = Color(0xFF2F3B52),
    onSecondaryContainer = Color(0xFFDDE5F4),
    background = Color(0xFF121418),
    surface = Color(0xFF121418),
    surfaceVariant = Color(0xFF2A2E35),
    onSurfaceVariant = Color(0xFFC3C7CF),
)

@Composable
internal fun PulseTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
