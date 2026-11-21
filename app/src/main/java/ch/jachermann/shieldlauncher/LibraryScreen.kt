@file:OptIn(ExperimentalFoundationApi::class)

package ch.jachermann.shieldlauncher

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
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
            logW(e)
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
fun NewRow(
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
fun LetterButton(letter: String, modifier: Modifier, onClick: () -> Unit) {
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
fun PosterCard(
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
