package app.echo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echo.EchoVM
import app.echo.data.Track
import app.echo.yt.YT
import kotlinx.coroutines.launch

/** Search YouTube or paste a video / playlist link; play it as music or video, or save it into a playlist. */
@Composable
fun OnlineTab(vm: EchoVM, open: () -> Unit) {
    val ps = vm.ps; val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>>(emptyList()) }
    var title by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    fun go(text: String) {
        if (text.isBlank() || busy) return
        busy = true; err = null
        scope.launch {
            runCatching {
                if (text.contains("http", true) || YT.videoId(text) != null || YT.playlistId(text) != null) YT.fromLink(text).let { title = it.title; it.tracks }
                else { title = null; YT.search(text) }
            }.onSuccess { results = it; if (it.isEmpty()) err = "Nothing found" }.onFailure { err = it.message ?: "Could not load that" }
            busy = false
        }
    }
    LaunchedEffect(vm.sharedLink) { vm.sharedLink?.let { q = it; vm.sharedLink = null; go(it) } }

    Column(Modifier.fillMaxSize()) {
        Header("Search")
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, placeholder = { Text("Search YouTube or paste a link", color = Dim) },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), leadingIcon = { Icon(Icons.Default.Search, null, tint = Dim) },
            trailingIcon = { if (q.isNotEmpty()) IconButton({ q = ""; results = emptyList(); title = null; err = null }) { Icon(Icons.Default.Close, "Clear") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go(q) }),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Surf, unfocusedContainerColor = Surf, focusedBorderColor = Accent, unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp), color = Accent)
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (results.isEmpty() && !busy && err == null) EmptyNote(Icons.Default.Public, "Stream anything", "Search YouTube, or paste a video or playlist link. Play it as music in the background, or switch to video. Save favourites to your playlists.")
        if (results.isNotEmpty()) {
            title?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
            LazyColumn(Modifier.weight(1f)) {
                item {
                    SectionButtons({ ps.play(results); open() }, { ps.play(results, shuffleOn = true); open() })
                    Row {
                        TextButton({ vm.addTarget = results }, Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Default.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text("Add all to a playlist") }
                        TextButton({ results.forEach { vm.offline.save(it, true) }; vm.toast("Downloading ${results.size} songs…") }) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Download all") }
                    }
                }
                itemsIndexed(results, key = { i, t -> "${t.id}#$i" }) { i, t -> TrackRow(t, ps.track?.id == t.id, ps.playing, vm, onClick = { ps.play(results, i); open() }) }
            }
        }
    }
}
