package com.streamline.cinema.data

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Builds the ACTION_VIEW intents used to hand streams to external players.
 * Just Player (`com.brouken.player`), VLC and MX Player read the `headers`
 * extra as alternating key/value strings, which is where the CDN Referer has
 * to travel. Hand-off always goes through the system chooser so any installed
 * video player can be picked.
 */
object ExternalIntents {

    /**
     * Type handed to the player. [kind] wins over the extension because a short
     * link or token endpoint has none, and a generic video type would let the
     * receiving player fall back to a demuxer that cannot read a playlist.
     */
    fun mimeFor(url: String, kind: StreamKind? = null): String {
        when (kind) {
            StreamKind.HLS -> return "application/x-mpegURL"
            StreamKind.DASH -> return "application/dash+xml"
            StreamKind.SMOOTH -> return "application/vnd.ms-sstr+xml"
            null -> Unit
        }
        val lower = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            lower.endsWith(".m3u8") || lower.endsWith(".m3u") -> "application/x-mpegURL"
            lower.endsWith(".mpd") -> "application/dash+xml"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mp4") -> "video/mp4"
            else -> "video/*"
        }
    }

    fun headerPairs(
        referer: String?,
        extra: Map<String, String>,
        userAgent: String
    ): Array<String> {
        // Case-insensitive: an addon may publish `user-agent` while the source
        // page supplies `User-Agent`, and two UA headers make CDNs answer 400.
        val merged = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        extra.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) merged[key] = value
        }
        // The explicit Referer wins over an addon-supplied one.
        if (!referer.isNullOrBlank()) merged["Referer"] = referer
        if (merged.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            merged["User-Agent"] = userAgent
        }
        return merged.entries.flatMap { listOf(it.key, it.value) }.toTypedArray()
    }

    fun intent(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = Http.BROWSER_USER_AGENT,
        kind: StreamKind? = null
    ): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), mimeFor(url, kind))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra("headers", headerPairs(referer, headers, userAgent))
    }

    /** System chooser listing every installed player that accepts the target. */
    fun chooser(target: Intent, title: String = "Play with"): Intent =
        Intent.createChooser(target, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Opens [url] in one named player, or in the system chooser when
     * [packageName] is null, and reports whether a target activity took it.
     * The type falls back to a generic video MIME when nothing resolves the
     * exact one, and a named player that is installed but does not advertise
     * the MIME is still reachable through its package.
     */
    fun launchIn(
        context: Context,
        packageName: String?,
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = Http.BROWSER_USER_AGENT,
        kind: StreamKind? = null
    ): Boolean {
        if (url.isBlank()) return false
        val manager = context.packageManager
        val typed = intent(url = url, referer = referer, headers = headers, userAgent = userAgent, kind = kind)
        val video = Intent(typed).setDataAndType(Uri.parse(url), "video/*").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // A package takes precedence over the MIME: some builds declare only
        // video/* even though they open HLS, so the typed attempt is skipped
        // for them rather than letting resolveActivity veto the hand-off.
        val target = if (packageName == null) {
            if (typed.resolveActivity(manager) != null) typed else video
        } else {
            val named = Intent(typed).setPackage(packageName)
            when {
                named.resolveActivity(manager) != null -> named
                Intent(video).setPackage(packageName).resolveActivity(manager) != null ->
                    Intent(video).setPackage(packageName)
                else -> return false
            }
        }
        return runCatching {
            context.startActivity(if (packageName == null) chooser(target) else target)
            true
        }.getOrDefault(false)
    }
}
