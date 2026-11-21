package ch.jachermann.shieldlauncher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Accent = Color(0xFF7FB2FF)
private val Ok = Color(0xFF8FE3B0)
private val Warn = Color(0xFFFFB4A8)

/** Merkt sich, ob die Einrichtung abgeschlossen ist. */
object Setup {
    private fun prefs(context: Context) = context.getSharedPreferences("launcher_ui", Context.MODE_PRIVATE)
    fun isDone(context: Context) = prefs(context).getBoolean("onboarding_done", false)
    fun setDone(context: Context, done: Boolean) = prefs(context).edit().putBoolean("onboarding_done", done).apply()
}

/** Schritte der Einrichtung. */
enum class SetupStep(val title: String) {
    WELCOME("Willkommen"),
    PLEX("Plex"),
    LIBRARIES("Mediatheken"),
    YOUTUBE("YouTube"),
    HOME("Fertig")
}

/**
 * Einrichtung beim ersten Start. Mit onlyStep wird nur ein einzelner Schritt gezeigt
 * (z. B. die Mediatheken-Auswahl aus den Einstellungen).
 */
@Composable
fun OnboardingFlow(
    repo: PlexRepo,
    start: SetupStep = SetupStep.WELCOME,
    onlyStep: Boolean = false,
    onCancel: (() -> Unit)? = null,
    onDone: () -> Unit
) {
    var step by remember { mutableStateOf(start) }
    // Bilder aus der Mediathek für den Hintergrund (sobald Plex verbunden ist)
    var backdrops by remember { mutableStateOf(repo.loadCache().flatMap { r -> r.items.mapNotNull { it.backdropUrl } }.distinct().take(12)) }
    var plexReady by remember { mutableIntStateOf(0) }
    LaunchedEffect(plexReady) {
        if (plexReady == 0 || backdrops.isNotEmpty()) return@LaunchedEffect
        try {
            val rows = repo.loadRows(repo.loadServers())
            if (rows.isNotEmpty()) {
                withContext(Dispatchers.IO) { repo.saveCache(rows) } // macht auch den ersten Start schneller
                backdrops = rows.flatMap { r -> r.items.mapNotNull { it.backdropUrl } }.distinct().take(12)
            }
        } catch (e: Exception) {
            logW(e)
        }
    }

    fun next() {
        if (onlyStep || step == SetupStep.HOME) onDone()
        else step = SetupStep.entries[step.ordinal + 1]
    }
    fun back() {
        when {
            onlyStep -> onCancel?.invoke() ?: onDone()
            step.ordinal > 0 -> step = SetupStep.entries[step.ordinal - 1]
            else -> onCancel?.invoke() // beim ersten Einrichten bleibt man hier
        }
    }
    // Immer aktiv: sonst würde Zurück den Launcher selbst schliessen
    BackHandler(enabled = true) { back() }

    Box(Modifier.fillMaxSize().background(Bg)) {
        SetupBackground(backdrops)

        // Kopfzeile: Name links, Fortschritt rechts (wie die Kopfzeile des Launchers)
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 26.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText("Shield Launcher", style = TextStyle(color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp))
            Spacer(Modifier.width(10.dp))
            BasicText(if (onlyStep) "Einstellungen" else "Einrichtung", style = TextStyle(color = TextDim, fontSize = 13.sp))
            Spacer(Modifier.weight(1f))
            if (!onlyStep) StepProgress(step)
        }

        // Grosse Schrittnummer als ruhiges Gestaltungselement
        if (!onlyStep) {
            Crossfade(step, tween(600), label = "num", modifier = Modifier.align(Alignment.BottomEnd)) { s ->
                BasicText(
                    "0${s.ordinal + 1}",
                    modifier = Modifier.padding(end = 40.dp, bottom = 8.dp),
                    style = TextStyle(color = Color.White.copy(alpha = 0.05f), fontSize = 220.sp, fontWeight = FontWeight.Black)
                )
            }
        }

        // Inhalt mit Übergang in Laufrichtung, wie beim Reiterwechsel im Launcher
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (fadeIn(tween(420, delayMillis = 90, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(tween(520, easing = FastOutSlowInEasing)) { w -> dir * w / 8 }) togetherWith
                    (fadeOut(tween(220)) + slideOutHorizontally(tween(360, easing = FastOutSlowInEasing)) { w -> -dir * w / 12 })
            },
            label = "steps",
            modifier = Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 84.dp, bottom = 36.dp)
        ) { s ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Column(Modifier.width(600.dp)) {
                    when (s) {
                        SetupStep.WELCOME -> WelcomeStep(::next)
                        SetupStep.PLEX -> PlexStep(repo, onConnected = { plexReady++ }, onNext = ::next)
                        SetupStep.LIBRARIES -> LibrariesStep(repo, if (onlyStep) "Speichern" else "Weiter", ::next)
                        SetupStep.YOUTUBE -> YouTubeStep(::next)
                        SetupStep.HOME -> HomeStep(::next)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Hintergrund

/** Langsam wechselnde Bilder aus der Mediathek mit Ken-Burns-Bewegung, sonst ein ruhiger Farbverlauf. */
@Composable
private fun BoxScope.SetupBackground(images: List<String>) {
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(19000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.radialGradient(
            listOf(Color(0xFF1E3A6E).copy(alpha = 0.55f), Color.Transparent),
            center = Offset(size.width * (0.55f + 0.3f * a), size.height * (0.15f + 0.4f * b)),
            radius = size.width * 0.55f
        ))
        drawRect(Brush.radialGradient(
            listOf(Color(0xFF4A2E73).copy(alpha = 0.45f), Color.Transparent),
            center = Offset(size.width * (0.95f - 0.35f * b), size.height * (0.9f - 0.3f * a)),
            radius = size.width * 0.5f
        ))
    }

    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(images) {
        while (images.size > 1) {
            delay(9000)
            index = (index + 1) % images.size
        }
    }
    val url = images.getOrNull(index % maxOf(images.size, 1))
    Crossfade(url, tween(1600), label = "bg") { u ->
        if (u != null) {
            val zoom = remember { Animatable(1.0f) }
            LaunchedEffect(u) { zoom.animateTo(1.12f, tween(11000, easing = LinearEasing)) }
            AsyncImage(
                model = u, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value; alpha = 0.5f }
            )
        }
    }
    // Abdunklung wie beim Hintergrund des Launchers: links dunkel für den Text
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg.copy(alpha = 0.95f), 0.45f to Bg.copy(alpha = 0.7f), 1f to Bg.copy(alpha = 0.15f))))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Bg.copy(alpha = 0.5f), 0.25f to Color.Transparent, 0.75f to Color.Transparent, 1f to Bg.copy(alpha = 0.9f))))
}

