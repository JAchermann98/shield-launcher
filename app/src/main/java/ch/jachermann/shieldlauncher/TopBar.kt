@file:OptIn(ExperimentalFoundationApi::class)

package ch.jachermann.shieldlauncher

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TopBar(
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
            // genau auf die nächste volle Minute warten
            delay(60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }
    LaunchedEffect(Unit) {
        try {
            updateAvailable = Updater.check(context) != null
        } catch (e: Exception) {
            logW(e)
        }
        try {
            if (!updateAvailable) updateAvailable = Updater.checkSmartTube(context).updateAvailable
        } catch (e: Exception) {
            logW(e)
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
            try { context.startActivity(i) } catch (e: Exception) { logW(e) }
        }) { color ->
            Image(Icons.Filled.Settings, contentDescription = "Einstellungen", colorFilter = ColorFilter.tint(color), modifier = Modifier.size(18.dp))
        }
    }
}

fun clock(): String = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())

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
fun TabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
fun IconButton(dot: Boolean, modifier: Modifier, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
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
fun GridIcon(color: Color) {
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
fun ImageViewIcon(color: Color) {
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
fun DownloadIcon(color: Color) {
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
