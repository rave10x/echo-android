package app.echo.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.echo.EchoVM

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoRoot(vm: EchoVM, showNow: Boolean, setNow: (Boolean) -> Unit) {
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.events.collect { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(it) } }
    LaunchedEffect(vm.sharedLink) { if (vm.sharedLink != null) { tab = 4; nav.popBackStack("home", false) } }
    val open = { setNow(true) }
    val enc = { s: String -> Uri.encode(s) }

    Surface(Modifier.fillMaxSize(), color = Bg, contentColor = MaterialTheme.colorScheme.onBackground) { Box(Modifier.fillMaxSize()) {
        NavHost(nav, "home", Modifier.fillMaxSize(),
            enterTransition = { slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(250)) }, exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { fadeIn(tween(250)) }, popExitTransition = { slideOutHorizontally(tween(300)) { it / 4 } + fadeOut(tween(250)) }) {
            composable("home") { HomeTabs(vm, tab, { tab = it }, nav, open) }
            composable("playlist/{id}") { e -> PlaylistScreen(vm, e.arguments?.getString("id").orEmpty(), { nav.popBackStack() }, open) }
            composable("album/{name}") { e -> GroupScreen(vm, Uri.decode(e.arguments?.getString("name").orEmpty()), true, { nav.popBackStack() }, open) }
            composable("artist/{name}") { e -> GroupScreen(vm, Uri.decode(e.arguments?.getString("name").orEmpty()), false, { nav.popBackStack() }, open) }
            composable("stats") { StatsScreen(vm, { nav.popBackStack() }, open) }
            composable("settings") { SettingsScreen(vm) { nav.popBackStack() } }
        }
        val outerDark = LocalPalette.current.dark; val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        LaunchedEffect(showNow, outerDark) { applyBars(act, if (showNow) true else outerDark) }
        AnimatedVisibility(showNow, enter = slideInVertically(tween(320)) { it } + fadeIn(), exit = slideOutVertically(tween(280)) { it } + fadeOut()) {
            EchoTheme("dark", bars = false) { Surface(Modifier.fillMaxSize(), color = Bg, contentColor = MaterialTheme.colorScheme.onBackground) { NowPlaying(vm) { setNow(false) } } }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp))
    } }
    BackHandler(showNow) { setNow(false) }
    vm.editTarget?.let { EditSongDialog(vm, it) { vm.editTarget = null } }
    if (vm.showEq) EqualizerSheet(vm) { vm.showEq = false }
    vm.addTarget?.let { AddToPlaylistSheet(vm, it) { vm.addTarget = null } }
}

@Composable
private fun HomeTabs(vm: EchoVM, tab: Int, setTab: (Int) -> Unit, nav: androidx.navigation.NavHostController, open: () -> Unit) {
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermissionResult() }
    LaunchedEffect(Unit) { if (!vm.granted) perm.launch(vm.requiredPermissions()) }
    val items = listOf("Home" to Icons.Default.Home, "Library" to Icons.Default.LibraryMusic, "Playlists" to Icons.Default.QueueMusic, "Videos" to Icons.Default.VideoLibrary, "Search" to Icons.Default.Search)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Box(Modifier.weight(1f)) {
            if (!vm.granted && tab != 0 && tab != 4) Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.LibraryMusic, null, Modifier.size(56.dp), tint = Accent)
                Spacer(Modifier.height(12.dp)); Text("Allow access to your music and videos", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Echo plays the audio and video files stored on this phone. Nothing is uploaded.", color = Dim, modifier = Modifier.padding(vertical = 8.dp))
                Button(onClick = { perm.launch(vm.requiredPermissions()) }) { Text("Allow access") }
            } else when (tab) {
                0 -> HomeTab(vm, open) { setTab(4) }
                1 -> LibraryTab(vm, nav, open)
                2 -> PlaylistsTab(vm, nav)
                3 -> VideosTab(vm, open)
                else -> OnlineTab(vm, open)
            }
        }
        MiniPlayer(vm, open)
        NavigationBar(containerColor = Surf, windowInsets = WindowInsets.navigationBars) {
            items.forEachIndexed { i, (l, ic) ->
                NavigationBarItem(tab == i, { setTab(i) }, { Icon(ic, l) }, label = { Text(l, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, indicatorColor = Accent, selectedTextColor = Accent, unselectedIconColor = Dim, unselectedTextColor = Dim))
            }
        }
    }
}
