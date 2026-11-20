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
    fun setMinSec(s: Int) = update { it.copy(minSec = s) }
    fun setNoRecordings(b: Boolean) = update { it.copy(noRecordings = b) }
    fun saveQueue(q: List<Track>, index: Int, pos: Long) = update { it.copy(lastQueue = q.take(500), lastIndex = index, lastPos = pos) }
    suspend fun saveQueueAsync(q: List<Track>, index: Int, pos: Long) = withContext(Dispatchers.IO) { saveQueue(q, index, pos) }
}
