@file:OptIn(ExperimentalFoundationApi::class)

package ch.jachermann.shieldlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpdatesOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var launcherUpdate by remember { mutableStateOf<Updater.Update?>(null) }
    var launcherText by remember { mutableStateOf("Wird geprüft …") }
    var st by remember { mutableStateOf<Updater.SmartTubeStatus?>(null) }
    var stText by remember { mutableStateOf("Wird geprüft …") }
    var checkCount by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val df = remember { SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY) }

    LaunchedEffect(checkCount) {
        launcherText = "Wird geprüft …"
        stText = "Wird geprüft …"
        launcherUpdate = try { Updater.check(context) } catch (e: Exception) { logW(e); null }
        launcherText = launcherUpdate?.let { "Neue Version verfügbar (Build ${it.build})" }
            ?: "Aktuell (Build ${Updater.currentBuild(context)})"
        val s = try { Updater.checkSmartTube(context) } catch (e: Exception) { logW(e); null }
        st = s
        stText = when {
            s == null -> "Konnte nicht geprüft werden"
            s.installedPkg == null -> "Nicht installiert"
            s.otherSource != null -> "Aus ${s.otherSource} installiert. Für Updates hier: deinstallieren und neu installieren"
            s.updateAvailable -> "Neue Version verfügbar (vom ${df.format(Date(s.releaseAt))})"
            else -> "Aktuell (installiert am ${df.format(Date(s.installedAt))})"
        }
    }
    LaunchedEffect(Unit) {
        delay(100)
        try { first.requestFocus() } catch (e: Exception) { }
    }

    fun progressText(pct: Int) = if (pct >= 0) "Wird heruntergeladen … $pct %" else "Wird heruntergeladen …"

    /** Lädt herunter und öffnet direkt das Installationsfenster. Mehrfaches Drücken wird ignoriert. */
    fun runInstall(setText: (String) -> Unit, resetText: String, block: suspend ((Int) -> Unit) -> Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            setText(progressText(0))
            try {
                val ok = block { pct -> setText(progressText(pct)) }
                if (ok) setText("Installation wird geöffnet …")
                else {
                    setText(resetText)
                    Toast.makeText(context, "Bitte Installation erlauben und danach nochmals drücken.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                logW(e)
                setText("Fehler: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.95f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).trapFocus()) {
            BasicText("Updates", style = TextStyle(color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(20.dp))
            UpdateRow("Shield Launcher", launcherText, if (launcherUpdate != null) "Aktualisieren" else "Erneut prüfen", Modifier.focusRequester(first)) {
                val u = launcherUpdate
                if (u == null) {
                    if (!busy) checkCount++
                } else {
                    val reset = launcherText
                    runInstall({ launcherText = it }, reset) { p -> Updater.install(context, u, p) }
                }
            }
            Spacer(Modifier.height(12.dp))
            val s = st
            val stAction = when {
                s == null -> "Erneut prüfen"
                s.installedPkg == null -> "Installieren"
                s.otherSource != null -> "Deinstallieren"
                s.updateAvailable -> "Aktualisieren"
                else -> "Erneut prüfen"
            }
            UpdateRow("SmartTube (YouTube ohne Werbung)", stText, stAction, Modifier) {
                if (s != null && s.otherSource != null && s.installedPkg != null) {
                    // Andere Signatur: erst deinstallieren, danach erkennt die Prüfung "Nicht installiert"
                    val pkg = s.installedPkg
                    try {
                        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) { logW(e) }
                    scope.launch {
                        repeat(60) {
                            delay(2000)
                            if (Updater.findSmartTube(context) == null) { checkCount++; return@launch }
                        }
                    }
                } else if (s == null || (s.installedPkg != null && !s.updateAvailable)) {
                    if (!busy) checkCount++
                } else {
                    val reset = stText
                    runInstall({ stText = it }, reset) { p -> Updater.installUrl(context, s.url, "smarttube.apk", p) }
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
fun UpdateRow(name: String, status: String, action: String, buttonModifier: Modifier, onClick: () -> Unit) {
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
        Column(Modifier.trapFocus()) {
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

fun loadApps(context: Context): List<ResolveInfo> {
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

// ---------------------------------------------------------------- Auswahl der Mediathek

/** Aufklappliste unter dem Mediathek-Knopf. options: id (null = alle) zu Name. */
@Composable
fun LibraryDropdown(
    options: List<Pair<String?, String>>,
    current: String?,
    anchor: androidx.compose.ui.geometry.Rect?,
    onSelect: (String?) -> Unit,
    onClose: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val reqs = remember(options) { options.map { FocusRequester() } }
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { appear.animateTo(1f, tween(220)) }
        delay(60)
        val i = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
        try { reqs[i].requestFocus() } catch (e: Exception) { }
    }
    val width = 260.dp
    // Rechtsbündig unter dem Knopf, aber nie über den Bildschirmrand hinaus
    val x = with(density) { anchor?.let { (it.right.toDp() - width).coerceAtLeast(16.dp) } ?: 600.dp }
    val y = with(density) { anchor?.let { it.bottom.toDp() + 8.dp } ?: 64.dp }

    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.35f * appear.value))) {
        Column(
            Modifier
                .padding(start = x, top = y)
                .width(width)
                .graphicsLayer {
                    alpha = appear.value
                    translationY = (1f - appear.value) * -12f * density.density
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                    scaleX = 0.96f + 0.04f * appear.value
                    scaleY = 0.96f + 0.04f * appear.value
                }
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xF01A202A))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                .padding(6.dp)
                .trapFocus()
        ) {
            options.forEachIndexed { i, (id, name) ->
                val src = remember { MutableInteractionSource() }
                val focused by src.collectIsFocusedAsState()
                val selected = id == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(reqs[i])
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (focused) TextMain else Color.Transparent)
                        .clickable(interactionSource = src, indication = null) { onSelect(id) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        name, modifier = Modifier.weight(1f), maxLines = 1,
                        style = TextStyle(color = if (focused) Bg else TextMain, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    )
                    if (selected) BasicText("✓", style = TextStyle(color = if (focused) Bg else Color(0xFF7FB2FF), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                }
                if (i == 0 && options.size > 1) {
                    Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                }
            }
        }
    }
}
