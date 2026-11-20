package app.echo.ui

import android.content.Context
import android.content.Intent
import android.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import app.echo.data.LyricsRepo
import app.echo.data.Track
import app.echo.playback.ArtLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** Renders the lyric lines around the current position onto a square card (cover colour background) and opens the share sheet. */
fun shareLyricCard(ctx: Context, t: Track, posMs: Long, tint: Color) {
    CoroutineScope(Dispatchers.IO).launch {
        val l = LyricsRepo.get(ctx, t)
        val lines: List<String> = if (l.synced.isNotEmpty()) { val cur = l.synced.indexOfLast { it.t <= posMs }.coerceAtLeast(0); l.synced.drop(cur).take(4).map { it.text }.filter { it.isNotBlank() } }
            else l.plain.lines().filter { it.isNotBlank() }.take(4)
        if (lines.isEmpty()) return@launch
        val w = 1080; val bmp = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        val base = tint.toArgb()
        c.drawRect(0f, 0f, w.toFloat(), w.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), w.toFloat(), base, 0xFF0E0F13.toInt(), Shader.TileMode.CLAMP) })
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.toArgb(); textSize = 64f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        var y = 220f
        for (line in lines) { for (part in wrap(line, tp, w - 160f)) { c.drawText(part, 80f, y, tp); y += 84f }; y += 18f }
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = 40f }
        c.drawText(t.title.take(40), 80f, w - 140f, Paint(sp).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) })
        c.drawText(t.artist.take(40), 80f, w - 90f, sp)
        c.drawText("Echo", w - 200f, w - 90f, Paint(sp).apply { color = 0xFFFF5C8A.toInt(); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) })
        val f = File(ctx.cacheDir, "share").also { it.mkdirs() }.let { File(it, "lyric.png") }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share lyric card").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
    val out = ArrayList<String>(); var line = ""
    for (word in s.split(" ")) { val t = if (line.isEmpty()) word else "$line $word"; if (p.measureText(t) > maxW && line.isNotEmpty()) { out += line; line = word } else line = t }
    if (line.isNotEmpty()) out += line
    return out
}
