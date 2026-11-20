package app.echo.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.echo.EchoVM
import app.echo.data.LIKED
import app.echo.data.Track

@Composable
fun Header(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f)); trailing()
    }
}

@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, placeholder = { Text(hint, color = Dim) }, shape = RoundedCornerShape(28.dp),
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Dim) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Default.Close, "Clear") } },
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Surf, unfocusedContainerColor = Surf, focusedBorderColor = Accent, unfocusedBorderColor = Color.Transparent))
}

@Composable
fun LibraryTab(vm: EchoVM, nav: NavHostController, open: () -> Unit) {
    val songs by vm.songs.collectAsState(); val data by vm.store.state.collectAsState()
    val ps = vm.ps
    var q by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val filtered = remember(songs, q, sort, data.plays) {
        val f = if (q.isBlank()) songs else songs.filter { (it.title + " " + it.artist + " " + it.album).contains(q.trim(), true) }
        when (sort) { 1 -> f.sortedByDescending { it.added }; 2 -> f.sortedByDescending { data.plays[it.id] ?: 0 }.filter { (data.plays[it.id] ?: 0) > 0 || q.isNotBlank() }; else -> f }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Library") {
            if (vm.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            IconButton({ nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Settings") }
        }
        SearchBox(q, { q = it }, "Search your music")
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Songs", "Albums", "Artists").forEachIndexed { i, l -> FilterChip(mode == i, { mode = i }, { Text(l) }) }
        }
        when (mode) {
            0 -> {
                if (songs.isEmpty() && !vm.loading) EmptyNote(Icons.Default.MusicOff, "No music found", "Add songs to this phone, or lower the minimum length in Settings (${data.minSec}s now). ${vm.hidden} short or recording files are hidden.")
                else LazyColumn(Modifier.fillMaxSize()) {
                    item { SectionButtons({ ps.play(filtered) }, { ps.play(filtered, shuffleOn = true) }) }
                    item { Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${filtered.size} songs", color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        listOf("A–Z", "Recent", "Most played").forEachIndexed { i, l -> FilterChip(sort == i, { sort = i }, { Text(l, fontSize = 12.sp) }) }
                    } }
                    itemsIndexed(filtered, key = { _, t -> t.id }) { i, t ->
                        TrackRow(t, ps.track?.id == t.id, ps.playing, vm, onClick = { ps.play(filtered, i); open() })
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
            1 -> {
                val albums = remember(filtered) { filtered.groupBy { it.album }.toList().sortedBy { it.first.lowercase() } }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(albums, key = { it.first }) { (name, ts) ->
                        GroupRow(name, "${ts.first().artist} · ${ts.size} songs", ts.first()) { nav.navigate("album/${Uri.encode(name)}") }
                    }
                }
            }
            else -> {
                val artists = remember(filtered) { filtered.groupBy { it.artist }.toList().sortedBy { it.first.lowercase() } }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(artists, key = { it.first }) { (name, ts) ->
                        GroupRow(name, "${ts.size} songs", ts.first(), round = true) { nav.navigate("artist/${Uri.encode(name)}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(title: String, sub: String, cover: Track, round: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(cover, Modifier.size(58.dp), if (round) CircleShape else RoundedCornerShape(12.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Dim)
    }
}

/** Shared "list of tracks with a back button, Play and Shuffle". */
@Composable
fun TrackListScreen(
    vm: EchoVM, title: String, subtitle: String, tracks: List<Track>, onBack: () -> Unit, open: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}, rowExtra: (Int, Track) -> List<Pair<String, () -> Unit>> = { _, _ -> emptyList() }, empty: String = "Nothing here yet.",
) {
    val ps = vm.ps
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f)); actions()
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Dim, fontSize = 13.sp)
        }
        if (tracks.isEmpty()) Text(empty, color = Dim, modifier = Modifier.padding(24.dp))
        else LazyColumn(Modifier.weight(1f)) {
            item { SectionButtons({ ps.play(tracks); open() }, { ps.play(tracks, shuffleOn = true); open() }) }
            itemsIndexed(tracks, key = { i, t -> "${t.id}#$i" }) { i, t -> TrackRow(t, ps.track?.id == t.id, ps.playing, vm, onClick = { ps.play(tracks, i); open() }, extra = rowExtra(i, t), wide = t.video) }
        }
        MiniPlayer(vm, open)
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

@Composable
fun GroupScreen(vm: EchoVM, name: String, album: Boolean, onBack: () -> Unit, open: () -> Unit) {
    val songs by vm.songs.collectAsState()
    val ts = remember(songs, name) { songs.filter { (if (album) it.album else it.artist) == name } }
    TrackListScreen(vm, name, if (album) "${ts.firstOrNull()?.artist.orEmpty()} · ${ts.size} songs" else "${ts.size} songs", ts, onBack, open,
        actions = { IconButton({ vm.addTarget = ts }) { Icon(Icons.Default.PlaylistAdd, "Add all to playlist") } })
}

@Composable
fun PlaylistsTab(vm: EchoVM, nav: NavHostController) {
    val data by vm.store.state.collectAsState()
    var creating by remember { mutableStateOf(false) }; var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Header("Playlists") { IconButton({ creating = true }) { Icon(Icons.Default.Add, "New playlist") } }
        LazyColumn(Modifier.fillMaxSize()) {
            item { SmartRow(Icons.Default.History, "Recently played", "${data.recent.size} songs") { nav.navigate("playlist/recent") } }
            item { SmartRow(Icons.Default.Whatshot, "Most played", "${data.plays.size} songs") { nav.navigate("playlist/top") } }
            items(data.playlists, key = { it.id }) { p ->
                SmartRow(if (p.id == LIKED) Icons.Default.Favorite else Icons.Default.QueueMusic, p.name, "${p.tracks.size} songs", cover = p.tracks.firstOrNull()) { nav.navigate("playlist/${p.id}") }
            }
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("New playlist") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({ vm.store.createPlaylist(name); name = ""; creating = false }) { Text("Create") } },
        dismissButton = { TextButton({ creating = false }) { Text("Cancel") } })
}

@Composable
private fun SmartRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, cover: Track? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (cover != null) Art(cover, Modifier.size(58.dp), RoundedCornerShape(12.dp))
        else Box(Modifier.size(58.dp).background(Surf2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Accent) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(sub, color = Dim, fontSize = 13.sp) }
        Icon(Icons.Default.ChevronRight, null, tint = Dim)
    }
}

