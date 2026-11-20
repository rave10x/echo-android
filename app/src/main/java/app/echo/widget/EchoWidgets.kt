package app.echo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import app.echo.EchoApp
import app.echo.MainActivity
import app.echo.R
import app.echo.data.Track
import app.echo.playback.ArtLoader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors

/** What a widget shows: the current (or last played) song and whether it is playing. */
private class WState(val track: Track?, val playing: Boolean, val hasQueue: Boolean)

class MiniWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { EchoWidgets.refresh(c) }
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: android.os.Bundle?) { EchoWidgets.refresh(c) }
}
class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { EchoWidgets.refresh(c) }
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: android.os.Bundle?) { EchoWidgets.refresh(c) }
}

/** Handles the buttons on the widgets. Runs in the app process, so it drives the same player as the notification. */
class WidgetReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val ui = (c.applicationContext as EchoApp).ui
        when (i.action) {
            "echo.widget.toggle" -> { if (ui.track == null) ui.restore(); ui.toggle() }
            "echo.widget.next" -> { if (ui.track == null) ui.restore(); ui.next() }
            "echo.widget.prev" -> { if (ui.track == null) ui.restore(); ui.prev() }
        }
        EchoWidgets.refresh(c)
    }
}

object EchoWidgets {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient()
    private var artKey: String? = null
    private var artBmp: Bitmap? = null
    private var pending = false

    /** Re-draw every placed widget (debounced; the cover is fetched off the main thread). */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        if (pending) return
        pending = true
        main.postDelayed({
            pending = false
            val ui = (app as EchoApp).ui
            val d = app.store.data
            val t = ui.track ?: d.lastQueue.getOrNull(d.lastIndex)
            val st = WState(t, ui.playing, t != null)
            io.execute { render(app, st) }
        }, 150)
    }

    private fun hasWidgets(c: Context): Boolean {
        val m = AppWidgetManager.getInstance(c)
        return m.getAppWidgetIds(ComponentName(c, MiniWidget::class.java)).isNotEmpty() || m.getAppWidgetIds(ComponentName(c, PlayerWidget::class.java)).isNotEmpty()
    }

    private fun render(c: Context, st: WState) {
        if (!hasWidgets(c)) return
        val art = st.track?.let { cover(c, it) }
        val m = AppWidgetManager.getInstance(c)
        m.getAppWidgetIds(ComponentName(c, MiniWidget::class.java)).forEach { m.updateAppWidget(it, mini(c, st, art)) }
        m.getAppWidgetIds(ComponentName(c, PlayerWidget::class.java)).forEach { m.updateAppWidget(it, player(c, st, art)) }
    }

    private fun bcast(c: Context, action: String) = PendingIntent.getBroadcast(c, action.hashCode(), Intent(c, WidgetReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun open(c: Context) = PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Play button: with nothing to resume it just opens the app, otherwise it toggles playback. */
    private fun RemoteViews.play(c: Context, st: WState) {
        setOnClickPendingIntent(R.id.btn_play, if (st.hasQueue) bcast(c, "echo.widget.toggle") else open(c))
        setImageViewResource(R.id.icon_play, if (st.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
        setContentDescription(R.id.icon_play, if (st.playing) "Pause" else "Play")
    }

    private fun mini(c: Context, st: WState, art: Bitmap?): RemoteViews {
        // a song loaded (playing or paused) shows the transport controls; nothing loaded shows the vinyl + play
        if (!st.playing && !st.hasQueue || (!st.playing && st.track == null)) {
            return RemoteViews(c.packageName, R.layout.widget_mini_idle).apply {
                setOnClickPendingIntent(R.id.disc, open(c)); play(c, st)
                art?.let { setImageViewBitmap(R.id.art, it) }
            }
        }
        return RemoteViews(c.packageName, R.layout.widget_mini_playing).apply {
            setOnClickPendingIntent(R.id.disc, open(c))
            setOnClickPendingIntent(R.id.btn_prev, bcast(c, "echo.widget.prev")); setOnClickPendingIntent(R.id.btn_next, bcast(c, "echo.widget.next"))
            play(c, st)
        }
    }

    private fun player(c: Context, st: WState, art: Bitmap?): RemoteViews = RemoteViews(c.packageName, R.layout.widget_player).apply {
        setOnClickPendingIntent(R.id.disc, open(c))
        setOnClickPendingIntent(R.id.btn_prev, bcast(c, "echo.widget.prev")); setOnClickPendingIntent(R.id.btn_next, bcast(c, "echo.widget.next"))
        play(c, st)
        setTextViewText(R.id.title, st.track?.title ?: "Nothing playing")
        setTextViewText(R.id.artist, st.track?.artist?.ifBlank { null } ?: if (st.track == null) "Tap the disc to open Echo" else "")
        art?.let { setImageViewBitmap(R.id.art, it) }
    }

    // ---- cover art, cropped to a circle for the middle of the vinyl ----
    private fun cover(c: Context, t: Track): Bitmap? {
        if (artKey == t.id && artBmp != null) return artBmp
        val src = t.art ?: return null
        val raw = runCatching {
            if (src.startsWith("http")) http.newCall(Request.Builder().url(src).build()).execute().use { r -> r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            else ArtLoader.loadSync(c, src, 256)
        }.getOrNull() ?: return null
        // YouTube's 4:3 thumbnails have black bars top and bottom: cut them before squaring
        val yt = src.contains("ytimg.com")
        val h = if (yt) (raw.height * 0.75f).toInt() else raw.height
        val y0 = if (yt) (raw.height - h) / 2 else 0
        val side = minOf(raw.width, h)
        val sq = Rect((raw.width - side) / 2, y0 + (h - side) / 2, (raw.width - side) / 2 + side, y0 + (h - side) / 2 + side)
        val out = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        cv.drawCircle(80f, 80f, 80f, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        cv.drawBitmap(raw, sq, Rect(0, 0, 160, 160), p)
        artKey = t.id; artBmp = out
        return out
    }
}
