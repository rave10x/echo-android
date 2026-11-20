package app.echo

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.echo.ui.EchoRoot
import app.echo.ui.EchoTheme
import app.echo.ui.FullscreenHost
import app.echo.ui.LocalFullscreen

class MainActivity : ComponentActivity(), FullscreenHost {
    private val vm: EchoVM by viewModels()
    var openNow by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        handle(intent)
        setContent { EchoTheme { CompositionLocalProvider(LocalFullscreen provides this@MainActivity) { EchoRoot(vm, openNow) { openNow = it } } } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        when (i?.action) {
            Intent.ACTION_SEND -> if (i.type == "text/plain") vm.sharedLink = i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> i.data?.let { vm.playExternal(it, i.type ?: contentResolver.getType(it)); openNow = true }
        }
    }

    override fun setImmersive(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars()); requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else { c.show(WindowInsetsCompat.Type.systemBars()); requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}
