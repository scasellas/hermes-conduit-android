package app.hermesconduit.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Amber/gold accent, matching the iOS app's `conduitAccent`.
private val AccentDark = Color(0xFFE0AB47)
private val AccentLight = Color(0xFF8C5E21)
private val AccentSoftDark = Color(0xFFFAD47A)
private val AccentSoftLight = Color(0xFFAB7D47)

val AttentionDark = Color(0xFFF5A63D)
val AttentionLight = Color(0xFFBD6B0A)
val SuccessDark = Color(0xFF61CC80)
val SuccessLight = Color(0xFF218745)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF2A1A00),
    primaryContainer = Color(0xFF4A3510),
    onPrimaryContainer = AccentSoftDark,
    secondary = Color(0xFF6194FA),
    background = Color(0xFF000000),
    surface = Color(0xFF0E0E10),
    surfaceVariant = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF242427),
    surfaceContainerLow = Color(0xFF141416),
)

private val LightColors = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5E3C4),
    onPrimaryContainer = Color(0xFF3B2600),
    secondary = Color(0xFF3B66C9),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF2F2F7),
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFE9E9EF),
    surfaceContainerLow = Color(0xFFF7F7FA),
)

@Composable
fun ConduitTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

@Composable
fun attentionColor() = if (isSystemInDarkTheme()) AttentionDark else AttentionLight

@Composable
fun successColor() = if (isSystemInDarkTheme()) SuccessDark else SuccessLight

