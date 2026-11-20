package app.echo.ui

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
            Text("Hide short files", fontWeight = FontWeight.Bold)
            Text("Songs shorter than this are left out of the library (alerts, voice clips, ringtones).", color = Dim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Off", 30 to "30 s", 60 to "1 min", 90 to "90 s", 120 to "2 min").forEach { (s, l) -> FilterChip(d.minSec == s, { vm.setMinSec(s) }, { Text(l) }) }
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
            Text("Lyrics come from LRCLIB (synced when available) or a .lrc file next to the song. They are cached on this phone.", color = Dim, fontSize = 13.sp)
        }
    }
}
