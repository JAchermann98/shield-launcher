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
import androidx.compose.ui.layout.layout
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

@Composable
fun YouTubeScreen(
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
fun YouTubeLogin(yt: YouTubeRepo, onDone: () -> Unit) {
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
fun YouTubeSearchScreen(
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
    var fieldHadFocus by remember { mutableStateOf(false) }
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
                status = if (found.isEmpty()) "Nichts gefunden für „$text“" + (yt.lastSearchInfo.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "") else ""
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
            fieldHadFocus = false
            delay(80)
            try { fieldFocus.requestFocus() } catch (e: Exception) { }
            delay(120)
            keyboard?.show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Ergebnisse unterhalb der Suchleiste, nach oben abgeschnitten, damit nichts die Leiste überdeckt
        Box(Modifier.fillMaxSize().padding(top = 116.dp).clipToBounds()) {
        Box(Modifier.fillMaxWidth().layout { m, c ->
            val extra = 60.dp.roundToPx()
            val h = c.maxHeight + extra
            val p = m.measure(c.copy(minHeight = h, maxHeight = h))
            layout(p.width, c.maxHeight) { p.place(0, -extra) }
        }) {
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
        }
        }

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
                            try { boxFocus.requestFocus() } catch (e: Exception) { }
                            runSearch(query.text)
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                            .onFocusChanged {
                                // onFocusChanged meldet sich schon beim Einblenden (noch ohne Fokus),
                                // darum erst beenden, wenn das Feld wirklich einmal Fokus hatte
                                if (it.isFocused) {
                                    fieldHadFocus = true
                                    keyboard?.show()
                                } else if (fieldHadFocus && editing) {
                                    fieldHadFocus = false
                                    editing = false
                                }
                            }
                            .onPreviewKeyEvent { ev ->
                                val ne = ev.nativeKeyEvent
                                if (ne.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                                when (ne.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                        keyboard?.hide()
                                        editing = false
                                        try { boxFocus.requestFocus() } catch (e: Exception) { }
                                        true
                                    }
                                    else -> false
                                }
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
