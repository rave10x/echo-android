package app.echo.data

import android.content.Context
import app.echo.yt.YT
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.io.File

@Serializable data class Shelf(val id: String, val title: String, val subtitle: String = "", val tracks: List<Track> = emptyList())
@Serializable private data class HomeCache(val at: Long = 0, val shelves: List<Shelf> = emptyList())

/**
 * The Home feed: ready-made shelves of well-known songs (resolved on YouTube once, cached for 12 h)
 * plus shelves built from what you play ("Because you listened to …", "More from <artist>").
 */
class HomeRepo(ctx: Context, private val store: Store) {
    private val file = File(ctx.filesDir, "home.json")
    private val _shelves = MutableStateFlow<List<Shelf>>(emptyList()); val shelves: StateFlow<List<Shelf>> = _shelves
    private val _loading = MutableStateFlow(false); val loading: StateFlow<Boolean> = _loading
    private val _personal = MutableStateFlow<List<Shelf>>(emptyList()); val personal: StateFlow<List<Shelf>> = _personal
    private var job: Job? = null
    private val gate = Semaphore(3)

    private val seeds = listOf(
        Triple("rock", "Rock anthems", listOf("Queen We Will Rock You", "Imagine Dragons Believer", "Linkin Park In The End", "AC/DC Thunderstruck", "Guns N' Roses Sweet Child O' Mine", "Journey Don't Stop Believin'", "Nirvana Smells Like Teen Spirit", "Survivor Eye of the Tiger")),
        Triple("pump", "Pump up", listOf("The Score Born For This", "Eminem Lose Yourself", "Imagine Dragons Thunder", "Skillet Monster", "Kanye West Stronger", "Linkin Park Numb", "Fall Out Boy Centuries", "Imagine Dragons Radioactive")),
        Triple("pop", "Pop hits", listOf("The Weeknd Blinding Lights", "Dua Lipa Levitating", "Ed Sheeran Shape of You", "Harry Styles As It Was", "Billie Eilish bad guy", "Bruno Mars Uptown Funk", "Taylor Swift Shake It Off", "Post Malone Circles")),
        Triple("bolly", "Bollywood hits", listOf("Arijit Singh Kesariya", "Arijit Singh Tum Hi Ho", "Apna Bana Le Arijit Singh", "Chaleya Jawan", "Kal Ho Naa Ho Sonu Nigam", "Tera Hone Laga Hoon", "Raataan Lambiyan", "Pasoori")),
        Triple("chill", "Chill & focus", listOf("Coldplay Viva La Vida", "Lewis Capaldi Someone You Loved", "Billie Eilish ocean eyes", "Ed Sheeran Perfect", "Imagine Dragons Demons", "Passenger Let Her Go", "Alan Walker Faded", "Maroon 5 Memories")),
        Triple("classic", "Timeless classics", listOf("Queen Bohemian Rhapsody", "Michael Jackson Billie Jean", "Led Zeppelin Stairway to Heaven", "Eagles Hotel California", "Pink Floyd Another Brick in the Wall", "ABBA Dancing Queen", "Bon Jovi Livin' on a Prayer", "A-ha Take On Me")),
    )

    private fun readCache(): HomeCache? = runCatching { AppJson.decodeFromString<HomeCache>(file.readText()) }.getOrNull()
    private fun writeCache(l: List<Shelf>) { runCatching { file.writeText(AppJson.encodeToString(HomeCache.serializer(), HomeCache(System.currentTimeMillis(), l))) } }

    fun load(force: Boolean = false, scope: CoroutineScope) {
        val cached = readCache()
        if (cached != null && _shelves.value.isEmpty()) _shelves.value = cached.shelves
        if (job?.isActive == true) return
        buildPersonal(scope)
        val fresh = cached != null && System.currentTimeMillis() - cached.at < 12 * 3600_000L && cached.shelves.size >= seeds.size
        if (fresh && !force) return
        job = scope.launch {
            _loading.value = true
            val done = LinkedHashMap<String, Shelf>()
            cached?.shelves?.forEach { done[it.id] = it }
            coroutineScope {
                seeds.map { (id, title, queries) ->
                    async(Dispatchers.IO) {
                        val tracks = queries.map { q -> async { gate.withPermit { runCatching { YT.topSong(q) }.getOrNull() } } }.awaitAll().filterNotNull().distinctBy { it.id }
                        if (tracks.size >= 3) { synchronized(done) { done[id] = Shelf(id, title, tracks = tracks); _shelves.value = seeds.mapNotNull { done[it.first] } } }
                    }
                }.awaitAll()
            }
            writeCache(seeds.mapNotNull { done[it.first] })
            _loading.value = false
        }
    }

    /** Shelves built from your own listening. Cheap: one related-videos call and one search. */
    fun buildPersonal(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val d = store.data
            val out = ArrayList<Shelf>()
            val lastYt = d.recent.firstOrNull { it.yt }
            if (lastYt != null) runCatching { YT.related(lastYt.ytId) }.getOrNull()?.filter { r -> d.recent.none { it.id == r.id } }?.take(14)?.takeIf { it.size >= 3 }
                ?.let { out += Shelf("because", "Because you played ${lastYt.title.take(40)}", tracks = it) }
            val topArtist = d.plays.entries.sortedByDescending { it.value }.mapNotNull { (k, _) -> (d.recent + d.playlists.flatMap { it.tracks }).firstOrNull { it.id == k } }
                .map { it.artist }.firstOrNull { it.isNotBlank() && !it.startsWith("Unknown") }
            if (topArtist != null) runCatching { YT.search("$topArtist top songs") }.getOrNull()?.filter { it.durMs in 90_000..600_000 }?.take(14)?.takeIf { it.size >= 3 }
                ?.let { out += Shelf("artist", "More from $topArtist", tracks = it) }
            _personal.value = out
        }
    }
}
