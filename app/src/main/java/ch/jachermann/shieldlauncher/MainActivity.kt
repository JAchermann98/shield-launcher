@file:OptIn(ExperimentalFoundationApi::class)

package ch.jachermann.shieldlauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.view.KeyEvent as AndroidKeyEvent
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current
    // Bestehende Installationen mit Plex-Anmeldung gelten als eingerichtet
    var setup by remember {
        mutableStateOf(
            when {
                Setup.isDone(context) && repo.userToken != null -> null
                repo.userToken != null -> { Setup.setDone(context, true); null }
                else -> SetupStep.WELCOME
            }
        )
    }
    var onlyStep by remember { mutableStateOf(false) }
    var rerun by remember { mutableStateOf(false) }
    var session by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(Bg)) {
        val current = setup
        if (current != null) {
            // Erneuter Durchlauf aus den Einstellungen lässt sich mit Zurück abbrechen
            val cancel: (() -> Unit)? = if (rerun) ({
                // Nur abbrechen, wenn Plex noch angemeldet ist (man kann sich in der Einrichtung abmelden)
                if (repo.userToken != null) {
                    Setup.setDone(context, true)
                    setup = null
                    onlyStep = false
                    rerun = false
                }
            }) else null
            OnboardingFlow(repo, current, onlyStep, cancel) {
                Setup.setDone(context, true)
                setup = null
                onlyStep = false
                rerun = false
                session++
            }
        } else {
            key(session) {
                HomeRoot(
                    repo, resumeCount, homeCount,
                    onSetup = { step, single ->
                        if (!single) Setup.setDone(context, false)
                        onlyStep = single
                        rerun = true
                        setup = step
                    },
                    onReload = { session++ },
                    onPlexLogout = {
                        repo.logout()
                        Setup.setDone(context, false)
                        onlyStep = false
                        rerun = false
                        setup = SetupStep.PLEX
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Rahmen mit Kopfzeile und Seiten

@Composable
fun HomeRoot(
    repo: PlexRepo,
    resumeCount: Int,
    homeCount: Int,
    onSetup: (SetupStep, Boolean) -> Unit,
    onReload: () -> Unit,
    onPlexLogout: () -> Unit
) {
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
    var showSettings by remember { mutableStateOf(false) }
    var showLibMenu by remember { mutableStateOf(false) }
    var libBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val bridge = remember { FocusBridge() }
    val topReq = remember { listOf("plex", "yt", "apps", "start", "filme", "serien", "ytstart", "ytsearch", "lib", "view", "update", "settings").associateWith { FocusRequester() } }
    // Ansicht für Filme/Serien: Bildansicht (false) oder Rasteransicht (true), getrennt gemerkt
    val uiPrefs = remember { context.getSharedPreferences("launcher_ui", Context.MODE_PRIVATE) }
    // Mediatheken für die Auswahl auf den Seiten Filme und Serien (null = alle)
    var libSections by remember { mutableStateOf<List<LibrarySection>>(emptyList()) }
    var libMovie by remember { mutableStateOf(uiPrefs.getString("lib_movie", null)) }
    var libShow by remember { mutableStateOf(uiPrefs.getString("lib_show", null)) }
    LaunchedEffect(servers) {
        if (servers.isNotEmpty()) libSections = try { repo.librarySections(servers) } catch (e: Exception) { logW(e); emptyList() }
    }
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
            showSettings = false
            showLibMenu = false
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
            showSettings -> showSettings = false
            showLibMenu -> {
                showLibMenu = false
                try { topReq["lib"]?.requestFocus() } catch (e: Exception) { }
            }
            tab != Tab.START -> tab = Tab.START
        }
    }

    // Startseite still im Hintergrund aktualisieren
    LaunchedEffect(resumeCount) {
        if (System.currentTimeMillis() - lastRefresh < 20_000) return@LaunchedEffect
        lastRefresh = System.currentTimeMillis()
        try {
            var loaded = if (servers.isNotEmpty()) repo.loadRows(servers) else emptyList()
            // Server nicht erreichbar (z. B. neue IP-Adresse) oder noch keiner bekannt: neu suchen
            if (servers.isEmpty() || loaded.isEmpty() || repo.unreachableServers > 0) {
                val found = repo.findServers()
                if (found.isNotEmpty() && found != servers) {
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
            logW(e)
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
                Tab.FILME -> key(libMovie) {
                    val title = libSections.firstOrNull { it.id == libMovie }?.title ?: "Alle Filme"
                    LibraryScreen(repo, servers, "movie", title, focusState, onFocusItem, openItem, bridge, active, gridMovies, libMovie) { libScrolled = it }
                }
                Tab.SERIEN -> key(libShow) {
                    val title = libSections.firstOrNull { it.id == libShow }?.title ?: "Alle Serien"
                    LibraryScreen(repo, servers, "show", title, focusState, onFocusItem, openItem, bridge, active, gridShows, libShow) { libScrolled = it }
                }
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
                onUpdates = { showUpdates = true },
                libLabel = when (tab) {
                    Tab.FILME, Tab.SERIEN -> {
                        val type = if (tab == Tab.FILME) "movie" else "show"
                        val list = libSections.filter { it.type == type }
                        val cur = if (tab == Tab.FILME) libMovie else libShow
                        if (list.size > 1) list.firstOrNull { it.id == cur }?.title ?: "Alle Mediatheken" else null
                    }
                    else -> null
                },
                onLib = { showLibMenu = true },
                onLibBounds = { libBounds = it },
                onSettings = { showSettings = true }
            )
        }

        if (showApps) {
            AllAppsOverlay(onClose = { showApps = false })
        }
        if (showUpdates) {
            UpdatesOverlay(onClose = { showUpdates = false })
        }
        if (showLibMenu && (tab == Tab.FILME || tab == Tab.SERIEN)) {
            val type = if (tab == Tab.FILME) "movie" else "show"
            val cur = if (tab == Tab.FILME) libMovie else libShow
            val options = listOf<Pair<String?, String>>(null to (if (tab == Tab.FILME) "Alle Filme" else "Alle Serien")) +
                libSections.filter { it.type == type }.map { it.id to it.title }
            LibraryDropdown(
                options = options,
                current = cur,
                anchor = libBounds,
                onSelect = { next ->
                    showLibMenu = false
                    if (next != cur) {
                        if (tab == Tab.FILME) libMovie = next else libShow = next
                        uiPrefs.edit().putString(if (tab == Tab.FILME) "lib_movie" else "lib_show", next).apply()
                        libScrolled = false
                        tabFirst.remove(tab)
                    }
                    scope.launch { delay(60); try { topReq["lib"]?.requestFocus() } catch (e: Exception) { } }
                },
                onClose = {
                    showLibMenu = false
                    scope.launch { delay(60); try { topReq["lib"]?.requestFocus() } catch (e: Exception) { } }
                }
            )
        }
        if (showSettings) {
            SettingsOverlay(
                repo = repo,
                onRunSetup = { showSettings = false; onSetup(SetupStep.WELCOME, false) },
                onLibraries = { showSettings = false; onSetup(SetupStep.LIBRARIES, true) },
                onYouTubeLogout = onReload,
                onPlexLogout = { showSettings = false; onPlexLogout() }
            )
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
