@file:OptIn(ExperimentalFoundationApi::class)

package ch.jachermann.shieldlauncher

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import kotlinx.coroutines.delay

@Composable
fun StartScreen(
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
fun TitleOrLogo(item: PlexItem, height: Dp) {
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
fun MetaRow(item: PlexItem) {
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

fun formatDuration(ms: Long): String {
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
fun rememberPivotSpec(pivot: Dp): BringIntoViewSpec {
    val px = with(LocalDensity.current) { pivot.toPx() }
    return remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - px
        }
    }
}

@Composable
fun ContentRow(
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
                        // Nur den eigenen Eintrag entfernen: Wenn sich Zeilen verschieben, hat eine
                        // andere Zeile diesen Platz evtl. schon übernommen
                        onDispose { if (requesters[rowIndex to index] === fr) requesters.remove(rowIndex to index) }
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
fun LandscapeCard(
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
const val LIB_WINDOW_TOP_IMAGE = 300 // Bildansicht: wie auf der Startseite
const val LIB_WINDOW_TOP_GRID = 70   // Rasteransicht: Reihen direkt unter der Kopfzeile
const val LIB_COLS = 7
const val LIB_NEW_H = 152        // Höhe der Neu-Reihe
const val LIB_FIRST_POSTER_H = 216 // erste Posterreihe inkl. Überschrift
const val LIB_POSTER_H = 194     // weitere Posterreihen
