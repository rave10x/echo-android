package app.echo.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Album art / video thumbnails for local files (content:// URIs from MediaStore). */
object ArtLoader {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }

    fun loadSync(ctx: Context, uri: String, px: Int): Bitmap? {
        cache.get("$uri@$px")?.let { return it }
        val u = Uri.parse(uri)
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(u, Size(px, px), null)
            else MediaMetadataRetriever().use { r -> r.setDataSource(ctx, u); r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        }.getOrNull()
        if (bmp != null) cache.put("$uri@$px", bmp)
        return bmp
    }
    suspend fun load(ctx: Context, uri: String, px: Int) = withContext(Dispatchers.IO) { loadSync(ctx, uri, px) }
}

private fun placeholder(): Bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { bm ->
    val c = android.graphics.Canvas(bm)
    val p = android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 256f, 256f, 0xFF3A2A4A.toInt(), 0xFF1F2A44.toInt(), android.graphics.Shader.TileMode.CLAMP) }
    c.drawRect(0f, 0f, 256f, 256f, p)
    val n = android.graphics.Paint().apply { color = 0x99FFFFFF.toInt(); isAntiAlias = true }
    c.drawCircle(108f, 170f, 26f, n); c.drawRect(126f, 72f, 140f, 170f, n); c.drawRect(126f, 72f, 176f, 92f, n)
}

/** Lets the media notification / lock screen show local album art. */
class ArtBitmapLoader(private val ctx: Context) : BitmapLoader {
    private val default = DataSourceBitmapLoader(ctx)
    override fun supportsMimeType(mimeType: String) = default.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray) = default.decodeBitmap(data)
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != "content") return default.loadBitmap(uri)
        val f = SettableFuture.create<Bitmap>()
        Thread {
            val b = ArtLoader.loadSync(ctx, uri.toString(), 512)
            f.set(b ?: placeholder())
        }.start()
        return f
    }
}
