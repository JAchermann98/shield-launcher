@file:OptIn(ExperimentalFoundationApi::class)

package ch.jachermann.shieldlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
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
private val Accent = Color(0xFF7FB2FF)

/** Verhindert das automatische Mitscrollen der Zeilenliste; wir steuern es selbst. */
private val NoAutoScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

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
            HomeScreen(repo, resumeCount)
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

// ---------------------------------------------------------------- Startbildschirm

@Composable
fun HomeScreen(repo: PlexRepo, resumeCount: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultSpec = LocalBringIntoViewSpec.current

    var servers by remember { mutableStateOf(repo.loadServers()) }
    var rows by remember { mutableStateOf(repo.loadCache()) }
    val focusState = remember { mutableStateOf(rows.firstOrNull()?.items?.firstOrNull()) }
    var status by remember { mutableStateOf(if (rows.isEmpty()) "Lade Inhalte von Plex …" else "") }
    var showApps by remember { mutableStateOf(false) }
    var lastRefresh by remember { mutableLongStateOf(0L) }
    val firstFocus = remember { FocusRequester() }
    var didInitialFocus by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    BackHandler(enabled = true) { if (showApps) showApps = false }

    // Still im Hintergrund aktualisieren (beim Start und beim Zurückkehren)
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
                // Hintergrundbilder der ersten Titel vorladen
                val loader = context.imageLoader
                loaded.forEach { r ->
                    r.items.take(4).forEach { item ->
                        item.backdropUrl?.let { loader.enqueue(ImageRequest.Builder(context).data(it).build()) }
                    }
                }
            } else if (rows.isEmpty()) {
                status = "Keine Inhalte gefunden. Ist der Plex-Server erreichbar?"
            }
        } catch (e: Exception) {
            if (rows.isEmpty()) status = "Fehler beim Laden: ${e.message}"
        }
    }

    LaunchedEffect(rows.isNotEmpty()) {
        if (rows.isNotEmpty() && !didInitialFocus) {
            delay(120)
            try {
                firstFocus.requestFocus()
                didInitialFocus = true
            } catch (e: Exception) {
            }
        }
    }

    fun openItem(item: PlexItem, play: Boolean) {
        scope.launch {
            val target = if (item.fromWatchlist) repo.resolveOnServer(item, servers) else item
            if (target == null) {
                Toast.makeText(context, "Dieser Titel ist auf deinen Servern nicht verfügbar.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = if (play) repo.playUri(target) else repo.detailsUri(target)
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                i.setPackage("com.plexapp.android")
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            } catch (e: Exception) {
                Toast.makeText(context, "Plex konnte nicht geöffnet werden.", Toast.LENGTH_LONG).show()
            }
        }
    }

    val onFocusItem: (PlexItem) -> Unit = remember { { focusState.value = it } }

    Box(Modifier.fillMaxSize()) {
        Backdrop(focusState)

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(start = 48.dp, top = 24.dp)) {
                TopBar(onApps = { showApps = true })
            }
            Hero(focusState, status)

            // Zeilenbereich: scrollt nur hier unten, der Kopfbereich bleibt stehen
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 48.dp, top = 6.dp, bottom = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        itemsIndexed(rows, key = { _, r -> r.title }) { rowIndex, row ->
                            ContentRow(
                                row = row,
                                defaultSpec = defaultSpec,
                                firstFocus = if (rowIndex == 0) firstFocus else null,
                                modifier = Modifier.onFocusChanged {
                                    if (it.hasFocus) scope.launch { listState.animateScrollToItem(rowIndex) }
                                },
                                onFocus = onFocusItem,
                                onClick = { openItem(it, play = false) },
                                onLongClick = { openItem(it, play = true) }
                            )
                        }
                    }
                }
            }
        }

        if (showApps) {
            AllAppsOverlay(onClose = { showApps = false })
        }
    }
}

// ---------------------------------------------------------------- Hintergrund

@Composable
fun Backdrop(focusState: MutableState<PlexItem?>) {
    val target = focusState.value?.backdropUrl
    var shown by remember { mutableStateOf(target) }
    // Erst wechseln, wenn die Auswahl kurz stehen bleibt: schont die Leistung beim schnellen Durchblättern
    LaunchedEffect(target) {
        if (target != shown) {
            delay(220)
            shown = target
        }
    }
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.78f)) {
        Crossfade(targetState = shown, animationSpec = tween(600), label = "backdrop") { url ->
            if (url != null) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF141925)))
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Bg.copy(alpha = 0.95f), 0.45f to Bg.copy(alpha = 0.55f), 0.8f to Bg.copy(alpha = 0.05f))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.55f to Bg.copy(alpha = 0.35f), 1f to Bg)
            )
        )
    }
}

// ---------------------------------------------------------------- Kopfzeile

@Composable
fun TopBar(onApps: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var time by remember { mutableStateOf(clock()) }
    var updateLabel by remember { mutableStateOf("Update") }
    LaunchedEffect(Unit) {
        while (true) {
            time = clock()
            delay(15000)
        }
    }
    LaunchedEffect(Unit) {
        try {
            if (Updater.check(context) != null) updateLabel = "Update verfügbar"
        } catch (e: Exception) {
        }
    }
    Row(Modifier.fillMaxWidth().padding(end = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(time, style = TextStyle(color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.width(20.dp))
        PillButton("Plex") { launchApp(context, listOf("com.plexapp.android")) }
        Spacer(Modifier.width(8.dp))
        PillButton("YouTube") { launchYouTube(context) }
        Spacer(Modifier.width(8.dp))
        PillButton("Alle Apps") { onApps() }
        Spacer(Modifier.weight(1f))
        PillButton(updateLabel) {
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
        }
        Spacer(Modifier.width(8.dp))
        PillButton("Einstellungen") {
            val i = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(i) } catch (e: Exception) { }
        }
    }
}

private fun clock(): String = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())

