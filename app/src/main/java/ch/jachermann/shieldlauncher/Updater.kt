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
import java.net.HttpURLConnection
import java.net.URL

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
    suspend fun install(context: Context, update: Update): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            return false
        }
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val f = File(dir, "update.apk")
            var conn = URL(update.url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            // GitHub leitet auf einen anderen Host weiter
            var redirects = 0
            while (conn.responseCode in 300..399 && redirects < 5) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                conn = URL(loc).openConnection() as HttpURLConnection
                redirects++
            }
            conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            f
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return true
    }
}
