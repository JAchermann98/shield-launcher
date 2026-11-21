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
            logW(e)
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

    @Synchronized
    private fun accessToken(): String? {
        val refresh = prefs.getString("refresh_token", null) ?: return null
        val token = prefs.getString("access_token", null)
        if (token != null && System.currentTimeMillis() < prefs.getLong("expires_at", 0)) return token
        val (id, secret) = client()
        val (code, text) = request(
            "POST", "https://www.youtube.com/o/oauth2/token", formHeaders,
            form("client_id" to id, "client_secret" to secret, "refresh_token" to refresh, "grant_type" to "refresh_token")
        )
        if (code !in 200..299) {
            // Zugriff wurde widerrufen oder ist abgelaufen: abmelden, damit die Anmeldung neu erscheint
            if (text.contains("invalid_grant")) logout()
            return null
        }
        saveToken(JSONObject(text))
        return prefs.getString("access_token", null)
    }

    // ---------- Inhalte ----------

    private fun browse(browseId: String): JSONObject? = innertube("browse", "browseId", browseId)

    /** Letzter Fehler der Suche, wird im Launcher angezeigt. */
    var lastSearchInfo = ""
        private set

    /** Anfrage mit dem Web-Client ohne Anmeldung (für die Suche über ganz YouTube). */
    private fun innertubeWeb(endpoint: String, key: String, value: String): Pair<Int, JSONObject?> {
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", WEB_VERSION)
                .put("hl", "de")
                .put("gl", "CH")))
            .put(key, value)
        val (code, text) = request(
            "POST", "https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false",
            mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
                "X-YouTube-Client-Name" to "1",
                "X-YouTube-Client-Version" to WEB_VERSION,
                "Origin" to "https://www.youtube.com"
            ),
            body.toString()
        )
        return Pair(code, if (code in 200..299) try { JSONObject(text) } catch (e: Exception) { null } else null)
    }

    private var lastCode = 0

    private fun innertube(endpoint: String, key: String, value: String): JSONObject? {
        val token = accessToken() ?: return null
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "TVHTML5")
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", "de")
                .put("gl", "CH")))
            .put(key, value)
        val (code, text) = request(
            "POST", "https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false",
            mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $token",
                "User-Agent" to TV_UA,
                "X-YouTube-Client-Name" to "7",
                "X-YouTube-Client-Version" to CLIENT_VERSION
            ),
            body.toString()
        )
        lastCode = code
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

    private fun thumbOf(arr: JSONArray?, videoId: String): String {
        var t = arr?.optJSONObject(arr.length() - 1)?.optString("url")?.takeIf { it.isNotEmpty() }
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        if (t.startsWith("//")) t = "https:$t"
        return t
    }

    private fun simpleItem(videoId: String, title: String, channel: String, info: String, thumb: String) = PlexItem(
        serverId = "youtube", ratingKey = videoId, type = "youtube", title = title, subtitle = channel,
        year = "", durationMs = 0, viewOffsetMs = 0, posterUrl = thumb, landscapeUrl = thumb, backdropUrl = thumb,
        resolution = "", audio = "", summary = listOf(channel, info).filter { it.isNotEmpty() }.joinToString("  ·  ")
    )

    /** Web-Format: videoRenderer, compactVideoRenderer, gridVideoRenderer */
    private fun videoRendererToItem(v: JSONObject): PlexItem? {
        val id = v.optString("videoId").ifEmpty { return null }
        val title = text(v.opt("title")).ifEmpty { text(v.opt("headline")) }.ifEmpty { return null }
        val channel = text(v.opt("ownerText")).ifEmpty { text(v.opt("shortBylineText")) }.ifEmpty { text(v.opt("longBylineText")) }
        val info = listOf(text(v.opt("viewCountText")).ifEmpty { text(v.opt("shortViewCountText")) },
            text(v.opt("publishedTimeText")), text(v.opt("lengthText"))).filter { it.isNotEmpty() }.joinToString(" · ")
        return simpleItem(id, title, channel, info, thumbOf(v.optJSONObject("thumbnail")?.optJSONArray("thumbnails"), id))
    }

    /** Neues Format: lockupViewModel */
    private fun lockupToItem(l: JSONObject): PlexItem? {
        if (l.optString("contentType").let { it.isNotEmpty() && it != "LOCKUP_CONTENT_TYPE_VIDEO" }) return null
        val id = l.optString("contentId").ifEmpty { (findKey(l, "videoId") as? String) ?: return null }
        val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = meta?.optJSONObject("title")?.optString("content").orEmpty().ifEmpty { return null }
        val parts = mutableListOf<String>()
        (findKey(meta, "metadataRows") as? JSONArray)?.let { rows ->
            for (i in 0 until rows.length()) {
                val ps = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
                for (j in 0 until ps.length()) ps.optJSONObject(j)?.optJSONObject("text")?.optString("content")
                    ?.takeIf { it.isNotEmpty() }?.let { parts += it }
            }
        }
        val sources = findKey(l.optJSONObject("contentImage"), "sources") as? JSONArray
        return simpleItem(id, title, parts.firstOrNull() ?: "", parts.drop(1).joinToString(" · "), thumbOf(sources, id))
    }

    private fun collectTiles(node: Any?, out: MutableList<PlexItem>) {
        when (node) {
            is JSONObject -> {
                val tile = node.optJSONObject("tileRenderer")
                if (tile != null) {
                    tileToItem(tile)?.let { out += it }
                    return
                }
                for (key in listOf("videoRenderer", "compactVideoRenderer", "gridVideoRenderer")) {
                    val v = node.optJSONObject(key) ?: continue
                    videoRendererToItem(v)?.let { out += it }
                    return
                }
                node.optJSONObject("lockupViewModel")?.let { l ->
                    lockupToItem(l)?.let { out += it }
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

    /** Suche: liefert die gefundenen Videos. */
    suspend fun search(query: String): List<PlexItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<PlexItem>()
        lastSearchInfo = ""
        // 1. Über ganz YouTube mit dem Web-Client suchen (liefert die vollständigen Treffer)
        val (webCode, web) = try { innertubeWeb("search", "query", query) } catch (e: Exception) { Pair(-1, null) }
        if (web != null) collectTiles(web, out)
        // 2. Falls das nichts bringt: Suche mit dem angemeldeten TV-Client
        if (out.isEmpty()) {
            val tv = try { innertube("search", "query", query) } catch (e: Exception) { null }
            if (tv != null) collectTiles(tv, out)
            if (out.isEmpty()) lastSearchInfo = "Web $webCode, TV $lastCode" + if (tv != null) ", Antwort ohne Videos" else ""
        }
        out.distinctBy { it.ratingKey }.take(60)
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
        try { add("Neu in deinen Abos", tiles("FEsubscriptions")) } catch (e: Exception) { logW(e) }
        try {
            val shelves = mutableListOf<Pair<String, List<PlexItem>>>()
            collectShelves(browse("FEwhat_to_watch"), shelves)
            shelves.take(6).forEach { add(it.first, it.second) }
        } catch (e: Exception) { logW(e) }
        try { add("Später ansehen", tiles("VLWL")) } catch (e: Exception) { logW(e) }
        try { add("Verlauf", tiles("FEhistory")) } catch (e: Exception) { logW(e) }
        rows
    }

    companion object {
        private const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
        private const val WEB_VERSION = "2.20251101.01.00"
        private const val CLIENT_VERSION = "7.20250923.13.00"
        private const val TV_UA = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
        // Nur als Rückfall, falls das Auslesen aus youtube.com/tv scheitert (bekannte Werte der YouTube-TV-App)
        private const val FALLBACK_CLIENT_ID = "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
        private const val FALLBACK_CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
    }
}
