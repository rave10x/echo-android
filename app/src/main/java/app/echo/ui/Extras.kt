package app.echo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echo.EchoVM
import app.echo.data.Effects
import app.echo.data.Track
import app.echo.data.TrackEdit
import java.text.SimpleDateFormat
import java.util.*

/** Edit title / artist / album and cover of a song from this phone. */
@Composable
fun EditSongDialog(vm: EchoVM, t: Track, onDismiss: () -> Unit) {
    val data by vm.store.state.collectAsState()
    val cur = data.edits[t.id]
    var title by remember(t.id) { mutableStateOf(t.title) }; var artist by remember(t.id) { mutableStateOf(t.artist) }; var album by remember(t.id) { mutableStateOf(t.album) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) vm.setCover(t, u) }
    val shown = t.copy(art = cur?.cover ?: t.art)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit song info") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Art(shown, Modifier.size(72.dp), RoundedCornerShape(12.dp), 256); Spacer(Modifier.width(12.dp))
                Column { OutlinedButton({ pick.launch("image/*") }) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Choose cover") } }
            }
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
            OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true)
            OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true)
        }
    }, confirmButton = {
        TextButton({ vm.store.setEdit(t.id, (cur ?: TrackEdit()).copy(title = title, artist = artist, album = album)); onDismiss() }) { Text("Save") }
    }, dismissButton = {
        Row { if (cur != null) TextButton({ vm.store.setEdit(t.id, null); onDismiss() }) { Text("Reset") }; TextButton(onDismiss) { Text("Cancel") } }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(vm: EchoVM, onDismiss: () -> Unit) {
    val d by vm.store.state.collectAsState(); val fx = vm.effects
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Surf, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Equalizer", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Switch(d.eqOn, { v -> vm.store.mutate { it.copy(eqOn = v) }; fx.apply() }, enabled = fx.supported)
            }
            if (!fx.supported) Text("This phone does not offer an equalizer for this player.", color = Dim)
            else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Effects.PRESETS.keys.forEach { n -> FilterChip(d.eqPreset == n, { vm.store.mutate { it.copy(eqPreset = n, eqLevels = fx.levelsFor(n), eqOn = true) }; fx.apply() }, { Text(n) }) }
                }
                val levels = if (d.eqLevels.size == fx.bands) d.eqLevels else fx.levelsFor("Flat")
                for (i in 0 until fx.bands) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fx.freqs[i].let { if (it >= 1000) "${it / 1000}k" else "$it" } + " Hz", Modifier.width(64.dp), fontSize = 12.sp, color = Dim)
                        Slider(levels[i].toFloat(), { v -> val n = levels.toMutableList().also { it[i] = v.toInt() }; vm.store.mutate { it.copy(eqLevels = n, eqPreset = "Custom", eqOn = true) }; fx.apply() },
                            valueRange = fx.minLevel.toFloat()..fx.maxLevel.toFloat(), modifier = Modifier.weight(1f), colors = SliderDefaults.colors(activeTrackColor = Accent, thumbColor = Accent))
                        Text("${levels[i] / 100} dB", Modifier.width(48.dp), fontSize = 12.sp, color = Dim)
                    }
                }
                Text("Bass boost", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                Slider(d.bass.toFloat(), { v -> vm.store.mutate { it.copy(bass = v.toInt(), eqOn = true) }; fx.apply() }, valueRange = 0f..1000f, colors = SliderDefaults.colors(activeTrackColor = Accent, thumbColor = Accent))
            }
        }
    }
}


@Composable
fun StatsScreen(vm: EchoVM, onBack: () -> Unit, open: () -> Unit) {
    val d by vm.store.state.collectAsState()
    var range by remember { mutableIntStateOf(1) }
    val fmtDay = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val cutoff = remember(range) { val c = Calendar.getInstance(); when (range) { 0 -> c.add(Calendar.DAY_OF_YEAR, -7); 1 -> c.add(Calendar.DAY_OF_YEAR, -30); else -> c.add(Calendar.YEAR, -5) }; fmtDay.format(c.time) }
    val rows = d.listen.filter { it.day >= cutoff }
    val total = rows.sumOf { it.ms }
    val topSongs = rows.groupBy { it.id }.map { (_, l) -> l.first() to l.sumOf { it.ms } }.sortedByDescending { it.second }.take(8)
    val topArtists = rows.filter { it.artist.isNotBlank() }.groupBy { it.artist }.map { it.key to it.value.sumOf { r -> r.ms } }.sortedByDescending { it.second }.take(5)
    val last7 = remember(d.listen) { (6 downTo 0).map { n -> val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, -n); fmtDay.format(c.time) to c.get(Calendar.DAY_OF_WEEK) } }
    fun dur(ms: Long): String { val m = ms / 60000; return if (m >= 60) "${m / 60} h ${m % 60} min" else if (m == 0L) "${ms / 1000} s" else "$m min" }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }; Text("Listening stats", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("7 days", "30 days", "All time").forEachIndexed { i, l -> FilterChip(range == i, { range = i }, { Text(l) }) } }
            Column(Modifier.fillMaxWidth().background(Surf, RoundedCornerShape(18.dp)).padding(18.dp)) {
                Text("You listened for", color = Dim, fontSize = 13.sp); Text(if (total == 0L) "—" else dur(total), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Accent)
                Text("${rows.map { it.id }.distinct().size} different songs", color = Dim, fontSize = 13.sp)
            }
            Text("Last 7 days", fontWeight = FontWeight.Bold)
            val mx = last7.maxOf { (day, _) -> d.listen.filter { it.day == day }.sumOf { it.ms } }.coerceAtLeast(1L)
            Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                last7.forEach { (day, dow) ->
                    val ms = d.listen.filter { it.day == day }.sumOf { it.ms }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.width(18.dp).height((70f * ms / mx).dp.coerceAtLeast(3.dp)).background(if (ms > 0) Accent else Surf2, RoundedCornerShape(5.dp)))
                        Text("SMTWTFS"[dow - 1].toString(), fontSize = 11.sp, color = Dim, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (topSongs.isNotEmpty()) { Text("Top songs", fontWeight = FontWeight.Bold)
                topSongs.forEachIndexed { i, (l, ms) -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(24.dp), color = Dim); Column(Modifier.weight(1f)) { Text(l.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text(l.artist, color = Dim, fontSize = 12.sp, maxLines = 1) }
                    Text(dur(ms), color = Dim, fontSize = 13.sp) } } }
            if (topArtists.isNotEmpty()) { Text("Top artists", fontWeight = FontWeight.Bold)
                topArtists.forEach { (a, ms) -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(a, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Text(dur(ms), color = Dim, fontSize = 13.sp) } } }
            if (rows.isEmpty()) Text("Listen to some music and your stats will appear here.", color = Dim)
            Spacer(Modifier.height(24.dp))
        }
    }
}
