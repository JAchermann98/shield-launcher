@file:OptIn(ExperimentalFoundationApi::class)

package ch.jachermann.shieldlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0A0C10)
private val TextMain = Color(0xFFF2F4F7)
private val TextSoft = Color(0xFFC9D3E0)
private val TextDim = Color(0xFFA9B4C4)
private val Tile = Color(0xFF1C222C)

private enum class Tab { START, FILME, SERIEN }

/** Verbindet Kopfzeile und Inhalt: wohin der Fokus beim Hoch-/Runterdrücken springt. */
private class FocusBridge {
    var toTop: () -> Unit = {}
    var toContent: () -> Unit = {}
}

// Layout der Startseite (in dp, Bildschirm 960 x 540)
private const val FOCUS_Y = 318
private const val ROW_STEP = 168
private const val PREV_Y = -118

class MainActivity : ComponentActivity() {

    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = PlexRepo(this)
        setContent {
            LauncherApp(repo, resumeCount)
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }
}

// ---------------------------------------------------------------- App

@Composable
fun LauncherApp(repo: PlexRepo, resumeCount: Int) {
    var loggedIn by remember { mutableStateOf(repo.userToken != null) }
    Box(Modifier.fillMaxSize().background(Bg)) {
        if (!loggedIn) {
            LoginScreen(repo) { loggedIn = true }
        } else {
            HomeRoot(repo, resumeCount)
        }
    }
}

// ---------------------------------------------------------------- Anmeldung

@Composable
fun LoginScreen(repo: PlexRepo, onDone: () -> Unit) {
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            val (pinId, c) = repo.createPin()
            code = c
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 10 * 60 * 1000) {
                delay(2000)
                if (repo.checkPin(pinId)) {
                    onDone()
                    return@LaunchedEffect
                }
            }
            error = "Zeit abgelaufen. Bitte die App neu starten."
        } catch (e: Exception) {
            error = "Keine Verbindung zu Plex: ${e.message}"
        }
    }
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText("Mit Plex verbinden", style = TextStyle(color = TextMain, fontSize = 32.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(16.dp))
        BasicText("Öffne auf dem Handy plex.tv/link und gib diesen Code ein:", style = TextStyle(color = TextSoft, fontSize = 18.sp))
        Spacer(Modifier.height(24.dp))
        BasicText(code ?: "…", style = TextStyle(color = TextMain, fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp))
        error?.let {
            Spacer(Modifier.height(24.dp))
            BasicText(it, style = TextStyle(color = Color(0xFFFFB4A8), fontSize = 16.sp))
        }
    }
}

// ---------------------------------------------------------------- Rahmen mit Kopfzeile und Seiten

