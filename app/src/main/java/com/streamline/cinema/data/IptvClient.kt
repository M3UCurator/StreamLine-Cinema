package com.streamline.cinema.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * Downloads and decodes IPTV playlists and XMLTV guides. Local sources are read
 * through the content resolver (SAF `content://` URIs) or the file path, remote
 * sources through OkHttp with the pipe-syntax headers applied.
 */
class IptvClient(context: Context) {

    private val appContext = context.applicationContext
    private val singleGuideCap = DeviceProfile.singleEpgProgramCap(DeviceProfile.isLowRam(appContext))

    private class CachedGuide(val parse: GuideParse, val at: Long)

    /** Bytes plus where they came from: [url] is the final URL after redirects. */
    private class Fetched(val bytes: ByteArray, val url: String)

    private val guideCache = object : LinkedHashMap<String, CachedGuide>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedGuide>?): Boolean =
            size > MAX_CACHED_GUIDES
    }

    suspend fun load(source: IptvSource): IptvPlaylist = withContext(Dispatchers.IO) {
        val parsed = StreamUrls.parse(source.location)
        val location = parsed.url
        if (location.isBlank()) throw IOException("Enter a playlist URL or pick a local file")
        if (isNonHttpScheme(location)) {
            return@withContext IptvPlaylist(
                channels = listOf(singleChannel(source, location)),
                kind = IptvKind.STREAM
            )
        }
        val prefix = readPrefix(location, parsed.headers)
        // Short links (jmp2.uk, bit.ly) hide the real manifest URL; relative
        // entries and HLS variants must resolve against the redirect target.
        val playlistUrl = prefix.url
        if (playlistUrl != location) {
            Log.i("StreamLine:iptv", "${source.name.ifBlank { "source" }} resolved to ${playlistUrl.take(160)}")
        }
        val prefixText = decodeText(prefix.bytes, sniffOnly = true)
        when {
            looksLikeXmlTv(prefixText, playlistUrl) -> throw IOException(
                "This is an XMLTV guide, not a playlist. Paste it into the EPG field."
            )
            looksLikeXspf(prefixText) -> {
                val bytes = fetchCapped(playlistUrl, parsed.headers, MAX_PLAYLIST_BYTES).bytes
                val text = decodeText(bytes)
                val playlist = IptvParser.parseXspf(text, source.id)
                finish(playlist.copy(kind = IptvKind.XSPF), source)
            }
            looksLikeM3u(prefixText) -> {
                if (looksLikeHlsMedia(prefixText)) {
                    IptvPlaylist(
                        channels = listOf(singleChannel(source, source.location, playlistUrl)),
                        kind = IptvKind.STREAM
                    )
                } else {
                    val bytes = fetchCapped(playlistUrl, parsed.headers, MAX_PLAYLIST_BYTES).bytes
                    val text = decodeText(bytes)
                    val playlist = IptvParser.parseM3u(text, source.id, baseUrl = playlistUrl)
                    finish(playlist.copy(kind = IptvKind.M3U), source)
                }
            }
            else -> IptvPlaylist(
                channels = listOf(singleChannel(source, source.location, playlistUrl)),
                kind = IptvKind.STREAM
            )
        }
    }

    suspend fun loadGuide(
        location: String,
        headers: Map<String, String> = emptyMap(),
        force: Boolean = false
    ): GuideParse = withContext(Dispatchers.IO) {
        val parsed = StreamUrls.parse(location)
        if (parsed.url.isBlank()) return@withContext GuideParse(EpgGuide.EMPTY)
        val key = parsed.url + "|" + parsed.headers.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        val now = SystemClock.elapsedRealtime()
        synchronized(guideCache) {
            if (!force) {
                guideCache[key]?.takeIf { now - it.at < GUIDE_TTL_MS }?.let { return@withContext it.parse }
            }
        }
        val startedAt = SystemClock.elapsedRealtime()
        val parse = if (isRemoteGuide(parsed.url)) {
            parseRemoteGuide(parsed.url, StreamUrls.mergeHeaders(parsed.headers, headers))
        } else {
            // Local/SAF guides still need the bytes in memory (the resolver only
            // exposes streams once); the cap is the compressed size on disk.
            val bytes = fetchCapped(parsed.url, parsed.headers, MAX_GUIDE_BYTES).bytes
            if (bytes.isEmpty()) throw IOException("The guide URL returned an empty document")
            parseLocalGuide(bytes)
        }
        Log.i(
            "StreamLine:epg",
            "guide ${parsed.url.substringBefore('?')} · loaded in ${SystemClock.elapsedRealtime() - startedAt}ms" +
                " · ${parse.report.summary}"
        )
        synchronized(guideCache) {
            val elapsed = SystemClock.elapsedRealtime()
            guideCache.entries.removeAll { elapsed - it.value.at >= GUIDE_TTL_MS }
            guideCache[key] = CachedGuide(parse, elapsed)
        }
        parse
    }

    /**
     * Remote guides are parsed straight from the socket. Buffering a whole
     * document (`ByteArrayOutputStream` growth plus `toByteArray` copy) peaked at
     * roughly three times its size before SAX even started on a 32-bit TV box;
     * streaming keeps only the parser window in memory.
     */
    private suspend fun parseRemoteGuide(location: String, headers: Map<String, String>): GuideParse {
        val requestHeaders = StreamUrls.mergeHeaders(
            mapOf("User-Agent" to Http.BROWSER_USER_AGENT, "Accept" to "*/*"),
            headers
        )
        val request = Request.Builder()
            .url(location)
            .apply {
                requestHeaders.forEach { (name, value) -> if (name.isNotBlank()) header(name, value) }
            }
            .build()
        val startedAt = SystemClock.elapsedRealtime()
        return Http.documentClient.awaitRedirects(request).use { resolved ->
            val response = resolved.response
            if (response.code !in 200..299) throw IOException("HTTP ${response.code}")
            if (resolved.redirected) {
                Log.d(
                    "StreamLine:redirect",
                    "${location.take(120)} → ${resolved.finalUrl.take(120)} · ${resolved.hops.size} hop(s)"
                )
            }
            val body = response.body ?: throw IOException("Empty response")
            Log.d(
                "StreamLine:epg",
                "guide ${location.substringBefore('?')} · ${body.contentLength().takeIf { it > 0 }?.div(1024) ?: "?"}KB" +
                    " · ${SystemClock.elapsedRealtime() - startedAt}ms to headers"
            )
            IptvParser.maybeGunzip(body.byteStream()).use { stream ->
                val capped = CappedInputStream(stream, MAX_GUIDE_DECODED_BYTES, "guide")
                IptvParser.parseXmlTvDetailed(capped, maxPrograms = singleGuideCap)
            }
        }
    }

    private fun parseLocalGuide(bytes: ByteArray): GuideParse =
        IptvParser.maybeGunzip(ByteArrayInputStream(bytes)).use { stream ->
            val capped = CappedInputStream(stream, MAX_GUIDE_DECODED_BYTES, "guide")
            IptvParser.parseXmlTvDetailed(capped, maxPrograms = singleGuideCap)
        }

    private fun isRemoteGuide(location: String): Boolean =
        location.startsWith("http://", true) || location.startsWith("https://", true)

    fun clearGuideCache() {
        synchronized(guideCache) { guideCache.clear() }
    }

    private fun finish(playlist: IptvPlaylist, source: IptvSource): IptvPlaylist {
        if (playlist.channels.isEmpty()) {
            throw IOException("No channels found in this playlist")
        }
        val kept = playlist.channels.take(MAX_IPTV_CHANNELS)
        val overflow = playlist.channels.size - kept.size
        val report = playlist.report ?: PlaylistReport(total = kept.size)
        return playlist.copy(
            channels = kept,
            report = if (overflow > 0) report.copy(total = kept.size, skipped = report.skipped + overflow) else report
        )
    }

    private fun singleChannel(
        source: IptvSource,
        rawLocation: String,
        resolvedUrl: String = rawLocation
    ): LiveChannel {
        val parsed = StreamUrls.parse(rawLocation)
        val label = source.name.ifBlank {
            StreamUrls.parse(resolvedUrl).url.substringAfter("://").substringBefore('/').ifBlank { "Stream" }
        }
        return LiveChannel(
            id = "stream",
            name = label,
            type = "tv",
            addonUrl = iptvAddonUrl(source.id),
            poster = null,
            description = null,
            releaseInfo = null,
            category = null,
            // Keep the pipe headers; only swap the short link for its target.
            streamUrl = StreamUrls.withHeaders(resolvedUrl, parsed.headers),
            epgId = null,
            channelNumber = null,
            sourceId = source.id
        )
    }

    private suspend fun readPrefix(location: String, headers: Map<String, String>): Fetched {
        return runCatching { fetchCapped(location, headers, PREFIX_BYTES, rangePrefix = true) }
            .getOrElse { throw IOException("Playlist unreachable: ${it.message ?: "request failed"}") }
    }

    private suspend fun fetchCapped(
        location: String,
        headers: Map<String, String>,
        maxBytes: Int,
        rangePrefix: Boolean = false
    ): Fetched {
        return when {
            location.startsWith("content://") -> Fetched(
                appContext.contentResolver.openInputStream(Uri.parse(location))
                    ?.use { it.readCapped(maxBytes) }
                    ?: throw IOException("Cannot read the selected file"),
                location
            )
            location.startsWith("file://") ->
                Fetched(readFile(File(Uri.parse(location).path ?: ""), maxBytes), location)
            !location.contains("://") -> Fetched(readFile(File(location), maxBytes), location)
            location.startsWith("http://", true) || location.startsWith("https://", true) -> {
                val requestHeaders = StreamUrls.mergeHeaders(
                    mapOf("User-Agent" to Http.BROWSER_USER_AGENT, "Accept" to "*/*"),
                    headers
                )
                val request = Request.Builder()
                    .url(location)
                    .apply {
                        requestHeaders.forEach { (name, value) -> if (name.isNotBlank()) header(name, value) }
                        if (rangePrefix) header("Range", "bytes=0-${PREFIX_BYTES - 1}")
                    }
                    .build()
                Http.documentClient.awaitRedirects(request).use { resolved ->
                    val response = resolved.response
                    if (response.code !in 200..299) {
                        throw IOException("HTTP ${response.code}")
                    }
                    if (resolved.redirected) {
                        Log.d(
                            "StreamLine:redirect",
                            "${location.take(120)} → ${resolved.finalUrl.take(120)} · ${resolved.hops.size} hop(s)"
                        )
                    }
                    Fetched(
                        response.body?.byteStream()?.readCapped(maxBytes)
                            ?: throw IOException("Empty response"),
                        resolved.finalUrl
                    )
                }
            }
            else -> throw IOException("Unsupported source scheme: ${location.substringBefore("://")}")
        }
    }

    private fun readFile(file: File, maxBytes: Int): ByteArray {
        if (!file.exists()) throw IOException("File not found: ${file.name}")
        return file.inputStream().use { it.readCapped(maxBytes) }
    }

    /**
     * UTF-8 with a Latin-1 fallback: older provider playlists are still saved in
     * ANSI, and decoding those as UTF-8 turns channel names into replacement
     * characters.
     */
    private fun decodeText(bytes: ByteArray, sniffOnly: Boolean = false): String {
        if (bytes.isEmpty()) return ""
        val decoded = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        if (decoded.contains('\uFFFD')) {
            return bytes.toString(Charsets.ISO_8859_1).removePrefix("\uFEFF")
        }
        return if (sniffOnly) decoded.take(PREFIX_TEXT_CHARS) else decoded
    }

    private fun looksLikeM3u(prefix: String): Boolean =
        prefix.trimStart().startsWith("#EXTM3U", ignoreCase = true)

    private fun looksLikeHlsMedia(prefix: String): Boolean {
        val upper = prefix.uppercase(Locale.US)
        return upper.contains("#EXT-X-STREAM-INF") ||
            upper.contains("#EXT-X-TARGETDURATION") ||
            upper.contains("#EXT-X-MEDIA-SEQUENCE") ||
            upper.contains("#EXT-X-PLAYLIST-TYPE")
    }

    private fun looksLikeXspf(prefix: String): Boolean =
        prefix.contains("<playlist", ignoreCase = true)

    private fun looksLikeXmlTv(prefix: String, location: String): Boolean {
        val lower = location.lowercase(Locale.US).substringBefore('?')
        if (lower.endsWith(".xml") || lower.endsWith(".xml.gz") || lower.endsWith(".gz")) {
            if (lower.endsWith(".xml") || lower.endsWith(".xml.gz")) return true
        }
        val trimmed = prefix.trimStart()
        return trimmed.startsWith("<?xml") && prefix.contains("<tv", ignoreCase = true) &&
            prefix.contains("<programme", ignoreCase = true)
    }

    private fun isNonHttpScheme(location: String): Boolean {
        val scheme = location.substringBefore("://", "").lowercase(Locale.US)
        return scheme.isNotEmpty() && scheme != "http" && scheme != "https" &&
            scheme != "content" && scheme != "file"
    }

    private fun InputStream.readCapped(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total < maxBytes) {
            val read = read(buffer)
            if (read < 0) break
            val take = minOf(read, maxBytes - total)
            out.write(buffer, 0, take)
            total += take
        }
        return out.toByteArray()
    }

    private companion object {
        const val PREFIX_BYTES = 64 * 1024
        const val PREFIX_TEXT_CHARS = 16 * 1024
        const val MAX_PLAYLIST_BYTES = 16 * 1024 * 1024
        const val MAX_GUIDE_BYTES = 24 * 1024 * 1024
        // Decompressed limit: a compressed guide of a few MB can expand ~10x,
        // and the parser needs the whole window before SAX emits programmes.
        const val MAX_GUIDE_DECODED_BYTES = 256L * 1024 * 1024
        const val MAX_CACHED_GUIDES = 4
        const val GUIDE_TTL_MS = 15 * 60 * 1000L
    }
}