@Composable
fun PillButton(label: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val isFocused by src.collectIsFocusedAsState()
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (isFocused) TextMain else Color.White.copy(alpha = 0.10f))
            .clickable(interactionSource = src, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        BasicText(label, style = TextStyle(color = if (isFocused) Bg else TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    }
}

// ---------------------------------------------------------------- Kopfbereich mit Infos

@Composable
fun Hero(focusState: MutableState<PlexItem?>, status: String) {
    val item = focusState.value
    Box(Modifier.padding(start = 48.dp, top = 12.dp).width(480.dp).height(196.dp), contentAlignment = Alignment.BottomStart) {
        if (item == null) {
            BasicText(status, style = TextStyle(color = TextSoft, fontSize = 16.sp))
        } else {
            Crossfade(targetState = item, animationSpec = tween(250), label = "hero") { cur ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Bottom) {
                    BasicText(
                        cur.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 37.sp)
                    )
                    if (cur.subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        BasicText(cur.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = TextSoft, fontSize = 15.sp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val meta = listOfNotNull(
                            cur.year.ifEmpty { null },
                            if (cur.durationMs > 0) formatDuration(cur.durationMs) else null
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) BasicText(meta, style = TextStyle(color = TextSoft, fontSize = 13.sp))
                        if (cur.resolution.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Badge(cur.resolution) }
                        if (cur.audio.isNotEmpty()) { Spacer(Modifier.width(6.dp)); Badge(cur.audio) }
                    }
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
                        BasicText(cur.summary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = TextDim, fontSize = 13.sp, lineHeight = 17.sp))
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val min = (ms / 60000).toInt()
    return if (min >= 60) "${min / 60} Std. ${min % 60} Min." else "$min Min."
}

@Composable
fun Badge(text: String) {
    Box(Modifier.border(1.dp, TextMain.copy(alpha = 0.55f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        BasicText(text, style = TextStyle(color = TextMain, fontSize = 10.sp, fontWeight = FontWeight.SemiBold))
    }
}

// ---------------------------------------------------------------- Zeilen

@Composable
fun ContentRow(
    row: PlexRow,
    defaultSpec: BringIntoViewSpec,
    firstFocus: FocusRequester?,
    modifier: Modifier,
    onFocus: (PlexItem) -> Unit,
    onClick: (PlexItem) -> Unit,
    onLongClick: (PlexItem) -> Unit
) {
    Column(modifier) {
        BasicText(row.title, style = TextStyle(color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(8.dp))
        // In der Zeile selbst normal seitwärts mitscrollen
        CompositionLocalProvider(LocalBringIntoViewSpec provides defaultSpec) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(start = 6.dp, end = 64.dp, top = 8.dp, bottom = 8.dp)
            ) {
                itemsIndexed(row.items, key = { i, it -> "${it.serverId}/${it.ratingKey}/$i" }) { index, item ->
                    Card(
                        item = item,
                        landscape = row.landscape,
                        modifier = if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
                        onFocus = onFocus,
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                }
            }
        }
    }
}

@Composable
fun Card(
    item: PlexItem,
    landscape: Boolean,
    modifier: Modifier,
    onFocus: (PlexItem) -> Unit,
    onClick: (PlexItem) -> Unit,
    onLongClick: (PlexItem) -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val isFocused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.1f else 1f, animationSpec = tween(160), label = "scale")
    val w = if (landscape) 176.dp else 100.dp
    val h = if (landscape) 99.dp else 150.dp
    val shape = RoundedCornerShape(8.dp)
    Column(modifier.width(w)) {
        Box(
            Modifier
                .size(w, h)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .onFocusChanged { if (it.isFocused) onFocus(item) }
                .clip(shape)
                .background(Tile)
                .border(2.dp, if (isFocused) Accent else Color.Transparent, shape)
                .combinedClickable(
                    interactionSource = src,
                    indication = null,
                    onLongClick = { onLongClick(item) },
                    onClick = { onClick(item) }
                )
        ) {
            val url = if (landscape) item.landscapeUrl ?: item.posterUrl else item.posterUrl
            if (url != null) {
                AsyncImage(model = url, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                BasicText(item.title, modifier = Modifier.padding(8.dp), style = TextStyle(color = TextDim, fontSize = 12.sp))
            }
            if (item.progress > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(6.dp).height(3.dp)
                        .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(item.progress).background(if (isFocused) Accent else TextMain))
                }
            }
        }
        if (landscape) {
            Spacer(Modifier.height(6.dp))
            BasicText(
                if (item.subtitle.isNotEmpty()) "${item.title} · ${item.subtitle}" else item.title,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = if (isFocused) TextMain else TextDim, fontSize = 11.sp)
            )
        }
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
                .border(2.dp, if (isFocused) Accent else Color.Transparent, shape)
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
