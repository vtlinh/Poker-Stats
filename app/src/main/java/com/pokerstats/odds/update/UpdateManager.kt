package com.pokerstats.odds.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.pokerstats.odds.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Details of an available newer release. */
data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val notes: String,
)

/**
 * Checks GitHub Releases for a newer build, downloads the APK (once per
 * version), installs it, and cleans up stale downloads.
 *
 * The version scheme is `major.minor.build`; comparison is numeric per
 * component so e.g. 1.30.10 > 1.30.9 and 1.31.1 > 1.30.12.
 */
object UpdateManager {

    private const val APK_NAME = "poker-odds.apk"
    private const val UPDATES_DIR = "updates"

    /** Returns update details if the latest release is newer than this build. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val json = fetchLatestRelease() ?: return@withContext null
        val tag = json.optString("tag_name").ifEmpty { json.optString("name") }
        val latest = normalizeVersion(tag)
        if (latest.isEmpty()) return@withContext null

        val current = normalizeVersion(BuildConfig.VERSION_NAME)
        if (compareVersions(latest, current) <= 0) return@withContext null

        val apkUrl = findApkAssetUrl(json) ?: return@withContext null
        UpdateInfo(
            versionName = latest.joinToString("."),
            downloadUrl = apkUrl,
            notes = json.optString("body").take(500),
        )
    }

    /**
     * Downloads the update APK to internal storage, reusing an existing file
     * for the same version so each version is fetched at most once.
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, UPDATES_DIR).apply { mkdirs() }
        val target = File(dir, "poker-odds-${info.versionName}.apk")
        if (target.exists() && target.length() > 0) {
            onProgress(1f)
            return@withContext target
        }

        val tmp = File(dir, target.name + ".part")
        val connection = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "PokerOdds-Updater")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            connection.inputStream.use { input ->
                val total = connection.contentLengthLong
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        onProgress(1f)
        target
    }

    /** True if the OS will let us install packages without extra user steps. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Sends the user to grant "install unknown apps" for this app. */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /** Launches the system installer for a downloaded APK. */
    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /**
     * Deletes downloaded APKs for the current version or older — run on launch
     * so a successful update cleans up the file it was installed from.
     */
    fun cleanupOldDownloads(context: Context) {
        val dir = File(context.filesDir, UPDATES_DIR)
        if (!dir.isDirectory) return
        val current = normalizeVersion(BuildConfig.VERSION_NAME)
        dir.listFiles()?.forEach { file ->
            val version = file.name.removePrefix("poker-odds-").removeSuffix(".apk")
            val parsed = normalizeVersion(version)
            if (file.name.endsWith(".part") ||
                (parsed.isNotEmpty() && compareVersions(parsed, current) <= 0)
            ) {
                file.delete()
            }
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun fetchLatestRelease(): JSONObject? {
        return try {
            val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "PokerOdds-Updater")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                if (connection.responseCode != 200) return null
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun findApkAssetUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name == APK_NAME || name.endsWith(".apk")) {
                return asset.optString("browser_download_url").ifEmpty { null }
            }
        }
        return null
    }

    /** Extracts the leading numeric `a.b.c` from a version/tag string. */
    private fun normalizeVersion(raw: String): List<Int> {
        val match = Regex("""(\d+)(?:\.(\d+))?(?:\.(\d+))?""").find(raw) ?: return emptyList()
        return match.groupValues.drop(1).filter { it.isNotEmpty() }.map { it.toInt() }
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}
