package com.streamline.cinema.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.streamline.cinema.BuildConfig
import com.streamline.cinema.UpdateInstallReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** One downloadable APK attached to a GitHub release. */
data class UpdateAsset(
    val name: String,
    val url: String,
    val size: Long
)

/** The subset of the GitHub release payload the updater needs. */
data class UpdateRelease(
    val tag: String,
    val name: String,
    val pageUrl: String,
    val notes: String,
    val asset: UpdateAsset?
)

/** Mirrors the state of the Settings → About update row and the startup prompt. */
data class UpdateUiState(
    val checking: Boolean = false,
    val release: UpdateRelease? = null,
    val latestTag: String = "",
    val upToDate: Boolean = false,
    val noRelease: Boolean = false,
    val error: String? = null,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val installStarted: Boolean = false
)

/** The repository is reachable but has no published release; not a network failure. */
class NoPublishedReleaseException :
    IOException("No published release on GitHub yet")

/** "v12.77" → [12, 77]; suffixes such as "12.77-beta" only keep the numbers. */
fun parseVersion(raw: String): List<Int> =
    raw.trim().removePrefix("v").removePrefix("V")
        .split('.')
        .mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }

/** Numeric comparison, so v12.100 is newer than v12.99 (a plain string sort is not). */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val a = parseVersion(candidate)
    if (a.isEmpty()) return false
    val b = parseVersion(current)
    for (index in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(index) { 0 }
        val right = b.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return false
}

/**
 * Picks the asset matching the installed flavor: an all-ABI install stays on
 * the all-ABI build, a 32-bit install takes the smaller ARM-only APK.
 */
fun pickUpdateAsset(assets: List<UpdateAsset>, allAbis: Boolean): UpdateAsset? {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    if (apks.isEmpty()) return null
    return apks.firstOrNull { it.name.contains("-allabis", ignoreCase = true) == allAbis }
        ?: apks.firstOrNull { it.name.contains("allabis", ignoreCase = true) }
        ?: apks.first()
}

class UpdateChecker(private val context: Context) {

    companion object {
        const val REPO_URL = "https://github.com/M3UCurator/StreamLine-Cinema"
        const val LATEST_RELEASE_API =
            "https://api.github.com/repos/M3UCurator/StreamLine-Cinema/releases/latest"
        private const val TAG = "StreamLine:update"
        private const val USER_AGENT = "StreamLineCinema/${BuildConfig.ITERATION}"
        private const val FREE_SPACE_MARGIN = 16L * 1024 * 1024
    }

    /** Reads the latest published (non-draft, non-prerelease) release. */
    suspend fun latest(): UpdateRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        Http.client.await(request).use { response ->
            when (response.code) {
                404 -> throw NoPublishedReleaseException()
                403, 429 -> throw IOException("GitHub rate limit reached · try again later")
            }
            if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val tag = json.optString("tag_name")
            if (tag.isBlank()) throw IOException("GitHub release has no tag")
            val assets = json.optJSONArray("assets")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val asset = array.optJSONObject(index) ?: return@mapNotNull null
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (name.isBlank() || url.isBlank()) null
                    else UpdateAsset(name, url, asset.optLong("size"))
                }
            }.orEmpty()
            UpdateRelease(
                tag = tag,
                name = json.optString("name").ifBlank { tag },
                pageUrl = json.optString("html_url").ifBlank { REPO_URL },
                notes = json.optString("body"),
                asset = pickUpdateAsset(assets, BuildConfig.ALL_ABIS)
            )
        }
    }

    /** Streams the APK into the cache and reports 0..1 progress as it copies. */
    suspend fun download(asset: UpdateAsset, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            if (asset.size > 0 && directory.usableSpace < asset.size + FREE_SPACE_MARGIN) {
                throw IOException("Not enough free space for ${asset.name}")
            }
            val target = File(directory, asset.name)
            val temporary = File(directory, "${asset.name}.part")
            val request = Request.Builder()
                .url(asset.url)
                .header("User-Agent", USER_AGENT)
                .build()
            try {
                Http.streamingClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Download HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: asset.size
                    var copied = 0L
                    var lastPercent = -1
                    temporary.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (total > 0) {
                                    val percent = (copied * 100 / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                                    }
                                }
                            }
                        }
                    }
                }
                if (asset.size > 0 && temporary.length() != asset.size) {
                    throw IOException("Download incomplete (${temporary.length()} of ${asset.size} bytes)")
                }
                if (target.exists() && !target.delete()) {
                    throw IOException("Could not replace the previous download")
                }
                if (!temporary.renameTo(target)) throw IOException("Could not finalize the APK")
                onProgress(1f)
                Log.d(TAG, "downloaded ${target.name} (${target.length()} bytes)")
                target
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            }
        }

    /**
     * Commits a full-install session for this package. The system prompts the
     * user to allow the update; [UpdateInstallReceiver] forwards the prompt and
     * reports the result. Requires REQUEST_INSTALL_PACKAGES in the manifest.
     */
    fun install(apk: File): Boolean {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output, 64 * 1024)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java)
                .setAction(UpdateInstallReceiver.ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        Log.d(TAG, "installer session committed for ${apk.name}")
        return true
    }
}
