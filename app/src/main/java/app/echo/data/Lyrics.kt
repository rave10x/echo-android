package app.echo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Serializable data class LyricLine(val t: Long, val text: String)
@Serializable data class Lyrics(val synced: List<LyricLine> = emptyList(), val plain: String = "", val source: String = "") {
    val isEmpty get() = synced.isEmpty() && plain.isBlank()
}

/** Lyrics from a sidecar .lrc file, a local cache, or LRCLIB (free, synced). */
object LyricsRepo {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val ts = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")

    fun parseLrc(s: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in s.lines()) {
            val stamps = ts.findAll(raw).toList(); if (stamps.isEmpty()) continue
            val text = raw.replace(ts, "").replace(Regex("<\\d+:\\d+(?:[.:]\\d+)?>"), "").replace(Regex("\\s+"), " ").trim()
            for (m in stamps) {
                val frac = m.groupValues[3]
                val ms = (if (frac.isEmpty()) 0 else (frac.padEnd(3, '0').take(3).toInt()))
                out += LyricLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms, text)
            }
        }
        return out.sortedBy { it.t }
    }

    private fun cleanTitle(t: String) = t.replace(Regex("(?i)[(\\[](official|lyrics?|lyric video|audio|video|music video|hd|4k|remaster(ed)?|visualizer)[^)\\]]*[)\\]]"), "")
        .replace(Regex("(?i)\\b(official (music )?video|lyric video|full video)\\b"), "").replace(Regex("\\s+"), " ").trim(' ', '-', '|')
    private fun cleanArtist(a: String) = a.replace(Regex("(?i)\\s*-\\s*topic$"), "").replace(Regex("(?i)vevo$"), "").trim()

    suspend fun get(ctx: Context, t: Track): Lyrics = withContext(Dispatchers.IO) {
        val cache = File(ctx.filesDir, "lyrics/${t.id.replace(Regex("[^\\w-]"), "_")}.json")
        runCatching { AppJson.decodeFromString<Lyrics>(cache.readText()) }.getOrNull()?.let { return@withContext it }
        // 1) .lrc next to the audio file
        if (t.path.startsWith("/")) runCatching {
            val f = File(t.path.substringBeforeLast('.') + ".lrc")
            if (f.exists()) parseLrc(f.readText()).takeIf { it.isNotEmpty() }?.let { return@withContext Lyrics(it, "", "file") }
        }
        // 2) LRCLIB
        var title = cleanTitle(t.title); var artist = cleanArtist(t.artist)
        if (t.yt && " - " in title) { artist = title.substringBefore(" - ").trim(); title = cleanTitle(title.substringAfter(" - ")) }
        val dur = (t.durMs / 1000).toInt()
        fun call(url: String): String? = runCatching { http.newCall(Request.Builder().url(url).header("User-Agent", "Echo/0.1").build()).execute().use { if (it.isSuccessful) it.body?.string() else null } }.getOrNull()
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        fun parse(o: JSONObject): Lyrics? {
            val sy = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let { parseLrc(it) }.orEmpty()
            val pl = o.optString("plainLyrics").takeIf { it != "null" }.orEmpty()
            return Lyrics(sy, pl, "lrclib").takeIf { !it.isEmpty }
        }
        var res: Lyrics? = null
        if (artist.isNotBlank() && !artist.startsWith("Unknown")) call("https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}&album_name=${enc(t.album)}&duration=$dur")?.let { res = runCatching { parse(JSONObject(it)) }.getOrNull() }
        if (res == null) call("https://lrclib.net/api/search?q=${enc((artist.takeIf { !it.startsWith("Unknown") } ?: "") + " " + title)}")?.let { body ->
            val arr = runCatching { JSONArray(body) }.getOrNull()
            if (arr != null) {
                val items = (0 until arr.length()).map { arr.getJSONObject(it) }
                val best = items.filter { dur <= 0 || Math.abs(it.optInt("duration") - dur) <= 8 }.let { l -> l.firstOrNull { it.optString("syncedLyrics").isNotBlank() && it.optString("syncedLyrics") != "null" } ?: l.firstOrNull() }
                if (best != null) res = parse(best)
            }
        }
        res?.also { r -> runCatching { cache.parentFile?.mkdirs(); cache.writeText(AppJson.encodeToString(Lyrics.serializer(), r)) } } ?: Lyrics()
    }
}
