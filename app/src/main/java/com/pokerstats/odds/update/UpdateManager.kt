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

/** Details of an available newer build. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
)

/**
 * Rolling-release updater.
 *
 * CI publishes every build from `main` to a single fixed GitHub release tagged
 * [RELEASE_TAG], clobbering two assets each time:
 *
 *  - `poker-odds.apk` — the signed APK
 *  - `version.json`   — `{ "versionCode": N, "versionName": "a.b.c" }`
 *
 * The app fetches the manifest and compares its integer `versionCode` against
 * its own [BuildConfig.VERSION_CODE]; anything higher is an available update.
 * There are no git tags and no release enumeration involved — the download URLs
 * are fixed, so a single unauthenticated GET answers "is there an update?".
 */
object UpdateManager {

    private const val APK_NAME = "poker-odds.apk"
    private const val MANIFEST_NAME = "version.json"
    private const val RELEASE_TAG = "poker-latest"
    private const val UPDATES_DIR = "updates"

    private val apkUrl: String
        get() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/$RELEASE_TAG/$APK_NAME"

    private val manifestUrl: String
        get() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/$RELEASE_TAG/$MANIFEST_NAME"

    /** Returns update details if the rolling release is newer than this build. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val json = fetchManifest() ?: return@withContext null
        val latestCode = json.optInt("versionCode", -1)
        if (latestCode <= BuildConfig.VERSION_CODE) return@withContext null
        UpdateInfo(
            versionCode = latestCode,
            versionName = json.optString("versionName").ifEmpty { latestCode.toString() },
            downloadUrl = apkUrl,
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
        val target = File(dir, "poker-odds-${info.versionCode}.apk")
        if (target.exists() && target.length() > 0) {
            onProgress(1f)
            return@withContext target
        }

        val tmp = File(dir, target.name + ".part")
        val connection = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "PokerPro-Updater")
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
        dir.listFiles()?.forEach { file ->
            val code = file.name.removePrefix("poker-odds-").removeSuffix(".apk").toIntOrNull()
            if (file.name.endsWith(".part") || (code != null && code <= BuildConfig.VERSION_CODE)) {
                file.delete()
            }
        }
    }

    // --- helpers -----------------------------------------------------------

    private fun fetchManifest(): JSONObject? {
        return try {
            val connection = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "PokerPro-Updater")
                setRequestProperty("Accept", "application/json")
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
}
