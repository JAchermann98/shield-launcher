package ch.jachermann.shieldlauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Prüft das GitHub-Release "latest" und installiert eine neuere Version. */
object Updater {

    private const val RELEASE_API = "https://api.github.com/repos/JAchermann98/shield-launcher/releases/tags/latest"

    data class Update(val build: Int, val url: String)

    fun currentBuild(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }

    suspend fun check(context: Context): Update? = withContext(Dispatchers.IO) {
        val conn = URL(RELEASE_API).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        if (conn.responseCode !in 200..299) return@withContext null
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val o = JSONObject(body)
        val build = Regex("Build (\\d+)").find(o.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
        val assets = o.optJSONArray("assets") ?: return@withContext null
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
        }
        if (url == null || build <= currentBuild(context)) null else Update(build, url)
    }

    /** true = Installation gestartet, false = Berechtigung fehlt (Einstellungen wurden geöffnet). */
    suspend fun install(context: Context, update: Update, onProgress: (Int) -> Unit = {}): Boolean =
        installUrl(context, update.url, "update.apk", onProgress)

    // ---------- SmartTube ----------

    private const val SMARTTUBE_API = "https://api.github.com/repos/yuliskov/SmartTube/releases/tags/latest"
    private const val SMARTTUBE_APK = "https://github.com/yuliskov/SmartTube/releases/download/latest/smarttube_stable.apk"

    data class SmartTubeStatus(val installedPkg: String?, val installedAt: Long, val releaseAt: Long, val url: String) {
        /** Neuere Version auf GitHub als die installierte (mit einer Stunde Spielraum). */
        val updateAvailable: Boolean get() = installedPkg != null && releaseAt > 0 && releaseAt > installedAt + 3_600_000
    }

    fun findSmartTube(context: Context): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).firstOrNull {
            it.loadLabel(pm).toString().contains("SmartTube", ignoreCase = true) ||
                it.activityInfo.packageName.contains("smarttube", ignoreCase = true)
        }?.activityInfo?.packageName
    }

    suspend fun checkSmartTube(context: Context): SmartTubeStatus = withContext(Dispatchers.IO) {
        val pkg = findSmartTube(context)
        val installedAt = if (pkg != null) context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime else 0L
        var url = SMARTTUBE_APK
        var releaseAt = 0L
        // 1. Versuch: GitHub-Schnittstelle (Datum der stabilen APK)
        try {
            val conn = URL(SMARTTUBE_API).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            if (conn.responseCode in 200..299) {
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val assets = o.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name") == "smarttube_stable.apk") {
                            url = a.optString("browser_download_url", SMARTTUBE_APK)
                            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                            releaseAt = fmt.parse(a.optString("updated_at"))?.time ?: 0L
                        }
                    }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            logW(e)
        }
        // 2. Versuch: Änderungsdatum der Datei selbst
        if (releaseAt == 0L) {
            try {
                var conn = URL(SMARTTUBE_APK).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.instanceFollowRedirects = false
                var redirects = 0
                while (conn.responseCode in 300..399 && redirects < 5) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    conn = URL(loc).openConnection() as HttpURLConnection
                    conn.requestMethod = "HEAD"
                    conn.instanceFollowRedirects = false
                    redirects++
                }
                releaseAt = conn.lastModified
                conn.disconnect()
            } catch (e: Exception) {
                logW(e)
            }
        }
        SmartTubeStatus(pkg, installedAt, releaseAt, url)
    }

    /**
     * Lädt eine APK herunter und öffnet das Android-Installationsfenster.
     * onProgress bekommt 0..100 (oder -1, wenn die Grösse unbekannt ist).
     * true = Installationsfenster wird geöffnet, false = Berechtigung fehlt (Einstellungen wurden geöffnet).
     */
    suspend fun installUrl(context: Context, url: String, fileName: String, onProgress: (Int) -> Unit = {}): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            return false
        }
        withContext(Dispatchers.IO) { File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() } }
        val file = withContext(Dispatchers.IO) { download(url, File(File(context.cacheDir, "updates").apply { mkdirs() }, fileName), onProgress) }
        openInstaller(context, file)
        return true
    }

    private suspend fun download(url: String, target: File, onProgress: (Int) -> Unit): File {
        var conn = open(url)
        // GitHub leitet auf einen anderen Host weiter
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = open(loc)
            redirects++
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("Download fehlgeschlagen (HTTP $code)")
        }
        val total = conn.contentLengthLong
        val tmp = File(target.parentFile, target.name + ".part")
        var read = 0L
        var lastPct = -2
        try {
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) (read * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        // Unvollständige Datei nie installieren
        if (read < 100_000 || (total > 0 && read != total)) {
            tmp.delete()
            throw IOException("Download unvollständig ($read von $total Bytes)")
        }
        target.delete()
        if (!tmp.renameTo(target)) throw IOException("Datei konnte nicht gespeichert werden")
        return target
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 30000
        }

    /**
     * Öffnet das normale Android-Installationsfenster. Dieser Weg wird von Play Protect
     * als manuelle Installation behandelt (die Sitzungs-Installation wird bei unbekannten
     * Entwicklern blockiert).
     */
    private fun openInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}
