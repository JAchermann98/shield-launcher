package ch.jachermann.shieldlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Test-App: Prüft, ob sich ein bestimmter Film direkt in der Plex-App öffnen lässt.
 * Alle Ergebnisse werden unten im Protokoll angezeigt (zum Abfotografieren).
 */
class TestActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private val prefs by lazy { getSharedPreferences("launcher_test", Context.MODE_PRIVATE) }

    private val clientId: String
        get() {
            var id = prefs.getString("client_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString("client_id", id).apply()
            }
            return id
        }

    private var selServerId: String? = null
    private var selRatingKey: String? = null
    private var selTitle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0A0C10"))
            setPadding(48, 32, 48, 32)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val buttonScroll = ScrollView(this).apply { addView(buttons) }
        root.addView(buttonScroll, LinearLayout.LayoutParams(560, LinearLayout.LayoutParams.MATCH_PARENT))

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#E3E8EF"))
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(false)
        }
        scroll = ScrollView(this).apply {
            addView(logView)
            isFocusable = true
            setPadding(32, 0, 0, 0)
        }
        root.addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        fun addButton(label: String, action: () -> Unit) {
            val b = Button(this).apply {
                text = label
                textSize = 17f
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { action() }
            }
            buttons.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        addButton("1. Mit Plex anmelden") { login() }
        addButton("2. Weiterschauen laden") { loadOnDeck() }
        addButton("Test A: plex://server Link") { testA() }
        addButton("Test B: app.plex.tv Link") { testB() }
        addButton("Test C: plex://play") { testC() }
        addButton("Test D: plex://preplay") { testD() }
        addButton("Test E: Android-TV-Einträge von Plex") { testE() }
        addButton("Installierte Apps auflisten") { listApps() }
        addButton("Protokoll löschen") { logView.text = "" }

        setContentView(root)

        log("Launcher Test ${packageManager.getPackageInfo(packageName, 0).versionName}")
        log("Plex-App: ${plexPackage() ?: "NICHT gefunden"}")
        log(if (prefs.getString("token", null) != null) "Plex-Anmeldung vorhanden." else "Noch nicht bei Plex angemeldet. Starte mit Schritt 1.")
    }

    // ---------- Hilfsfunktionen ----------

    private fun log(msg: String) {
        runOnUiThread {
            logView.append(msg + "\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun bg(block: () -> Unit) {
        Thread {
            try {
                block()
            } catch (e: Exception) {
                log("FEHLER: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun plexHeaders(token: String? = null): Map<String, String> {
        val h = mutableMapOf(
            "Accept" to "application/json",
            "X-Plex-Product" to "Shield Launcher",
            "X-Plex-Version" to "0.1",
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Platform" to "Android",
            "X-Plex-Device" to "SHIELD Android TV",
            "X-Plex-Device-Name" to "Shield Launcher Test"
        )
        if (token != null) h["X-Plex-Token"] = token
        return h
    }

    private fun http(method: String, url: String, headers: Map<String, String>, timeoutMs: Int = 10000): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (method == "POST") {
            conn.doOutput = true
            conn.outputStream.use { it.write(ByteArray(0)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return Pair(code, body)
    }

    private fun plexPackage(): String? {
        val candidates = listOf("com.plexapp.android", "com.plexapp.android.tv")
        for (p in candidates) {
            try {
                packageManager.getPackageInfo(p, 0)
                return p
            } catch (ignored: Exception) {
            }
        }
        return null
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---------- 1. Anmeldung ----------

    private fun login() = bg {
        log("--- Anmeldung ---")
        val (code, body) = http("POST", "https://plex.tv/api/v2/pins", plexHeaders())
        if (code !in 200..299) {
            log("PIN-Anfrage fehlgeschlagen: HTTP $code $body")
            return@bg
        }
        val pin = JSONObject(body)
        val pinId = pin.getLong("id")
        val pinCode = pin.getString("code")
        log("")
        log("Öffne auf Handy oder PC:  plex.tv/link")
        log("und gib diesen Code ein:   $pinCode")
        log("")
        log("Warte auf Bestätigung (max. 5 Minuten) ...")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 5 * 60 * 1000) {
            Thread.sleep(2000)
            val (c2, b2) = http("GET", "https://plex.tv/api/v2/pins/$pinId", plexHeaders())
            if (c2 in 200..299) {
                val token = JSONObject(b2).optString("authToken", "")
                if (token.isNotEmpty() && token != "null") {
                    prefs.edit().putString("token", token).apply()
                    log("Angemeldet. Weiter mit Schritt 2.")
                    return@bg
                }
            }
        }
        log("Zeit abgelaufen. Bitte Schritt 1 wiederholen.")
    }

    // ---------- 2. Weiterschauen laden ----------

    private fun loadOnDeck() = bg {
        val token = prefs.getString("token", null)
        if (token == null) {
            log("Zuerst Schritt 1 ausführen.")
            return@bg
        }
        log("--- Server suchen ---")
        val (code, body) = http("GET", "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", plexHeaders(token))
        if (code !in 200..299) {
            log("Server-Liste fehlgeschlagen: HTTP $code")
            return@bg
        }
        val resources = JSONArray(body)
        var found = false
        for (i in 0 until resources.length()) {
            val r = resources.getJSONObject(i)
            if (!r.optString("provides").contains("server")) continue
            val name = r.optString("name")
            val serverId = r.optString("clientIdentifier")
            val serverToken = r.optString("accessToken", token)
            log("Server: $name (owned=${r.optBoolean("owned")})")
            log("  ID: $serverId")

            val conns = r.optJSONArray("connections") ?: JSONArray()
            val uris = mutableListOf<Pair<String, Boolean>>()
            for (j in 0 until conns.length()) {
                val c = conns.getJSONObject(j)
                uris.add(Pair(c.optString("uri"), c.optBoolean("relay")))
            }
            uris.sortBy { it.second }

            var baseUri: String? = null
            for ((uri, relay) in uris) {
                try {
                    val (ic, _) = http("GET", "$uri/identity", plexHeaders(serverToken), 4000)
                    if (ic in 200..299) {
                        baseUri = uri
                        log("  Verbindung OK${if (relay) " (Relay)" else ""}")
                        break
                    }
                } catch (ignored: Exception) {
                }
            }
            if (baseUri == null) {
                log("  Keine Verbindung möglich.")
                continue
            }

            val (dc, db) = http("GET", "$baseUri/library/onDeck", plexHeaders(serverToken))
            if (dc !in 200..299) {
                log("  Weiterschauen fehlgeschlagen: HTTP $dc")
                continue
            }
            val items = JSONObject(db).optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: JSONArray()
            log("  Weiterschauen: ${items.length()} Einträge")
            for (k in 0 until minOf(items.length(), 5)) {
                val m = items.getJSONObject(k)
                val title = if (m.optString("type") == "episode") {
                    "${m.optString("grandparentTitle")} – ${m.optString("title")}"
                } else m.optString("title")
                log("   • $title  [ratingKey ${m.optString("ratingKey")}]")
                if (!found) {
                    selServerId = serverId
                    selRatingKey = m.optString("ratingKey")
                    selTitle = title
                    found = true
                }
            }
        }
        if (found) {
            log("")
            log("Ausgewählt für Tests: $selTitle")
            log("Jetzt Test A bis E nacheinander ausprobieren.")
        } else {
            log("Kein Weiterschauen-Eintrag gefunden. Starte in Plex kurz einen Film, brich ab und lade neu.")
        }
    }

    // ---------- Tests ----------

    private fun requireSelection(): Boolean {
        if (selServerId == null || selRatingKey == null) {
            log("Zuerst Schritt 2 ausführen.")
            return false
        }
        return true
    }

    private fun launch(label: String, uri: String, withPackage: Boolean = true) {
        log("--- $label ---")
        log(uri)
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            if (withPackage) plexPackage()?.let { i.setPackage(it) }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            log("Gestartet. Öffnet Plex den Film '$selTitle'?")
        } catch (e: Exception) {
            log("Konnte nicht gestartet werden: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun metaKey() = "/library/metadata/$selRatingKey"

    private fun testA() {
        if (!requireSelection()) return
        launch("Test A", "plex://server://$selServerId/com.plexapp.plugins.library${metaKey()}")
    }

    private fun testB() {
        if (!requireSelection()) return
        launch("Test B", "https://app.plex.tv/desktop/#!/server/$selServerId/details?key=${enc(metaKey())}")
    }

    private fun testC() {
        if (!requireSelection()) return
        launch("Test C", "plex://play/?metadataKey=${enc(metaKey())}&server=$selServerId")
    }

    private fun testD() {
        if (!requireSelection()) return
        launch("Test D", "plex://preplay/?metadataKey=${enc(metaKey())}&server=$selServerId")
    }

    private fun testE() = bg {
        log("--- Test E: Einträge, die Plex an Android TV meldet ---")
        val sources = listOf(
            "Weiterschauen (watch_next)" to "content://android.media.tv/watch_next_program",
            "Kanal-Zeilen (preview)" to "content://android.media.tv/preview_program"
        )
        var firstIntent: String? = null
        var firstTitle: String? = null
        for ((label, uriStr) in sources) {
            try {
                val c = contentResolver.query(Uri.parse(uriStr), null, null, null, null)
                if (c == null) {
                    log("$label: keine Antwort")
                    continue
                }
                c.use { cur ->
                    val pkgIdx = cur.getColumnIndex("package_name")
                    val titleIdx = cur.getColumnIndex("title")
                    val intentIdx = cur.getColumnIndex("intent_uri")
                    var count = 0
                    log("$label: ${cur.count} Einträge insgesamt")
                    while (cur.moveToNext()) {
                        val pkg = if (pkgIdx >= 0) cur.getString(pkgIdx) ?: "" else ""
                        if (!pkg.contains("plex")) continue
                        val title = if (titleIdx >= 0) cur.getString(titleIdx) ?: "" else ""
                        val intent = if (intentIdx >= 0) cur.getString(intentIdx) ?: "" else ""
                        if (count < 3) {
                            log("  • $title")
                            log("    $intent")
                        }
                        if (firstIntent == null && intent.isNotEmpty()) {
                            firstIntent = intent
                            firstTitle = title
                        }
                        count++
                    }
                    log("  davon von Plex: $count")
                }
            } catch (e: SecurityException) {
                log("$label: KEINE BERECHTIGUNG (${e.message})")
            } catch (e: Exception) {
                log("$label: Fehler ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        val fi = firstIntent
        if (fi != null) {
            log("Starte ersten Plex-Eintrag: $firstTitle")
            runOnUiThread {
                try {
                    val i = Intent.parseUri(fi, Intent.URI_INTENT_SCHEME)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    log("Gestartet. Öffnet Plex genau diesen Titel?")
                } catch (e: Exception) {
                    log("Start fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } else {
            log("Kein startbarer Plex-Eintrag gefunden.")
        }
    }

    // ---------- Apps ----------

    private fun listApps() {
        log("--- Installierte TV-Apps ---")
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
        apps.sortedBy { it.loadLabel(packageManager).toString().lowercase() }.forEach {
            log("${it.loadLabel(packageManager)}  →  ${it.activityInfo.packageName}")
        }
    }
}
