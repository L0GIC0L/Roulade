package io.github.l0gic0l.roulade.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette (Android 10–11, no wallpaper colours): warm amber primary, steel-blue secondary.
private val LightColors = lightColorScheme(
    primary = Color(0xFF805600), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB1), onPrimaryContainer = Color(0xFF291800),
    secondary = Color(0xFF3A6383), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE5FF), onSecondaryContainer = Color(0xFF001D31),
    tertiary = Color(0xFF55633C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8E9B7), onTertiaryContainer = Color(0xFF131F02),
    background = Color(0xFFFFF8F3), surface = Color(0xFFFFF8F3),
    surfaceContainerLow = Color(0xFFFBF2EA), surfaceContainer = Color(0xFFF5ECE4),
    surfaceContainerHigh = Color(0xFFEFE6DE), surfaceContainerHighest = Color(0xFFE9E1D9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB94C), onPrimary = Color(0xFF442B00),
    primaryContainer = Color(0xFF614000), onPrimaryContainer = Color(0xFFFFDDB1),
    secondary = Color(0xFFA3CBF0), onSecondary = Color(0xFF00344F),
    secondaryContainer = Color(0xFF1F4B6A), onSecondaryContainer = Color(0xFFCDE5FF),
    tertiary = Color(0xFFBCCD9D), onTertiary = Color(0xFF283413),
    tertiaryContainer = Color(0xFF3E4B27), onTertiaryContainer = Color(0xFFD8E9B7),
    background = Color(0xFF17130E), surface = Color(0xFF17130E),
    surfaceContainerLow = Color(0xFF1F1B16), surfaceContainer = Color(0xFF231F1A),
    surfaceContainerHigh = Color(0xFF2E2924), surfaceContainerHighest = Color(0xFF39342E),
)

/** Material You: wallpaper-based dynamic colour on Android 12+, a matching static palette before that. */
@Composable
fun RouladeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
