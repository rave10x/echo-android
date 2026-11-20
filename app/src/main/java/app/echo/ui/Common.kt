package app.echo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echo.EchoVM
import app.echo.data.LIKED
import app.echo.data.Track
import app.echo.playback.ArtLoader
import coil3.compose.AsyncImage

fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%d:%02d".format(m, s % 60) }

@Composable
fun Placeholder(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.MusicNote) {
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF3A2A4A), Color(0xFF1F2A44)))), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.fillMaxSize(.45f), tint = Color(0x88FFFFFF))
    }
}

/** Cover art: local MediaStore thumbnail or a web image; music-note placeholder when there is none. */
@Composable
fun Art(track: Track?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp), px: Int = 256) {
    val m = modifier.clip(shape)
    val art = track?.art
    val ph = if (track?.video == true) Icons.Default.Movie else Icons.Default.MusicNote
    when {
        art == null -> Placeholder(m, ph)
        // YouTube's 4:3 thumbnails carry black bars top and bottom: zoom just enough to hide them
        art.startsWith("http") -> Box(m, contentAlignment = Alignment.Center) {
            AsyncImage(art, null, Modifier.fillMaxSize().graphicsLayer(scaleX = 1.34f, scaleY = 1.34f), contentScale = ContentScale.Crop)
        }
        else -> {
            val ctx = LocalContext.current
            val bmp by produceState<ImageBitmap?>(null, art) { value = ArtLoader.load(ctx, art, px)?.asImageBitmap() }
            if (bmp == null) Placeholder(m, ph) else androidx.compose.foundation.Image(bmp!!, null, m, contentScale = ContentScale.Crop)
        }
    }
}

@Composable
fun TrackRow(
    t: Track, current: Boolean, playing: Boolean, vm: EchoVM, onClick: () -> Unit, modifier: Modifier = Modifier,
    extra: List<Pair<String, () -> Unit>> = emptyList(), wide: Boolean = false, showIndex: Int? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val liked = vm.store.state.collectAsState().value.playlists.firstOrNull { it.id == LIKED }?.tracks?.any { it.id == t.id } == true
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        showIndex?.let { Text("$it", Modifier.width(28.dp), color = Dim, fontSize = 13.sp) }
        Box {
            Art(t, if (wide) Modifier.width(104.dp).aspectRatio(16 / 9f) else Modifier.size(52.dp))
            if (current) Box(Modifier.matchParentSize().clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Default.GraphicEq else Icons.Default.Pause, null, tint = Accent)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = if (current) Accent else MaterialTheme.colorScheme.onSurface)
            val sub = if (t.video && t.artist.isBlank()) fmt(t.durMs) else listOf(t.artist, if (t.durMs > 0) fmt(t.durMs) else "").filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Dim)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = Dim) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Play next") }, { menu = false; vm.ps.playNext(t); vm.toast("Plays next") }, leadingIcon = { Icon(Icons.Default.PlaylistPlay, null) })
                DropdownMenuItem({ Text("Add to queue") }, { menu = false; vm.ps.enqueue(listOf(t)); vm.toast("Added to queue") }, leadingIcon = { Icon(Icons.Default.QueueMusic, null) })
                DropdownMenuItem({ Text("Add to playlist") }, { menu = false; vm.addTarget = listOf(t) }, leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) })
                DropdownMenuItem({ Text(if (liked) "Remove from liked" else "Like") }, { menu = false; vm.store.toggleLike(t) },
                    leadingIcon = { Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) })
                extra.forEach { (l, f) -> DropdownMenuItem({ Text(l) }, { menu = false; f() }) }
            }
        }
    }
}

/** Playlist picker shown from any "Add to playlist" action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(vm: EchoVM, tracks: List<Track>, onDismiss: () -> Unit) {
    val data by vm.store.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surf) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Add to playlist", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(if (tracks.size == 1) tracks[0].title else "${tracks.size} songs", color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (creating) {
                OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.store.createPlaylist(name, tracks); vm.toast("Added to “${name.trim().ifBlank { "New playlist" }}”"); onDismiss() }) { Text("Create and add") }
            } else {
                ListItem({ Text("New playlist", color = Accent, fontWeight = FontWeight.Bold) }, Modifier.clickable { creating = true }, leadingContent = { Icon(Icons.Default.Add, null, tint = Accent) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(data.playlists, key = { it.id }) { p ->
                        ListItem({ Text(p.name) }, Modifier.clickable { vm.store.add(p.id, tracks); vm.toast("Added to “${p.name}”"); onDismiss() },
                            supportingContent = { Text("${p.tracks.size} songs", color = Dim) },
                            leadingContent = { Icon(if (p.id == LIKED) Icons.Default.Favorite else Icons.Default.QueueMusic, null, tint = Dim) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    }
                }
            }
        }
    }
}

@Composable
fun SectionButtons(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onPlay, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play", fontWeight = FontWeight.Bold) }
        FilledTonalButton(onClick = onShuffle, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle", fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun EmptyNote(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = Accent)
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(body, color = Dim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun MiniPlayer(vm: EchoVM, onOpen: () -> Unit) {
    val ps = vm.ps; val t = ps.track ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Surf2).clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(t, Modifier.size(44.dp), RoundedCornerShape(8.dp), 128)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(ps.error ?: t.artist.ifBlank { if (t.video) "Video" else "" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = if (ps.error != null) MaterialTheme.colorScheme.error else Dim)
            }
            IconButton(onClick = { ps.prev() }) { Icon(Icons.Default.SkipPrevious, "Previous") }
            IconButton(onClick = { ps.toggle() }, Modifier.size(44.dp).background(Accent, CircleShape)) {
                if (ps.buffering && !ps.playing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(if (ps.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (ps.playing) "Pause" else "Play", tint = Color.White)
            }
            IconButton(onClick = { ps.next() }) { Icon(Icons.Default.SkipNext, "Next") }
        }
        LinearProgressIndicator(progress = { if (ps.dur > 0) (ps.pos.toFloat() / ps.dur).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth().height(2.dp), color = Accent, trackColor = Color.Transparent)
    }
}
