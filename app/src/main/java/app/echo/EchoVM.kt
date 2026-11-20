package app.echo

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.echo.data.HomeRepo
import app.echo.data.Library
import app.echo.data.Track
import app.echo.data.TrackEdit
import app.echo.data.edited
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class EchoVM(private val app: Application) : AndroidViewModel(app) {
    private val ea get() = app as EchoApp
    val store get() = ea.store
    val ps get() = ea.ui
    val offline get() = ea.offline
    val effects get() = ea.effects
    val home by lazy { HomeRepo(app, store) }
    private val raw = MutableStateFlow<List<Track>>(emptyList())
    private val _songs = MutableStateFlow<List<Track>>(emptyList()); val songs: StateFlow<List<Track>> = _songs
    var editTarget by mutableStateOf<Track?>(null)
    var showEq by mutableStateOf(false)
    private val _videos = MutableStateFlow<List<Track>>(emptyList()); val videos: StateFlow<List<Track>> = _videos
    var hidden by mutableStateOf(0); private set
    var loading by mutableStateOf(false); private set
    var granted by mutableStateOf(hasPermission()); private set
    var addTarget by mutableStateOf<List<Track>?>(null)
    var sharedLink by mutableStateOf<String?>(null)
    val events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    fun toast(s: String) { events.tryEmit(s) }

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasPermission(): Boolean {
        val need = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(app, need) == PackageManager.PERMISSION_GRANTED
    }

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(raw, store.state.let { st -> kotlinx.coroutines.flow.flow { st.collect { emit(it.edits) } } }) { r, e -> r.map { it.edited(e[it.id]) } }
                .collect { _songs.value = it; ea.librarySongs = it }
        }
    }
    /** Save a picked image as this song's cover (scaled down, kept in the app's storage). */
    fun setCover(t: Track, uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bmp = app.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
                val s = 800f / maxOf(bmp.width, bmp.height).coerceAtLeast(1); val out = if (s < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
                val f = java.io.File(app.filesDir, "covers").also { it.mkdirs() }.let { java.io.File(it, t.id.replace(Regex("[^\\w-]"), "_") + "_" + System.currentTimeMillis() + ".jpg") }
                f.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                val cur = store.data.edits[t.id] ?: TrackEdit()
                store.setEdit(t.id, cur.copy(cover = "file://" + f.absolutePath))
            }.onFailure { toast("Could not use that image") }
        }
    }
    init { if (granted) rescan(); ps.restore(); home.load(false, viewModelScope) }
    fun viewModelScopeRef() = viewModelScope
    fun refreshHome() = home.load(true, viewModelScope)

    fun onPermissionResult() { granted = hasPermission(); if (granted) rescan() }

    fun rescan() {
        if (!hasPermission()) { granted = false; return }
        granted = true
        viewModelScope.launch {
            loading = true
            val d = store.data
            val s = Library.scan(app, d.minSec, d.noRecordings)
            raw.value = s.songs; _videos.value = s.videos; hidden = s.hidden; loading = false
        }
    }
    fun setMinSec(s: Int) { store.setMinSec(s); rescan() }
    fun setNoRecordings(b: Boolean) { store.setNoRecordings(b); rescan() }

    /** A file opened from a file manager ("Open with Echo"). */
    fun playExternal(uri: Uri, mime: String?) {
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Audio"
        val video = mime?.startsWith("video") == true
        ps.play(listOf(Track("x:$uri", uri.toString(), name, "", "", 0, video, uri.toString())))
    }
}
