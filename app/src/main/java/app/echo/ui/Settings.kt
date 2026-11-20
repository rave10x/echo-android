package app.echo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echo.EchoVM

@Composable
fun SettingsScreen(vm: EchoVM, onBack: () -> Unit) {
    val d by vm.store.state.collectAsState()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }; Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Appearance", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system" to "System", "dark" to "Dark", "light" to "Light").forEach { (k, l) -> FilterChip(d.theme == k, { vm.store.setTheme(k) }, { Text(l) }) } }
            HorizontalDivider()
            Text("Sound", fontWeight = FontWeight.Bold)
            OutlinedButton({ vm.showEq = true }) { Text("Equalizer & bass boost") }
            Text("Fade between songs", fontWeight = FontWeight.Medium)
            Text("Fades the end of a song out and the next one in. Off gives normal gapless playback.", color = Dim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0 to "Off", 2 to "2 s", 4 to "4 s", 6 to "6 s", 10 to "10 s").forEach { (v, l) -> FilterChip(d.fadeSec == v, { vm.store.mutate { it.copy(fadeSec = v) } }, { Text(l) }) } }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Show lyric line in notification", fontWeight = FontWeight.Medium); Text("The current line appears under the song title on the lock screen and in the notification.", color = Dim, fontSize = 13.sp) }
                Switch(d.lyricNotif, { v -> vm.store.mutate { it.copy(lyricNotif = v) } })
            }
            HorizontalDivider()
            Text("Hide short files", fontWeight = FontWeight.Bold)
            Text("Songs shorter than this are left out of the library (alerts, voice clips, ringtones).", color = Dim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Off", 30 to "30s", 60 to "1m", 90 to "90s", 120 to "2m").forEach { (s, l) -> FilterChip(d.minSec == s, { vm.setMinSec(s) }, { Text(l) }) }
            }
            HorizontalDivider()
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hide call recordings & voice notes", fontWeight = FontWeight.Bold)
                    Text("Skips recorder folders, call recordings, WhatsApp / Telegram voice notes and ringtones.", color = Dim, fontSize = 13.sp)
                }
                Switch(d.noRecordings, { vm.setNoRecordings(it) })
            }
            HorizontalDivider()
            Text("${vm.hidden} file(s) are currently hidden by these rules.", color = Dim)
            OutlinedButton({ vm.rescan(); vm.toast("Scanning…") }) { Text("Rescan library") }
            HorizontalDivider()
            Text("Offline songs", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Save songs I listen to", fontWeight = FontWeight.Medium)
                    Text("YouTube songs you play are downloaded in the background so they play with no internet.", color = Dim, fontSize = 13.sp)
                }
                Switch(d.autoSave, { vm.store.setAutoSave(it) })
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Save only on Wi-Fi", fontWeight = FontWeight.Medium); Text("Automatic downloads wait for Wi-Fi so they do not use mobile data. Manual downloads always work.", color = Dim, fontSize = 13.sp) }
                Switch(d.wifiOnly, { v -> vm.store.mutate { it.copy(wifiOnly = v) } })
            }
            val saved by vm.offline.saved.collectAsState()
            Text("${saved.size} songs saved · ${"%.1f".format(vm.offline.usedBytes / 1048576.0)} MB used", color = Dim)
            Text("Storage limit (oldest unplayed songs are removed first)", fontSize = 13.sp, color = Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(500 to "500 MB", 1024 to "1 GB", 2048 to "2 GB", 5120 to "5 GB").forEach { (mb, l) -> FilterChip(d.cacheMb == mb, { vm.store.setCacheMb(mb) }, { Text(l) }) }
            }
            OutlinedButton({ vm.offline.clear(); vm.toast("Offline songs removed") }, enabled = saved.isNotEmpty()) { Text("Remove all offline songs") }
            HorizontalDivider()
            Text("Backup", fontWeight = FontWeight.Bold)
            Text("Saves your playlists, liked songs, history, stats, edits and settings to a file. Songs and downloads are not included.", color = Dim, fontSize = 13.sp)
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> if (u != null) { runCatching { ctx.contentResolver.openOutputStream(u)!!.use { it.write(vm.store.exportJson().toByteArray()) } }.onSuccess { vm.toast("Backup saved") }.onFailure { vm.toast("Could not save the backup") } } }
            val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { runCatching { vm.store.importMerge(ctx.contentResolver.openInputStream(u)!!.bufferedReader().use { it.readText() }) }.onSuccess { vm.toast("Backup restored") }.onFailure { vm.toast("That is not an Echo backup") } } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ export.launch("echo-backup.json") }) { Text("Export backup") }
                OutlinedButton({ import.launch(arrayOf("application/json", "*/*")) }) { Text("Restore backup") }
            }
            HorizontalDivider()
            Text("Lyrics come from LRCLIB (synced when available) or a .lrc file next to the song. They are cached on this phone.", color = Dim, fontSize = 13.sp)
        }
    }
}
