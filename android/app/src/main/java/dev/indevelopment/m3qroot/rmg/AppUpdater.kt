package dev.indevelopment.m3qroot.rmg

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import dev.indevelopment.m3qroot.VersionCompare
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-place update from GitHub releases.
 *
 * Ported from Root-My-Galaxy-Extended (`AppUpdater.kt`) by igorcv88
 * (Apache License 2.0), adapted to SamSU: two candidate repositories are
 * checked so a fork without its own releases still reports upstream ones, the
 * version comparison is numeric, and the downloaded APK is identified before it
 * is handed to the installer.
 */

data class UpdateInfo(
    val versionName: String,
    val apkUrl: String?,
    val releaseUrl: String,
    val repo: String,
)

/** Progress in the 0.0 - 1.0 range, reported from the download thread. */
fun interface DownloadProgress {
    fun onProgress(fraction: Float)
}

object AppUpdater {
    /**
     * Checked in order: the fork that publishes these builds first, then the
     * upstream it derives from.
     */
    private val REPOS = listOf("rushiranpise/samsu", "mitschud/samsu")
    private const val API_BASE = "https://api.github.com/repos"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val DOWNLOAD_TIMEOUT_MS = 60_000
    private const val MIME_APK = "application/vnd.android.package-archive"

    /** Latest release of the first candidate repository that publishes one. */
    fun fetchLatestRelease(context: Context): UpdateInfo? {
        val userAgent = userAgent(context)
        for (repo in REPOS) {
            val info = fetchRelease(repo, userAgent)
            if (info != null) return info
        }
        return null
    }

    fun releasesPage(repo: String): String = "https://github.com/$repo/releases/latest"

    /**
     * Downloads the release APK into the cache. Blocking: call from a worker.
     *
     * @return the downloaded file, or null when the request or the write failed.
     */
    fun downloadApk(
        context: Context,
        url: String,
        progress: DownloadProgress?,
    ): File? {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "update.apk")
        val temporary = File(directory, "update.apk.part")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", userAgent(context))
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = DOWNLOAD_TIMEOUT_MS
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val total = connection.contentLength.toLong()
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                progress?.onProgress(
                                    (downloaded.toDouble() / total.toDouble())
                                        .coerceIn(0.0, 1.0).toFloat(),
                                )
                            }
                        }
                        output.flush()
                    }
                }
                if (downloaded == 0L) {
                    temporary.delete()
                    return null
                }
                if (!temporary.renameTo(target)) {
                    if (!target.delete() || !temporary.renameTo(target)) {
                        temporary.delete()
                        return null
                    }
                }
                return target
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            temporary.delete()
            return null
        }
    }

    /**
     * Sanity-checks a downloaded APK before it reaches the installer: it must be
     * this package and it must actually be newer. Catches an error page or a
     * mis-served asset being installed over a working build.
     *
     * @return an empty string when the file may be installed, else the reason.
     */
    fun describeDownloadProblem(
        context: Context,
        apk: File,
        expectedVersion: String,
        currentVersion: String,
    ): String {
        if (!apk.isFile || apk.length() == 0L) return "the downloaded file is empty"
        val flags = PackageManager.GET_META_DATA
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        }.getOrNull() ?: return "the downloaded file is not an Android package"
        if (info.packageName != context.packageName) {
            return "the downloaded package is ${info.packageName}, not ${context.packageName}"
        }
        val version = info.versionName.orEmpty()
        if (expectedVersion.isNotEmpty() && version.isNotEmpty() &&
            !VersionCompare.isNewer(version, currentVersion)
        ) {
            return "the downloaded build $version is not newer than $currentVersion"
        }
        return ""
    }

    /** Hands the APK to the system installer. Blocking: call from a worker. */
    fun installApk(context: Context, apk: File): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }.getOrNull() ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_APK)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            // Requesting package installation can be blocked by the device policy.
            false
        }
    }

    fun openReleasesPage(context: Context, releaseUrl: String) {
        val target = releaseUrl.ifEmpty { releasesPage(REPOS.first()) }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun fetchRelease(repo: String, userAgent: String): UpdateInfo? = try {
        val connection =
            URL("$API_BASE/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").trim()
                if (tag.isEmpty()) {
                    null
                } else {
                    var apkUrl: String? = null
                    json.optJSONArray("assets")?.let { assets ->
                        for (index in 0 until assets.length()) {
                            val asset = assets.getJSONObject(index)
                            if (asset.optString("name").endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url")
                                    .ifEmpty { null }
                                break
                            }
                        }
                    }
                    UpdateInfo(
                        versionName = tag,
                        apkUrl = apkUrl,
                        releaseUrl = json.optString("html_url").ifEmpty {
                            releasesPage(repo)
                        },
                        repo = repo,
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun userAgent(context: Context): String = runCatching {
        val version = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName.orEmpty()
        "SamSU/$version"
    }.getOrDefault("SamSU")
}
