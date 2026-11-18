package ch.jachermann.shieldlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.imageLoader
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Einstellungen des Bildschirmschoners. */
class ScreensaverPrefs(context: Context) {
    private val p = context.getSharedPreferences("screensaver", Context.MODE_PRIVATE)
    var intervalSec: Int
        get() = p.getInt("interval", 20)
        set(v) = p.edit().putInt("interval", v).apply()
    var dimPercent: Int
        get() = p.getInt("dim", 50)
        set(v) = p.edit().putInt("dim", v).apply()
    var showTitle: Boolean
        get() = p.getBoolean("title", true)
        set(v) = p.edit().putBoolean("title", v).apply()
    var showClock: Boolean
        get() = p.getBoolean("clock", true)
        set(v) = p.edit().putBoolean("clock", v).apply()
}

/** Die eigentliche Anzeige: Filmbilder mit Überblendung, langsamer Bewegung und Abdunklung. */
class ScreensaverView(context: Context) : FrameLayout(context) {

    private data class Slide(val url: String, val title: String)

    private val prefs = ScreensaverPrefs(context)
    private val handler = Handler(Looper.getMainLooper())
    private val images = arrayOf(ImageView(context), ImageView(context))
    private var front = 0
    private val dim = View(context)
    private val title = TextView(context)
    private val clock = TextView(context)
    private val empty = TextView(context)
    private val shade = View(context)
    private var slides: List<Slide> = emptyList()
    private var index = 0
    private var running = false

    init {
        setBackgroundColor(Color.BLACK)
        images.forEach {
            it.scaleType = ImageView.ScaleType.CENTER_CROP
            it.alpha = 0f
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        dim.setBackgroundColor(Color.BLACK)
        addView(dim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        shade.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x99000000.toInt(), 0x00000000))
        addView(shade, LayoutParams(LayoutParams.MATCH_PARENT, 260, Gravity.BOTTOM))
        title.setTextColor(0xCCF2F4F7.toInt())
        title.textSize = 26f
        title.typeface = Typeface.DEFAULT_BOLD
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = 80; bottomMargin = 64
        })
        clock.setTextColor(0xB3F2F4F7.toInt())
        clock.textSize = 30f
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            rightMargin = 80; topMargin = 56
        })
        empty.setTextColor(0x99F2F4F7.toInt())
        empty.textSize = 20f
        empty.text = "Noch keine Bilder vorhanden. Öffne zuerst einmal den Shield Launcher."
        empty.visibility = View.GONE
        addView(empty, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun loadSlides(): List<Slide> {
        val repo = PlexRepo(context)
        val items = mutableListOf<PlexItem>()
        repo.loadCache().forEach { items += it.items }
        items += repo.loadLibraryCache("movie").second
        items += repo.loadLibraryCache("show").second
        return items
            .mapNotNull { i -> i.backdropUrl?.let { Slide(it.replace("width=1280&height=720", "width=1920&height=1080"), i.title) } }
            .distinctBy { it.url }
            .shuffled()
    }

    fun start() {
        if (running) return
        running = true
        dim.alpha = prefs.dimPercent / 100f
        title.visibility = if (prefs.showTitle) View.VISIBLE else View.GONE
        clock.visibility = if (prefs.showClock) View.VISIBLE else View.GONE
        slides = loadSlides()
        if (slides.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        index = 0
        tickClock()
        next()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        images.forEach { it.animate().cancel() }
    }

    private fun tickClock() {
        if (!running) return
        clock.text = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
        handler.postDelayed({ tickClock() }, 20_000)
    }

    private fun next() {
        if (!running || slides.isEmpty()) return
        val slide = slides[index % slides.size]
        index++
        val back = 1 - front
        val view = images[back]
        val request = ImageRequest.Builder(context)
            .data(slide.url)
            .target(
                onSuccess = { drawable ->
                    view.setImageDrawable(drawable)
                    show(back, slide.title)
                },
                onError = {
                    handler.postDelayed({ next() }, 500)
                }
            )
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun show(back: Int, text: String) {
        if (!running) return
        val interval = prefs.intervalSec * 1000L
        val inView = images[back]
        val outView = images[front]
        // Langsame Kamerafahrt (Ken-Burns-Effekt), jedes Mal in eine andere Richtung
        inView.animate().cancel()
        inView.scaleX = 1.0f
        inView.scaleY = 1.0f
        inView.translationX = 0f
        inView.translationY = 0f
        inView.bringToFront()
        dim.bringToFront()
        shade.bringToFront()
        title.bringToFront()
        clock.bringToFront()
        empty.bringToFront()
        inView.alpha = 0f
        inView.animate().alpha(1f).setDuration(1800).start()
        inView.animate()
            .scaleX(1.1f).scaleY(1.1f)
            .translationX(Random.nextInt(-40, 41).toFloat())
            .translationY(Random.nextInt(-24, 25).toFloat())
            .setDuration(interval + 2000)
            .start()
        outView.animate().alpha(0f).setDuration(1800).start()
        front = back

        title.animate().alpha(0f).setDuration(500).withEndAction {
            title.text = text
            // Texte leicht versetzen, damit sich nichts ins OLED einbrennt
            (title.layoutParams as LayoutParams).apply {
                leftMargin = 64 + Random.nextInt(0, 48); bottomMargin = 48 + Random.nextInt(0, 40)
            }
            (clock.layoutParams as LayoutParams).apply {
                rightMargin = 64 + Random.nextInt(0, 48); topMargin = 40 + Random.nextInt(0, 40)
            }
            title.requestLayout()
            clock.requestLayout()
            title.animate().alpha(1f).setDuration(800).start()
        }.start()

        handler.postDelayed({ next() }, interval)
    }

}

/** Android-Bildschirmschoner ("Daydream"). */
class FilmDreamService : DreamService() {
    private var view: ScreensaverView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val v = ScreensaverView(this)
        view = v
        setContentView(v)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.start()
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }
}

/** Vorschau des Bildschirmschoners: eine beliebige Taste beendet sie. */
class ScreensaverPreviewActivity : Activity() {
    private lateinit var view: ScreensaverView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = ScreensaverView(this)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        view.start()
    }

    override fun onPause() {
        view.stop()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        finish()
        return true
    }
}

