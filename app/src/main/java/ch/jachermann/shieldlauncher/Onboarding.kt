package ch.jachermann.shieldlauncher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Merkt sich, ob die Einrichtung abgeschlossen ist. */
object Setup {
    private fun prefs(context: Context) = context.getSharedPreferences("launcher_ui", Context.MODE_PRIVATE)
    fun isDone(context: Context) = prefs(context).getBoolean("onboarding_done", false)
    fun setDone(context: Context, done: Boolean) = prefs(context).edit().putBoolean("onboarding_done", done).apply()
}

/** Schritte der Einrichtung. */
enum class SetupStep(val title: String) {
    WELCOME("Willkommen"),
    PLEX("Plex verbinden"),
    LIBRARIES("Mediatheken"),
    YOUTUBE("YouTube"),
    HOME("Startbildschirm")
}

/**
 * Einrichtung beim ersten Start. Mit onlyStep wird nur ein einzelner Schritt gezeigt
 * (z. B. die Mediatheken-Auswahl aus den Einstellungen).
 */
@Composable
fun OnboardingFlow(repo: PlexRepo, start: SetupStep = SetupStep.WELCOME, onlyStep: Boolean = false, onDone: () -> Unit) {
    var step by remember { mutableStateOf(start) }
    fun next() {
        if (onlyStep || step == SetupStep.HOME) onDone()
        else step = SetupStep.entries[step.ordinal + 1]
    }
    fun back() {
        if (onlyStep) onDone()
        else if (step.ordinal > 0) step = SetupStep.entries[step.ordinal - 1]
    }
    BackHandler(enabled = onlyStep || step.ordinal > 0) { back() }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 40.dp)) {
            if (!onlyStep) {
                StepIndicator(step)
                Spacer(Modifier.height(28.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    SetupStep.WELCOME -> WelcomeStep(::next)
                    SetupStep.PLEX -> PlexStep(repo, ::next)
                    SetupStep.LIBRARIES -> LibrariesStep(repo, if (onlyStep) "Speichern" else "Weiter", ::next)
                    SetupStep.YOUTUBE -> YouTubeStep(::next)
                    SetupStep.HOME -> HomeStep(::next)
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(current: SetupStep) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SetupStep.entries.forEachIndexed { i, s ->
            val active = s == current
            val done = s.ordinal < current.ordinal
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(if (active) TextMain else if (done) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                BasicText("${i + 1}", style = TextStyle(color = if (active) Bg else TextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(Modifier.width(6.dp))
            BasicText(s.title, style = TextStyle(color = if (active) TextMain else TextDim, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal))
            if (i < SetupStep.entries.size - 1) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.width(18.dp).height(1.dp).background(Color.White.copy(alpha = 0.2f)))
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- Bausteine

@Composable
private fun StepTitle(title: String, text: String) {
    BasicText(title, style = TextStyle(color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold))
    Spacer(Modifier.height(10.dp))
    BasicText(text, style = TextStyle(color = TextSoft, fontSize = 15.sp, lineHeight = 22.sp))
}

/** Grosser Knopf; der erste einer Seite bekommt automatisch den Fokus. */
@Composable
fun SetupButton(label: String, modifier: Modifier = Modifier, primary: Boolean = true, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> TextMain
                    primary -> Color.White.copy(alpha = 0.22f)
                    else -> Color.White.copy(alpha = 0.08f)
                }
            )
            .clickable(interactionSource = src, indication = null) { onClick() }
            .padding(horizontal = 22.dp, vertical = 10.dp)
    ) {
        BasicText(label, style = TextStyle(color = if (focused) Bg else TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun InfoLine(text: String, color: Color = TextSoft) {
    BasicText(text, style = TextStyle(color = color, fontSize = 14.sp, lineHeight = 20.sp))
}

@Composable
private fun rememberAutoFocus(key: Any? = Unit): FocusRequester {
    val f = remember { FocusRequester() }
    LaunchedEffect(key) {
        delay(150)
        try { f.requestFocus() } catch (e: Exception) { }
    }
    return f
}

// ---------------------------------------------------------------- 1. Willkommen

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    val focus = rememberAutoFocus()
    Column {
        StepTitle(
            "Willkommen beim Shield Launcher",
            "Ein Startbildschirm ohne Werbung für deine Shield.\n\n" +
                "• Filme und Serien aus Plex mit grossen Bildern, abgespielt direkt in der Plex-App\n" +
                "• YouTube ohne Werbung über SmartTube, mit Abos, Später ansehen und Suche\n" +
                "• Bildschirmschoner mit Bildern aus deiner Mediathek\n\n" +
                "Die Einrichtung dauert etwa zwei Minuten. Du brauchst dein Handy für die Anmeldungen."
        )
        Spacer(Modifier.height(32.dp))
        SetupButton("Los geht's", Modifier.focusRequester(focus), onClick = onNext)
    }
}

// ---------------------------------------------------------------- 2. Plex

@Composable
private fun PlexStep(repo: PlexRepo, onNext: () -> Unit) {
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
        if (found.isNotEmpty()) repo.saveServers(found)
        servers = found
    }

    Column {
        if (!loggedIn) {
            StepTitle("Mit Plex verbinden", "Öffne auf dem Handy plex.tv/link, melde dich an und gib diesen Code ein:")
            Spacer(Modifier.height(24.dp))
            BasicText(code ?: "…", style = TextStyle(color = TextMain, fontSize = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp))
            error?.let {
                Spacer(Modifier.height(18.dp))
                InfoLine(it, Color(0xFFFFB4A8))
                Spacer(Modifier.height(14.dp))
                val f = rememberAutoFocus(it)
                SetupButton("Neuen Code anfordern", Modifier.focusRequester(f)) { attempt++ }
            }
        } else {
            StepTitle("Plex ist verbunden", "Der Launcher hat nach Servern gesucht, auf die du Zugriff hast.")
            Spacer(Modifier.height(22.dp))
            val list = servers
            when {
                list == null -> InfoLine("Suche Server …")
                list.isEmpty() -> {
                    InfoLine("Kein erreichbarer Server gefunden. Prüfe, ob der Server läuft und für dich freigegeben ist.", Color(0xFFFFB4A8))
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
                    list.forEach { s ->
                        val how = when (s.kind) {
                            "lokal" -> "im Heimnetz (schnellste Verbindung)"
                            "extern" -> "über das Internet"
                            "Relay" -> "über Plex-Relay (langsamer, max. 2 Mbit/s)"
                            else -> ""
                        }
                        InfoLine("✓  ${s.name}  ·  $how", TextMain)
                        Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(26.dp))
                    val f = rememberAutoFocus("ok")
                    SetupButton("Weiter", Modifier.focusRequester(f), onClick = onNext)
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

    Column {
        StepTitle(
            "Was ist neu?",
            "Wähle die Mediatheken für die Reihen „Neu in Filme“ und „Neu in Serien“ auf der Startseite. " +
                "Die Seiten Filme und Serien zeigen immer alle Mediatheken."
        )
        Spacer(Modifier.height(18.dp))
        val list = sections
        if (list == null) {
            InfoLine("Lade Mediatheken …")
        } else {
            if (list.isEmpty()) InfoLine("Keine Film- oder Serien-Mediatheken gefunden.", Color(0xFFFFB4A8))
            Column(Modifier.weight(1f, fill = false).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                list.forEachIndexed { i, sec ->
                    val f = if (i == 0) rememberAutoFocus() else remember { FocusRequester() }
                    val label = (if (sec.type == "movie") "Filme" else "Serien") + "  ·  " + sec.title +
                        if (multiServer) "  (${sec.serverName})" else ""
                    ToggleRow(label, sec.id in selected, Modifier.focusRequester(f)) {
                        selected = if (sec.id in selected) selected - sec.id else selected + sec.id
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            val none = if (list.isEmpty()) rememberAutoFocus() else remember { FocusRequester() }
            SetupButton(nextLabel, Modifier.focusRequester(none)) {
                repo.newSections = selected
                onNext()
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Row(
        modifier
            .width(520.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(alpha = 0.16f) else Tile)
            .border(2.dp, if (focused) TextMain else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(interactionSource = src, indication = null) { onToggle() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                .background(if (checked) TextMain else Color.Transparent)
                .border(2.dp, TextMain, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) BasicText("✓", style = TextStyle(color = Bg, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.width(12.dp))
        BasicText(label, style = TextStyle(color = TextMain, fontSize = 14.sp))
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

    if (showLogin) {
        Column {
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                YouTubeLogin(yt) {
                    ytLoggedIn = true
                    showLogin = false
                }
            }
            Spacer(Modifier.height(10.dp))
            val f = rememberAutoFocus()
            SetupButton("Abbrechen", Modifier.focusRequester(f), primary = false) { showLogin = false }
        }
        return
    }

    Column {
        StepTitle(
            "YouTube ohne Werbung",
            "Videos laufen in der App SmartTube. Mit deinem YouTube-Konto zeigt der Launcher deine Abos, " +
                "Später ansehen und den Verlauf. Dieser Schritt ist freiwillig."
        )
        Spacer(Modifier.height(22.dp))
        InfoLine(if (smartTube != null) "✓  SmartTube ist installiert" else "•  SmartTube ist noch nicht installiert", if (smartTube != null) TextMain else TextSoft)
        if (stText.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            InfoLine(stText, TextDim)
        }
        Spacer(Modifier.height(6.dp))
        InfoLine(if (ytLoggedIn) "✓  YouTube-Konto ist verbunden" else "•  YouTube-Konto ist nicht verbunden", if (ytLoggedIn) TextMain else TextSoft)
        Spacer(Modifier.height(26.dp))

        val f = rememberAutoFocus(smartTube to ytLoggedIn)
        Row {
            when {
                smartTube == null -> SetupButton("SmartTube installieren", Modifier.focusRequester(f)) {
                    if (busy) return@SetupButton
                    busy = true
                    scope.launch {
                        try {
                            val ok = Updater.installUrl(context, "https://github.com/yuliskov/SmartTube/releases/download/latest/smarttube_stable.apk", "smarttube.apk") { p ->
                                stText = if (p >= 0) "Wird heruntergeladen … $p %" else "Wird heruntergeladen …"
                            }
                            stText = if (ok) "Bitte im Installationsfenster bestätigen." else "Bitte die Installation erlauben und danach nochmals drücken."
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
                SetupButton(if (smartTube != null && !ytLoggedIn) "Ohne Konto weiter" else "Überspringen", primary = false, onClick = onNext)
            }
        }
    }
}

// ---------------------------------------------------------------- 5. Startbildschirm

@Composable
private fun HomeStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val f = rememberAutoFocus()
    Column {
        StepTitle(
            "Fast fertig",
            "Damit die Home-Taste der Fernbedienung immer den Shield Launcher öffnet:\n\n" +
                "1.  Drücke nach der Einrichtung die Home-Taste.\n" +
                "2.  Erscheint eine Auswahl, wähle „Shield Launcher“ und „Immer“.\n" +
                "3.  Erscheint keine Auswahl, öffnet die Home-Taste noch den Google-Startbildschirm. " +
                "Dann muss dieser einmalig per Computer deaktiviert werden (siehe Anleitung).\n\n" +
                "Bildschirmschoner: Einstellungen › Geräteeinstellungen › Bildschirmschoner › „Shield Launcher Filmbilder“."
        )
        Spacer(Modifier.height(28.dp))
        Row {
            SetupButton("Fertig", Modifier.focusRequester(f), onClick = onNext)
            Spacer(Modifier.width(12.dp))
            SetupButton("Bildschirmschoner einstellen", primary = false) {
                val i = Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(i) } catch (e: Exception) {
                    logW(e)
                    try { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { logW(e2) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Einstellungen

@Composable
fun SettingsOverlay(
    repo: PlexRepo,
    onClose: () -> Unit,
    onRunSetup: () -> Unit,
    onLibraries: () -> Unit,
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
        Column(Modifier.width(560.dp)) {
            BasicText("Einstellungen", style = TextStyle(color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(18.dp))
            SettingsRow("Einrichtung erneut starten", "Alle Schritte vom Anfang", "Starten", Modifier.focusRequester(first), onRunSetup)
            Spacer(Modifier.height(8.dp))
            SettingsRow("Mediatheken für „Neu“", "Welche Mediatheken auf der Startseite als neu erscheinen", "Wählen", Modifier, onLibraries)
            Spacer(Modifier.height(8.dp))
            SettingsRow(
                "YouTube",
                if (ytLoggedIn) "Konto verbunden" else "Nicht verbunden (Anmeldung im YouTube-Bereich)",
                if (ytLoggedIn) "Abmelden" else "–",
                Modifier
            ) {
                if (ytLoggedIn) {
                    yt.logout()
                    ytLoggedIn = false
                }
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
private fun SettingsRow(name: String, status: String, action: String, buttonModifier: Modifier, onClick: () -> Unit) {
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
        Spacer(Modifier.width(12.dp))
        PillButton(action, buttonModifier) { onClick() }
    }
}