@Composable
fun HomeRoot(repo: PlexRepo, resumeCount: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf(repo.loadServers()) }
    var rows by remember { mutableStateOf(repo.loadCache()) }
    var status by remember { mutableStateOf(if (rows.isEmpty()) "Lade Inhalte von Plex …" else "") }
    var lastRefresh by remember { mutableLongStateOf(0L) }
    val focusState = remember { mutableStateOf(rows.firstOrNull()?.items?.firstOrNull()) }
    var tab by remember { mutableStateOf(Tab.START) }
    var focusedRow by remember { mutableIntStateOf(0) }
    var libScrolled by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    val bridge = remember { FocusBridge() }
    val topReq = remember { listOf("plex", "yt", "apps", "start", "filme", "serien", "update", "settings").associateWith { FocusRequester() } }
    var lastTop by remember { mutableStateOf<String?>(null) }
    bridge.toTop = {
        val k = lastTop ?: when (tab) { Tab.START -> "start"; Tab.FILME -> "filme"; Tab.SERIEN -> "serien" }
        try { topReq[k]?.requestFocus() } catch (e: Exception) { }
    }

    BackHandler(enabled = true) {
        when {
            showApps -> showApps = false
            tab != Tab.START -> tab = Tab.START
        }
    }

    // Startseite still im Hintergrund aktualisieren
    LaunchedEffect(resumeCount) {
        if (System.currentTimeMillis() - lastRefresh < 20_000) return@LaunchedEffect
        lastRefresh = System.currentTimeMillis()
        try {
            var loaded = if (servers.isNotEmpty()) repo.loadRows(servers) else emptyList()
            if (loaded.isEmpty()) {
                val found = repo.findServers()
                if (found.isNotEmpty()) {
                    servers = found
                    repo.saveServers(found)
                    loaded = repo.loadRows(found)
                }
            }
            if (loaded.isNotEmpty()) {
                rows = loaded
                status = ""
                if (focusState.value == null) focusState.value = loaded.first().items.firstOrNull()
                withContext(Dispatchers.IO) { repo.saveCache(loaded) }
                val loader = context.imageLoader
                loaded.forEach { r ->
                    r.items.take(4).forEach { item ->
                        item.backdropUrl?.let { loader.enqueue(ImageRequest.Builder(context).data(it).build()) }
                        item.logoUrl?.let { loader.enqueue(ImageRequest.Builder(context).data(it).build()) }
                    }
                }
            } else if (rows.isEmpty()) {
                status = "Keine Inhalte gefunden. Ist der Plex-Server erreichbar?"
            }
        } catch (e: Exception) {
            if (rows.isEmpty()) status = "Fehler beim Laden: ${e.message}"
        }
    }

    val openItem: (PlexItem, Boolean) -> Unit = { item, play ->
        scope.launch {
            val target = if (item.fromWatchlist) repo.resolveOnServer(item, servers) else item
            if (target == null) {
                Toast.makeText(context, "Dieser Titel ist auf deinen Servern nicht verfügbar.", Toast.LENGTH_LONG).show()
            } else {
                val uri = if (play) repo.playUri(target) else repo.detailsUri(target)
                try {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    i.setPackage("com.plexapp.android")
                    // Plex frisch starten, sonst bleibt es auf der zuletzt offenen Seite stehen
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(i)
                } catch (e: Exception) {
                    Toast.makeText(context, "Plex konnte nicht geöffnet werden.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val onFocusItem: (PlexItem) -> Unit = remember { { focusState.value = it } }

    val topBarVisible = if (tab == Tab.START) focusedRow == 0 else !libScrolled
    val topAlpha by animateFloatAsState(if (topBarVisible) 1f else 0f, tween(300), label = "topAlpha")

    Box(Modifier.fillMaxSize()) {
        Backdrop(focusState, dim = tab != Tab.START)

        when (tab) {
            Tab.START -> StartScreen(
                rows = rows,
                status = status,
                focusState = focusState,
                onFocusItem = onFocusItem,
                onRowFocused = { focusedRow = it },
                openItem = openItem,
                bridge = bridge
            )
            Tab.FILME -> LibraryScreen(repo, servers, "movie", "Alle Filme", onFocusItem, openItem, bridge) { libScrolled = it }
            Tab.SERIEN -> LibraryScreen(repo, servers, "show", "Alle Serien", onFocusItem, openItem, bridge) { libScrolled = it }
        }

        Box(
            Modifier
                .padding(start = 48.dp, top = 22.dp)
                .graphicsLayer { alpha = topAlpha; translationY = (1f - topAlpha) * -30f }
        ) {
            TopBar(
                tab = tab,
                onTab = { tab = it; focusedRow = 0; libScrolled = false },
                onApps = { showApps = true },
                reqs = topReq,
                onFocused = { lastTop = it },
                onDown = { bridge.toContent() }
            )
        }

        if (showApps) {
            AllAppsOverlay(onClose = { showApps = false })
        }
    }
}

// ---------------------------------------------------------------- Hintergrund

@Composable
fun Backdrop(focusState: MutableState<PlexItem?>, dim: Boolean) {
    val target = focusState.value?.backdropUrl
    var shown by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        if (target != shown) {
            delay(200)
            shown = target
        }
    }
    val dimAlpha by animateFloatAsState(if (dim) 0.55f else 0f, tween(400), label = "dim")
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = shown, animationSpec = tween(550), label = "backdrop") { url ->
            if (url != null) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF141925)))
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Bg.copy(alpha = 0.92f), 0.4f to Bg.copy(alpha = 0.55f), 0.75f to Bg.copy(alpha = 0f))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Bg.copy(alpha = 0.35f), 0.18f to Color.Transparent, 0.5f to Color.Transparent, 0.72f to Bg.copy(alpha = 0.8f), 1f to Bg)
            )
        )
        Box(Modifier.fillMaxSize().background(Bg.copy(alpha = dimAlpha)))
    }
}

