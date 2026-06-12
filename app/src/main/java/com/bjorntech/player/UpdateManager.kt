package com.bjorntech.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight, dependency-free in-app updater. Checks the project's GitHub
 * Releases for a newer version than the installed one and, if the user agrees,
 * downloads the published APK and hands it to the system package installer.
 *
 * The only network access in the whole app happens here.
 */
object UpdateManager {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/Xbjornsen/BjornPlayer/releases/latest"

    data class UpdateInfo(
        val versionName: String,   // e.g. "1.2"
        val tag: String,           // e.g. "v1.2"
        val apkUrl: String,
        val notes: String
    )

    /** Returns info about a newer release, or null if up to date / offline / on error. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "BjornPlayer")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })

            val tag = json.optString("tag_name").ifEmpty { return@withContext null }
            val latest = tag.removePrefix("v")
            if (!isNewer(latest, BuildConfig.VERSION_NAME)) return@withContext null

            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            val url = apkUrl ?: return@withContext null
            UpdateInfo(latest, tag, url, json.optString("body"))
        } catch (e: Exception) {
            null
        }
    }

    /** Downloads the release APK to cacheDir/updates. Returns the file or null on failure. */
    suspend fun downloadApk(context: Context, info: UpdateInfo): File? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "BjornPlayer")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "BjornPlayer-${info.tag}.apk")
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            out
        } catch (e: Exception) {
            null
        }
    }

    /** True if this app is allowed to install APKs (Android 8+ requires per-app consent). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Sends the user to the "allow install from this source" settings screen. */
    fun requestInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Launches the system installer for a downloaded APK via FileProvider. */
    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Numeric dotted-version compare: is [remote] strictly newer than [current]? */
    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".")
        val c = current.split(".")
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrNull(i)?.toIntOrNull() ?: 0
            val cv = c.getOrNull(i)?.toIntOrNull() ?: 0
            if (rv != cv) return rv > cv
        }
        return false
    }
}
