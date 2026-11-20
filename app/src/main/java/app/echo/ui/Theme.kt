package app.echo.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF5C8A)
val Bg = Color(0xFF0E0F13)
val Surf = Color(0xFF181A20)
val Surf2 = Color(0xFF21242C)
val Dim = Color(0xFF9AA0AC)

interface FullscreenHost { fun setImmersive(on: Boolean) }
val LocalFullscreen = staticCompositionLocalOf<FullscreenHost?> { null }

@Composable
fun EchoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color.White, secondary = Color(0xFF7C9CFF), background = Bg, onBackground = Color(0xFFF2F3F5),
            surface = Surf, onSurface = Color(0xFFF2F3F5), surfaceVariant = Surf2, onSurfaceVariant = Dim, surfaceContainer = Surf, surfaceContainerHigh = Surf2,
            surfaceContainerHighest = Surf2, outlineVariant = Color(0xFF2C3039), primaryContainer = Color(0xFF3A1B27), onPrimaryContainer = Color(0xFFFFD9E2),
        ),
        content = content,
    )
}
