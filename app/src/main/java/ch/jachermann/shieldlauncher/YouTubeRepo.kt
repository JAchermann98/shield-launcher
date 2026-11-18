package ch.jachermann.shieldlauncher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * YouTube über die interne Schnittstelle der YouTube-TV-App (wie SmartTube):
 * Anmeldung per Code (google.com/device), Inhalte über InnerTube mit dem TVHTML5-Client.
 * Abgespielt wird in SmartTube.
 */
class YouTubeRepo(context: Context) {

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val intervalSec: Int)

    private val prefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)

    val loggedIn: Boolean get() = prefs.getString("refresh_token", null) != null

    fun logout() {
        prefs.edit().remove("refresh_token").remove("access_token").remove("expires_at").apply()
    }

    // ---------- HTTP ----------

    private fun request(method: String, url: String, headers: Map<String, String>, body: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return Pair(code, text)
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }

    private val formHeaders = mapOf("Content-Type" to "application/x-www-form-urlencoded", "User-Agent" to TV_UA)

    // ---------- Zugangsdaten der YouTube-TV-App ----------

    /** client_id und client_secret aus dem öffentlichen Skript von youtube.com/tv (einen Tag zwischengespeichert). */
    private fun client(): Pair<String, String> {
        val cachedAt = prefs.getLong("client_at", 0)
        val id = prefs.getString("client_id", null)
        val secret = prefs.getString("client_secret", null)
        if (id != null && secret != null && System.currentTimeMillis() - cachedAt < 24 * 3600 * 1000L) return Pair(id, secret)
        try {
            val (c1, page) = request("GET", "https://www.youtube.com/tv", mapOf("User-Agent" to TV_UA))
            if (c1 in 200..299) {
                val src = Regex("id=\\\"base-js\\\" src=\\\"([^\\\"]+)\\\"").find(page)?.groupValues?.get(1)
                    ?: Regex("<script[^>]+src=\\\"([^\\\"]*/base\\.js)\\\"").find(page)?.groupValues?.get(1)
                if (src != null) {
                    val jsUrl = if (src.startsWith("http")) src else "https://www.youtube.com$src"
                    val (c2, js) = request("GET", jsUrl, mapOf("User-Agent" to TV_UA))
                    if (c2 in 200..299) {
                        val m = Regex("clientId:\\\"([^\\\"]+\\.apps\\.googleusercontent\\.com)\\\",[^\\\"]*?:\\\"([^\\\"]+)\\\"").find(js)
                        if (m != null) {
                            val r = Pair(m.groupValues[1], m.groupValues[2])
                            prefs.edit().putString("client_id", r.first).putString("client_secret", r.second)
                                .putLong("client_at", System.currentTimeMillis()).apply()
                            return r
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        // Rückfall: zuletzt bekannte Werte
        if (id != null && secret != null) return Pair(id, secret)
        return Pair(FALLBACK_CLIENT_ID, FALLBACK_CLIENT_SECRET)
    }

    // ---------- Anmeldung ----------

    suspend fun startLogin(): DeviceCode = withContext(Dispatchers.IO) {
        val (id, _) = client()
        val body = form(
            "client_id" to id,
            "scope" to SCOPE,
            "device_id" to UUID.randomUUID().toString(),
            "device_model" to "ytlr::"
        )
        var (code, text) = request("POST", "https://www.youtube.com/o/oauth2/device/code", formHeaders, body)
        if (code !in 200..299) {
            val r = request("POST", "https://oauth2.googleapis.com/device/code", formHeaders, form("client_id" to id, "scope" to SCOPE))
            code = r.first
            text = r.second
        }
        if (code !in 200..299) throw IllegalStateException("Code-Anfrage fehlgeschlagen ($code)")
        val o = JSONObject(text)
        DeviceCode(
            o.getString("device_code"),
            o.getString("user_code"),
            o.optString("verification_url", o.optString("verification_uri", "https://www.google.com/device")),
            o.optInt("interval", 5)
        )
    }

    /** true = angemeldet; false = noch warten; Ausnahme = abgelehnt oder abgelaufen. */
    suspend fun pollLogin(dc: DeviceCode): Boolean = withContext(Dispatchers.IO) {
        val (id, secret) = client()
        val (code, text) = request(
            "POST", "https://www.youtube.com/o/oauth2/token", formHeaders,
            form(
                "client_id" to id,
                "client_secret" to secret,
                "code" to dc.deviceCode,
                "grant_type" to "http://oauth.net/grant_type/device/1.0"
            )
        )
        val o = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        val err = o.optString("error")
        when {
            o.has("refresh_token") -> {
                saveToken(o)
                true
            }
            err == "authorization_pending" || err == "slow_down" -> false
            err.isNotEmpty() -> throw IllegalStateException(if (err == "access_denied") "Anmeldung abgelehnt" else "Code abgelaufen")
            code in 200..299 -> false
            else -> false
        }
    }

    private fun saveToken(o: JSONObject) {
        val e = prefs.edit()
        if (o.has("refresh_token")) e.putString("refresh_token", o.getString("refresh_token"))
        e.putString("access_token", o.getString("access_token"))
        e.putLong("expires_at", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000)
        e.apply()
    }

    private fun accessToken(): String? {
        val refresh = prefs.getString("refresh_token", null) ?: return null
        val token = prefs.getString("access_token", null)
        if (token != null && System.currentTimeMillis() < prefs.getLong("expires_at", 0)) return token
        val (id, secret) = client()
        val (code, text) = request(
            "POST", "https://www.youtube.com/o/oauth2/token", formHeaders,
            form("client_id" to id, "client_secret" to secret, "refresh_token" to refresh, "grant_type" to "refresh_token")
        )
        if (code !in 200..299) return null
        saveToken(JSONObject(text))
        return prefs.getString("access_token", null)
    }

    // ---------- Inhalte ----------

    private fun browse(browseId: String): JSONObject? {
        val token = accessToken() ?: return null
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "TVHTML5")
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", "de")
                .put("gl", "CH")))
            .put("browseId", browseId)
        val (code, text) = request(
            "POST", "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
            mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $token",
                "User-Agent" to TV_UA,
                "X-YouTube-Client-Name" to "7",
                "X-YouTube-Client-Version" to CLIENT_VERSION
            ),
            body.toString()
        )
        if (code !in 200..299) return null
        return try { JSONObject(text) } catch (e: Exception) { null }
    }

    private fun text(node: Any?): String {
        if (node !is JSONObject) return ""
        node.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = node.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" }
    }

    /** Sucht rekursiv den ersten Wert zu einem Schlüssel. */
    private fun findKey(node: Any?, key: String): Any? {
        when (node) {
            is JSONObject -> {
                if (node.has(key)) return node.get(key)
                for (k in node.keys()) findKey(node.get(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findKey(node.get(i), key)?.let { return it }
        }
        return null
    }

    private fun tileToItem(tile: JSONObject): PlexItem? {
        val videoId = (findKey(tile.optJSONObject("onSelectCommand"), "videoId") as? String) ?: return null
        val meta = tile.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
        val title = text(meta?.opt("title")).ifEmpty { return null }
        val lines = mutableListOf<String>()
        meta?.optJSONArray("lines")?.let { arr ->
            for (i in 0 until arr.length()) {
                val items = arr.optJSONObject(i)?.optJSONObject("lineRenderer")?.optJSONArray("items") ?: continue
                val line = (0 until items.length())
                    .map { text(items.optJSONObject(it)?.optJSONObject("lineItemRenderer")?.opt("text")).trim() }
                    .filter { it.isNotEmpty() && it != "•" }
                    .joinToString(" · ")
                if (line.isNotEmpty()) lines += line
            }
        }
        val thumbs = tile.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        var thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.isNotEmpty() }
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        if (thumb.startsWith("//")) thumb = "https:$thumb"
        val channel = lines.firstOrNull()?.substringBefore(" · ") ?: ""
        return PlexItem(
            serverId = "youtube",
            ratingKey = videoId,
            type = "youtube",
            title = title,
            subtitle = channel,
            year = "",
            durationMs = 0,
            viewOffsetMs = 0,
            posterUrl = thumb,
            landscapeUrl = thumb,
            backdropUrl = thumb,
            resolution = "",
            audio = "",
            summary = lines.joinToString("  ·  ")
        )
    }

    private fun collectTiles(node: Any?, out: MutableList<PlexItem>) {
        when (node) {
            is JSONObject -> {
                val tile = node.optJSONObject("tileRenderer")
                if (tile != null) {
                    tileToItem(tile)?.let { out += it }
                    return
                }
                for (k in node.keys()) collectTiles(node.get(k), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectTiles(node.get(i), out)
        }
    }

    /** Zeilen ("Shelves") mit Titel und Videos. */
    private fun collectShelves(node: Any?, out: MutableList<Pair<String, List<PlexItem>>>) {
        when (node) {
            is JSONObject -> {
                val shelf = node.optJSONObject("shelfRenderer")
                if (shelf != null) {
                    val title = text(findKey(shelf.optJSONObject("headerRenderer"), "title")).ifEmpty { text(shelf.opt("title")) }
                    val items = mutableListOf<PlexItem>()
                    collectTiles(shelf, items)
                    if (items.isNotEmpty()) out += Pair(title, items.distinctBy { it.ratingKey })
                    return
                }
                for (k in node.keys()) collectShelves(node.get(k), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectShelves(node.get(i), out)
        }
    }

    private fun tiles(browseId: String, max: Int = 40): List<PlexItem> {
        val out = mutableListOf<PlexItem>()
        collectTiles(browse(browseId) ?: return emptyList(), out)
        return out.distinctBy { it.ratingKey }.take(max)
    }

    suspend fun loadRows(): List<PlexRow> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<PlexRow>()
        val used = mutableSetOf<String>()
        fun add(title: String, items: List<PlexItem>) {
            if (items.isEmpty()) return
            var t = title.ifEmpty { "Empfohlen" }
            var n = 2
            while (t in used) { t = "$title ($n)"; n++ }
            used += t
            rows += PlexRow(t, items, landscape = true, resume = false)
        }
        try { add("Neu in deinen Abos", tiles("FEsubscriptions")) } catch (e: Exception) { }
        try {
            val shelves = mutableListOf<Pair<String, List<PlexItem>>>()
            collectShelves(browse("FEwhat_to_watch"), shelves)
            shelves.take(6).forEach { add(it.first, it.second) }
        } catch (e: Exception) { }
        try { add("Später ansehen", tiles("VLWL")) } catch (e: Exception) { }
        try { add("Verlauf", tiles("FEhistory")) } catch (e: Exception) { }
        rows
    }

    companion object {
        private const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
        private const val CLIENT_VERSION = "7.20250923.13.00"
        private const val TV_UA = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
        // Nur als Rückfall, falls das Auslesen aus youtube.com/tv scheitert (bekannte Werte der YouTube-TV-App)
        private const val FALLBACK_CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
        private const val FALLBACK_CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
    }
}
