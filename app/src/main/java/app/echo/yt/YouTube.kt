package app.echo.yt

import app.echo.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

private class OkDownloader : Downloader() {
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).build()
    override fun execute(request: Request): Response {
        val method = request.httpMethod(); val data = request.dataToSend()
        val body = data?.toRequestBody() ?: if (method == "POST") ByteArray(0).toRequestBody() else null
        val b = okhttp3.Request.Builder().url(request.url()).method(method, body)
        request.headers().forEach { (k, vs) -> b.removeHeader(k); vs.forEach { b.addHeader(k, it) } }
        if (request.headers()["User-Agent"] == null) b.header("User-Agent", UA)
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", r.request.url.toString())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}

object YT {
    private var ready = false
    @Synchronized private fun init() { if (!ready) { NewPipe.init(OkDownloader()); ready = true } }
    private val service get() = ServiceList.YouTube
    private val idRe = Regex("^[\\w-]{11}$")

    private fun query(u: URI, key: String): String? =
        u.rawQuery?.split('&')?.map { it.split('=', limit = 2) }?.firstOrNull { it[0] == key }?.getOrNull(1)

    fun videoId(input: String): String? {
        val s = input.trim()
        if (idRe.matches(s)) return s
        val u = runCatching { URI(if (Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(s)) s else "https://$s") }.getOrNull() ?: return null
        val host = (u.host ?: return null).removePrefix("www.").removePrefix("m.").removePrefix("music.")
        if (host == "youtu.be") return u.path.trim('/').substringBefore('/').takeIf { idRe.matches(it) }
        if (host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")) {
            query(u, "v")?.takeIf { idRe.matches(it) }?.let { return it }
            Regex("^/(?:shorts|embed|live|v)/([\\w-]{11})").find(u.path ?: "")?.let { return it.groupValues[1] }
        }
        return null
    }
    fun playlistId(input: String): String? {
        val s = input.trim()
        runCatching { URI(s) }.getOrNull()?.let { query(it, "list") }?.let { return it }
        return if (Regex("^(PL|UU|OL|FL|LL)[\\w-]{10,}$").matches(s)) s else null
    }

    private fun thumb(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
    private fun artist(n: String?) = (n ?: "").replace(Regex("(?i)\\s*-\\s*topic$"), "")
    private fun item(it: StreamInfoItem): Track? {
        val id = videoId(it.url) ?: return null
        if (Regex("^\\[(Private|Deleted) video]$", RegexOption.IGNORE_CASE).matches(it.name ?: "")) return null
        return Track("y:$id", "yt:$id", it.name ?: "Video", artist(it.uploaderName), "", it.duration.coerceAtLeast(0) * 1000, false, thumb(id))
    }

    suspend fun search(q: String): List<Track> = withContext(Dispatchers.IO) {
        init()
        val info = SearchInfo.getInfo(service, service.searchQHFactory.fromQuery(q, listOf("videos"), ""))
        info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { item(it) }
    }

    /** Songs YouTube suggests next to this video (what the "up next" list would show), short single tracks only. */
    suspend fun related(id: String): List<Track> = withContext(Dispatchers.IO) {
        init()
        val i = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$id")
        i.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull { item(it) }.filter { it.durMs in 90_000..600_000 }
    }

    /** Best single-song match for a "Artist Title" query (skips compilations and mixes). */
    suspend fun topSong(q: String): Track? = search(q).firstOrNull { it.durMs in 90_000..600_000 }

    suspend fun video(id: String): Track = withContext(Dispatchers.IO) {
        init()
        val i = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$id")
        Track("y:$id", "yt:$id", i.name ?: "Video", artist(i.uploaderName), "", i.duration.coerceAtLeast(0) * 1000, false, thumb(id))
    }

    class Pl(val title: String, val tracks: List<Track>)
    suspend fun playlist(id: String): Pl = withContext(Dispatchers.IO) {
        init()
        val url = "https://www.youtube.com/playlist?list=$id"
        val info = PlaylistInfo.getInfo(service, url)
        val items = ArrayList(info.relatedItems); var page = info.nextPage
        while (page != null && items.size < 1000) { val more = PlaylistInfo.getMoreItems(service, url, page); items += more.items; page = more.nextPage }
        val tracks = items.mapNotNull { item(it) }
        if (tracks.isEmpty()) error("No playable videos found in this playlist")
        Pl(info.name ?: "YouTube playlist", tracks)
    }

    /** Any link text: a playlist link gives many tracks, a video link one. */
    suspend fun fromLink(text: String): Pl {
        val link = Regex("https?://\\S+").find(text)?.value ?: text.trim()
        playlistId(link)?.let { return playlist(it) }
        val v = videoId(link) ?: error("That is not a YouTube video or playlist link")
        val t = video(v); return Pl(t.title, listOf(t))
    }

    // ---- stream URLs (resolved lazily at play time; they expire, so cached for a few hours only) ----
    private class Cached(val url: String, val at: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    fun streamUrl(id: String, video: Boolean): String {
        val key = "$id/$video"
        cache[key]?.let { if (System.currentTimeMillis() - it.at < 4 * 3600_000L) return it.url }
        init()
        val i = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$id")
        val url = if (video) {
            val ok = i.videoStreams.filter { !it.isVideoOnly && it.isUrl }
            (ok.filter { it.height in 1..720 }.maxByOrNull { it.height } ?: ok.minByOrNull { it.height })?.content
        } else null
        val pick = url ?: i.hlsUrl?.takeIf { it.isNotBlank() && i.audioStreams.isEmpty() } ?: run {
            val a = i.audioStreams.filter { it.isUrl }
            (a.filter { it.format?.suffix == "m4a" }.maxByOrNull { it.averageBitrate } ?: a.maxByOrNull { it.averageBitrate })?.content
        } ?: error("No playable stream found")
        cache[key] = Cached(pick, System.currentTimeMillis())
        return pick
    }
}
