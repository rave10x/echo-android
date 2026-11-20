package app.echo.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.echo.EchoApp
import app.echo.MainActivity
import app.echo.data.LIKED
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Media3 service: publishes the player as a proper music session (notification with prev/play/next/seek, lock screen, Bluetooth, Android Auto-style controls). */
class EchoService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = MainScope()
    private val LIKE = SessionCommand("echo.like", Bundle.EMPTY)
    private val SHUFFLE = SessionCommand("echo.shuffle", Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        val app = application as EchoApp
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, app.player).setSessionActivity(open).setBitmapLoader(ArtBitmapLoader(this))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(s: MediaSession, c: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(s)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(LIKE).add(SHUFFLE).build()).build()
                override fun onCustomCommand(s: MediaSession, c: MediaSession.ControllerInfo, cmd: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    when (cmd.customAction) {
                        "echo.like" -> app.ui.track?.let { app.store.toggleLike(it) }
                        "echo.shuffle" -> app.ui.toggleShuffle()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).build()
        addSession(session!!) // we start the service ourselves (not via a controller bind), so register the session for the notification
        // custom buttons next to the standard transport controls: like + shuffle
        scope.launch {
            app.store.state.collectLatest { refreshLayout(app) }
        }
        app.player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) refreshLayout(app)
            }
        })
    }

    private fun refreshLayout(app: EchoApp) {
        val t = (app.player.currentMediaItem)?.toTrack()
        val liked = t != null && app.store.isLiked(t.id)
        val like = CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED).setDisplayName(if (liked) "Unlike" else "Like").setSessionCommand(LIKE).build()
        val shuffle = CommandButton.Builder(if (app.player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF).setDisplayName("Shuffle").setSessionCommand(SHUFFLE).build()
        session?.setCustomLayout(ImmutableList.of(like, shuffle))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel(); session?.release(); session = null
        super.onDestroy()
    }
}