/** Einstellungen, erreichbar über die Android-Bildschirmschoner-Einstellungen und "Alle Apps". */
class ScreensaverSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = ScreensaverPrefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0C10.toInt())
            setPadding(96, 64, 96, 64)
        }
        root.addView(TextView(this).apply {
            text = "Bildschirmschoner"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF2F4F7.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Aktivieren: Einstellungen > Geräteeinstellungen > Bildschirmschoner > \"Shield Launcher Filmbilder\""
            textSize = 15f
            setTextColor(0xFFA9B4C4.toInt())
            setPadding(0, 12, 0, 32)
        })

        fun option(label: () -> String, onClick: () -> Unit) {
            val b = Button(this)
            b.isAllCaps = false
            b.textSize = 18f
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.text = label()
            b.setOnClickListener { onClick(); b.text = label() }
            root.addView(b, LinearLayout.LayoutParams(720, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 })
        }

        val intervals = listOf(10, 20, 30, 60)
        val dims = listOf(30, 50, 70)
        option({ "Bildwechsel alle: ${prefs.intervalSec} Sekunden" }) {
            prefs.intervalSec = intervals[(intervals.indexOf(prefs.intervalSec) + 1) % intervals.size]
        }
        option({ "Abdunkeln: ${prefs.dimPercent} %" }) {
            prefs.dimPercent = dims[(dims.indexOf(prefs.dimPercent) + 1) % dims.size]
        }
        option({ "Filmtitel anzeigen: ${if (prefs.showTitle) "Ja" else "Nein"}" }) { prefs.showTitle = !prefs.showTitle }
        option({ "Uhr anzeigen: ${if (prefs.showClock) "Ja" else "Nein"}" }) { prefs.showClock = !prefs.showClock }
        option({ "Vorschau starten" }) { startActivity(Intent(this, ScreensaverPreviewActivity::class.java)) }
        option({ "Android-Bildschirmschoner-Einstellungen öffnen" }) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_DREAM_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
        }
        setContentView(root)
    }
}
