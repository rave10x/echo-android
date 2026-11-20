package app.echo.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import app.echo.EchoApp
import app.echo.YtMode
import app.echo.data.AppJson
import app.echo.data.Store
import app.echo.data.Track
import kotlinx.coroutines.*

fun Track.toItem(): MediaItem {
    val extras = Bundle().apply { putString("t", AppJson.encodeToString(Track.serializer(), this@toItem)) }
    val md = MediaMetadata.Builder().setTitle(title).setArtist(artist.ifBlank { null }).setAlbumTitle(album.ifBlank { null })
        .setArtworkUri(art?.let { Uri.parse(it) }).setExtras(extras).setIsPlayable(true).build()
    return MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(md).build()
}
fun MediaItem.toTrack(): Track? = mediaMetadata.extras?.getString("t")?.let { runCatching { AppJson.decodeFromString<Track>(it) }.getOrNull() }

/** Compose-friendly mirror of the ExoPlayer (same player the notification / lock screen controls). */
class PlayerState(private val ctx: Context, val p: Player, private val store: Store) : Player.Listener {
    var track by mutableStateOf<Track?>(null); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var pos by mutableLongStateOf(0L)
    var dur by mutableLongStateOf(0L); private set
    var shuffle by mutableStateOf(false); private set
    var repeat by mutableStateOf(Player.REPEAT_MODE_OFF); private set
    var speed by mutableStateOf(1f); private set
    var queue by mutableStateOf<List<Track>>(emptyList()); private set
    var index by mutableStateOf(0); private set
    var error by mutableStateOf<String?>(null)
    var sleepAt by mutableLongStateOf(0L); private set
    var ytVideo by mutableStateOf(false); private set
    private val scope = MainScope()
    private var sleepJob: Job? = null
    private var lastId: String? = null

    init {
        p.addListener(this); sync()
        scope.launch { while (true) { delay(if (p.isPlaying) 250 else 1000); pos = p.currentPosition.coerceAtLeast(0); if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) { p.pause(); sleepAt = 0 } } }
        scope.launch { while (true) { delay(15_000); persist() } }
    }

    private fun sync() {
        val item = p.currentMediaItem
        track = item?.toTrack(); playing = p.isPlaying; buffering = p.playbackState == Player.STATE_BUFFERING
        dur = if (p.duration > 0) p.duration else track?.durMs ?: 0
        shuffle = p.shuffleModeEnabled; repeat = p.repeatMode; speed = p.playbackParameters.speed
        index = p.currentMediaItemIndex
        if (queue.size != p.mediaItemCount || queue.getOrNull(index)?.id != track?.id) queue = (0 until p.mediaItemCount).mapNotNull { p.getMediaItemAt(it).toTrack() }
        if (track?.id != lastId) { lastId = track?.id; error = null; track?.let { if (p.playWhenReady) store.played(it) }; persist() }
    }
    override fun onEvents(player: Player, events: Player.Events) { sync() }
    override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
        error = (e.cause?.message ?: e.message ?: "Playback error")
        if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.play() }
    }
    private fun persist() { if (queue.isNotEmpty()) store.saveQueue(queue, index, p.currentPosition.coerceAtLeast(0)) }

    private fun ensureService() { runCatching { ctx.startService(Intent(ctx, EchoService::class.java)) } }

    fun play(list: List<Track>, start: Int = 0, shuffleOn: Boolean = false) {
        if (list.isEmpty()) return
        ensureService()
        p.shuffleModeEnabled = shuffleOn
        p.setMediaItems(list.map { it.toItem() }, if (shuffleOn) (list.indices).random() else start.coerceIn(0, list.lastIndex), 0)
        p.prepare(); p.play()
    }
    fun playNext(t: Track) { ensureService(); if (p.mediaItemCount == 0) play(listOf(t)) else p.addMediaItem(p.currentMediaItemIndex + 1, t.toItem()) }
    fun enqueue(ts: List<Track>) { ensureService(); if (p.mediaItemCount == 0) play(ts) else p.addMediaItems(ts.map { it.toItem() }) }
    fun jump(i: Int) { p.seekToDefaultPosition(i); p.play() }
    fun removeAt(i: Int) { if (i in 0 until p.mediaItemCount) p.removeMediaItem(i) }
    fun moveQueue(from: Int, to: Int) { if (from in 0 until p.mediaItemCount && to in 0 until p.mediaItemCount) p.moveMediaItem(from, to) }
    fun toggle() { if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition(0); p.play() } }
    fun next() { p.seekToNextMediaItem() }
    fun prev() { if (p.currentPosition > 4000) p.seekTo(0) else p.seekToPreviousMediaItem() }
    fun seek(ms: Long) { p.seekTo(ms); pos = ms }
    fun toggleShuffle() { p.shuffleModeEnabled = !p.shuffleModeEnabled }
    fun cycleRepeat() { p.repeatMode = when (p.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }
    fun changeSpeed(s: Float) { p.setPlaybackSpeed(s) }
    fun sleepIn(min: Int) { sleepAt = if (min <= 0) 0 else System.currentTimeMillis() + min * 60_000L }
    fun clear() { p.stop(); p.clearMediaItems() }

    /** YouTube only: re-open the current item as muxed video (or back to audio-only) at the same position. */
    fun changeYtVideo(on: Boolean) {
        YtMode.video = on; ytVideo = on
        val at = p.currentPosition; val play = p.playWhenReady
        p.stop(); p.prepare(); p.seekTo(at); p.playWhenReady = play
    }
    val showsVideo get() = track?.let { it.video || (it.yt && ytVideo) } == true

    /** Resume where you left off (paused). */
    fun restore() {
        val d = store.data
        if (p.mediaItemCount > 0 || d.lastQueue.isEmpty()) return
        p.setMediaItems(d.lastQueue.map { it.toItem() }, d.lastIndex.coerceIn(0, d.lastQueue.lastIndex), d.lastPos); p.prepare()
    }
}
