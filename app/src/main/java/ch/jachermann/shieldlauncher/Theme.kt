@file:OptIn(ExperimentalFoundationApi::class)

package ch.jachermann.shieldlauncher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Bg = Color(0xFF0A0C10)
val TextMain = Color(0xFFF2F4F7)
val TextSoft = Color(0xFFC9D3E0)
val TextDim = Color(0xFFA9B4C4)
val Tile = Color(0xFF1C222C)

enum class Tab { START, FILME, SERIEN, YOUTUBE, YTSEARCH }

val Tab.isYouTube: Boolean get() = this == Tab.YOUTUBE || this == Tab.YTSEARCH

/** Verbindet Kopfzeile und Inhalt: wohin der Fokus beim Hoch-/Runterdrücken springt. */
class FocusBridge {
    var toTop: () -> Unit = {}
    var toContent: () -> Unit = {}
}

// Layout der Startseite (in dp, Bildschirm 960 x 540)
const val FOCUS_Y = 318
const val ROW_STEP = 168
const val PREV_Y = -118
