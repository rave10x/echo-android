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
import app.echo.data.Offline
import app.echo.data.Store
import app.echo.playback.PlayerState
import app.echo.yt.YT
import java.io.IOException

/** YouTube items play audio-only unless the user switches to video. */
object YtMode { @Volatile var video = false }

class EchoApp : Application() {
    val store by lazy { Store(this) }
    private val http by lazy { DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36").setAllowCrossProtocolRedirects(true) }
    private val plain by lazy { DefaultDataSource.Factory(this, http) }
    /** Turns yt:<id> into a real, short-lived stream URL when a file is opened, or into the saved file if the song is offline. */
    val upstream by lazy {
        ResolvingDataSource.Factory(plain, ResolvingDataSource.Resolver { spec: DataSpec ->
            if (spec.uri.scheme == "yt") {
                val id = spec.uri.schemeSpecificPart
                val local = if (YtMode.video) null else offline.localFile(id)
                try { spec.withUri(if (local != null) Uri.fromFile(local) else Uri.parse(YT.streamUrl(id, YtMode.video))) } catch (e: Exception) { throw IOException(e.message ?: "Could not open YouTube stream", e) }
            } else spec
        })
    }
    val offline by lazy { Offline(this, store) }
    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(upstream))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 60_000, 1_500, 3_000).build())
            .build().apply { repeatMode = Player.REPEAT_MODE_OFF }
    }
    val effects by lazy { app.echo.data.Effects(store).also { fx ->
        fx.attach(player.audioSessionId)
        player.addListener(object : Player.Listener { override fun onAudioSessionIdChanged(audioSessionId: Int) { fx.attach(audioSessionId) } })
    } }
    /** The local library, shared with the Android Auto browser. */
    @Volatile var librarySongs: List<app.echo.data.Track> = emptyList()
    val ui by lazy { PlayerState(this, player, store, offline) }
}