// ---------------------------------------------------------------- Kopfzeile

@Composable
private fun TopBar(
    tab: Tab,
    onTab: (Tab) -> Unit,
    onApps: () -> Unit,
    reqs: Map<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onDown: () -> Unit
) {
    fun m(k: String): Modifier = Modifier.focusRequester(reqs.getValue(k)).onFocusChanged { if (it.isFocused) onFocused(k) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var time by remember { mutableStateOf(clock()) }
    var updateAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            time = clock()
            delay(15000)
        }
    }
    LaunchedEffect(Unit) {
        try {
            updateAvailable = Updater.check(context) != null
        } catch (e: Exception) {
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(end = 48.dp).onPreviewKeyEvent { ev ->
            val ne = ev.nativeKeyEvent
            if (ne.action == AndroidKeyEvent.ACTION_DOWN && ne.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN) { onDown(); true } else false
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(time, style = TextStyle(color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.width(18.dp))
        PillButton("Plex", m("plex")) { launchApp(context, listOf("com.plexapp.android")) }
        Spacer(Modifier.width(6.dp))
        PillButton("YouTube", m("yt")) { launchYouTube(context) }
        Spacer(Modifier.width(6.dp))
        PillButton("Alle Apps", m("apps")) { onApps() }
        Spacer(Modifier.weight(1f))
        TabButton("Start", tab == Tab.START, m("start")) { onTab(Tab.START) }
        Spacer(Modifier.width(4.dp))
        TabButton("Filme", tab == Tab.FILME, m("filme")) { onTab(Tab.FILME) }
        Spacer(Modifier.width(4.dp))
        TabButton("Serien", tab == Tab.SERIEN, m("serien")) { onTab(Tab.SERIEN) }
        Spacer(Modifier.width(22.dp))
        Box(Modifier.width(1.dp).height(20.dp).background(Color.White.copy(alpha = 0.25f)))
        Spacer(Modifier.width(16.dp))
        IconButton(dot = updateAvailable, modifier = m("update"), onClick = {
            scope.launch {
                try {
                    val u = Updater.check(context)
                    if (u == null) {
                        Toast.makeText(context, "Du hast bereits die neueste Version.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Lade Version ${u.build} herunter …", Toast.LENGTH_SHORT).show()
                        if (!Updater.install(context, u)) {
                            Toast.makeText(context, "Bitte Installation erlauben und danach nochmals auf Update drücken.", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Update fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }) { color -> DownloadIcon(color) }
        Spacer(Modifier.width(8.dp))
        IconButton(dot = false, modifier = m("settings"), onClick = {
            val i = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(i) } catch (e: Exception) { }
        }) { color ->
            Image(Icons.Filled.Settings, contentDescription = "Einstellungen", colorFilter = ColorFilter.tint(color), modifier = Modifier.size(18.dp))
        }
    }
}

private fun clock(): String = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())

@Composable
fun PillButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val isFocused by src.collectIsFocusedAsState()
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (isFocused) TextMain else Color.White.copy(alpha = 0.10f))
            .clickable(interactionSource = src, indication = null) { onClick() }
            .padding(horizontal = 13.dp, vertical = 6.dp)
    ) {
        BasicText(label, style = TextStyle(color = if (isFocused) Bg else TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
    }
}

/** Reiter für Start / Filme / Serien: grösser, die aktive Seite unterstrichen. */
@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val isFocused by src.collectIsFocusedAsState()
    Column(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (isFocused) TextMain else Color.Transparent)
            .clickable(interactionSource = src, indication = null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = when {
                    isFocused -> Bg
                    selected -> TextMain
                    else -> TextSoft.copy(alpha = 0.8f)
                },
                fontSize = 17.sp,
                fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.SemiBold
            )
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier.width(18.dp).height(2.dp).clip(RoundedCornerShape(1.dp))
                .background(if (selected && !isFocused) TextMain else Color.Transparent)
        )
    }
}

@Composable
private fun IconButton(dot: Boolean, modifier: Modifier, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val isFocused by src.collectIsFocusedAsState()
    Box(Modifier.size(32.dp)) {
        Box(
            modifier
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .background(if (isFocused) TextMain else Color.White.copy(alpha = 0.10f))
                .clickable(interactionSource = src, indication = null) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            icon(if (isFocused) Bg else TextMain)
        }
        if (dot) {
            Box(Modifier.align(Alignment.TopEnd).size(9.dp).clip(RoundedCornerShape(50)).background(Color(0xFF7FB2FF)))
        }
    }
}

/** Einfaches Download-Symbol (Pfeil nach unten auf einer Ablage). */
@Composable
private fun DownloadIcon(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.12f
        drawLine(color, Offset(w / 2, h * 0.08f), Offset(w / 2, h * 0.62f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.25f, h * 0.40f), Offset(w / 2, h * 0.65f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.75f, h * 0.40f), Offset(w / 2, h * 0.65f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.12f, h * 0.90f), Offset(w * 0.88f, h * 0.90f), stroke, StrokeCap.Round)
    }
}

