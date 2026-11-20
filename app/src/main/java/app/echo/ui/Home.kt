package app.echo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echo.EchoVM
import app.echo.data.Shelf
import app.echo.data.Track
import java.util.Calendar

@Composable
fun HomeTab(vm: EchoVM, open: () -> Unit, goSearch: () -> Unit) {
    val seeds by vm.home.shelves.collectAsState(); val personal by vm.home.personal.collectAsState(); val loading by vm.home.loading.collectAsState()
    val data by vm.store.state.collectAsState(); val ps = vm.ps
    val savedIds by vm.offline.saved.collectAsState()
    val hello = remember { when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Late night vibes" } }
    // refresh the "because you played" shelves when the last played song changes
    LaunchedEffect(data.recent.firstOrNull()?.id) { if (data.recent.isNotEmpty()) vm.home.buildPersonal(vm.viewModelScopeRef()) }

    fun playShelf(s: Shelf, i: Int) { ps.play(s.tracks, i); open() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Header(hello) {
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                IconButton({ vm.refreshHome() }) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton(goSearch) { Icon(Icons.Default.Search, "Search") }
            }
        }
        val offlineTracks = data.offline.filter { it.id in savedIds }
        if (offlineTracks.isNotEmpty()) item {
            ShelfTitle("Saved for offline", { ps.play(offlineTracks, 0); open() })
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(offlineTracks.take(20), key = { i, t -> "o${t.id}#$i" }) { i, t -> SongCard(t, ps.track?.id == t.id) { ps.play(offlineTracks, i); open() } }
            }
        }
        if (data.recent.isNotEmpty()) item {
            ShelfTitle("Pick up where you left off", null)
            val recent = data.recent.take(12)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(recent, key = { i, t -> "r${t.id}#$i" }) { i, t -> SongCard(t, ps.track?.id == t.id) { ps.play(recent, i); open() } }
            }
        }
        personal.forEach { s -> item(key = "p-${s.id}") { ShelfRow(s, ps.track?.id, { ps.play(s.tracks, 0); open() }) { i -> playShelf(s, i) } } }
        seeds.forEach { s -> item(key = "s-${s.id}") { ShelfRow(s, ps.track?.id, { ps.play(s.tracks, 0); open() }) { i -> playShelf(s, i) } } }
        if (seeds.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading) { CircularProgressIndicator(color = Accent); Text("Finding songs for you…", color = Dim, modifier = Modifier.padding(top = 12.dp)) }
                else EmptyNote(Icons.Default.WifiOff, "Can't load recommendations", "Connect to the internet and tap refresh. Your own music is in the Library tab.")
            }
        }
    }
}

@Composable
private fun ShelfTitle(title: String, onPlay: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onPlay != null) FilledTonalIconButton(onPlay, Modifier.size(38.dp)) { Icon(Icons.Default.PlayArrow, "Play all") }
    }
}

@Composable
private fun ShelfRow(s: Shelf, currentId: String?, onPlayAll: () -> Unit, onPlay: (Int) -> Unit) {
    ShelfTitle(s.title, onPlayAll)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(s.tracks, key = { i, t -> "${s.id}${t.id}#$i" }) { i, t -> SongCard(t, currentId == t.id) { onPlay(i) } }
    }
}

@Composable
private fun SongCard(t: Track, current: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(148.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Box {
            Art(t, Modifier.size(148.dp), RoundedCornerShape(14.dp), 320)
            if (current) Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color(0x66000000), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.GraphicEq, null, tint = Accent, modifier = Modifier.size(36.dp)) }
        }
        Text(t.title.replace(Regex("(?i)\\s*[(\\[](official|lyrics?|audio|video|music video|hd|4k|visualizer)[^)\\]]*[)\\]]"), ""), Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp, color = if (current) Accent else MaterialTheme.colorScheme.onSurface)
        Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Dim)
    }
}
