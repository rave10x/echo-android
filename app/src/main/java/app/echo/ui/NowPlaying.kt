package app.echo.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.palette.graphics.Palette
import app.echo.EchoVM
import app.echo.data.LIKED
import app.echo.data.Lyrics
import app.echo.data.LyricsRepo
import app.echo.data.Track
import app.echo.playback.ArtLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlaying(vm: EchoVM, onClose: () -> Unit) {
    val ps = vm.ps; val ctx = LocalContext.current
    val t = ps.track
    if (t == null) { LaunchedEffect(Unit) { onClose() }; return }
    val host = LocalFullscreen.current
    var lyricsOn by rememberSaveable { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    val data by vm.store.state.collectAsState()
    val liked = data.playlists.firstOrNull { it.id == LIKED }?.tracks?.any { it.id == t.id } == true
    val video = ps.showsVideo

    // background tint taken from the cover art
    var tint by remember(t.id) { mutableStateOf(Color(0xFF2A2140)) }
    LaunchedEffect(t.id) {
        val src = t.art ?: return@LaunchedEffect
        if (!src.startsWith("http")) ArtLoader.load(ctx, src, 96)?.let { b -> Palette.from(b).generate().let { p -> p.getDarkMutedColor(p.getMutedColor(0xFF2A2140.toInt())).let { tint = Color(it) } } }
    }
    val tintAnim by animateColorAsState(tint, label = "tint")

    DisposableEffect(full) { host?.setImmersive(full); onDispose { if (full) host?.setImmersive(false) } }
    BackHandler(full) { full = false }

    if (full && video) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(ps.p, true, Modifier.fillMaxSize())
            IconButton({ full = false }, Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.displayCutout).padding(8.dp)) { Icon(Icons.Default.FullscreenExit, "Exit fullscreen", tint = Color.White) }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tintAnim, Bg, Bg))).windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close", Modifier.size(32.dp)) }
            Text("Now playing", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 14.sp, color = Dim)
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Add to playlist") }, { menu = false; vm.addTarget = listOf(t) }, leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) })
                    DropdownMenuItem({ Text("Sleep timer") }, { menu = false; showSleep = true }, leadingIcon = { Icon(Icons.Default.Bedtime, null) })
                    DropdownMenuItem({ Text("Playback speed") }, { menu = false; showSpeed = true }, leadingIcon = { Icon(Icons.Default.Speed, null) })
                    if (t.yt) DropdownMenuItem({ Text(if (ps.ytVideo) "Switch to audio only" else "Show video") }, { menu = false; ps.changeYtVideo(!ps.ytVideo) }, leadingIcon = { Icon(if (ps.ytVideo) Icons.Default.Headphones else Icons.Default.Videocam, null) })
                }
            }
        }
        // cover / video / lyrics area
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Crossfade(if (lyricsOn) 1 else 0, label = "area") { m ->
                if (m == 1) LyricsView(t, ps.pos, { ps.seek(it) })
                else if (video) Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                    VideoSurface(ps.p, false, Modifier.fillMaxSize())
                    IconButton({ full = true }, Modifier.align(Alignment.BottomEnd)) { Icon(Icons.Default.Fullscreen, "Fullscreen", tint = Color.White) }
                } else Art(t, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(22.dp), 720)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t.subtitle.ifBlank { if (t.video) "Video" else "" }, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ vm.store.toggleLike(t) }) { Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Like", tint = if (liked) Accent else Color.White) }
        }
        ps.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, maxLines = 2) }
        // seek bar
        var drag by remember { mutableStateOf<Float?>(null) }
        val dur = ps.dur.coerceAtLeast(1)
        Slider(value = drag ?: (ps.pos.toFloat() / dur).coerceIn(0f, 1f), onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { ps.seek((it * dur).toLong()) }; drag = null },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color(0x33FFFFFF)))
        Row(Modifier.fillMaxWidth().offset(y = (-8).dp)) {
            Text(fmt(drag?.let { (it * dur).toLong() } ?: ps.pos), fontSize = 12.sp, color = Dim); Spacer(Modifier.weight(1f)); Text(fmt(ps.dur), fontSize = 12.sp, color = Dim)
        }
        // transport
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton({ ps.toggleShuffle() }) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (ps.shuffle) Accent else Dim) }
            IconButton({ ps.prev() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp)) }
            IconButton({ ps.toggle() }, Modifier.size(72.dp).background(Color.White, CircleShape)) {
                if (ps.buffering && !ps.playing) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color.Black)
                else Icon(if (ps.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (ps.playing) "Pause" else "Play", Modifier.size(40.dp), tint = Color.Black)
            }
            IconButton({ ps.next() }, Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "Next", Modifier.size(36.dp)) }
            IconButton({ ps.cycleRepeat() }) { Icon(if (ps.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat", tint = if (ps.repeat != Player.REPEAT_MODE_OFF) Accent else Dim) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton({ lyricsOn = !lyricsOn }) { Icon(Icons.Default.Lyrics, null, tint = if (lyricsOn) Accent else Dim); Spacer(Modifier.width(6.dp)); Text("Lyrics", color = if (lyricsOn) Accent else Dim) }
            TextButton({ showSleep = true }) { Icon(Icons.Default.Bedtime, null, tint = if (ps.sleepAt > 0) Accent else Dim); Spacer(Modifier.width(6.dp)); Text(if (ps.sleepAt > 0) "${((ps.sleepAt - System.currentTimeMillis()) / 60000 + 1)} min" else "Sleep", color = if (ps.sleepAt > 0) Accent else Dim) }
            TextButton({ showQueue = true }) { Icon(Icons.Default.QueueMusic, null, tint = Dim); Spacer(Modifier.width(6.dp)); Text("Queue", color = Dim) }
        }
    }

    if (showQueue) ModalBottomSheet({ showQueue = false }, containerColor = Surf) {
        Text("Up next · ${ps.queue.size}", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val ls = rememberLazyListState()
        LaunchedEffect(Unit) { ls.scrollToItem((ps.index - 1).coerceAtLeast(0)) }
        LazyColumn(Modifier.heightIn(max = 520.dp), ls, contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(ps.queue, key = { i, q -> "${q.id}#$i" }) { i, q ->
                Row(Modifier.fillMaxWidth().clickable { ps.jump(i) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(q, Modifier.size(44.dp), RoundedCornerShape(8.dp), 128); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(q.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (i == ps.index) Accent else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        Text(q.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Dim, fontSize = 12.sp)
                    }
                    if (i != ps.index) IconButton({ ps.removeAt(i) }) { Icon(Icons.Default.Close, "Remove", tint = Dim) }
                    if (i > 0 && i != ps.index) IconButton({ ps.moveQueue(i, i - 1) }) { Icon(Icons.Default.KeyboardArrowUp, "Move up", tint = Dim) }
                }
            }
        }
    }
    if (showSleep) ModalBottomSheet({ showSleep = false }, containerColor = Surf) {
        Column(Modifier.padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sleep timer", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            listOf(10, 15, 30, 45, 60, 0).forEach { m -> ListItem({ Text(if (m == 0) "Off" else "$m minutes") }, Modifier.clickable { ps.sleepIn(m); showSleep = false }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) }
        }
    }
    if (showSpeed) ModalBottomSheet({ showSpeed = false }, containerColor = Surf) {
        Column(Modifier.padding(20.dp).navigationBarsPadding()) {
            Text("Playback speed", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s -> FilterChip(ps.speed == s, { ps.changeSpeed(s); showSpeed = false }, { Text("${s}x") }) }
            }
        }
    }
}

@Composable
private fun VideoSurface(player: Player, controller: Boolean, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { c -> PlayerView(c).apply { useController = controller; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(android.graphics.Color.BLACK); this.player = player } },
        update = { it.player = player; it.useController = controller }, onRelease = { it.player = null })
}

