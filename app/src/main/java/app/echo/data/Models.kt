package app.echo.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One playable thing. id: "a:<mediastore id>" local audio, "v:<id>" local video, "y:<videoId>" YouTube. */
@Serializable data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durMs: Long = 0,
    val video: Boolean = false,
    val art: String? = null,
    val path: String = "",
    val added: Long = 0,
) {
    val yt get() = id.startsWith("y:")
    val ytId get() = id.removePrefix("y:")
    val subtitle get() = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")
}

@Serializable data class Playlist(val id: String, val name: String, val tracks: List<Track> = emptyList())

@Serializable data class DataFile(
    val playlists: List<Playlist> = emptyList(),
    val recent: List<Track> = emptyList(),
    val plays: Map<String, Int> = emptyMap(),
    val minSec: Int = 60,
    val noRecordings: Boolean = true,
    val lastQueue: List<Track> = emptyList(),
    val lastIndex: Int = 0,
    val lastPos: Long = 0,
    /** YouTube songs saved on this phone (their audio lives in the offline cache). */
    val offline: List<Track> = emptyList(),
    val autoSave: Boolean = true,
    val cacheMb: Int = 1024,
    val theme: String = "system",
    val eqOn: Boolean = false,
    val eqPreset: String = "Flat",
    val eqLevels: List<Int> = emptyList(),
    val bass: Int = 0,
    val fadeSec: Int = 0,
    val wifiOnly: Boolean = true,
    val lyricNotif: Boolean = false,
    val edits: Map<String, TrackEdit> = emptyMap(),
    val listen: List<Listen> = emptyList(),
)

/** User-edited song info; applied on top of what the file says. cover is a file:// path. */
@Serializable data class TrackEdit(val title: String? = null, val artist: String? = null, val album: String? = null, val cover: String? = null)
/** Listening time per day and song, for the stats screen. */
@Serializable data class Listen(val day: String, val id: String, val title: String, val artist: String, val ms: Long)

fun Track.edited(e: TrackEdit?): Track = if (e == null) this else copy(
    title = e.title?.takeIf { it.isNotBlank() } ?: title, artist = e.artist?.takeIf { it.isNotBlank() } ?: artist,
    album = e.album?.takeIf { it.isNotBlank() } ?: album, art = e.cover ?: art,
)

const val LIKED = "liked"
val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true; isLenient = true }