// ---------------------------------------------------------------- Startseite

@Composable
private fun StartScreen(
    rows: List<PlexRow>,
    status: String,
    focusState: MutableState<PlexItem?>,
    onFocusItem: (PlexItem) -> Unit,
    onRowFocused: (Int) -> Unit,
    openItem: (PlexItem, Boolean) -> Unit,
    bridge: FocusBridge
) {
    var focusedRow by remember { mutableIntStateOf(0) }
    var didInitialFocus by remember { mutableStateOf(false) }
    // Zuletzt gewählte Kachel je Zeile und die Fokus-Anker aller sichtbaren Kacheln
    val lastInRow = remember { mutableStateMapOf<Int, Int>() }
    val requesters = remember { mutableMapOf<Pair<Int, Int>, FocusRequester>() }

    fun focusRow(r: Int) {
        val idx = lastInRow[r] ?: 0
        val fr = requesters[r to idx]
            ?: requesters.entries.filter { it.key.first == r }.minByOrNull { it.key.second }?.value
        try { fr?.requestFocus() } catch (e: Exception) { }
    }

    bridge.toContent = { focusRow(focusedRow) }

    LaunchedEffect(rows.isNotEmpty()) {
        if (rows.isNotEmpty() && !didInitialFocus) {
            delay(150)
            focusRow(0)
            didInitialFocus = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                val ne = ev.nativeKeyEvent
                if (ne.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (ne.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (focusedRow > 0) focusRow(focusedRow - 1) else bridge.toTop()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (focusedRow < rows.size - 1) focusRow(focusedRow + 1)
                        true
                    }
                    else -> false
                }
            }
    ) {
        // Infos zum ausgewählten Titel, unten bündig über der aktiven Zeile
        Box(
            Modifier.offset(x = 48.dp, y = 66.dp).width(470.dp).height((FOCUS_Y - 66 - 10).dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Hero(focusState, status)
        }

        rows.forEachIndexed { i, row -> key(row.title) {
            val target: Int = when {
                i == focusedRow -> FOCUS_Y
                i < focusedRow -> PREV_Y - ROW_STEP * (focusedRow - 1 - i)
                else -> FOCUS_Y + ROW_STEP * (i - focusedRow)
            }
            val y by animateDpAsState(target.dp, tween(420, easing = FastOutSlowInEasing), label = "rowY$i")
            val alpha by animateFloatAsState(
                when {
                    i == focusedRow -> 1f
                    i == focusedRow - 1 -> 0.45f
                    i == focusedRow + 1 -> 0.9f
                    else -> 0f
                },
                tween(380),
                label = "rowA$i"
            )
            ContentRow(
                row = row,
                rowIndex = i,
                requesters = requesters,
                modifier = Modifier
                    .offset { IntOffset(0, y.roundToPx()) }
                    .graphicsLayer { this.alpha = alpha }
                    .onFocusChanged {
                        if (it.hasFocus && focusedRow != i) {
                            focusedRow = i
                            onRowFocused(i)
                        }
                    },
                onFocus = { item, idx ->
                    lastInRow[i] = idx
                    onFocusItem(item)
                },
                onClick = { openItem(it, false) },
                onLongClick = { openItem(it, true) }
            )
        } }
    }
}

// ---------------------------------------------------------------- Infos (Logo, Daten, Beschreibung)

@Composable
fun Hero(focusState: MutableState<PlexItem?>, status: String) {
    val item = focusState.value
    if (item == null) {
        BasicText(status, style = TextStyle(color = TextSoft, fontSize = 16.sp))
        return
    }
    Crossfade(targetState = item, animationSpec = tween(260), label = "hero", modifier = Modifier.fillMaxSize()) { cur ->
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            TitleOrLogo(cur)
            if (cur.subtitle.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                BasicText(cur.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = TextSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            }
            Spacer(Modifier.height(10.dp))
            MetaRow(cur)
            if (cur.progress > 0f) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(180.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.22f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(cur.progress).background(TextMain))
                    }
                    Spacer(Modifier.width(10.dp))
                    BasicText("Noch ${formatDuration(cur.durationMs - cur.viewOffsetMs)}", style = TextStyle(color = TextSoft, fontSize = 12.sp))
                }
            }
            if (cur.summary.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                BasicText(cur.summary, maxLines = 3, overflow = TextOverflow.Ellipsis, style = TextStyle(color = TextSoft, fontSize = 13.sp, lineHeight = 18.sp))
            }
        }
    }
}

