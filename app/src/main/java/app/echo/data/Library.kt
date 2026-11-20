package app.echo.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Scan(val songs: List<Track>, val videos: List<Track>, val hidden: Int)

object Library {
    /** Folder / file names that mean "not music": call recorders, voice memos, messenger voice notes, ringtones. */
    private val BAD_FOLDER = Regex("(call|record|voice|memo|dictaphone|whatsapp|telegram|ringtone|notification|alarm)", RegexOption.IGNORE_CASE)
    private val BAD_NAME = Regex("^(call|rec|record|recording|voice|aud|ptt|audio)[-_ ]?\\d|call.?record|voice.?(note|memo)", RegexOption.IGNORE_CASE)

    suspend fun scan(ctx: Context, minSec: Int, noRecordings: Boolean): Scan = withContext(Dispatchers.IO) {
        var hidden = 0
        val songs = ArrayList<Track>()
        val a = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = buildList {
            add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.TITLE); add(MediaStore.Audio.Media.ARTIST); add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION); add(MediaStore.Audio.Media.DATE_ADDED); add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.IS_MUSIC); add(MediaStore.Audio.Media.IS_RINGTONE); add(MediaStore.Audio.Media.IS_ALARM); add(MediaStore.Audio.Media.IS_NOTIFICATION)
            if (Build.VERSION.SDK_INT >= 31) add(MediaStore.Audio.Media.IS_RECORDING)
        }.toTypedArray()
        runCatching {
            ctx.contentResolver.query(a, cols, null, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                val ix = cols.withIndex().associate { it.value to it.index }
                while (c.moveToNext()) {
                    val id = c.getLong(ix.getValue(MediaStore.Audio.Media._ID))
                    val dur = c.getLong(ix.getValue(MediaStore.Audio.Media.DURATION))
                    val name = c.getString(ix.getValue(MediaStore.Audio.Media.DISPLAY_NAME)).orEmpty()
                    val loc = c.getString(ix.getValue(cols[7])).orEmpty()
                    val flagged = c.getInt(ix.getValue(MediaStore.Audio.Media.IS_RINGTONE)) != 0 || c.getInt(ix.getValue(MediaStore.Audio.Media.IS_ALARM)) != 0 ||
                        c.getInt(ix.getValue(MediaStore.Audio.Media.IS_NOTIFICATION)) != 0 ||
                        (Build.VERSION.SDK_INT >= 31 && c.getInt(ix.getValue(MediaStore.Audio.Media.IS_RECORDING)) != 0)
                    val isMusic = c.getInt(ix.getValue(MediaStore.Audio.Media.IS_MUSIC)) != 0
                    // call recordings / voice notes: recorder-like folder or file name (music tagged IS_MUSIC in a normal folder is kept)
                    val recorderLike = noRecordings && (BAD_NAME.containsMatchIn(name) || (!isMusic && BAD_FOLDER.containsMatchIn(loc)) || Regex("(call|record)", RegexOption.IGNORE_CASE).containsMatchIn(loc.substringBeforeLast('/', loc)))
                    if (flagged || dur < minSec * 1000L || recorderLike) { hidden++; continue }
                    val uri = ContentUris.withAppendedId(a, id)
                    fun clean(s: String?, d: String) = s?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: d
                    songs += Track(
                        id = "a:$id", uri = uri.toString(), title = clean(c.getString(ix.getValue(MediaStore.Audio.Media.TITLE)), name.substringBeforeLast('.')),
                        artist = clean(c.getString(ix.getValue(MediaStore.Audio.Media.ARTIST)), "Unknown artist"), album = clean(c.getString(ix.getValue(MediaStore.Audio.Media.ALBUM)), "Unknown album"),
                        durMs = dur, art = uri.toString(), path = if (Build.VERSION.SDK_INT >= 29) "$loc$name" else loc, added = c.getLong(ix.getValue(MediaStore.Audio.Media.DATE_ADDED)),
                    )
                }
            }
        }
        val videos = ArrayList<Track>()
        val v = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        runCatching {
            ctx.contentResolver.query(v, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED),
                "${MediaStore.Video.Media.DURATION} >= 3000", null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0); val uri = ContentUris.withAppendedId(v, id)
                    videos += Track("v:$id", uri.toString(), c.getString(1).orEmpty().ifBlank { "Video" }, "", "", c.getLong(2), true, uri.toString(), "", c.getLong(3))
                }
            }
        }
        Scan(songs, videos, hidden)
    }
}
