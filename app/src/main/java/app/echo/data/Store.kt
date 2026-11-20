package app.echo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Playlists, favourites, history and settings in one small JSON file. */
class Store(ctx: Context) {
    private val file = File(ctx.filesDir, "echo.json")
    private val _s = MutableStateFlow(load())
    val state: StateFlow<DataFile> = _s
    val data get() = _s.value

    private fun load(): DataFile {
        val d = runCatching { AppJson.decodeFromString<DataFile>(file.readText()) }.getOrDefault(DataFile())
        return if (d.playlists.any { it.id == LIKED }) d else d.copy(playlists = listOf(Playlist(LIKED, "Liked songs")) + d.playlists)
    }

    @Synchronized private fun update(f: (DataFile) -> DataFile) {
        val n = f(_s.value); _s.value = n
        runCatching { val t = File(file.path + ".tmp"); t.writeText(AppJson.encodeToString(DataFile.serializer(), n)); t.renameTo(file) }
    }

    fun playlist(id: String) = data.playlists.firstOrNull { it.id == id }
    fun isLiked(trackId: String) = playlist(LIKED)?.tracks?.any { it.id == trackId } == true

    fun createPlaylist(name: String, first: List<Track> = emptyList()): String {
        val id = "p" + System.currentTimeMillis().toString(36)
        update { it.copy(playlists = it.playlists + Playlist(id, name.trim().ifBlank { "New playlist" }, first)) }
        return id
    }
    fun rename(id: String, name: String) = update { d -> d.copy(playlists = d.playlists.map { if (it.id == id && id != LIKED && name.isNotBlank()) it.copy(name = name.trim()) else it }) }
    fun delete(id: String) = update { d -> d.copy(playlists = d.playlists.filter { it.id != id || id == LIKED }) }
    fun add(id: String, tracks: List<Track>) = update { d ->
        d.copy(playlists = d.playlists.map { p -> if (p.id != id) p else p.copy(tracks = p.tracks + tracks.filter { t -> p.tracks.none { it.id == t.id } }) })
    }
    fun remove(id: String, trackId: String) = update { d -> d.copy(playlists = d.playlists.map { p -> if (p.id == id) p.copy(tracks = p.tracks.filter { it.id != trackId }) else p }) }
    fun move(id: String, from: Int, to: Int) = update { d ->
        d.copy(playlists = d.playlists.map { p ->
            if (p.id != id || from !in p.tracks.indices || to !in p.tracks.indices) p
            else p.copy(tracks = p.tracks.toMutableList().also { it.add(to, it.removeAt(from)) })
        })
    }
    fun toggleLike(t: Track) { if (isLiked(t.id)) remove(LIKED, t.id) else add(LIKED, listOf(t)) }

    fun played(t: Track) = update { d ->
        d.copy(recent = (listOf(t) + d.recent.filter { it.id != t.id }).take(60), plays = d.plays + (t.id to (d.plays[t.id] ?: 0) + 1))
    }
    fun addOffline(t: Track) = update { d -> d.copy(offline = (listOf(t) + d.offline.filter { it.id != t.id })) }
    fun removeOffline(id: String) = update { d -> d.copy(offline = d.offline.filter { it.id != id }) }
    fun setAutoSave(b: Boolean) = update { it.copy(autoSave = b) }
    fun setCacheMb(mb: Int) = update { it.copy(cacheMb = mb) }
    fun mutate(f: (DataFile) -> DataFile) = update(f)
    fun setTheme(v: String) = update { it.copy(theme = v) }
    fun setEdit(id: String, e: TrackEdit?) = update { d -> d.copy(edits = if (e == null) d.edits - id else d.edits + (id to e)) }

    private val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    /** Add listening time for a song to today's entry (kept to the last ~120 days). */
    fun logListen(t: Track, ms: Long) {
        if (ms <= 0) return
        val day = dayFmt.format(java.util.Date())
        update { d ->
            val i = d.listen.indexOfFirst { it.day == day && it.id == t.id }
            val l = if (i >= 0) d.listen.toMutableList().also { it[i] = it[i].copy(ms = it[i].ms + ms) } else d.listen + Listen(day, t.id, t.title, t.artist, ms)
            d.copy(listen = l.takeLast(4000))
        }
    }

    /** Backup everything you created (playlists, likes, history, settings, edits). Audio files are not included. */
    fun exportJson(): String = AppJson.encodeToString(DataFile.serializer(), data.copy(lastQueue = emptyList()))
    /** Merge a backup into the current data; returns how many playlists were added or extended. */
    fun importMerge(text: String): Int {
        val b = AppJson.decodeFromString<DataFile>(text)
        var touched = 0
        update { d ->
            val pls = d.playlists.toMutableList()
            for (bp in b.playlists) {
                val i = pls.indexOfFirst { it.id == bp.id || (it.id != LIKED && it.name == bp.name && bp.id != LIKED) }
                if (i < 0) { pls += bp; touched++ }
                else { val add = bp.tracks.filter { t -> pls[i].tracks.none { it.id == t.id } }; if (add.isNotEmpty()) { pls[i] = pls[i].copy(tracks = pls[i].tracks + add); touched++ } }
            }
            val plays = (d.plays.keys + b.plays.keys).associateWith { maxOf(d.plays[it] ?: 0, b.plays[it] ?: 0) }
            val seen = d.listen.map { it.day + "|" + it.id }.toSet()
            d.copy(playlists = pls, plays = plays, recent = (d.recent + b.recent.filter { r -> d.recent.none { it.id == r.id } }).take(60),
                edits = b.edits + d.edits, listen = (d.listen + b.listen.filter { (it.day + "|" + it.id) !in seen }).takeLast(4000),
                offline = d.offline,
                theme = b.theme, eqOn = b.eqOn, eqPreset = b.eqPreset, eqLevels = b.eqLevels, bass = b.bass, fadeSec = b.fadeSec, wifiOnly = b.wifiOnly, autoSave = b.autoSave, minSec = b.minSec, noRecordings = b.noRecordings, lyricNotif = b.lyricNotif)
        }
        return touched
    }
    fun setMinSec(s: Int) = update { it.copy(minSec = s) }
    fun setNoRecordings(b: Boolean) = update { it.copy(noRecordings = b) }
    fun saveQueue(q: List<Track>, index: Int, pos: Long) = update { it.copy(lastQueue = q.take(500), lastIndex = index, lastPos = pos) }
    suspend fun saveQueueAsync(q: List<Track>, index: Int, pos: Long) = withContext(Dispatchers.IO) { saveQueue(q, index, pos) }
}