@Composable
private fun TitleOrLogo(item: PlexItem) {
    var logoFailed by remember(item.logoUrl) { mutableStateOf(item.logoUrl == null) }
    if (!logoFailed && item.logoUrl != null) {
        AsyncImage(
            model = item.logoUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onState = { if (it is AsyncImagePainter.State.Error) logoFailed = true },
            modifier = Modifier.height(84.dp).widthIn(max = 340.dp).fillMaxWidth()
        )
    } else {
        Box(Modifier.height(84.dp).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
            BasicText(
                item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 37.sp)
            )
        }
    }
}

@Composable
private fun MetaRow(item: PlexItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (item.contentRating.isNotEmpty()) { Badge(item.contentRating); Spacer(Modifier.width(8.dp)) }
        val parts = listOfNotNull(
            item.year.ifEmpty { null },
            item.genres.ifEmpty { null },
            if (item.durationMs > 0) formatDuration(item.durationMs) else null,
            if (item.rating.isNotEmpty()) "★ ${item.rating}" else null
        )
        if (parts.isNotEmpty()) {
            BasicText(parts.joinToString("  ·  "), maxLines = 1, style = TextStyle(color = TextSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        }
        if (item.resolution.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Badge(item.resolution) }
        if (item.audio.isNotEmpty()) { Spacer(Modifier.width(6.dp)); Badge(item.audio) }
    }
}

private fun formatDuration(ms: Long): String {
    val min = (ms / 60000).toInt()
    return if (min >= 60) "${min / 60} Std. ${min % 60} Min." else "$min Min."
}

@Composable
fun Badge(text: String) {
    Box(Modifier.border(1.dp, TextMain.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        BasicText(text, style = TextStyle(color = TextMain, fontSize = 10.sp, fontWeight = FontWeight.Bold))
    }
}

// ---------------------------------------------------------------- Zeilen

/** Hält die ausgewählte Kachel an fester Position, die Zeile gleitet darunter durch. */
@Composable
private fun rememberPivotSpec(pivot: Dp): BringIntoViewSpec {
    val px = with(LocalDensity.current) { pivot.toPx() }
    return remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - px
        }
    }
}

