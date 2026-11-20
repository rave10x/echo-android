package app.echo.data

import android.content.Context
import app.echo.yt.YT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Offline songs. YouTube songs you listen to are downloaded in the background (audio only) into the app's private storage;
 * the player prefers that file whenever it exists, so saved songs play with no internet.
 */
class Offline(ctx: Context, private val store: Store) {
    private val dir = File(ctx.filesDir, "offline_audio").also { it.mkdirs() }
    private val io = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val _saved = MutableStateFlow<Set<String>>(emptySet()); val saved: StateFlow<Set<String>> = _saved
    private val _working = MutableStateFlow<Map<String, Int>>(emptyMap()); val working: StateFlow<Map<String, Int>> = _working
    private val queued = HashSet<String>()
    private val cancelled = HashSet<String>()
    private val _active = MutableStateFlow<List<Track>>(emptyList()); val active: StateFlow<List<Track>> = _active
    private val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
    private fun metered() = runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)

    init { io.execute { refreshSaved() } }

    private fun safe(id: String) = id.removePrefix("y:").replace(Regex("[^\\w-]"), "")
    fun fileFor(id: String) = File(dir, safe(id) + ".m4a")

    /** The saved file for this YouTube id, or null. Touching it marks the song as recently used. */
    fun localFile(ytId: String): File? = fileFor(ytId).takeIf { it.exists() && it.length() > 0 }?.also { it.setLastModified(System.currentTimeMillis()) }

    fun refreshSaved() { _saved.value = store.data.offline.filter { fileFor(it.id).let { f -> f.exists() && f.length() > 0 } }.map { it.id }.toSet() }
    fun isSaved(id: String) = id in _saved.value
    val usedBytes get() = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Download a YouTube song's audio in the background (one at a time). */
    fun save(t: Track, manual: Boolean = false) {
        if (!t.yt || isSaved(t.id)) return
        if (!manual && store.data.wifiOnly && metered()) return
        synchronized(queued) { if (!queued.add(t.id)) return }
        _working.value = _working.value + (t.id to 0); _active.value = _active.value + t
        io.execute {
            val tmp = File(dir, safe(t.id) + ".part")
            try {
                val url = YT.streamUrl(t.ytId, false)
                // googlevideo throttles one long request to about playback speed; asking for 1 MB slices (range=a-b) downloads at full speed
                val chunk = 1_000_000L; var pos = 0L; var total = -1L; var last = -1
                tmp.outputStream().use { out ->
                    while (total < 0 || pos < total) {
                        if (synchronized(cancelled) { t.id in cancelled }) error("cancelled")
                        val u = url + (if ('?' in url) "&" else "?") + "range=$pos-${pos + chunk - 1}"
                        http.newCall(Request.Builder().url(u).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36").build()).execute().use { r ->
                            if (!r.isSuccessful) error("HTTP ${r.code}")
                            val body = r.body ?: error("empty")
                            // total size comes from Content-Range (bytes a-b/total) when present, else from the clen parameter in the URL
                            if (total < 0) total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                            var got = 0L
                            body.byteStream().use { inp -> val buf = ByteArray(64 * 1024); while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); got += n } }
                            pos += got
                            if (total < 0 && got < chunk) total = pos
                            if (got == 0L) total = pos
                        }
                        if (total > 0) { val pct = (pos * 100 / total).toInt().coerceAtMost(100); if (pct != last) { last = pct; _working.value = _working.value + (t.id to pct) } }
                    }
                }
                if (tmp.length() > 0 && tmp.renameTo(fileFor(t.id))) { store.addOffline(t); trim(); refreshSaved() }
            } catch (e: Exception) { android.util.Log.w("EchoOffline", "save failed: ${e}"); tmp.delete() }
            finally { synchronized(queued) { queued.remove(t.id) }; synchronized(cancelled) { cancelled.remove(t.id) }; _working.value = _working.value - t.id; _active.value = _active.value.filter { it.id != t.id } }
        }
    }

    /** Keep the folder under the size limit by deleting the least recently played songs. */
    private fun trim() {
        val limit = store.data.cacheMb * 1024L * 1024L
        val files = dir.listFiles { f -> f.extension == "m4a" }?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        while (total > limit && files.size > 1) { val f = files.removeAt(0); total -= f.length(); f.delete(); store.removeOffline("y:" + f.nameWithoutExtension) }
    }

    fun cancel(id: String) { synchronized(cancelled) { cancelled += id } }
    fun remove(id: String) { fileFor(id).delete(); store.removeOffline(id); refreshSaved() }
    fun clear() { io.execute { dir.listFiles()?.forEach { it.delete() }; store.data.offline.forEach { store.removeOffline(it.id) }; refreshSaved() } }
}
