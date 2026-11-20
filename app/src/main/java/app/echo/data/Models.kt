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
)

const val LIKED = "liked"
val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true; isLenient = true }
