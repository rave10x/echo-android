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
class PlayerState(private val ctx: Context, val p: Player, private val store: Store, val offline: app.echo.data.Offline) : Player.Listener {
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
    private var widgetKey: String? = null
    private var failures = 0
    private var saveTried: String? = null

    init {
        p.addListener(this); sync()
        scope.launch { while (true) { delay(if (p.isPlaying) (if (store.data.fadeSec > 0) 100 else 250) else 1000); pos = p.currentPosition.coerceAtLeast(0); tickListen(); tickFade(); tickLyricNotif(); if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) { p.pause(); sleepAt = 0 } } }
        scope.launch { while (true) { delay(15_000); persist() } }
    }

    // ---- listening stats: add the time spent playing a song to today's log every ~15 s and when the song changes
    private var lastTick = android.os.SystemClock.elapsedRealtime(); private var accMs = 0L; private var accTrack: Track? = null
    private fun tickListen() {
        val now = android.os.SystemClock.elapsedRealtime(); val dt = now - lastTick; lastTick = now
        val cur = track
        if (accTrack?.id != cur?.id) { flushListen(); accTrack = cur }
        if (p.isPlaying && dt in 1..5000) accMs += dt
        if (accMs >= 15_000) flushListen()
    }
    private fun flushListen() { accTrack?.let { if (accMs > 0) store.logListen(it, accMs) }; accMs = 0 }

    // ---- current lyric line shown in the notification / lock screen (replaces the artist text; the real artist comes back when it is switched off)
    private var lyr: app.echo.data.Lyrics? = null; private var lyrFor: String? = null; private var shownLine: String? = null
    private fun setNotifLine(line: String?) {
        val item = p.currentMediaItem ?: return; val base = item.toTrack() ?: return
        val md = item.mediaMetadata.buildUpon().setArtist(line ?: base.artist.ifBlank { null }).build()
        p.replaceMediaItem(p.currentMediaItemIndex, item.buildUpon().setMediaMetadata(md).build()); shownLine = line
    }
    private fun tickLyricNotif() {
        val t = track ?: return
        if (lyrFor != t.id) { lyrFor = t.id; lyr = null; shownLine = null
            if (store.data.lyricNotif) scope.launch { lyr = withContext(Dispatchers.IO) { app.echo.data.LyricsRepo.get(ctx, t) } } }
        if (!store.data.lyricNotif) { if (shownLine != null) setNotifLine(null); return }
        if (lyr == null && lyrFor == t.id) { scope.launch { lyr = withContext(Dispatchers.IO) { app.echo.data.LyricsRepo.get(ctx, t) } }; lyr = app.echo.data.Lyrics(source = "pending") ; return }
        val l = lyr?.synced?.takeIf { it.isNotEmpty() } ?: return
        val line = l.lastOrNull { it.t <= p.currentPosition + 200 }?.text?.takeIf { it.isNotBlank() }
        if (line != shownLine) setNotifLine(line)
    }

    // ---- "fade between songs": fade out over the last N seconds of a song and fade the next one in
    private var lastVol = 1f
    private fun tickFade() {
        val f = store.data.fadeSec * 1000L
        var v = 1f
        if (f > 0 && p.isPlaying && p.duration > 0 && track?.let { !(it.yt && it.durMs == 0L) } == true) {
            val left = p.duration - p.currentPosition
            if (left in 0 until f && (p.hasNextMediaItem() || p.repeatMode != Player.REPEAT_MODE_OFF)) v = minOf(v, left.toFloat() / f)
            val inMs = minOf(f, 3000L); if (p.currentPosition in 0 until inMs) v = minOf(v, 0.15f + 0.85f * p.currentPosition / inMs)
        }
        if (kotlin.math.abs(v - lastVol) > 0.01f || (v == 1f && lastVol != 1f)) { lastVol = v; p.volume = v }
    }

    private fun sync() {
        val item = p.currentMediaItem
        track = item?.toTrack(); playing = p.isPlaying; buffering = p.playbackState == Player.STATE_BUFFERING
        dur = if (p.duration > 0) p.duration else track?.durMs ?: 0
        shuffle = p.shuffleModeEnabled; repeat = p.repeatMode; speed = p.playbackParameters.speed
        index = p.currentMediaItemIndex
        if (queue.size != p.mediaItemCount || queue.getOrNull(index)?.id != track?.id) queue = (0 until p.mediaItemCount).mapNotNull { p.getMediaItemAt(it).toTrack() }
        if (widgetKey != "${track?.id}|$playing") { widgetKey = "${track?.id}|$playing"; app.echo.widget.EchoWidgets.refresh(ctx) }
        track?.let { t -> if (playing && t.yt && saveTried != t.id) { saveTried = t.id; if (store.data.autoSave) offline.save(t) } }
        if (track?.id != lastId) { lastId = track?.id; error = null; failures = 0
            // keep what you listen to: save the whole song so it plays offline next time
            track?.let { if (p.playWhenReady) store.played(it) }; persist() }
    }
    override fun onEvents(player: Player, events: Player.Events) { sync() }
    override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
        error = (e.cause?.message ?: e.message ?: "Playback error")
        if (++failures <= 3 && p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.play() }
    }
    private fun persist() { if (queue.isNotEmpty()) store.saveQueue(queue, index, p.currentPosition.coerceAtLeast(0)) }

    fun ensureService() { runCatching { ctx.startService(Intent(ctx, EchoService::class.java)) } }

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
    fun toggle() { if (p.isPlaying) p.pause() else { ensureService(); if (p.playbackState == Player.STATE_IDLE) p.prepare(); if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition(0); p.play() } }
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