@Composable
private fun LyricsView(t: Track, pos: Long, seek: (Long) -> Unit) {
    val ctx = LocalContext.current
    val lyr by produceState<Lyrics?>(null, t.id) { value = null; value = LyricsRepo.get(ctx, t) }
    val l = lyr
    when {
        l == null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = Accent); Text("Finding lyrics…", color = Dim, modifier = Modifier.padding(top = 12.dp)) }
        l.isEmpty -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lyrics, null, Modifier.size(44.dp), tint = Dim); Text("No lyrics found for this song", color = Dim, modifier = Modifier.padding(top = 8.dp)) }
        l.synced.isNotEmpty() -> {
            val cur = l.synced.indexOfLast { it.t <= pos + 250 }.coerceAtLeast(0)
            val ls = rememberLazyListState()
            LaunchedEffect(cur) { ls.animateScrollToItem((cur - 2).coerceAtLeast(0)) }
            LazyColumn(Modifier.fillMaxSize(), ls, contentPadding = PaddingValues(vertical = 80.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(l.synced) { i, line ->
                    Text(line.text.ifBlank { "♪" }, Modifier.fillMaxWidth().clickable { seek(line.t) }, fontSize = if (i == cur) 26.sp else 22.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp,
                        color = if (i == cur) Color.White else Color(0x66FFFFFF))
                }
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp)) { item { Text(l.plain, fontSize = 19.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium, color = Color(0xDDFFFFFF)) } }
    }
}