/** Fortschritt als Segmente; das aktive Segment füllt sich animiert. */
@Composable
private fun StepProgress(current: SetupStep) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SetupStep.entries.forEach { s ->
            val active = s == current
            val done = s.ordinal < current.ordinal
            val w by animateDpAsState(if (active) 54.dp else 26.dp, tween(450, easing = FastOutSlowInEasing), label = "w")
            val c by animateColorAsState(
                when { active -> TextMain; done -> Accent.copy(alpha = 0.8f); else -> Color.White.copy(alpha = 0.18f) },
                tween(450), label = "c"
            )
            Column(horizontalAlignment = Alignment.Start) {
                Box(Modifier.width(w).height(4.dp).clip(RoundedCornerShape(50)).background(c))
                Spacer(Modifier.height(5.dp))
                BasicText(
                    s.title,
                    style = TextStyle(color = if (active) TextMain else TextDim.copy(alpha = if (done) 0.9f else 0.55f), fontSize = 10.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                )
            }
            Spacer(Modifier.width(10.dp))
        }
    }
}

// ---------------------------------------------------------------- Bausteine

/** Blendet ein Element verzögert von unten ein (gestaffelt nach index). */
@Composable
private fun Reveal(index: Int, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120L + index * 70L)
        p.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
    }
    Box(Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 26f * density }) { content() }
}