@Composable
private fun ContentRow(
    row: PlexRow,
    rowIndex: Int,
    requesters: MutableMap<Pair<Int, Int>, FocusRequester>,
    modifier: Modifier,
    onFocus: (PlexItem, Int) -> Unit,
    onClick: (PlexItem) -> Unit,
    onLongClick: (PlexItem) -> Unit
) {
    // Ausgewählte Kachel an zweiter Stelle: die vorherige ist links noch angeschnitten sichtbar
    val pivot = rememberPivotSpec(118.dp)
    Column(modifier.fillMaxWidth()) {
        BasicText(row.title, modifier = Modifier.padding(start = 48.dp), style = TextStyle(color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 44.dp, end = 600.dp, top = 4.dp, bottom = 2.dp)
            ) {
                itemsIndexed(row.items, key = { i, it -> "${it.serverId}/${it.ratingKey}/$i" }) { index, item ->
                    val fr = remember { FocusRequester() }
                    DisposableEffect(rowIndex, index) {
                        requesters[rowIndex to index] = fr
                        onDispose { requesters.remove(rowIndex to index) }
                    }
                    LandscapeCard(
                        item = item,
                        modifier = Modifier.focusRequester(fr),
                        onFocus = { onFocus(it, index) },
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                }
            }
        }
    }
}

/** Breite Kachel; ausgewählt mit abgesetztem weissem Rahmen wie bei Dispatch. */
@Composable
private fun LandscapeCard(
    item: PlexItem,
    modifier: Modifier,
    onFocus: (PlexItem) -> Unit,
    onClick: (PlexItem) -> Unit,
    onLongClick: (PlexItem) -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val isFocused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.06f else 1f, tween(200, easing = FastOutSlowInEasing), label = "scale")
    val ring by animateFloatAsState(if (isFocused) 1f else 0f, tween(200), label = "ring")
    val inner = RoundedCornerShape(8.dp)
    val outer = RoundedCornerShape(12.dp)
    Column(modifier.width(184.dp)) {
        Box(
            Modifier
                .size(184.dp, 107.dp)
                .onFocusChanged { if (it.isFocused) onFocus(item) }
                .combinedClickable(interactionSource = src, indication = null, onLongClick = { onLongClick(item) }, onClick = { onClick(item) })
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .border(2.5.dp, TextMain.copy(alpha = ring), outer)
                    .padding(4.dp)
                    .clip(inner)
                    .background(Tile)
            ) {
                val url = item.landscapeUrl ?: item.posterUrl
                if (url != null) {
                    AsyncImage(model = url, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (item.progress > 0f) {
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(7.dp).height(3.dp)
                            .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))
                    ) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(item.progress).background(TextMain))
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        BasicText(
            if (item.subtitle.isNotEmpty()) "${item.title} · ${item.subtitle}" else item.title,
            modifier = Modifier.padding(horizontal = 4.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (isFocused) TextMain else TextDim, fontSize = 11.sp)
        )
    }
}

// ---------------------------------------------------------------- Filme / Serien