@Composable
fun PlaylistScreen(vm: EchoVM, id: String, onBack: () -> Unit, open: () -> Unit) {
    val data by vm.store.state.collectAsState()
    val songs by vm.songs.collectAsState()
    val real = data.playlists.firstOrNull { it.id == id }
    val tracks = when (id) { "recent" -> data.recent; "top" -> (data.plays.entries.sortedByDescending { it.value }.mapNotNull { (k, _) -> songs.firstOrNull { it.id == k } ?: data.recent.firstOrNull { it.id == k } ?: data.playlists.flatMap { it.tracks }.firstOrNull { it.id == k } }).take(100); else -> real?.tracks.orEmpty() }
    val title = when (id) { "recent" -> "Recently played"; "top" -> "Most played"; else -> real?.name ?: "Playlist" }
    var menu by remember { mutableStateOf(false) }; var rename by remember { mutableStateOf(false) }; var del by remember { mutableStateOf(false) }; var nm by remember(title) { mutableStateOf(title) }
    TrackListScreen(vm, title, "${tracks.size} songs", tracks, onBack, open,
        actions = {
            IconButton({ vm.ps.enqueue(tracks); vm.toast("Added to queue") }, enabled = tracks.isNotEmpty()) { Icon(Icons.Default.QueueMusic, "Add to queue") }
            if (real != null && id != LIKED) Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename") }, { menu = false; rename = true })
                    DropdownMenuItem({ Text("Delete playlist") }, { menu = false; del = true })
                }
            }
        },
        rowExtra = { i, t -> if (real == null) emptyList() else buildList {
            if (i > 0) add("Move up" to { vm.store.move(id, i, i - 1) })
            if (i < tracks.lastIndex) add("Move down" to { vm.store.move(id, i, i + 1) })
            add("Remove from playlist" to { vm.store.remove(id, t.id) })
        } }, empty = if (id == LIKED) "Tap the heart on a song to keep it here." else "No songs yet. Use “Add to playlist” on any song.")
    if (rename) AlertDialog({ rename = false }, title = { Text("Rename playlist") }, text = { OutlinedTextField(nm, { nm = it }, singleLine = true) },
        confirmButton = { TextButton({ vm.store.rename(id, nm); rename = false }) { Text("Save") } }, dismissButton = { TextButton({ rename = false }) { Text("Cancel") } })
    if (del) AlertDialog({ del = false }, title = { Text("Delete “$title”?") }, text = { Text("The songs themselves are not deleted.") },
        confirmButton = { TextButton({ vm.store.delete(id); del = false; onBack() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ del = false }) { Text("Cancel") } })
}

@Composable
fun VideosTab(vm: EchoVM, open: () -> Unit) {
    val videos by vm.videos.collectAsState(); val ps = vm.ps
    var q by rememberSaveable { mutableStateOf("") }
    val list = remember(videos, q) { if (q.isBlank()) videos else videos.filter { it.title.contains(q.trim(), true) } }
    Column(Modifier.fillMaxSize()) {
        Header("Videos")
        SearchBox(q, { q = it }, "Search videos")
        if (videos.isEmpty()) EmptyNote(Icons.Default.VideoLibrary, "No videos found", "Videos saved on this phone show up here. They keep playing as audio when you leave the app.")
        else LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(list, key = { _, t -> t.id }) { i, t -> TrackRow(t, ps.track?.id == t.id, ps.playing, vm, wide = true, onClick = { ps.play(list, i); open() }) }
        }
    }
}
