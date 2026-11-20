package app.echo.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.echo.EchoApp
import app.echo.MainActivity
import app.echo.R
import app.echo.data.LIKED
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Media3 service: publishes the player as a proper music session (notification with prev/play/next/seek, lock screen, Bluetooth, Android Auto-style controls). */
class EchoService : MediaLibraryService() {
    private var session: MediaLibraryService.MediaLibrarySession? = null
    private val scope = MainScope()
    private val LIKE = SessionCommand("echo.like", Bundle.EMPTY)
    private val SHUFFLE = SessionCommand("echo.shuffle", Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        val app = application as EchoApp
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibraryService.MediaLibrarySession.Builder(this, app.player, object : MediaLibraryService.MediaLibrarySession.Callback {
                    // ---- browse tree for Android Auto and other media browsers ----
                    override fun onGetLibraryRoot(s: MediaLibraryService.MediaLibrarySession, b: MediaSession.ControllerInfo, params: MediaLibraryService.LibraryParams?) =
                        Futures.immediateFuture(LibraryResult.ofItem(folder("root", "Echo"), params))
                    override fun onGetItem(s: MediaLibraryService.MediaLibrarySession, b: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
                        val t = find(app, mediaId)
                        return Futures.immediateFuture(if (t != null) LibraryResult.ofItem(t.toItem(), null) else LibraryResult.ofItem(folder(mediaId, mediaId), null))
                    }
                    override fun onGetChildren(s: MediaLibraryService.MediaLibrarySession, b: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                        val d = app.store.data
                        val items: List<MediaItem> = when {
                            parentId == "root" -> listOf(folder("songs", "Songs"), folder("playlists", "Playlists"), folder("offline", "Offline songs"), folder("recent", "Recently played"))
                            parentId == "songs" -> app.librarySongs.map { it.toItem() }
                            parentId == "offline" -> d.offline.filter { it.id in app.offline.saved.value }.map { it.toItem() }
                            parentId == "recent" -> d.recent.map { it.toItem() }
                            parentId == "playlists" -> d.playlists.map { folder("pl:" + it.id, it.name) }
                            parentId.startsWith("pl:") -> d.playlists.firstOrNull { it.id == parentId.removePrefix("pl:") }?.tracks?.map { it.toItem() }.orEmpty()
                            else -> emptyList()
                        }
                        val from = (page * pageSize).coerceAtMost(items.size)
                        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items.drop(from).take(pageSize)), params))
                    }
                    /** Browsers send only a media id: fill in the real item (uri, metadata) so it can play. */
                    override fun onAddMediaItems(s: MediaSession, c: MediaSession.ControllerInfo, items: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                        Futures.immediateFuture(items.map { find(app, it.mediaId)?.toItem() ?: it }.toMutableList())

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
        setMediaNotificationProvider(androidx.media3.session.DefaultMediaNotificationProvider(this).also { it.setSmallIcon(R.drawable.ic_stat_music) })
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

    private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
    private fun find(app: EchoApp, id: String): app.echo.data.Track? {
        val d = app.store.data
        return app.librarySongs.firstOrNull { it.id == id } ?: d.recent.firstOrNull { it.id == id } ?: d.offline.firstOrNull { it.id == id } ?: d.playlists.flatMap { it.tracks }.firstOrNull { it.id == id }
    }

    private fun refreshLayout(app: EchoApp) {
        val t = (app.player.currentMediaItem)?.toTrack()
        val liked = t != null && app.store.isLiked(t.id)
        val like = CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED).setDisplayName(if (liked) "Unlike" else "Like").setSessionCommand(LIKE).build()
        val shuffle = CommandButton.Builder(if (app.player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF).setDisplayName("Shuffle").setSessionCommand(SHUFFLE).build()
        session?.setCustomLayout(ImmutableList.of(like, shuffle))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel(); session?.release(); session = null
        super.onDestroy()
    }
}
