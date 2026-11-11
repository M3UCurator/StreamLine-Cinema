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

    fun mimeFor(url: String): String {
        val lower = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            lower.endsWith(".m3u8") -> "application/x-mpegURL"
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
        val merged = LinkedHashMap<String, String>()
        extra.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) merged[key] = value
        }
        // The explicit Referer wins over an addon-supplied one, regardless of casing.
        if (!referer.isNullOrBlank()) {
            merged.keys.filter { it.equals("Referer", ignoreCase = true) }.forEach { merged.remove(it) }
            merged["Referer"] = referer
        }
        if (merged.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            merged["User-Agent"] = userAgent
        }
        return merged.entries.flatMap { listOf(it.key, it.value) }.toTypedArray()
    }

    fun intent(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = Http.BROWSER_USER_AGENT
    ): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), mimeFor(url))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra("headers", headerPairs(referer, headers, userAgent))
    }

    /** System chooser listing every installed player that accepts the target. */
    fun chooser(target: Intent, title: String = "Play with"): Intent =
        Intent.createChooser(target, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Opens the chooser for [url] and reports whether a target activity took it.
     * Falls back to a generic video type when nothing resolves the exact MIME.
     */
    fun launch(
        context: Context,
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        userAgent: String = Http.BROWSER_USER_AGENT
    ): Boolean {
        if (url.isBlank()) return false
        val base = intent(url = url, referer = referer, headers = headers, userAgent = userAgent)
        val target = if (base.resolveActivity(context.packageManager) != null) {
            base
        } else {
            Intent(base).setDataAndType(Uri.parse(url), "video/*").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return runCatching {
            context.startActivity(chooser(target))
            true
        }.getOrDefault(false)
    }
}
