package app.echo.ui

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Accent = Color(0xFFFF5C8A)

class Palette(val dark: Boolean, val bg: Color, val surf: Color, val surf2: Color, val dim: Color, val text: Color)
val DarkPalette = Palette(true, Color(0xFF0E0F13), Color(0xFF181A20), Color(0xFF21242C), Color(0xFF9AA0AC), Color(0xFFF2F3F5))
val LightPalette = Palette(false, Color(0xFFF6F6F9), Color(0xFFFFFFFF), Color(0xFFEAEAF0), Color(0xFF6A6E76), Color(0xFF15161A))
val LocalPalette = staticCompositionLocalOf { DarkPalette }

val Bg: Color @Composable get() = LocalPalette.current.bg
val Surf: Color @Composable get() = LocalPalette.current.surf
val Surf2: Color @Composable get() = LocalPalette.current.surf2
val Dim: Color @Composable get() = LocalPalette.current.dim

interface FullscreenHost { fun setImmersive(on: Boolean) }
val LocalFullscreen = staticCompositionLocalOf<FullscreenHost?> { null }

fun applyBars(a: Activity?, dark: Boolean) {
    (a as? ComponentActivity)?.enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
        navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
    )
}

/** mode: "system", "dark" or "light". */
@Composable
fun EchoTheme(mode: String = "dark", bars: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val p = if (dark) DarkPalette else LightPalette
    val act = LocalContext.current as? Activity
    if (bars) SideEffect { applyBars(act, dark) }
    val scheme = if (dark) darkColorScheme(
        primary = Accent, onPrimary = Color.White, secondary = Color(0xFF7C9CFF), background = p.bg, onBackground = p.text, surface = p.surf, onSurface = p.text,
        surfaceVariant = p.surf2, onSurfaceVariant = p.dim, surfaceContainer = p.surf, surfaceContainerHigh = p.surf2, surfaceContainerHighest = p.surf2,
        outlineVariant = Color(0xFF2C3039), primaryContainer = Color(0xFF3A1B27), onPrimaryContainer = Color(0xFFFFD9E2),
    ) else lightColorScheme(
        primary = Accent, onPrimary = Color.White, secondary = Color(0xFF4A6BD6), background = p.bg, onBackground = p.text, surface = p.surf, onSurface = p.text,
        surfaceVariant = p.surf2, onSurfaceVariant = p.dim, surfaceContainer = p.surf, surfaceContainerHigh = p.surf2, surfaceContainerHighest = p.surf2,
        outlineVariant = Color(0xFFD9DAE0), primaryContainer = Color(0xFFFFD9E2), onPrimaryContainer = Color(0xFF3A0A1B),
    )
    CompositionLocalProvider(LocalPalette provides p) { MaterialTheme(colorScheme = scheme, content = content) }
}
