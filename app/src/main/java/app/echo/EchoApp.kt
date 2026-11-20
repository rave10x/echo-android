package app.echo

import android.app.Application
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.echo.data.Store
import app.echo.playback.PlayerState
import app.echo.yt.YT
import java.io.IOException

/** YouTube items play audio-only unless the user switches to video. */
object YtMode { @Volatile var video = false }

class EchoApp : Application() {
    val store by lazy { Store(this) }
    val player: ExoPlayer by lazy {
        val http = DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36").setAllowCrossProtocolRedirects(true)
        val dsf = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http), ResolvingDataSource.Resolver { spec: DataSpec ->
            if (spec.uri.scheme == "yt") {
                try { spec.withUri(Uri.parse(YT.streamUrl(spec.uri.schemeSpecificPart, YtMode.video))) } catch (e: Exception) { throw IOException(e.message ?: "Could not open YouTube stream", e) }
            } else spec
        })
        ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsf))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 60_000, 1_500, 3_000).build())
            .build().apply { repeatMode = Player.REPEAT_MODE_OFF }
    }
    val ui by lazy { PlayerState(this, player, store) }
}