@Composable
private fun LibraryScreen(
    repo: PlexRepo,
    servers: List<PlexServer>,
    type: String,
    allTitle: String,
    onFocusItem: (PlexItem) -> Unit,
    openItem: (PlexItem, Boolean) -> Unit,
    bridge: FocusBridge,
    onScrolled: (Boolean) -> Unit
) {
    var lastGridIdx by remember(type) { mutableIntStateOf(-1) }
    var letterMode by remember(type) { mutableStateOf(false) }
    val letterReqs = remember(type) { (listOf("#") + ('A'..'Z').map { it.toString() }).associateWith { FocusRequester() } }
    val scope = rememberCoroutineScope()
    val cached = remember(type) { repo.loadLibraryCache(type) }
    var newest by remember(type) { mutableStateOf(cached.first) }
    var all by remember(type) { mutableStateOf(cached.second) }
    var loading by remember(type) { mutableStateOf(all.isEmpty()) }
    val gridState = rememberLazyGridState()
    val firstFocus = remember(type) { FocusRequester() }
    val requesters = remember(type) { mutableStateMapOf<Int, FocusRequester>() }
    val pivot = rememberPivotSpec(8.dp)
    val density = LocalDensity.current
    val gridSpec = remember(density) {
        val topPx = with(density) { 64.dp.toPx() }
        val bottomPx = with(density) { 24.dp.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                offset < topPx -> offset - topPx
                offset + size > containerSize - bottomPx -> offset + size - (containerSize - bottomPx)
                else -> 0f
            }
        }
    }

    LaunchedEffect(type, servers) {
        if (servers.isEmpty()) return@LaunchedEffect
        try {
            val (n, a) = repo.loadLibrary(servers, type)
            if (a.isNotEmpty()) {
                newest = n
                all = a
                withContext(Dispatchers.IO) { repo.saveLibraryCache(type, n, a) }
            }
        } catch (e: Exception) {
        }
        loading = false
    }
    var didFocus by remember(type) { mutableStateOf(false) }
    LaunchedEffect(type, newest.isNotEmpty() || all.isNotEmpty()) {
        if (!didFocus && (newest.isNotEmpty() || all.isNotEmpty())) {
            delay(150)
            try { firstFocus.requestFocus(); didFocus = true } catch (e: Exception) { }
        }
    }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex > 0 }.collect { onScrolled(it) }
    }

    val letters = remember(all) { listOf("#") + ('A'..'Z').map { it.toString() } }
    val headerCount = 2

    fun focusGrid() {
        val fr = requesters[lastGridIdx]
            ?: requesters.keys.minOrNull()?.let { requesters[it] }
        try { if (fr != null) fr.requestFocus() else firstFocus.requestFocus() } catch (e: Exception) { }
    }
    bridge.toContent = { if (lastGridIdx >= 0 && requesters[lastGridIdx] != null) focusGrid() else try { firstFocus.requestFocus() } catch (e: Exception) { } }

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides gridSpec) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 48.dp, end = 64.dp, top = 70.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.onPreviewKeyEvent { ev ->
                    val ne = ev.nativeKeyEvent
                    if (ne.action == AndroidKeyEvent.ACTION_DOWN && ne.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) { bridge.toTop(); true } else false
                }) {
                    BasicText("Neu", style = TextStyle(color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(4.dp))
                    CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(start = 8.dp, end = 600.dp, top = 8.dp, bottom = 6.dp)
                        ) {
                            itemsIndexed(newest, key = { i, it -> "n/${it.serverId}/${it.ratingKey}/$i" }) { index, item ->
                                LandscapeCard(
                                    item = item,
                                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                    onFocus = onFocusItem,
                                    onClick = { openItem(it, false) },
                                    onLongClick = { openItem(it, true) }
                                )
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText(allTitle, style = TextStyle(color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.width(10.dp))
                    BasicText(
                        if (loading && all.isEmpty()) "wird geladen …" else "${all.size} Titel",
                        style = TextStyle(color = TextDim, fontSize = 12.sp)
                    )
                }
            }
            itemsIndexed(all, key = { i, it -> "a/${it.serverId}/${it.ratingKey}/$i" }) { index, item ->
                val fr = remember { FocusRequester() }
                DisposableEffect(index) {
                    requesters[index] = fr
                    onDispose { requesters.remove(index) }
                }
                PosterCard(
                    item = item,
                    modifier = Modifier.focusRequester(fr).onPreviewKeyEvent { ev ->
                        val ne = ev.nativeKeyEvent
                        if (ne.action == AndroidKeyEvent.ACTION_DOWN && ne.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT && index % 7 == 6) {
                            letterMode = true
                            scope.launch {
                                delay(30)
                                try { letterReqs[item.letter]?.requestFocus() } catch (e: Exception) { }
                            }
                            true
                        } else false
                    },
                    onFocus = { lastGridIdx = index; onFocusItem(it) },
                    onClick = { openItem(it, false) },
                    onLongClick = { openItem(it, true) }
                )
            }
        }
        }

        // A–Z-Leiste
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 14.dp, top = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEach { l ->
                LetterButton(
                    l,
                    Modifier
                        .focusRequester(letterReqs.getValue(l))
                        .focusProperties { canFocus = letterMode }
                        .onPreviewKeyEvent { ev ->
                            val ne = ev.nativeKeyEvent
                            if (ne.action == AndroidKeyEvent.ACTION_DOWN && ne.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                                focusGrid()
                                letterMode = false
                                true
                            } else false
                        }
                ) {
                    val idx = all.indexOfFirst { it.letter == l }
                    if (idx >= 0) {
                        scope.launch {
                            gridState.scrollToItem(idx + headerCount)
                            delay(80)
                            try { requesters[idx]?.requestFocus() } catch (e: Exception) { }
                            letterMode = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LetterButton(letter: String, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val isFocused by src.collectIsFocusedAsState()
    Box(
        modifier
            .size(18.dp, 15.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isFocused) TextMain else Color.Transparent)
            .clickable(interactionSource = src, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        BasicText(letter, style = TextStyle(color = if (isFocused) Bg else TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun PosterCard(
    item: PlexItem,
    modifier: Modifier,
    onFocus: (PlexItem) -> Unit,
    onClick: (PlexItem) -> Unit,
    onLongClick: (PlexItem) -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val isFocused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, tween(160), label = "pscale")
    val shape = RoundedCornerShape(7.dp)
    Column(modifier) {
        // Äussere Box: Fokus und Klick, unverändert in der Grösse (kein Hüpfen beim Scrollen)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .onFocusChanged { if (it.isFocused) onFocus(item) }
                .combinedClickable(interactionSource = src, indication = null, onLongClick = { onLongClick(item) }, onClick = { onClick(item) })
        ) {
            // Innere Box: nur die sichtbare Vergrösserung
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(shape)
                    .background(Tile)
                    .border(2.dp, if (isFocused) TextMain else Color.Transparent, shape)
            ) {
                if (item.posterUrl != null) {
                    AsyncImage(model = item.posterUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    BasicText(item.title, modifier = Modifier.padding(6.dp), style = TextStyle(color = TextDim, fontSize = 11.sp))
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        BasicText(
            item.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (isFocused) TextMain else TextDim, fontSize = 10.sp)
        )
    }
}

// ---------------------------------------------------------------- Alle Apps

@Composable
fun AllAppsOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    val apps = remember { loadApps(context) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        try { first.requestFocus() } catch (e: Exception) { }
    }
    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.96f)).padding(48.dp)) {
        Column {
            BasicText("Alle Apps", style = TextStyle(color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(20.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(8.dp)
            ) {
                itemsIndexed(apps) { idx, app ->
                    AppTile(app, context, if (idx == 0) Modifier.focusRequester(first) else Modifier) {
                        onClose()
                        launchApp(context, listOf(app.activityInfo.packageName))
                    }
                }
            }
        }
    }
}

@Composable
fun AppTile(app: ResolveInfo, context: Context, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val isFocused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, animationSpec = tween(150), label = "appScale")
    val pm = context.packageManager
    val bitmap = remember(app) {
        try {
            val d = app.activityInfo.loadBanner(pm) ?: app.activityInfo.applicationInfo.loadBanner(pm) ?: app.loadIcon(pm)
            d.toBitmap(320, 180).asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape)
                .background(Tile)
                .border(2.dp, if (isFocused) TextMain else Color.Transparent, shape)
                .clickable(interactionSource = src, indication = null) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(6.dp))
        BasicText(app.loadLabel(pm).toString(), maxLines = 1, style = TextStyle(color = if (isFocused) TextMain else TextDim, fontSize = 12.sp))
    }
}

private fun loadApps(context: Context): List<ResolveInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .sortedBy { it.loadLabel(pm).toString().lowercase() }
}

// ---------------------------------------------------------------- Apps starten

fun launchApp(context: Context, packages: List<String>): Boolean {
    val pm = context.packageManager
    for (p in packages) {
        val i = pm.getLeanbackLaunchIntentForPackage(p) ?: pm.getLaunchIntentForPackage(p) ?: continue
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return true
    }
    Toast.makeText(context, "App nicht installiert.", Toast.LENGTH_SHORT).show()
    return false
}

/** Öffnet SmartTube, falls installiert, sonst die normale YouTube-App. */
fun launchYouTube(context: Context) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val smart = pm.queryIntentActivities(intent, 0).firstOrNull {
        it.loadLabel(pm).toString().contains("SmartTube", ignoreCase = true) ||
            it.activityInfo.packageName.contains("smarttube", ignoreCase = true)
    }
    val pkgs = mutableListOf<String>()
    if (smart != null) pkgs += smart.activityInfo.packageName
    pkgs += "com.google.android.youtube.tv"
    launchApp(context, pkgs)
}