@Composable
private fun StepTitle(eyebrow: String, title: String, text: String) {
    Reveal(0) {
        BasicText(eyebrow.uppercase(), style = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
    }
    Spacer(Modifier.height(10.dp))
    Reveal(1) {
        BasicText(title, style = TextStyle(color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp))
    }
    Spacer(Modifier.height(12.dp))
    Reveal(2) {
        BasicText(text, style = TextStyle(color = TextSoft, fontSize = 15.sp, lineHeight = 23.sp))
    }
}

/**
 * Knopf im Stil des Launchers. Der Fokus wird über onFocusChanged erkannt,
 * damit er auch bei automatisch gesetztem Fokus sofort hell erscheint.
 */
@Composable
fun SetupButton(label: String, modifier: Modifier = Modifier, primary: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "s")
    val bg by animateColorAsState(
        when {
            focused -> TextMain
            primary -> Color.White.copy(alpha = 0.16f)
            else -> Color.White.copy(alpha = 0.06f)
        }, tween(180), label = "bg"
    )
    val fg by animateColorAsState(if (focused) Bg else TextMain, tween(180), label = "fg")
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (focused || !primary) Color.Transparent else Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 26.dp, vertical = 11.dp)
    ) {
        BasicText(label, style = TextStyle(color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun InfoLine(text: String, color: Color = TextSoft) {
    BasicText(text, style = TextStyle(color = color, fontSize = 14.sp, lineHeight = 20.sp))
}

/** Statuszeile mit animiertem Häkchen bzw. Punkt. */
@Composable
private fun StatusLine(ok: Boolean, text: String, sub: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val s by animateFloatAsState(if (ok) 1f else 0.6f, spring(dampingRatio = 0.45f), label = "chk")
        val c by animateColorAsState(if (ok) Ok else Color.White.copy(alpha = 0.25f), tween(300), label = "cc")
        Box(
            Modifier.size(24.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape).background(c.copy(alpha = if (ok) 0.2f else 0.12f)).border(1.5.dp, c, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (ok) BasicText("✓", style = TextStyle(color = Ok, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            BasicText(text, style = TextStyle(color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            if (sub.isNotEmpty()) BasicText(sub, style = TextStyle(color = TextDim, fontSize = 12.sp))
        }
    }
}

/** Setzt den Fokus zuverlässig: wiederholt, bis das Element ihn wirklich hat. */
@Composable
private fun rememberAutoFocus(key: Any? = Unit): FocusRequester {
    val f = remember { FocusRequester() }
    LaunchedEffect(key) {
        delay(350) // nach dem Seitenübergang
        repeat(8) {
            val ok = try { f.requestFocus(); true } catch (e: Exception) { false }
            if (ok) return@LaunchedEffect
            delay(100)
        }
    }
    return f
}

/** Drei pulsierende Punkte für "warten". */
@Composable
private fun WaitingDots(text: String) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 180, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(7.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(Accent))
            Spacer(Modifier.width(5.dp))
        }
        Spacer(Modifier.width(6.dp))
        BasicText(text, style = TextStyle(color = TextSoft, fontSize = 14.sp))
    }
}

/** Anmeldecode als einzelne Kacheln mit sanft pulsierendem Rahmen. */
@Composable
fun CodeTiles(code: String?) {
    val t = rememberInfiniteTransition(label = "code")
    val glow by t.animateFloat(0.25f, 0.8f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g")
    Row {
        val chars = code?.toList() ?: List(4) { ' ' }
        chars.forEachIndexed { i, ch ->
            if (ch == '-' || ch == ' ' && code != null) {
                Spacer(Modifier.width(14.dp))
                return@forEachIndexed
            }
            val p = remember(code) { Animatable(if (code == null) 1f else 0f) }
            LaunchedEffect(code) { if (code != null) { delay(i * 60L); p.animateTo(1f, spring(dampingRatio = 0.55f)) } }
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .size(width = 52.dp, height = 64.dp)
                    .graphicsLayer { scaleX = 0.7f + 0.3f * p.value; scaleY = 0.7f + 0.3f * p.value; alpha = p.value }
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.5.dp, Accent.copy(alpha = glow), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                BasicText(if (code == null) "" else ch.toString(), style = TextStyle(color = TextMain, fontSize = 32.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

// ---------------------------------------------------------------- 1. Willkommen

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    val focus = rememberAutoFocus()
    StepTitle(
        "Willkommen",
        "Dein Startbildschirm.\nOhne Werbung.",
        "Die Einrichtung dauert etwa zwei Minuten. Halte dein Handy bereit für die Anmeldungen."
    )
    Spacer(Modifier.height(26.dp))
    Row {
        FeatureTile(3, "Plex", "Filme und Serien mit grossen Bildern, abgespielt in der Plex-App")
        Spacer(Modifier.width(12.dp))
        FeatureTile(4, "YouTube", "Abos, Später ansehen und Suche, werbefrei über SmartTube")
        Spacer(Modifier.width(12.dp))
        FeatureTile(5, "Bildschirmschoner", "Bilder aus deiner Mediathek, wenn der TV ruht")
    }
    Spacer(Modifier.height(30.dp))
    Reveal(6) { SetupButton("Los geht's", Modifier.focusRequester(focus), onClick = onNext) }
}

@Composable
private fun FeatureTile(index: Int, title: String, text: String) {
    Reveal(index) {
        Column(
            Modifier.width(186.dp).height(104.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f))))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Box(Modifier.width(18.dp).height(3.dp).clip(RoundedCornerShape(50)).background(Accent))
            Spacer(Modifier.height(10.dp))
            BasicText(title, style = TextStyle(color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            BasicText(text, style = TextStyle(color = TextDim, fontSize = 11.sp, lineHeight = 15.sp))
        }
    }
}

// ---------------------------------------------------------------- 2. Plex

@Composable
private fun PlexStep(repo: PlexRepo, onConnected: () -> Unit, onNext: () -> Unit) {
    var loggedIn by remember { mutableStateOf(repo.userToken != null) }
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var servers by remember { mutableStateOf<List<PlexServer>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    // Anmeldung per Code
    LaunchedEffect(loggedIn, attempt) {
        if (loggedIn) return@LaunchedEffect
        error = null
        code = null
        try {
            val (pinId, c) = repo.createPin()
            code = c
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 10 * 60 * 1000) {
                delay(2000)
                if (repo.checkPin(pinId)) {
                    loggedIn = true
                    return@LaunchedEffect
                }
            }
            error = "Der Code ist abgelaufen."
        } catch (e: Exception) {
            logW(e)
            error = "Keine Verbindung zu Plex: ${e.message}"
        }
    }
    // Server suchen
    LaunchedEffect(loggedIn, attempt) {
        if (!loggedIn) return@LaunchedEffect
        servers = null
        val found = try { repo.findServers() } catch (e: Exception) { logW(e); emptyList() }
        if (found.isNotEmpty()) {
            repo.saveServers(found)
            onConnected()
        }
        servers = found
    }

    AnimatedContent(loggedIn, transitionSpec = { fadeIn(tween(400, delayMillis = 100)) togetherWith fadeOut(tween(200)) }, label = "plex") { ok ->
        Column {
            if (!ok) {
                StepTitle("Plex", "Mit Plex verbinden", "Öffne auf dem Handy plex.tv/link, melde dich an und gib diesen Code ein.")
                Spacer(Modifier.height(26.dp))
                Reveal(3) { CodeTiles(code) }
                Spacer(Modifier.height(20.dp))
                Reveal(4) {
                    val err = error
                    if (err == null) WaitingDots(if (code == null) "Code wird erstellt" else "Warte auf deine Bestätigung")
                    else Column {
                        InfoLine(err, Warn)
                        Spacer(Modifier.height(14.dp))
                        val f = rememberAutoFocus(err)
                        SetupButton("Neuen Code anfordern", Modifier.focusRequester(f)) { attempt++ }
                    }
                }
            } else {
                val list = servers
                StepTitle(
                    "Plex",
                    if (list.isNullOrEmpty()) "Plex ist verbunden" else "Alles bereit",
                    "Der Launcher sucht die Server, auf die du Zugriff hast, und wählt die schnellste Verbindung."
                )
                Spacer(Modifier.height(24.dp))
                when {
                    list == null -> WaitingDots("Suche Server")
                    list.isEmpty() -> {
                        InfoLine("Kein erreichbarer Server gefunden. Prüfe, ob der Server läuft und für dich freigegeben ist.", Warn)
                        Spacer(Modifier.height(18.dp))
                        val f = rememberAutoFocus("empty")
                        Row {
                            SetupButton("Erneut suchen", Modifier.focusRequester(f)) { attempt++ }
                            Spacer(Modifier.width(12.dp))
                            SetupButton("Abmelden", primary = false) {
                                repo.logout()
                                loggedIn = false
                            }
                        }
                    }
                    else -> {
                        list.forEachIndexed { i, s ->
                            val how = when (s.kind) {
                                "lokal" -> "Im Heimnetz · schnellste Verbindung"
                                "extern" -> "Über das Internet"
                                "Relay" -> "Über Plex-Relay · langsamer, reduzierte Qualität"
                                else -> "Verbunden"
                            }
                            Reveal(3 + i) { StatusLine(true, s.name, how) }
                            Spacer(Modifier.height(10.dp))
                        }
                        Spacer(Modifier.height(18.dp))
                        val f = rememberAutoFocus("ok")
                        Reveal(4 + list.size) { SetupButton("Weiter", Modifier.focusRequester(f), onClick = onNext) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 3. Mediatheken

@Composable
private fun LibrariesStep(repo: PlexRepo, nextLabel: String, onNext: () -> Unit) {
    var sections by remember { mutableStateOf<List<LibrarySection>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) {
        val list = try { repo.librarySections(repo.loadServers()) } catch (e: Exception) { logW(e); emptyList() }
        // Vorauswahl: bisherige Wahl, sonst 4K-Mediatheken, sonst alle
        val saved = repo.newSections
        selected = saved ?: list.filter { it.title.lowercase().contains("4k") }.map { it.id }.toSet()
            .ifEmpty { list.map { it.id }.toSet() }
        sections = list
    }
    val multiServer = (sections?.map { it.serverId }?.distinct()?.size ?: 0) > 1

    StepTitle(
        "Mediatheken",
        "Was ist neu?",
        "Wähle die Mediatheken für „Neu in Filme“ und „Neu in Serien“ auf der Startseite. Die Seiten Filme und Serien zeigen immer alles."
    )
    Spacer(Modifier.height(20.dp))
    val list = sections
    if (list == null) {
        WaitingDots("Lade Mediatheken")
        return
    }
    if (list.isEmpty()) InfoLine("Keine Film- oder Serien-Mediatheken gefunden.", Warn)
    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
        list.forEachIndexed { i, sec ->
            val f = if (i == 0) rememberAutoFocus() else remember { FocusRequester() }
            val label = sec.title + if (multiServer) "  ·  ${sec.serverName}" else ""
            Reveal(3 + i) {
                ToggleRow(if (sec.type == "movie") "FILME" else "SERIEN", label, sec.id in selected, Modifier.focusRequester(f)) {
                    selected = if (sec.id in selected) selected - sec.id else selected + sec.id
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    Spacer(Modifier.height(14.dp))
    AnimatedVisibility(list.isNotEmpty() && selected.isEmpty()) {
        Column {
            InfoLine("Ohne Auswahl erscheinen auf der Startseite keine Neu-Reihen.", TextDim)
            Spacer(Modifier.height(10.dp))
        }
    }
    val none = if (list.isEmpty()) rememberAutoFocus() else remember { FocusRequester() }
    Reveal(4 + list.size) {
        SetupButton(nextLabel, Modifier.focusRequester(none)) {
            // Ohne gefundene Mediatheken nichts speichern, damit später die Vorauswahl greift
            if (list.isNotEmpty()) repo.newSections = selected
            onNext()
        }
    }
}

@Composable
private fun ToggleRow(tag: String, label: String, checked: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (focused) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f), tween(180), label = "bg")
    val border by animateColorAsState(if (focused) TextMain else Color.White.copy(alpha = 0.06f), tween(180), label = "bd")
    val check by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.5f), label = "ck")
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, tween(180), label = "sc")
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .width(520.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(2.dp, border, RoundedCornerShape(12.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggle() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                .background(Accent.copy(alpha = check))
                .border(2.dp, if (checked) Accent else TextDim, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            BasicText("✓", modifier = Modifier.graphicsLayer { scaleX = check; scaleY = check; alpha = check }, style = TextStyle(color = Bg, fontSize = 13.sp, fontWeight = FontWeight.Black))
        }
        Spacer(Modifier.width(14.dp))
        BasicText(tag, style = TextStyle(color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp), modifier = Modifier.width(52.dp))
        BasicText(label, style = TextStyle(color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
    }
}

// ---------------------------------------------------------------- 4. YouTube

@Composable
private fun YouTubeStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val yt = remember { YouTubeRepo(context) }
    var smartTube by remember { mutableStateOf(Updater.findSmartTube(context)) }
    var stText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var ytLoggedIn by remember { mutableStateOf(yt.loggedIn) }
    var showLogin by remember { mutableStateOf(false) }

    // Nach der Installation zurück im Launcher: SmartTube wird automatisch erkannt
    LaunchedEffect(Unit) {
        while (smartTube == null) {
            delay(2000)
            smartTube = Updater.findSmartTube(context)
        }
        stText = ""
    }

    AnimatedContent(showLogin, transitionSpec = { fadeIn(tween(350, delayMillis = 80)) togetherWith fadeOut(tween(200)) }, label = "yt") { login ->
        Column {
            if (login) {
                YouTubeCodeLogin(yt, onDone = { ytLoggedIn = true; showLogin = false }, onCancel = { showLogin = false })
            } else {
                StepTitle(
                    "YouTube",
                    "YouTube ohne Werbung",
                    "Videos laufen in der App SmartTube. Mit deinem Konto zeigt der Launcher Abos, Später ansehen und Verlauf. Dieser Schritt ist freiwillig."
                )
                Spacer(Modifier.height(24.dp))
                Reveal(3) {
                    StatusLine(smartTube != null, if (smartTube != null) "SmartTube ist installiert" else "SmartTube ist nicht installiert", stText)
                }
                Spacer(Modifier.height(12.dp))
                Reveal(4) {
                    StatusLine(ytLoggedIn, if (ytLoggedIn) "YouTube-Konto ist verbunden" else "YouTube-Konto ist nicht verbunden")
                }
                Spacer(Modifier.height(28.dp))
                val f = rememberAutoFocus(smartTube to ytLoggedIn)
                Reveal(5) {
                    Row {
                        when {
                            smartTube == null -> SetupButton(if (busy) "Wird geladen …" else "SmartTube installieren", Modifier.focusRequester(f)) {
                                if (busy) return@SetupButton
                                busy = true
                                scope.launch {
                                    try {
                                        val ok = Updater.installUrl(context, "https://github.com/yuliskov/SmartTube/releases/download/latest/smarttube_stable.apk", "smarttube.apk") { p ->
                                            stText = if (p >= 0) "Wird heruntergeladen · $p %" else "Wird heruntergeladen"
                                        }
                                        stText = if (ok) "Bitte im Installationsfenster bestätigen" else "Bitte die Installation erlauben und nochmals drücken"
                                    } catch (e: Exception) {
                                        logW(e)
                                        stText = "Fehler: ${e.message}"
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                            !ytLoggedIn -> SetupButton("Mit YouTube verbinden", Modifier.focusRequester(f)) { showLogin = true }
                            else -> SetupButton("Weiter", Modifier.focusRequester(f), onClick = onNext)
                        }
                        if (smartTube == null || !ytLoggedIn) {
                            Spacer(Modifier.width(12.dp))
                            SetupButton(if (smartTube != null) "Ohne Konto weiter" else "Überspringen", primary = false, onClick = onNext)
                        }
                    }
                }
            }
        }
    }
}

/** YouTube-Anmeldung im Stil der Einrichtung (gleiche Logik wie im YouTube-Bereich). */
@Composable
private fun YouTubeCodeLogin(yt: YouTubeRepo, onDone: () -> Unit, onCancel: () -> Unit) {
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
            logW(e)
            error = e.message ?: "Anmeldung fehlgeschlagen."
        }
    }
    StepTitle("YouTube", "Mit YouTube verbinden", "Öffne auf dem Handy $url, wähle dein Konto und gib diesen Code ein.")
    Spacer(Modifier.height(24.dp))
    Reveal(3) { CodeTiles(code) }
    Spacer(Modifier.height(20.dp))
    val err = error
    if (err == null) WaitingDots(if (code == null) "Code wird erstellt" else "Warte auf deine Bestätigung")
    else InfoLine(err, Warn)
    Spacer(Modifier.height(22.dp))
    val f = rememberAutoFocus(err)
    Row {
        if (err != null) {
            SetupButton("Neuen Code anfordern", Modifier.focusRequester(f)) { attempt++ }
            Spacer(Modifier.width(12.dp))
            SetupButton("Abbrechen", primary = false, onClick = onCancel)
        } else {
            SetupButton("Abbrechen", Modifier.focusRequester(f), primary = false, onClick = onCancel)
        }
    }
}

// ---------------------------------------------------------------- 5. Fertig

@Composable
private fun HomeStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val f = rememberAutoFocus()
    StepTitle(
        "Fertig",
        "Alles eingerichtet",
        "Damit die Home-Taste immer den Shield Launcher öffnet:"
    )
    Spacer(Modifier.height(20.dp))
    Reveal(3) { NumberLine(1, "Drücke nach der Einrichtung die Home-Taste der Fernbedienung.") }
    Spacer(Modifier.height(10.dp))
    Reveal(4) { NumberLine(2, "Erscheint eine Auswahl, wähle „Shield Launcher“ und „Immer“.") }
    Spacer(Modifier.height(10.dp))
    Reveal(5) { NumberLine(3, "Öffnet sich der Google-Startbildschirm, muss dieser einmalig per Computer deaktiviert werden.") }
    Spacer(Modifier.height(16.dp))
    Reveal(6) { InfoLine("Tipp: Unter Bildschirmschoner kannst du „Shield Launcher Filmbilder“ wählen.", TextDim) }
    Spacer(Modifier.height(26.dp))
    Reveal(7) {
        Row {
            SetupButton("Zum Launcher", Modifier.focusRequester(f), onClick = onNext)
            Spacer(Modifier.width(12.dp))
            SetupButton("Bildschirmschoner", primary = false) {
                val i = Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(i) } catch (e: Exception) {
                    logW(e)
                    try { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { logW(e2) }
                }
            }
        }
    }
}

@Composable
private fun NumberLine(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Accent.copy(alpha = 0.18f)).border(1.dp, Accent, CircleShape), contentAlignment = Alignment.Center) {
            BasicText("$n", style = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.width(14.dp))
        BasicText(text, style = TextStyle(color = TextSoft, fontSize = 14.sp, lineHeight = 20.sp))
    }
}

// ---------------------------------------------------------------- Einstellungen

@Composable
fun SettingsOverlay(
    repo: PlexRepo,
    onRunSetup: () -> Unit,
    onLibraries: () -> Unit,
    onYouTubeLogout: () -> Unit,
    onPlexLogout: () -> Unit
) {
    val context = LocalContext.current
    val yt = remember { YouTubeRepo(context) }
    var ytLoggedIn by remember { mutableStateOf(yt.loggedIn) }
    var confirmPlex by remember { mutableStateOf(false) }
    val first = rememberAutoFocus()
    val version = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    }
    val servers = remember { repo.loadServers() }

    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.96f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).trapFocus()) {
            BasicText("Einstellungen", style = TextStyle(color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(18.dp))
            SettingsRow("Einrichtung erneut starten", "Alle Schritte vom Anfang", "Starten", Modifier.focusRequester(first), onRunSetup)
            Spacer(Modifier.height(8.dp))
            SettingsRow("Mediatheken für „Neu“", "Welche Mediatheken auf der Startseite als neu erscheinen", "Wählen", Modifier, onLibraries)
            Spacer(Modifier.height(8.dp))
            SettingsRow(
                "YouTube",
                if (ytLoggedIn) "Konto verbunden" else "Nicht verbunden (Anmeldung im YouTube-Bereich)",
                if (ytLoggedIn) "Abmelden" else null,
                Modifier
            ) {
                yt.logout()
                ytLoggedIn = false
                onYouTubeLogout()
            }
            Spacer(Modifier.height(8.dp))
            SettingsRow(
                "Plex",
                servers.joinToString { "${it.name} (${it.kind.ifEmpty { "verbunden" }})" }.ifEmpty { "Kein Server" },
                if (confirmPlex) "Wirklich abmelden?" else "Abmelden",
                Modifier
            ) {
                if (confirmPlex) onPlexLogout() else confirmPlex = true
            }
            Spacer(Modifier.height(8.dp))
            SettingsRow("Android-Einstellungen", "Netzwerk, Bild, Ton, Apps und mehr", "Öffnen", Modifier) {
                try { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { logW(e) }
            }
            Spacer(Modifier.height(16.dp))
            BasicText("Shield Launcher $version  ·  Zurück schliesst diese Übersicht", style = TextStyle(color = TextDim, fontSize = 12.sp))
        }
    }
}

@Composable
private fun SettingsRow(name: String, status: String, action: String?, buttonModifier: Modifier, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tile).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(name, style = TextStyle(color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(2.dp))
            BasicText(status, style = TextStyle(color = TextSoft, fontSize = 12.sp))
        }
        if (action != null) {
            Spacer(Modifier.width(12.dp))
            PillButton(action, buttonModifier) { onClick() }
        }
    }
}
