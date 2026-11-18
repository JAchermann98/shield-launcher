package ch.jachermann.shieldlauncher

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** Ein Titel, wie ihn der Launcher anzeigt. */
@Immutable
data class PlexItem(
    val serverId: String,
    val ratingKey: String,
    val type: String,
    val title: String,
    val subtitle: String,
    val year: String,
    val durationMs: Long,
    val viewOffsetMs: Long,
    val posterUrl: String?,
    val landscapeUrl: String?,
    val backdropUrl: String?,
    val resolution: String,
    val audio: String,
    val summary: String,
    val guid: String? = null,
    val fromWatchlist: Boolean = false
) {
    val progress: Float
        get() = if (durationMs > 0 && viewOffsetMs > 0) (viewOffsetMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

@Immutable
data class PlexRow(val title: String, val items: List<PlexItem>, val landscape: Boolean, val resume: Boolean)

data class PlexServer(val id: String, val name: String, val base: String, val token: String)

class PlexRepo(context: Context) {

    private val prefs = context.getSharedPreferences("launcher_test", Context.MODE_PRIVATE)

    val clientId: String
        get() {
            var id = prefs.getString("client_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString("client_id", id).apply()
            }
            return id
        }

    val userToken: String? get() = prefs.getString("token", null)

    private fun headers(token: String? = null): Map<String, String> {
        val h = mutableMapOf(
            "Accept" to "application/json",
            "X-Plex-Product" to "Shield Launcher",
            "X-Plex-Version" to "0.2",
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Platform" to "Android",
            "X-Plex-Device" to "SHIELD Android TV",
            "X-Plex-Device-Name" to "Shield Launcher"
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

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---------- Anmeldung ----------

    /** Liefert (pinId, code) für plex.tv/link. */
    suspend fun createPin(): Pair<Long, String> = withContext(Dispatchers.IO) {
        val (code, body) = http("POST", "https://plex.tv/api/v2/pins", headers())
        if (code !in 200..299) throw IllegalStateException("PIN-Anfrage fehlgeschlagen ($code)")
        val o = JSONObject(body)
        Pair(o.getLong("id"), o.getString("code"))
    }

    /** true, sobald der Code bestätigt wurde. */
    suspend fun checkPin(pinId: Long): Boolean = withContext(Dispatchers.IO) {
        val (code, body) = http("GET", "https://plex.tv/api/v2/pins/$pinId", headers())
        if (code !in 200..299) return@withContext false
        val token = JSONObject(body).optString("authToken", "")
        if (token.isNotEmpty() && token != "null") {
            prefs.edit().putString("token", token).apply()
            true
        } else false
    }

    // ---------- Server ----------

    suspend fun findServers(): List<PlexServer> = withContext(Dispatchers.IO) {
        val token = userToken ?: return@withContext emptyList()
        val (code, body) = http("GET", "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", headers(token))
        if (code !in 200..299) return@withContext emptyList()
        val res = JSONArray(body)
        val out = mutableListOf<PlexServer>()
        for (i in 0 until res.length()) {
            val r = res.getJSONObject(i)
            if (!r.optString("provides").contains("server")) continue
            val serverToken = r.optString("accessToken", token)
            val conns = r.optJSONArray("connections") ?: JSONArray()
            val uris = mutableListOf<Pair<String, Boolean>>()
            for (j in 0 until conns.length()) {
                val c = conns.getJSONObject(j)
                uris.add(Pair(c.optString("uri"), c.optBoolean("relay")))
            }
            uris.sortBy { it.second }
            for ((uri, _) in uris) {
                val ok = try {
                    http("GET", "$uri/identity", headers(serverToken), 4000).first in 200..299
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    out.add(PlexServer(r.optString("clientIdentifier"), r.optString("name"), uri, serverToken))
                    break
                }
            }
        }
        out
    }

    // ---------- Inhalte ----------

    private fun img(s: PlexServer, path: String, w: Int, h: Int): String? {
        if (path.isEmpty()) return null
        if (path.startsWith("http")) return path
        return "${s.base}/photo/:/transcode?width=$w&height=$h&minSize=1&upscale=1&url=${enc(path)}&X-Plex-Token=${s.token}"
    }

    private fun audioLabel(codec: String, channels: Int): String {
        val name = when (codec.lowercase()) {
            "truehd" -> "TrueHD"
            "eac3" -> "Dolby Digital+"
            "ac3" -> "Dolby Digital"
            "dca", "dts" -> "DTS"
            "aac" -> "AAC"
            "" -> ""
            else -> codec.uppercase()
        }
        val ch = when (channels) {
            8 -> " 7.1"
            6 -> " 5.1"
            2 -> " Stereo"
            else -> ""
        }
        return (name + ch).trim()
    }

    private fun parse(s: PlexServer, m: JSONObject): PlexItem {
        val type = m.optString("type")
        val title = when (type) {
            "episode" -> m.optString("grandparentTitle")
            "season" -> m.optString("parentTitle")
            else -> m.optString("title")
        }
        val subtitle = when (type) {
            "episode" -> "S${m.optInt("parentIndex")} E${m.optInt("index")} · ${m.optString("title")}"
            "season" -> m.optString("title")
            else -> ""
        }
        val year = m.optInt("year", m.optInt("parentYear", 0)).let { if (it > 0) it.toString() else "" }
        val poster = when (type) {
            "episode" -> m.optString("grandparentThumb").ifEmpty { m.optString("thumb") }
            "season" -> m.optString("thumb").ifEmpty { m.optString("parentThumb") }
            else -> m.optString("thumb")
        }
        val landscape = if (type == "episode") m.optString("thumb") else m.optString("art")
        val backdrop = when (type) {
            "episode", "season" -> m.optString("grandparentArt").ifEmpty { m.optString("art") }
            else -> m.optString("art")
        }
        val media = m.optJSONArray("Media")?.optJSONObject(0)
        val res = when (media?.optString("videoResolution")?.lowercase()) {
            "4k" -> "4K"
            "1080" -> "HD"
            "720" -> "HD"
            null, "" -> ""
            else -> "SD"
        }
        val audio = if (media != null) audioLabel(media.optString("audioCodec"), media.optInt("audioChannels")) else ""
        return PlexItem(
            serverId = s.id,
            ratingKey = m.optString("ratingKey"),
            type = type,
            title = title,
            subtitle = subtitle,
            year = year,
            durationMs = m.optLong("duration"),
            viewOffsetMs = m.optLong("viewOffset"),
            posterUrl = img(s, poster, 240, 360),
            landscapeUrl = img(s, landscape.ifEmpty { backdrop }, 400, 225),
            backdropUrl = img(s, backdrop, 1280, 720),
            resolution = res,
            audio = audio,
            summary = m.optString("summary"),
            guid = m.optString("guid")
        )
    }

    private fun list(s: PlexServer, path: String): List<PlexItem> {
        val sep = if (path.contains("?")) "&" else "?"
        val (code, body) = http("GET", "${s.base}$path${sep}X-Plex-Container-Start=0&X-Plex-Container-Size=30", headers(s.token))
        if (code !in 200..299) return emptyList()
        val arr = JSONObject(body).optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: return emptyList()
        return (0 until arr.length()).map { parse(s, arr.getJSONObject(it)) }
    }

    /** Lädt alle Zeilen des Startbildschirms. */
    suspend fun loadRows(servers: List<PlexServer>): List<PlexRow> = withContext(Dispatchers.IO) {
        val onDeck = mutableListOf<PlexItem>()
        val movies4k = mutableListOf<PlexItem>()
        val shows4k = mutableListOf<PlexItem>()
        val recent = mutableListOf<PlexItem>()
        for (s in servers) {
            try {
                onDeck += list(s, "/library/onDeck")
                recent += list(s, "/library/recentlyAdded")
                val (c, b) = http("GET", "${s.base}/library/sections", headers(s.token))
                if (c in 200..299) {
                    val dirs = JSONObject(b).optJSONObject("MediaContainer")?.optJSONArray("Directory") ?: JSONArray()
                    for (i in 0 until dirs.length()) {
                        val d = dirs.getJSONObject(i)
                        val t = d.optString("title").lowercase()
                        if (!t.contains("4k")) continue
                        val key = d.optString("key")
                        when (d.optString("type")) {
                            "movie" -> movies4k += list(s, "/library/sections/$key/recentlyAdded")
                            "show" -> shows4k += list(s, "/library/sections/$key/recentlyAdded")
                        }
                    }
                }
            } catch (e: Exception) {
                // Server nicht erreichbar: weiter mit dem nächsten
            }
        }
        val rows = mutableListOf<PlexRow>()
        if (onDeck.isNotEmpty()) rows += PlexRow("Weiterschauen", onDeck, landscape = true, resume = true)
        if (movies4k.isNotEmpty()) rows += PlexRow("Neu in Filme 4K", movies4k, landscape = false, resume = false)
        if (shows4k.isNotEmpty()) rows += PlexRow("Neu in Serien 4K", shows4k, landscape = false, resume = false)
        if (recent.isNotEmpty()) rows += PlexRow("Zuletzt hinzugefügt", recent, landscape = true, resume = false)
        val watch = loadWatchlist()
        if (watch.isNotEmpty()) rows += PlexRow("Watchlist", watch, landscape = false, resume = false)
        rows
    }

    private fun loadWatchlist(): List<PlexItem> {
        val token = userToken ?: return emptyList()
        return try {
            val (code, body) = http(
                "GET",
                "https://discover.provider.plex.tv/library/sections/watchlist/all?X-Plex-Container-Start=0&X-Plex-Container-Size=30",
                headers(token)
            )
            if (code !in 200..299) return emptyList()
            val arr = JSONObject(body).optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: return emptyList()
            (0 until arr.length()).map {
                val m = arr.getJSONObject(it)
                PlexItem(
                    serverId = "",
                    ratingKey = m.optString("ratingKey"),
                    type = m.optString("type"),
                    title = m.optString("title"),
                    subtitle = "",
                    year = m.optInt("year").let { y -> if (y > 0) y.toString() else "" },
                    durationMs = m.optLong("duration"),
                    viewOffsetMs = 0,
                    posterUrl = m.optString("thumb").ifEmpty { null },
                    landscapeUrl = m.optString("art").ifEmpty { null },
                    backdropUrl = m.optString("art").ifEmpty { null },
                    resolution = "",
                    audio = "",
                    summary = m.optString("summary"),
                    guid = m.optString("guid"),
                    fromWatchlist = true
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Sucht einen Watchlist-Titel auf den Servern. */
    suspend fun resolveOnServer(item: PlexItem, servers: List<PlexServer>): PlexItem? = withContext(Dispatchers.IO) {
        val guid = item.guid ?: return@withContext null
        for (s in servers) {
            try {
                val found = list(s, "/library/all?guid=${enc(guid)}")
                if (found.isNotEmpty()) return@withContext found.first()
            } catch (e: Exception) {
            }
        }
        null
    }

    // ---------- Zwischenspeicher ----------

    private val cacheFile = java.io.File(context.filesDir, "rows.json")

    fun saveServers(servers: List<PlexServer>) {
        val arr = JSONArray()
        servers.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("base", it.base).put("token", it.token))
        }
        prefs.edit().putString("servers", arr.toString()).apply()
    }

    fun loadServers(): List<PlexServer> = try {
        val arr = JSONArray(prefs.getString("servers", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PlexServer(o.getString("id"), o.getString("name"), o.getString("base"), o.getString("token"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun itemToJson(i: PlexItem): JSONObject = JSONObject()
        .put("serverId", i.serverId).put("ratingKey", i.ratingKey).put("type", i.type)
        .put("title", i.title).put("subtitle", i.subtitle).put("year", i.year)
        .put("durationMs", i.durationMs).put("viewOffsetMs", i.viewOffsetMs)
        .put("posterUrl", i.posterUrl ?: "").put("landscapeUrl", i.landscapeUrl ?: "").put("backdropUrl", i.backdropUrl ?: "")
        .put("resolution", i.resolution).put("audio", i.audio).put("summary", i.summary)
        .put("guid", i.guid ?: "").put("fromWatchlist", i.fromWatchlist)

    private fun itemFromJson(o: JSONObject): PlexItem = PlexItem(
        serverId = o.optString("serverId"), ratingKey = o.optString("ratingKey"), type = o.optString("type"),
        title = o.optString("title"), subtitle = o.optString("subtitle"), year = o.optString("year"),
        durationMs = o.optLong("durationMs"), viewOffsetMs = o.optLong("viewOffsetMs"),
        posterUrl = o.optString("posterUrl").ifEmpty { null },
        landscapeUrl = o.optString("landscapeUrl").ifEmpty { null },
        backdropUrl = o.optString("backdropUrl").ifEmpty { null },
        resolution = o.optString("resolution"), audio = o.optString("audio"), summary = o.optString("summary"),
        guid = o.optString("guid").ifEmpty { null }, fromWatchlist = o.optBoolean("fromWatchlist")
    )

    fun saveCache(rows: List<PlexRow>) {
        try {
            val arr = JSONArray()
            rows.forEach { r ->
                val items = JSONArray()
                r.items.forEach { items.put(itemToJson(it)) }
                arr.put(JSONObject().put("title", r.title).put("landscape", r.landscape).put("resume", r.resume).put("items", items))
            }
            cacheFile.writeText(arr.toString())
        } catch (e: Exception) {
        }
    }

    fun loadCache(): List<PlexRow> = try {
        if (!cacheFile.exists()) emptyList() else {
            val arr = JSONArray(cacheFile.readText())
            (0 until arr.length()).map { idx ->
                val o = arr.getJSONObject(idx)
                val items = o.getJSONArray("items")
                PlexRow(
                    o.getString("title"),
                    (0 until items.length()).map { itemFromJson(items.getJSONObject(it)) },
                    o.optBoolean("landscape"),
                    o.optBoolean("resume")
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    // ---------- Links in die Plex-App ----------

    fun playUri(item: PlexItem): String {
        val inner = "server://${item.serverId}/com.plexapp.plugins.library/library/metadata/${item.ratingKey}"
        return "plex://watch/video?uri=${enc(inner)}"
    }

    fun detailsUri(item: PlexItem): String {
        val (key, tv) = when (item.type) {
            "show" -> Pair(item.ratingKey, "tv/")
            else -> Pair(item.ratingKey, "")
        }
        return "plex://libraries/${enc(item.serverId)}/unmatched/$tv${enc("/library/metadata/$key")}"
    }
}
