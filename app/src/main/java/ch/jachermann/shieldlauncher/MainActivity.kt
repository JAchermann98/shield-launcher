@file:OptIn(ExperimentalFoundationApi::class)

package ch.jachermann.shieldlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.unit.coerceAtLeast
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0A0C10)
private val TextMain = Color(0xFFF2F4F7)
private val TextSoft = Color(0xFFC9D3E0)
private val TextDim = Color(0xFFA9B4C4)
private val Tile = Color(0xFF1C222C)

private enum class Tab { START, FILME, SERIEN, YOUTUBE, YTSEARCH }

private val Tab.isYouTube: Boolean get() = this == Tab.YOUTUBE || this == Tab.YTSEARCH

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
    private var homeCount by mutableIntStateOf(0)
    private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = PlexRepo(this)
        setContent {
            LauncherApp(repo, resumeCount, homeCount)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home-Taste, während der Launcher schon sichtbar ist: zur Startseite des Bereichs.
        // Kommt man aus einer anderen App zurück, bleibt alles, wie es war.
        if (intent.hasCategory(Intent.CATEGORY_HOME) && visible) homeCount++
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }
}

// ---------------------------------------------------------------- App

@Composable
fun LauncherApp(repo: PlexRepo, resumeCount: Int, homeCount: Int) {
    var loggedIn by remember { mutableStateOf(repo.userToken != null) }
    Box(Modifier.fillMaxSize().background(Bg)) {
        if (!loggedIn) {
            LoginScreen(repo) { loggedIn = true }
        } else {
            HomeRoot(repo, resumeCount, homeCount)
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
fun HomeRoot(repo: PlexRepo, resumeCount: Int, homeCount: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf(repo.loadServers()) }
    var rows by remember { mutableStateOf(repo.loadCache()) }
    var status by remember { mutableStateOf(if (rows.isEmpty()) "Lade Inhalte von Plex …" else "") }
    var lastRefresh by remember { mutableLongStateOf(0L) }
    val focusState = remember { mutableStateOf(rows.firstOrNull()?.items?.firstOrNull()) }
    var tab by remember { mutableStateOf(Tab.START) }
    val tabState = rememberUpdatedState(tab)
    val tabFirst = remember { mutableStateMapOf<Tab, PlexItem>() }
    var focusedRow by remember { mutableIntStateOf(0) }
    var libScrolled by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showUpdates by remember { mutableStateOf(false) }
    val bridge = remember { FocusBridge() }
    val topReq = remember { listOf("plex", "yt", "apps", "start", "filme", "serien", "ytstart", "ytsearch", "view", "update", "settings").associateWith { FocusRequester() } }
    // Ansicht für Filme/Serien: Bildansicht (false) oder Rasteransicht (true), getrennt gemerkt
    val uiPrefs = remember { context.getSharedPreferences("launcher_ui", Context.MODE_PRIVATE) }
    var gridMovies by remember { mutableStateOf(uiPrefs.getBoolean("grid_movie", false)) }
    var gridShows by remember { mutableStateOf(uiPrefs.getBoolean("grid_show", false)) }
    var gridStart by remember { mutableStateOf(uiPrefs.getBoolean("grid_start", false)) }
    var gridYt by remember { mutableStateOf(uiPrefs.getBoolean("grid_youtube", false)) }
    val gridMode = when (tab) { Tab.FILME -> gridMovies; Tab.SERIEN -> gridShows; Tab.START -> gridStart; Tab.YOUTUBE, Tab.YTSEARCH -> gridYt }
    val ytRepo = remember { YouTubeRepo(context) }
    val toggleView: () -> Unit = {
        when (tab) {
            Tab.FILME -> { gridMovies = !gridMovies; uiPrefs.edit().putBoolean("grid_movie", gridMovies).apply() }
            Tab.SERIEN -> { gridShows = !gridShows; uiPrefs.edit().putBoolean("grid_show", gridShows).apply() }
            Tab.YOUTUBE, Tab.YTSEARCH -> { gridYt = !gridYt; uiPrefs.edit().putBoolean("grid_youtube", gridYt).apply() }
            Tab.START -> { gridStart = !gridStart; uiPrefs.edit().putBoolean("grid_start", gridStart).apply() }
        }
    }
    var lastTop by remember { mutableStateOf<String?>(null) }
    // Nur beim Start der App bzw. nach der Home-Taste springt die Auswahl automatisch in die Zeilen
    var autoFocusStart by remember { mutableStateOf(true) }
    bridge.toTop = {
        val k = lastTop ?: when (tab) { Tab.START -> "start"; Tab.FILME -> "filme"; Tab.SERIEN -> "serien"; Tab.YOUTUBE -> "ytstart"; Tab.YTSEARCH -> "ytsearch" }
        try { topReq[k]?.requestFocus() } catch (e: Exception) { }
    }

    LaunchedEffect(homeCount) {
        if (homeCount > 0) {
            showApps = false
            showUpdates = false
            val home = if (tab.isYouTube) Tab.YOUTUBE else Tab.START
            if (home == Tab.START) autoFocusStart = true
            if (tab != home) {
                tab = home
                focusedRow = 0
                libScrolled = false
                lastTop = null
            }
        }
    }

    BackHandler(enabled = true) {
        when {
            showApps -> showApps = false
            showUpdates -> showUpdates = false
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
    // Bei Serien/Staffeln Qualität und Ton aus einer Folge nachladen
    val serversState = rememberUpdatedState(servers)
    val mediaCache = remember { mutableMapOf<String, Pair<String, String>>() }
    val onFocusItemBase: (PlexItem) -> Unit = remember {
        { item ->
            focusState.value = item
            if ((item.type == "show" || item.type == "season") && item.audio.isEmpty() && !item.fromWatchlist) {
                val k = "${item.serverId}/${item.ratingKey}"
                val cachedMedia = mediaCache[k]
                if (cachedMedia != null) {
                    focusState.value = item.copy(resolution = cachedMedia.first.ifEmpty { item.resolution }, audio = cachedMedia.second)
                } else {
                    scope.launch {
                        delay(250)
                        if (focusState.value?.ratingKey != item.ratingKey) return@launch
                        val m = repo.leafMedia(item, serversState.value) ?: return@launch
                        mediaCache[k] = m
                        if (focusState.value?.ratingKey == item.ratingKey) {
                            focusState.value = item.copy(resolution = m.first.ifEmpty { item.resolution }, audio = m.second)
                        }
                    }
                }
            }
        }
    }

    val topBarVisible = if (tab == Tab.START || tab.isYouTube) focusedRow == 0 else !libScrolled
    val topAlpha by animateFloatAsState(if (topBarVisible) 1f else 0f, tween(300), label = "topAlpha")

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { ev ->
            val ne = ev.nativeKeyEvent
            if (ne.action == AndroidKeyEvent.ACTION_DOWN && ne.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                toggleView(); true
            } else false
        }
    ) {
        Backdrop(focusState, dim = gridMode)

        // Beim Reiterwechsel gleich das Bild der neuen Seite zeigen, nicht das der alten Auswahl
        LaunchedEffect(tab) {
            val first = if (tab == Tab.START) rows.firstOrNull()?.items?.firstOrNull() else tabFirst[tab]
            focusState.value = first
        }

        // Weicher Seitenwechsel: neue Seite gleitet aus der Wechselrichtung herein und blendet auf,
        // die alte blendet zügig aus und rückt leicht zur Seite
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                val enter = fadeIn(tween(380, delayMillis = 80, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(tween(460, easing = FastOutSlowInEasing)) { w -> dir * w / 10 } +
                    scaleIn(tween(460, easing = FastOutSlowInEasing), initialScale = 0.985f)
                val exit = fadeOut(tween(220, easing = FastOutSlowInEasing)) +
                    slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> -dir * w / 16 }
                enter togetherWith exit
            },
            label = "tabs"
        ) { t ->
            val active = t == tab
            // Nur die sichtbare Seite darf das Hintergrundbild setzen
            val onFocusItem: (PlexItem) -> Unit = remember(t) {
                { item ->
                    if (t == tabState.value) {
                        if (t != Tab.START && t != Tab.YTSEARCH && tabFirst[t] == null) tabFirst[t] = item
                        onFocusItemBase(item)
                    }
                }
            }
            when (t) {
                Tab.START -> StartScreen(
                    rows = rows,
                    status = status,
                    focusState = focusState,
                    onFocusItem = onFocusItem,
                    onRowFocused = { focusedRow = it },
                    openItem = openItem,
                    bridge = bridge,
                    active = active,
                    autoFocus = autoFocusStart,
                    onAutoFocused = { autoFocusStart = false },
                    gridMode = gridStart
                )
                Tab.FILME -> LibraryScreen(repo, servers, "movie", "Alle Filme", focusState, onFocusItem, openItem, bridge, active, gridMovies) { libScrolled = it }
                Tab.SERIEN -> LibraryScreen(repo, servers, "show", "Alle Serien", focusState, onFocusItem, openItem, bridge, active, gridShows) { libScrolled = it }
                Tab.YOUTUBE -> YouTubeScreen(
                    yt = ytRepo,
                    repo = repo,
                    resumeCount = resumeCount,
                    focusState = focusState,
                    onFocusItem = onFocusItem,
                    onRowFocused = { focusedRow = it },
                    bridge = bridge,
                    active = active,
                    gridMode = gridYt
                )
                Tab.YTSEARCH -> YouTubeSearchScreen(
                    yt = ytRepo,
                    focusState = focusState,
                    onFocusItem = onFocusItem,
                    onRowFocused = { focusedRow = it },
                    bridge = bridge,
                    active = active,
                    gridMode = gridYt
                )
            }
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
                onDown = { bridge.toContent() },
                gridMode = gridMode,
                onToggleView = toggleView,
                onUpdates = { showUpdates = true }
            )
        }

        if (showApps) {
            AllAppsOverlay(onClose = { showApps = false })
        }
        if (showUpdates) {
            UpdatesOverlay(onClose = { showUpdates = false })
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
    onDown: () -> Unit,
    gridMode: Boolean,
    onToggleView: () -> Unit,
    onUpdates: () -> Unit
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
        try {
            if (!updateAvailable) updateAvailable = Updater.checkSmartTube(context).updateAvailable
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
        // Bereiche: Plex (Start/Filme/Serien) und YouTube. Erneutes Drücken auf YouTube öffnet SmartTube selbst.
        PillButton("Plex", m("plex"), selected = !tab.isYouTube) { if (tab.isYouTube) onTab(Tab.START) }
        Spacer(Modifier.width(6.dp))
        PillButton("YouTube", m("yt"), selected = tab.isYouTube) { if (tab.isYouTube) launchYouTube(context) else onTab(Tab.YOUTUBE) }
        Spacer(Modifier.width(6.dp))
        PillButton("Alle Apps", m("apps")) { onApps() }
        Spacer(Modifier.weight(1f))
        if (tab.isYouTube) {
            TabButton("Start", tab == Tab.YOUTUBE, m("ytstart")) { onTab(Tab.YOUTUBE) }
            Spacer(Modifier.width(4.dp))
            TabButton("Suche", tab == Tab.YTSEARCH, m("ytsearch")) { onTab(Tab.YTSEARCH) }
        }
        if (!tab.isYouTube) TabButton("Start", tab == Tab.START, m("start")) { onTab(Tab.START) }
        Spacer(Modifier.width(4.dp))
        if (!tab.isYouTube) TabButton("Filme", tab == Tab.FILME, m("filme")) { onTab(Tab.FILME) }
        Spacer(Modifier.width(4.dp))
        if (!tab.isYouTube) TabButton("Serien", tab == Tab.SERIEN, m("serien")) { onTab(Tab.SERIEN) }
        // Umschalter Bild-/Rasteransicht (für jede Seite getrennt gemerkt)
        Spacer(Modifier.width(8.dp))
        IconButton(dot = false, modifier = m("view"), onClick = onToggleView) { color ->
            if (gridMode) ImageViewIcon(color) else GridIcon(color)
        }
        Spacer(Modifier.width(22.dp))
        Box(Modifier.width(1.dp).height(20.dp).background(Color.White.copy(alpha = 0.25f)))
        Spacer(Modifier.width(16.dp))
        IconButton(dot = updateAvailable, modifier = m("update"), onClick = onUpdates) { color -> DownloadIcon(color) }
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
fun PillButton(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val isFocused by src.collectIsFocusedAsState()
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    isFocused -> TextMain
                    selected -> Color.White.copy(alpha = 0.28f)
                    else -> Color.White.copy(alpha = 0.10f)
                }
            )
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

/** Raster-Symbol (vier Kacheln): wechselt zur Rasteransicht. */
@Composable
private fun GridIcon(color: Color) {
    Canvas(Modifier.size(15.dp)) {
        val g = size.width * 0.14f
        val c = (size.width - g) / 2
        for (x in 0..1) for (y in 0..1) {
            drawRoundRect(color, Offset(x * (c + g), y * (c + g)), androidx.compose.ui.geometry.Size(c, c), androidx.compose.ui.geometry.CornerRadius(c * 0.2f))
        }
    }
}

/** Bild-Symbol (Rahmen mit grossem Bild und Leiste): wechselt zur Bildansicht. */
@Composable
private fun ImageViewIcon(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(color, Offset(0f, h * 0.08f), androidx.compose.ui.geometry.Size(w, h * 0.56f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f))
        val bw = (w - w * 0.2f) / 3
        for (i in 0..2) {
            drawRoundRect(color, Offset(i * (bw + w * 0.1f), h * 0.72f), androidx.compose.ui.geometry.Size(bw, h * 0.22f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
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
    bridge: FocusBridge,
    active: Boolean,
    autoFocus: Boolean,
    onAutoFocused: () -> Unit,
    gridMode: Boolean
) {
    // Rasteransicht: Infos ausblenden, Zeilen rücken unter die Kopfzeile, mehrere Zeilen sichtbar
    val focusY by animateDpAsState(if (gridMode) 76.dp else FOCUS_Y.dp, tween(480, easing = FastOutSlowInEasing), label = "focusY")
    val heroAlpha by animateFloatAsState(if (gridMode) 0f else 1f, tween(300), label = "startHero")
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

    if (active) bridge.toContent = { focusRow(focusedRow) }

    LaunchedEffect(rows.isNotEmpty(), autoFocus) {
        if (rows.isNotEmpty() && active && autoFocus) {
            delay(150)
            focusRow(0)
            didInitialFocus = true
            onAutoFocused()
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
            Modifier.offset(x = 48.dp, y = 66.dp).width(470.dp).height((FOCUS_Y - 66 - 10).dp)
                .graphicsLayer { alpha = heroAlpha },
            contentAlignment = Alignment.BottomStart
        ) {
            Hero(focusState, status)
        }

        rows.forEachIndexed { i, row -> key(row.title) {
            // Lage relativ zur aktiven Zeile; die aktive Zeile selbst steht bei focusY
            val rel: Int = when {
                i == focusedRow -> 0
                i < focusedRow -> (PREV_Y - FOCUS_Y) - ROW_STEP * (focusedRow - 1 - i)
                else -> ROW_STEP * (i - focusedRow)
            }
            val relY by animateDpAsState(rel.dp, tween(420, easing = FastOutSlowInEasing), label = "rowY$i")
            val y = focusY + relY
            val alpha by animateFloatAsState(
                when {
                    i == focusedRow -> 1f
                    i == focusedRow - 1 -> if (gridMode) 0f else 0.45f
                    i == focusedRow + 1 -> 0.9f
                    i == focusedRow + 2 && gridMode -> 0.85f
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
fun Hero(focusState: MutableState<PlexItem?>, status: String, compact: Boolean = false) {
    val item = focusState.value
    if (item == null) {
        BasicText(status, style = TextStyle(color = TextSoft, fontSize = 16.sp))
        return
    }
    // Überblenden nur beim Wechsel des Titels, nicht wenn Details (z. B. Qualität) nachgeladen werden
    val byKey = remember { mutableStateMapOf<String, PlexItem>() }
    val itemKey = "${item.serverId}/${item.ratingKey}"
    byKey[itemKey] = item
    Crossfade(
        targetState = itemKey,
        animationSpec = tween(260),
        label = "hero",
        modifier = Modifier.fillMaxSize()
    ) { k ->
        val cur = byKey[k] ?: item
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            TitleOrLogo(cur, if (compact) 62.dp else 84.dp)
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
                BasicText(cur.summary, maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis, style = TextStyle(color = TextSoft, fontSize = 13.sp, lineHeight = 18.sp))
            }
        }
    }
}

@Composable
private fun TitleOrLogo(item: PlexItem, height: Dp) {
    var logoFailed by remember(item.logoUrl) { mutableStateOf(item.logoUrl == null) }
    if (!logoFailed && item.logoUrl != null) {
        AsyncImage(
            model = item.logoUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onState = { if (it is AsyncImagePainter.State.Error) logoFailed = true },
            modifier = Modifier.height(height).widthIn(max = 340.dp).fillMaxWidth()
        )
    } else {
        Box(Modifier.height(height).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
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

// Layout der Filme-/Serien-Seite (dp): oben Bild und Infos, unten das Fenster mit den Reihen
private const val LIB_WINDOW_TOP_IMAGE = 300 // Bildansicht: wie auf der Startseite
private const val LIB_WINDOW_TOP_GRID = 70   // Rasteransicht: Reihen direkt unter der Kopfzeile
private const val LIB_COLS = 7
private const val LIB_NEW_H = 152        // Höhe der Neu-Reihe
private const val LIB_FIRST_POSTER_H = 216 // erste Posterreihe inkl. Überschrift
private const val LIB_POSTER_H = 194     // weitere Posterreihen

@Composable
private fun LibraryScreen(
    repo: PlexRepo,
    servers: List<PlexServer>,
    type: String,
    allTitle: String,
    focusState: MutableState<PlexItem?>,
    onFocusItem: (PlexItem) -> Unit,
    openItem: (PlexItem, Boolean) -> Unit,
    bridge: FocusBridge,
    active: Boolean,
    gridMode: Boolean,
    onScrolled: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val windowTop by animateDpAsState(
        (if (gridMode) LIB_WINDOW_TOP_GRID else LIB_WINDOW_TOP_IMAGE).dp,
        tween(480, easing = FastOutSlowInEasing),
        label = "windowTop"
    )
    val heroAlpha by animateFloatAsState(if (gridMode) 0f else 1f, tween(300), label = "heroAlpha")
    val cached = remember(type) { repo.loadLibraryCache(type) }
    var newest by remember(type) { mutableStateOf(cached.first) }
    var all by remember(type) { mutableStateOf(cached.second) }
    var loading by remember(type) { mutableStateOf(all.isEmpty()) }

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

    // Beim Öffnen gleich ein passendes Bild zeigen (ohne die Auswahl aus dem Reiter zu holen)
    LaunchedEffect(type, active, newest.isNotEmpty() || all.isNotEmpty()) {
        if (active) (newest.firstOrNull() ?: all.firstOrNull())?.let { onFocusItem(it) }
    }

    val hasNew = newest.isNotEmpty()
    val offsetRows = if (hasNew) 1 else 0
    val posterRows = (all.size + LIB_COLS - 1) / LIB_COLS
    val rowCount = offsetRows + posterRows

    var focusedRow by remember(type) { mutableIntStateOf(0) }
    var lastNeu by remember(type) { mutableIntStateOf(0) }
    var lastCol by remember(type) { mutableIntStateOf(0) }
    var letterMode by remember(type) { mutableStateOf(false) }
    val letters = remember { listOf("#") + ('A'..'Z').map { it.toString() } }
    val letterReqs = remember(type) { letters.associateWith { FocusRequester() } }
    val neuReqs = remember(type) { mutableMapOf<Int, FocusRequester>() }
    val posterReqs = remember(type) { mutableMapOf<Int, FocusRequester>() }

    LaunchedEffect(focusedRow) { onScrolled(focusedRow > 0) }

    /** Oberkante einer Reihe (dp), gemessen ab der ersten Reihe. */
    fun rowTop(r: Int): Int {
        var top = 0
        if (r <= 0) return 0
        if (hasNew) top += LIB_NEW_H
        val p = r - offsetRows
        if (p > 0) top += LIB_FIRST_POSTER_H + (p - 1) * LIB_POSTER_H
        return top
    }

    /** Reihe anwählen: Fenster gleitet hin, Auswahl bleibt in derselben Spalte. */
    fun focusRow(row: Int) {
        if (rowCount == 0) return
        val r = row.coerceIn(0, rowCount - 1)
        focusedRow = r
        scope.launch {
            delay(40) // eine Bildschirm-Aktualisierung abwarten, bis die Reihe da ist
            val fr = if (hasNew && r == 0) {
                neuReqs[lastNeu] ?: neuReqs.entries.minByOrNull { it.key }?.value
            } else {
                val pr = r - offsetRows
                val idx = (pr * LIB_COLS + lastCol).coerceAtMost(all.size - 1)
                posterReqs[idx]
            }
            try { fr?.requestFocus() } catch (e: Exception) { }
        }
    }

    if (active) bridge.toContent = { focusRow(focusedRow) }

    Box(Modifier.fillMaxSize()) {
        // Untere Hälfte dunkler, damit die Poster gut lesbar sind
        Box(
            Modifier.fillMaxSize().padding(top = (windowTop - 60.dp).coerceAtLeast(0.dp)).background(
                Brush.verticalGradient(0f to Color.Transparent, 0.25f to Bg.copy(alpha = 0.7f), 1f to Bg.copy(alpha = 0.92f))
            )
        )

        // Infos zum ausgewählten Titel
        Box(
            Modifier.offset(x = 48.dp, y = 62.dp).width(470.dp).height((LIB_WINDOW_TOP_IMAGE - 62 - 12).dp)
                .graphicsLayer { alpha = heroAlpha },
            contentAlignment = Alignment.BottomStart
        ) {
            Hero(focusState, if (loading) "Lade Mediathek …" else "")
        }

        // Fenster mit den Reihen
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = windowTop)
                .clipToBounds()
                .onPreviewKeyEvent { ev ->
                    val ne = ev.nativeKeyEvent
                    if (ne.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (ne.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            if (focusedRow == 0) bridge.toTop() else focusRow(focusedRow - 1)
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (focusedRow < rowCount - 1) focusRow(focusedRow + 1)
                            true
                        }
                        else -> false
                    }
                }
        ) {
            if (rowCount > 0) {
                val from = (focusedRow - 2).coerceAtLeast(0)
                val to = (focusedRow + 3).coerceAtMost(rowCount - 1)
                for (r in from..to) key(type, r) {
                    val target = 6 + rowTop(r) - rowTop(focusedRow)
                    val y by animateDpAsState(target.dp, tween(420, easing = FastOutSlowInEasing), label = "libY")
                    val a by animateFloatAsState(
                        when {
                            r < focusedRow -> 0f
                            r == focusedRow -> 1f
                            else -> 0.85f
                        },
                        tween(380),
                        label = "libA"
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .offset { IntOffset(0, y.roundToPx()) }
                            .graphicsLayer { alpha = a }
                    ) {
                        if (hasNew && r == 0) {
                            NewRow(newest, neuReqs, onFocus = { item, i ->
                                lastNeu = i
                                if (focusedRow != 0) focusedRow = 0
                                onFocusItem(item)
                            }, openItem = openItem)
                        } else {
                            val pr = r - offsetRows
                            Column(Modifier.padding(start = 48.dp, end = 64.dp)) {
                                if (pr == 0) {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        BasicText(allTitle, style = TextStyle(color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                                        Spacer(Modifier.width(10.dp))
                                        BasicText("${all.size} Titel", style = TextStyle(color = TextDim, fontSize = 12.sp))
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    for (c in 0 until LIB_COLS) {
                                        val idx = pr * LIB_COLS + c
                                        if (idx < all.size) {
                                            val item = all[idx]
                                            val fr = remember(idx) { FocusRequester() }
                                            DisposableEffect(idx) {
                                                posterReqs[idx] = fr
                                                onDispose { posterReqs.remove(idx) }
                                            }
                                            PosterCard(
                                                item = item,
                                                modifier = Modifier.weight(1f).focusRequester(fr).onPreviewKeyEvent { ev ->
                                                    val ne = ev.nativeKeyEvent
                                                    val atEnd = c == LIB_COLS - 1 || idx == all.size - 1
                                                    if (ne.action == AndroidKeyEvent.ACTION_DOWN && ne.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT && atEnd) {
                                                        letterMode = true
                                                        scope.launch {
                                                            delay(30)
                                                            try { letterReqs[item.letter]?.requestFocus() } catch (e: Exception) { }
                                                        }
                                                        true
                                                    } else false
                                                },
                                                onFocus = {
                                                    lastCol = c
                                                    if (focusedRow != r) focusedRow = r
                                                    onFocusItem(it)
                                                },
                                                onClick = { openItem(it, false) },
                                                onLongClick = { openItem(it, true) }
                                            )
                                        } else {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // A–Z-Leiste: nur über "rechts" aus der letzten Spalte erreichbar
        Column(
            Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 70.dp),
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
                                focusRow(focusedRow)
                                letterMode = false
                                true
                            } else false
                        }
                ) {
                    val idx = all.indexOfFirst { it.letter == l }
                    if (idx >= 0) {
                        lastCol = idx % LIB_COLS
                        focusRow(offsetRows + idx / LIB_COLS)
                        letterMode = false
                    }
                }
            }
        }
    }
}

@Composable
private fun NewRow(
    items: List<PlexItem>,
    requesters: MutableMap<Int, FocusRequester>,
    onFocus: (PlexItem, Int) -> Unit,
    openItem: (PlexItem, Boolean) -> Unit
) {
    val pivot = rememberPivotSpec(118.dp)
    Column(Modifier.fillMaxWidth()) {
        BasicText("Neu", modifier = Modifier.padding(start = 48.dp), style = TextStyle(color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 44.dp, end = 600.dp, top = 4.dp, bottom = 2.dp)
            ) {
                itemsIndexed(items, key = { i, it -> "n/${it.serverId}/${it.ratingKey}/$i" }) { index, item ->
                    val fr = remember { FocusRequester() }
                    DisposableEffect(index) {
                        requesters[index] = fr
                        onDispose { requesters.remove(index) }
                    }
                    LandscapeCard(
                        item = item,
                        modifier = Modifier.focusRequester(fr),
                        onFocus = { onFocus(it, index) },
                        onClick = { openItem(it, false) },
                        onLongClick = { openItem(it, true) }
                    )
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

// ---------------------------------------------------------------- YouTube

@Composable
private fun YouTubeScreen(
    yt: YouTubeRepo,
    repo: PlexRepo,
    resumeCount: Int,
    focusState: MutableState<PlexItem?>,
    onFocusItem: (PlexItem) -> Unit,
    onRowFocused: (Int) -> Unit,
    bridge: FocusBridge,
    active: Boolean,
    gridMode: Boolean
) {
    val context = LocalContext.current
    var loggedIn by remember { mutableStateOf(yt.loggedIn) }
    if (!loggedIn) {
        YouTubeLogin(yt) { loggedIn = true }
        return
    }
    var rows by remember { mutableStateOf(repo.loadRowsFile("youtube_rows.json")) }
    var status by remember { mutableStateOf(if (rows.isEmpty()) "Lade YouTube …" else "") }
    var lastLoad by remember { mutableLongStateOf(0L) }

    LaunchedEffect(resumeCount) {
        if (System.currentTimeMillis() - lastLoad < 30_000) return@LaunchedEffect
        lastLoad = System.currentTimeMillis()
        try {
            val r = yt.loadRows()
            if (r.isNotEmpty()) {
                rows = r
                status = ""
                withContext(Dispatchers.IO) { repo.saveRowsFile("youtube_rows.json", r) }
            } else if (rows.isEmpty()) {
                status = "Keine Videos gefunden. Bitte später nochmals versuchen."
            }
        } catch (e: Exception) {
            if (rows.isEmpty()) status = "YouTube konnte nicht geladen werden: ${e.message}"
        }
    }
    // Beim Öffnen das erste Video als Hintergrund zeigen
    LaunchedEffect(active, rows.isNotEmpty()) {
        if (active) rows.firstOrNull()?.items?.firstOrNull()?.let { onFocusItem(it) }
    }

    StartScreen(
        rows = rows,
        status = status,
        focusState = focusState,
        onFocusItem = onFocusItem,
        onRowFocused = onRowFocused,
        openItem = { item, _ -> openYouTubeVideo(context, item.ratingKey) },
        bridge = bridge,
        active = active,
        autoFocus = false,
        onAutoFocused = {},
        gridMode = gridMode
    )
}

@Composable
private fun YouTubeLogin(yt: YouTubeRepo, onDone: () -> Unit) {
    var code by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("google.com/device") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        code = null
        try {
            val dc = yt.startLogin()
            code = dc.userCode
            url = dc.url.removePrefix("https://").removePrefix("www.")
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 15 * 60 * 1000) {
                delay(dc.intervalSec * 1000L)
                if (yt.pollLogin(dc)) {
                    onDone()
                    return@LaunchedEffect
                }
            }
            error = "Der Code ist abgelaufen."
        } catch (e: Exception) {
            error = e.message ?: "Anmeldung fehlgeschlagen."
        }
    }
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText("Mit YouTube verbinden", style = TextStyle(color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(14.dp))
        BasicText("Öffne auf dem Handy $url und gib diesen Code ein:", style = TextStyle(color = TextSoft, fontSize = 17.sp))
        Spacer(Modifier.height(22.dp))
        BasicText(code ?: "…", style = TextStyle(color = TextMain, fontSize = 48.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp))
        Spacer(Modifier.height(16.dp))
        BasicText("Abgespielt wird anschliessend in SmartTube, ohne Werbung.", style = TextStyle(color = TextDim, fontSize = 13.sp))
        error?.let {
            Spacer(Modifier.height(20.dp))
            BasicText(it, style = TextStyle(color = Color(0xFFFFB4A8), fontSize = 15.sp))
            Spacer(Modifier.height(12.dp))
            PillButton("Neuen Code anfordern") { attempt++ }
        }
    }
}

@Composable
private fun YouTubeSearchScreen(
    yt: YouTubeRepo,
    focusState: MutableState<PlexItem?>,
    onFocusItem: (PlexItem) -> Unit,
    onRowFocused: (Int) -> Unit,
    bridge: FocusBridge,
    active: Boolean,
    gridMode: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var editing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<PlexRow>>(emptyList()) }
    var status by remember { mutableStateOf("Suchbegriff eingeben oder sprechen") }
    val boxFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val micFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Eigene Brücke für die Ergebnis-Zeilen: "hoch" aus der ersten Zeile führt zum Suchfeld
    val rowsBridge = remember { FocusBridge() }
    rowsBridge.toTop = { try { boxFocus.requestFocus() } catch (e: Exception) { } }
    if (active) bridge.toContent = { try { boxFocus.requestFocus() } catch (e: Exception) { } }

    fun runSearch(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        status = "Suche nach „$text“ …"
        results = emptyList()
        scope.launch {
            try {
                val found = yt.search(text)
                results = if (found.isEmpty()) emptyList() else found.chunked(12).mapIndexed { i, part ->
                    PlexRow(if (i == 0) "Ergebnisse für „$text“" else "Weitere Ergebnisse (${i + 1})", part, landscape = true, resume = false)
                }
                status = if (found.isEmpty()) "Nichts gefunden für „$text“" else ""
                found.firstOrNull()?.let { onFocusItem(it) }
                if (found.isNotEmpty()) {
                    delay(150)
                    rowsBridge.toContent()
                }
            } catch (e: Exception) {
                status = "Suche fehlgeschlagen: ${e.message}"
            }
        }
    }

    // Spracheingabe über die Android-Spracherkennung
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            query = TextFieldValue(spoken, TextRange(spoken.length))
            runSearch(spoken)
        }
    }

    LaunchedEffect(editing) {
        if (editing) {
            delay(50)
            try { fieldFocus.requestFocus() } catch (e: Exception) { }
            keyboard?.show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        StartScreen(
            rows = results,
            status = status,
            focusState = focusState,
            onFocusItem = onFocusItem,
            onRowFocused = onRowFocused,
            openItem = { item, _ -> openYouTubeVideo(context, item.ratingKey) },
            bridge = rowsBridge,
            active = true,
            autoFocus = false,
            onAutoFocused = {},
            gridMode = gridMode
        )

        if (results.isEmpty() && status.isNotEmpty()) {
            BasicText(status, modifier = Modifier.offset(x = 66.dp, y = 120.dp), style = TextStyle(color = TextSoft, fontSize = 14.sp))
        }

        // Suchleiste unter der Kopfzeile
        Row(
            Modifier.offset(x = 48.dp, y = 68.dp).onPreviewKeyEvent { ev ->
                val ne = ev.nativeKeyEvent
                if (ne.action != AndroidKeyEvent.ACTION_DOWN || editing) return@onPreviewKeyEvent false
                when (ne.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { bridge.toTop(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { if (results.isNotEmpty()) rowsBridge.toContent(); true }
                    else -> false
                }
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            val src = remember { MutableInteractionSource() }
            val boxFocused by src.collectIsFocusedAsState()
            Box(
                Modifier
                    .width(420.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (boxFocused || editing) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f))
                    .border(2.dp, if (boxFocused || editing) TextMain else Color.Transparent, RoundedCornerShape(50))
                    .focusRequester(boxFocus)
                    .clickable(interactionSource = src, indication = null) { editing = true }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (editing) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = TextMain, fontSize = 16.sp),
                        cursorBrush = SolidColor(TextMain),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            editing = false
                            runSearch(query.text)
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus).onFocusChanged {
                            if (!it.isFocused && editing) editing = false
                        }
                    )
                } else {
                    BasicText(
                        query.text.ifEmpty { "YouTube durchsuchen …" },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = if (query.text.isEmpty()) TextDim else TextMain, fontSize = 16.sp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            PillButton("Spracheingabe", Modifier.focusRequester(micFocus)) {
                try {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-CH")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Was möchtest du suchen?")
                    speech.launch(i)
                } catch (e: Exception) {
                    Toast.makeText(context, "Spracheingabe ist nicht verfügbar.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/** Öffnet ein Video direkt in SmartTube (sonst in der YouTube-App). */
fun openYouTubeVideo(context: Context, videoId: String) {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
    i.setPackage(Updater.findSmartTube(context) ?: "com.google.android.youtube.tv")
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(i)
    } catch (e: Exception) {
        Toast.makeText(context, "SmartTube konnte nicht geöffnet werden.", Toast.LENGTH_LONG).show()
    }
}

// ---------------------------------------------------------------- Updates

@Composable
private fun UpdatesOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var launcherUpdate by remember { mutableStateOf<Updater.Update?>(null) }
    var launcherText by remember { mutableStateOf("Wird geprüft …") }
    var st by remember { mutableStateOf<Updater.SmartTubeStatus?>(null) }
    var stText by remember { mutableStateOf("Wird geprüft …") }
    var checkCount by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    val df = remember { SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY) }

    LaunchedEffect(checkCount) {
        launcherText = "Wird geprüft …"
        stText = "Wird geprüft …"
        launcherUpdate = try { Updater.check(context) } catch (e: Exception) { null }
        launcherText = launcherUpdate?.let { "Neue Version verfügbar (Build ${it.build})" }
            ?: "Aktuell (Build ${Updater.currentBuild(context)})"
        val s = try { Updater.checkSmartTube(context) } catch (e: Exception) { null }
        st = s
        stText = when {
            s == null -> "Konnte nicht geprüft werden"
            s.installedPkg == null -> "Nicht installiert"
            s.updateAvailable -> "Neue Version verfügbar (vom ${df.format(Date(s.releaseAt))})"
            else -> "Aktuell (installiert am ${df.format(Date(s.installedAt))})"
        }
    }
    LaunchedEffect(Unit) {
        delay(100)
        try { first.requestFocus() } catch (e: Exception) { }
    }

    fun installHint(ok: Boolean) {
        if (!ok) Toast.makeText(context, "Bitte Installation erlauben und danach nochmals drücken.", Toast.LENGTH_LONG).show()
    }

    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.95f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp)) {
            BasicText("Updates", style = TextStyle(color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(20.dp))
            UpdateRow("Shield Launcher", launcherText, if (launcherUpdate != null) "Aktualisieren" else "Erneut prüfen", Modifier.focusRequester(first)) {
                val u = launcherUpdate
                if (u == null) {
                    checkCount++
                } else scope.launch {
                    Toast.makeText(context, "Lade Shield Launcher herunter …", Toast.LENGTH_SHORT).show()
                    try { installHint(Updater.install(context, u)) } catch (e: Exception) {
                        Toast.makeText(context, "Update fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val s = st
            val stAction = when {
                s == null -> "Erneut prüfen"
                s.installedPkg == null -> "Installieren"
                s.updateAvailable -> "Aktualisieren"
                else -> "Erneut prüfen"
            }
            UpdateRow("SmartTube (YouTube ohne Werbung)", stText, stAction, Modifier) {
                if (s == null || (s.installedPkg != null && !s.updateAvailable)) {
                    checkCount++
                } else scope.launch {
                    Toast.makeText(context, "Lade SmartTube herunter …", Toast.LENGTH_SHORT).show()
                    try { installHint(Updater.installUrl(context, s.url, "smarttube.apk")) } catch (e: Exception) {
                        Toast.makeText(context, "Download fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            BasicText(
                "SmartTube wird direkt von der offiziellen GitHub-Seite geladen. Android installiert ein Update nur, wenn es vom selben Entwickler signiert ist. Mit Zurück schliesst du diese Übersicht.",
                style = TextStyle(color = TextDim, fontSize = 12.sp, lineHeight = 17.sp)
            )
        }
    }
}

@Composable
private fun UpdateRow(name: String, status: String, action: String, buttonModifier: Modifier, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tile).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(name, style = TextStyle(color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(3.dp))
            BasicText(status, style = TextStyle(color = TextSoft, fontSize = 13.sp))
        }
        Spacer(Modifier.width(12.dp))
        PillButton(action, buttonModifier) { onClick() }
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
