package com.streamline.cinema.data

import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory

/** How an IPTV source is read: a playlist file or a single stream endpoint. */
enum class IptvKind(val label: String) {
    M3U("M3U / M3U8"),
    XSPF("XSPF"),
    STREAM("Single stream");

    companion object {
        fun from(raw: String?): IptvKind = entries.firstOrNull { it.name == raw } ?: M3U
    }
}

/**
 * One user-added IPTV source. [location] keeps the raw URL/URI including the
 * `url|Header=value` pipe syntax; local files are stored as `content://` URIs
 * with a persisted read grant. [epgUrl] is the user override, while
 * [extractedEpgUrl] is what the M3U header (`url-tvg`, `x-tvg-url`, `tvg-url`)
 * advertised on the last load. [epgShiftMinutes] corrects guides whose
 * provider stamps every programme with a wrong UTC offset; it only applies
 * while [epgShiftAuto] is false, so a wrong provider can be corrected by
 * detection and a manual override always wins.
 */
data class IptvSource(
    val id: String,
    val name: String,
    val location: String,
    val kind: IptvKind = IptvKind.M3U,
    val epgUrl: String = "",
    val extractedEpgUrl: String = "",
    val epgShiftMinutes: Int = 0,
    val epgShiftAuto: Boolean = true,
    val enabled: Boolean = true,
    val addedAt: Long = 0L,
    val channelCount: Int = 0,
    val lastLoadedAt: Long = 0L,
    /** Summary of the last parse: "142 channels · 3 skipped · 1 malformed". */
    val lastReport: String = ""
) {
    val effectiveEpgUrl: String
        get() = epgUrl.ifBlank { extractedEpgUrl }

    val cleanLocation: String get() = StreamUrls.clean(location)

    val isLocal: Boolean
        get() = cleanLocation.startsWith("content://") ||
            cleanLocation.startsWith("file://") ||
            (!cleanLocation.contains("://") && cleanLocation.isNotBlank())

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("location", location)
        put("kind", kind.name)
        put("epgUrl", epgUrl)
        put("extractedEpgUrl", extractedEpgUrl)
        put("epgShiftMinutes", epgShiftMinutes)
        put("epgShiftAuto", epgShiftAuto)
        put("enabled", enabled)
        put("addedAt", addedAt)
        put("channelCount", channelCount)
        put("lastLoadedAt", lastLoadedAt)
        put("lastReport", lastReport)
    }

    companion object {
        fun fromJson(o: JSONObject) = IptvSource(
            id = o.optString("id"),
            name = o.optString("name"),
            location = o.optString("location"),
            kind = IptvKind.from(o.stringOrNull("kind")),
            epgUrl = o.optString("epgUrl"),
            extractedEpgUrl = o.optString("extractedEpgUrl"),
            epgShiftMinutes = o.optInt("epgShiftMinutes").coerceIn(-12 * 60, 12 * 60),
            // Sources saved before detection existed keep a non-zero shift as
            // an explicit correction; the rest hand themselves back to AUTO.
            epgShiftAuto = o.optBoolean("epgShiftAuto", o.optInt("epgShiftMinutes") == 0),
            enabled = o.optBoolean("enabled", true),
            addedAt = o.optLong("addedAt"),
            channelCount = o.optInt("channelCount"),
            lastLoadedAt = o.optLong("lastLoadedAt"),
            lastReport = o.optString("lastReport")
        )
    }
}

/**
 * What a playlist parse kept and dropped. A provider feed with a few bad rows
 * still yields a usable channel list; the summary lets the Live tab say so
 * instead of leaving the degradation invisible.
 */
data class PlaylistReport(
    val total: Int,
    val skipped: Int = 0,
    val malformed: Int = 0
) {
    val summary: String
        get() = buildString {
            append(total).append(" channels")
            if (skipped > 0) append(" · ").append(skipped).append(" skipped")
            if (malformed > 0) append(" · ").append(malformed).append(" malformed")
        }
}

data class IptvPlaylist(
    val channels: List<LiveChannel>,
    val epgUrl: String? = null,
    val title: String? = null,
    val kind: IptvKind = IptvKind.M3U,
    val report: PlaylistReport? = null
)

/** A parsed guide plus what the parse skipped or could not read. */
data class GuideParse(
    val guide: EpgGuide,
    val report: PlaylistReport = PlaylistReport(total = guide.channelCount)
)

data class EpgProgram(
    val channelId: String,
    val title: String,
    val subTitle: String? = null,
    val description: String? = null,
    val startMs: Long,
    val stopMs: Long
) {
    fun overlaps(atMs: Long): Boolean = startMs <= atMs && atMs < stopMs
}

data class EpgChannelInfo(
    val id: String,
    val name: String,
    val number: Int? = null,
    val icon: String? = null
)

data class EpgNowNext(
    val now: EpgProgram? = null,
    val next: EpgProgram? = null
)

/** A programme matched by title search, paired with the channel carrying it. */
data class EpgProgramHit(
    val channelId: String,
    val channelName: String,
    val program: EpgProgram
)

/**
 * Parsed XMLTV guide: one programme list per channel id plus the channel
 * display names so a `tvg-id`-less playlist entry can still be matched.
 * [declaredOffsetMinutes] is the UTC offset the document stamps most of its
 * programmes with (null when it publishes wall-clock stamps), which lets a
 * mislabelled provider be recognised instead of shifted by hand.
 */
class EpgGuide(
    private val programsByChannel: Map<String, List<EpgProgram>> = emptyMap(),
    val channels: Map<String, EpgChannelInfo> = emptyMap(),
    val declaredOffsetMinutes: Int? = null,
    private val shiftMinutes: Int = 0
) {
    private val shiftMs: Long = shiftMinutes * 60_000L
    private val nameIndex: Map<String, String> = channels.values.associate { normalizeChannelName(it.name) to it.id }

    val channelCount: Int get() = programsByChannel.size
    val programCount: Int get() = programsByChannel.values.sumOf { it.size }
    val isEmpty: Boolean get() = programCount == 0

    /** Correction this guide carries, so the UI can say "guide is +2h off — adjusted". */
    val appliedShiftMinutes: Int get() = shiftMinutes

    fun programs(channelId: String): List<EpgProgram> {
        val list = programsByChannel[channelId].orEmpty()
        return if (shiftMs == 0L) list else list.map { it.shiftedBy(shiftMs) }
    }

    /** Matches by `tvg-id` first, then by a normalised display name. */
    fun nowNext(epgId: String?, channelName: String, atMs: Long = System.currentTimeMillis()): EpgNowNext? {
        val keys = buildList {
            epgId?.takeIf { it.isNotBlank() }?.let { add(it) }
            nameIndex[normalizeChannelName(channelName)]?.let { add(it) }
            if (programsByChannel.containsKey(channelName)) add(channelName)
        }.distinct()
        // Times are stored unshifted; search and compare in the guide's own clock.
        val lookupMs = atMs - shiftMs
        for (key in keys) {
            val list = programsByChannel[key] ?: continue
            if (list.isEmpty()) continue
            val index = lastStartingAt(list, lookupMs)
            val now = index.takeIf { it >= 0 }?.let { list[it] }?.takeIf { it.overlaps(lookupMs) }
                ?.shiftedBy(shiftMs)
            val next = when {
                index >= 0 && index < list.lastIndex -> list[index + 1].shiftedBy(shiftMs)
                index < 0 -> list.first().shiftedBy(shiftMs)
                else -> null
            }
            if (now != null || next != null) return EpgNowNext(now = now, next = next)
        }
        return null
    }

    /**
     * Title search over the guide for the Playroom's unified search. Only
     * programmes still on air or upcoming are returned, currently-airing
     * matches first, so a hit is always something the user can tune to.
     * Scanning is capped per guide so a multi-hundred-thousand row document
     * cannot stall a keystroke; callers run it off the main thread.
     */
    fun searchPrograms(
        query: String,
        atMs: Long = System.currentTimeMillis(),
        limit: Int = 8
    ): List<EpgProgramHit> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty() || limit <= 0) return emptyList()
        val now = atMs - shiftMs
        val hits = ArrayList<EpgProgramHit>(limit * 4)
        programsByChannel.forEach { (id, list) ->
            var perChannel = 0
            val info = channels[id]
            for (program in list) {
                if (program.stopMs <= now) continue
                val matches = program.title.lowercase().contains(needle) ||
                    program.subTitle?.lowercase()?.contains(needle) == true
                if (!matches) continue
                hits += EpgProgramHit(id, info?.name ?: id, program.shiftedBy(shiftMs))
                if (++perChannel >= limit) break
            }
        }
        return hits
            .distinctBy { it.channelId to it.program.title.lowercase() }
            .sortedWith(compareBy({ it.program.startMs > atMs }, { it.program.startMs }))
            .take(limit)
    }

    /**
     * Moves every programme by [minutes]; providers occasionally stamp their
     * whole guide with the wrong UTC offset, so the correction belongs to the
     * source rather than to the parser. The offset is kept off the programme
     * lists — copying up to 200k programmes tied a multi-MB guide to a second
     * full copy in the heap — and applied when a programme is read.
     */
    fun shifted(minutes: Int): EpgGuide {
        if (minutes == 0) return this
        return EpgGuide(programsByChannel, channels, declaredOffsetMinutes, shiftMinutes + minutes)
    }

    /**
     * The correction a malformed guide needs, in minutes.
     *
     * epgshare's South African feed labels every programme `+0700` while the
     * schedule inside is a UTC+8 wall clock, so the whole country lands an
     * hour late. Only that unmistakable signature is corrected: a feed whose
     * ids are all `.za` and whose programmes all share one `+0700` label. A
     * mixed guide, a guide without channel ids, or the provider's own fix to
     * the label all leave this at 0, so the correction cannot outlive the bug.
     */
    fun autoShiftMinutes(): Int {
        if (declaredOffsetMinutes != 7 * 60) return 0
        if (channels.isEmpty()) return 0
        val allSouthAfrican = channels.keys.all { it.endsWith(".za", ignoreCase = true) }
        return if (allSouthAfrican) -60 else 0
    }

    /**
     * Compact channel → programme index for cold start. A 24 MB guide takes
     * seconds to download and parse again after every process death; writing
     * just the timestamps and titles lets the Live tab render NOW/NEXT from the
     * previous session while the full guide refreshes in the background.
     * Descriptions are dropped: they are never shown in now/next.
     */
    fun indexJson(maxPrograms: Int = MAX_INDEX_PROGRAMS): String {
        val channelArray = JSONArray()
        channels.values.forEach { info ->
            channelArray.put(
                JSONObject().put("i", info.id).put("n", info.name).apply {
                    info.number?.let { put("u", it) }
                }
            )
        }
        val programObject = JSONObject()
        var total = 0
        programsByChannel.forEach channelLoop@ { (id, list) ->
            if (total >= maxPrograms) return@channelLoop
            val array = JSONArray()
            for (program in list) {
                if (total >= maxPrograms) break
                array.put(
                    JSONArray()
                        .put(program.startMs)
                        .put(program.stopMs)
                        .put(program.title.take(160))
                        .put(program.subTitle?.take(120).orEmpty())
                )
                total++
            }
            if (array.length() > 0) programObject.put(id, array)
        }
        return JSONObject()
            .put("v", 1)
            .put("shift", shiftMinutes)
            .put("channels", channelArray)
            .put("programs", programObject)
            .toString()
    }

    private fun lastStartingAt(list: List<EpgProgram>, atMs: Long): Int {
        var low = 0
        var high = list.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (list[mid].startMs <= atMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    companion object {
        val EMPTY = EpgGuide()

        /** [maxPrograms] is device-class dependent; see `DeviceProfile`. */
        fun merge(parts: List<EpgGuide>, maxPrograms: Int = MAX_MERGED_EPG_PROGRAMS): EpgGuide {
            if (parts.isEmpty()) return EMPTY
            // A single guide is already sorted and capped; copying it only ties
            // another full programme set to the heap for the whole session.
            if (parts.size == 1) return parts[0]
            val programs = HashMap<String, MutableList<EpgProgram>>()
            val channels = HashMap<String, EpgChannelInfo>()
            var total = 0
            parts.forEach partLoop@ { part ->
                part.channels.forEach { (id, info) -> channels.putIfAbsent(id, info) }
                if (total >= maxPrograms) return@partLoop
                part.programsByChannel.forEach channelLoop@ { (id, list) ->
                    if (total >= maxPrograms) return@channelLoop
                    val target = programs.getOrPut(id) { ArrayList(list.size) }
                    if (part.shiftMs == 0L) {
                        target.addAll(list)
                        total += list.size
                    } else {
                        list.forEach { target.add(it.shiftedBy(part.shiftMs)) }
                        total += list.size
                    }
                }
            }
            // Each source is capped on its own; merging several still multiplies
            // the per-channel list, so re-apply the same bound after sorting.
            val sorted = programs.mapValues { (_, list) ->
                list.sortedBy { it.startMs }
                    .distinctBy { it.startMs to it.title }
                    .take(MAX_PROGRAMS_PER_CHANNEL)
            }
            return EpgGuide(sorted, channels)
        }

        /** Rebuilds a guide from [indexJson]; null when the document is unusable. */
        fun fromIndexJson(text: String?): EpgGuide? = runCatching {
            val root = JSONObject(text ?: return null)
            if (root.optInt("v") != 1) return null
            val channels = LinkedHashMap<String, EpgChannelInfo>()
            val channelArray = root.optJSONArray("channels") ?: JSONArray()
            for (index in 0 until channelArray.length()) {
                val entry = channelArray.optJSONObject(index) ?: continue
                val id = entry.optString("i").takeIf { it.isNotBlank() } ?: continue
                channels[id] = EpgChannelInfo(
                    id = id,
                    name = entry.optString("n", id),
                    number = entry.optInt("u").takeIf { it > 0 }
                )
            }
            val programObject = root.optJSONObject("programs") ?: return null
            val programs = HashMap<String, List<EpgProgram>>()
            var total = 0
            programObject.keys().forEach channelLoop@ { id ->
                if (total >= MAX_INDEX_PROGRAMS) return@channelLoop
                val array = programObject.optJSONArray(id) ?: return@channelLoop
                val list = ArrayList<EpgProgram>(minOf(array.length(), MAX_PROGRAMS_PER_CHANNEL))
                for (index in 0 until array.length()) {
                    if (total >= MAX_INDEX_PROGRAMS) break
                    val row = array.optJSONArray(index) ?: continue
                    val start = row.optLong(0, Long.MIN_VALUE)
                    val stop = row.optLong(1, Long.MIN_VALUE)
                    val title = row.optString(2).takeIf { it.isNotBlank() } ?: continue
                    if (start == Long.MIN_VALUE || stop <= start) continue
                    list += EpgProgram(
                        channelId = id,
                        title = title,
                        subTitle = row.optString(3).takeIf { it.isNotBlank() },
                        startMs = start,
                        stopMs = stop
                    )
                    total++
                }
                if (list.isNotEmpty()) programs[id] = list.sortedBy { it.startMs }
            }
            if (programs.isEmpty()) return null
            EpgGuide(
                programsByChannel = programs,
                channels = channels,
                shiftMinutes = root.optInt("shift").coerceIn(-12 * 60, 12 * 60)
            )
        }.getOrNull()
    }
}

private fun EpgProgram.shiftedBy(deltaMs: Long): EpgProgram =
    if (deltaMs == 0L) this else copy(startMs = startMs + deltaMs, stopMs = stopMs + deltaMs)

// Compiled once: nowNext() runs for every channel card, and building four
// Regex objects per call turned scrolling into a GC storm on big playlists.
private val nameTagRegex = Regex("\\[[^\\]]*]")
private val nameParenRegex = Regex("\\([^)]*\\)")
private val nameQualityRegex = Regex("\\b(hd|fhd|uhd|sd|4k|1080p|720p|576p|480p)\\b")
private val nameNonAlnumRegex = Regex("[^a-z0-9]+")

private fun normalizeChannelName(raw: String): String =
    raw.lowercase(Locale.US)
        .replace(nameTagRegex, " ")
        .replace(nameParenRegex, " ")
        .replace(nameQualityRegex, " ")
        .replace(nameNonAlnumRegex, "")

/**
 * Playlist and guide parsers. Everything here is plain JVM code (SAX + regex)
 * so the parsing rules are unit-testable off-device.
 */
object IptvParser {

    // Providers mix quoting styles; Internet-radio exports are often
    // single-quoted, which used to drop every tvg-* attribute silently.
    private val attributeRegex = Regex(
        """([A-Za-z0-9_.:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')"""
    )
    private val unquotedAttributeRegex = Regex("""([A-Za-z0-9_.:-]+)\s*=\s*([^\s"',]+)""")
    private val epgAttributeRegex = Regex(
        """(?:^|\s)(?:url-tvg|x-tvg-url|tvg-url)\s*=\s*(?:"([^"]+)"|'([^']+)')""",
        RegexOption.IGNORE_CASE
    )
    private val entityDeclRegex = Regex(
        """<!ENTITY\s+[^\s>]+\s+(?:"([^"]*)"|'([^']*)')""",
        RegexOption.IGNORE_CASE
    )
    private val entityRefRegex = Regex("""&([A-Za-z_][A-Za-z0-9_.:-]*);""")
    private val BUILT_IN_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")
    private const val PROLOG_SCAN_BYTES = 64 * 1024

    /** Reads `url-tvg`, `x-tvg-url` or `tvg-url` out of the `#EXTM3U` header. */
    fun extractEpgUrlFromM3u(text: String): String? {
        val header = text.lineSequence()
            .map { it.removePrefix("\uFEFF").trim() }
            .firstOrNull { it.startsWith("#EXTM3U", ignoreCase = true) }
            ?: return null
        val match = epgAttributeRegex.find(header) ?: return null
        val raw = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?: match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
            ?: return null
        return raw.split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    /**
     * Parses an extended M3U playlist line by line. [onBatch] receives channels
     * as they are built, so a caller with a huge playlist can show progress
     * instead of waiting for the whole document. The returned [PlaylistReport]
     * counts rows that were dropped so partial results stay explainable.
     */
    fun parseM3u(
        text: String,
        sourceId: String,
        baseUrl: String = "",
        batchSize: Int = 500,
        onBatch: ((List<LiveChannel>) -> Unit)? = null
    ): IptvPlaylist {
        val epg = extractEpgUrlFromM3u(text)
        val channels = ArrayList<LiveChannel>()
        val usedIds = HashMap<String, Int>()
        var pending: ParsedExtInf? = null
        var pendingGroup: String? = null
        var pendingHeaders = LinkedHashMap<String, String>()
        var currentGroup: String? = null
        var skipped = 0
        var malformed = 0
        var reported = 0
        val batch = if (batchSize > 0) batchSize else Int.MAX_VALUE

        fun flushBatch(force: Boolean = false) {
            val callback = onBatch ?: return
            if (channels.size - reported >= batch || (force && channels.size > reported)) {
                val slice = channels.subList(reported, channels.size).toList()
                reported = channels.size
                runCatching { callback(slice) }
            }
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.removePrefix("\uFEFF").trim()
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    // Two EXTINF records without a URL between them mean the
                    // first row was truncated; count it instead of silently
                    // relabelling its successor.
                    if (pending != null) malformed++
                    val parsed = parseExtInf(line)
                    if (parsed.displayName.isBlank() && parsed.attributes.isEmpty()) {
                        malformed++
                        pending = null
                    } else {
                        pending = parsed
                    }
                    pendingHeaders = LinkedHashMap()
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().ifBlank { null }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    parseVlcOption(line.substringAfter(':').trim(), pendingHeaders)
                }
                line.startsWith("#", ignoreCase = true) || line.isBlank() -> Unit
                else -> {
                    val info = pending ?: run {
                        skipped++
                        return@forEach
                    }
                    // A 16 MiB playlist can describe 100k+ entries; stop building
                    // objects at the same bound the store enforces.
                    if (channels.size >= MAX_IPTV_CHANNELS) {
                        skipped++
                        pending = null
                        return@forEach
                    }
                    val resolved = resolveUrl(line, baseUrl) ?: run {
                        malformed++
                        pending = null
                        return@forEach
                    }
                    val headers = LinkedHashMap<String, String>()
                    info.userAgent?.let { headers["User-Agent"] = it }
                    info.referrer?.let { headers["Referer"] = it }
                    headers.putAll(pendingHeaders)
                    val name = info.displayName.ifBlank { info.attributes["tvg-name"].orEmpty() }
                        .ifBlank { "Channel ${channels.size + 1}" }
                    val tvgId = info.attributes["tvg-id"].orEmpty().trim()
                    val slug = tvgId.ifBlank { info.attributes["tvg-name"].orEmpty().trim() }
                        .ifBlank { name }
                    val id = uniqueId(slug, usedIds)
                    val category = (info.attributes["group-title"]?.trim().orEmpty()
                        .ifBlank { pendingGroup.orEmpty() }
                        .ifBlank { currentGroup.orEmpty() })
                        .split(';')
                        .map { it.trim() }
                        .firstOrNull { it.isNotEmpty() }
                    channels += LiveChannel(
                        id = id,
                        name = name,
                        type = "tv",
                        addonUrl = iptvAddonUrl(sourceId),
                        poster = info.attributes["tvg-logo"]?.trim()?.takeIf { it.isNotEmpty() },
                        description = null,
                        releaseInfo = null,
                        category = category,
                        streamUrl = withPipeHeaders(resolved, headers),
                        epgId = tvgId.takeIf { it.isNotEmpty() },
                        channelNumber = info.attributes["tvg-chno"]?.trim()?.toIntOrNull(),
                        sourceId = sourceId
                    )
                    if (pendingGroup != null) currentGroup = pendingGroup
                    pending = null
                    pendingHeaders.clear()
                    flushBatch()
                }
            }
        }
        if (pending != null) malformed++
        flushBatch(force = true)
        return IptvPlaylist(
            channels = channels,
            epgUrl = epg,
            report = PlaylistReport(total = channels.size, skipped = skipped, malformed = malformed)
        )
    }

    fun parseXspf(text: String, sourceId: String): IptvPlaylist {
        val handler = XspfHandler(sourceId)
        parseXml(text.byteInputStream(Charsets.UTF_8), handler)
        return IptvPlaylist(
            channels = handler.channels,
            epgUrl = handler.epgUrl,
            title = handler.playlistTitle,
            report = PlaylistReport(total = handler.channels.size)
        )
    }

    /**
     * Streams an XMLTV document (plain or already gunzipped) and keeps the
     * programmes around now: from six hours ago to two days ahead. The cap
     * keeps a multi-hundred-megabyte guide from exhausting a 32-bit TV box.
     */
    fun parseXmlTv(
        input: InputStream,
        nowMs: Long = System.currentTimeMillis(),
        maxPrograms: Int = MAX_EPG_PROGRAMS
    ): EpgGuide = parseXmlTvDetailed(input, nowMs, maxPrograms).guide

    /**
     * Same parse, but keeps the report so the UI can say how much of a broken
     * provider guide was dropped instead of silently showing fewer programmes.
     */
    fun parseXmlTvDetailed(
        input: InputStream,
        nowMs: Long = System.currentTimeMillis(),
        maxPrograms: Int = MAX_EPG_PROGRAMS
    ): GuideParse {
        val handler = XmlTvHandler(nowMs, maxPrograms)
        parseXml(input, handler)
        return GuideParse(handler.toGuide(), handler.toReport())
    }

    fun parseXmlTv(
        text: String,
        nowMs: Long = System.currentTimeMillis()
    ): EpgGuide = parseXmlTv(text.byteInputStream(Charsets.UTF_8), nowMs)

    /** pako's `ungzip` equivalent over the platform GZIP decoder. */
    fun ungzip(raw: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }

    fun ungzip(input: InputStream): InputStream = GZIPInputStream(input)

    /** Transparently wraps a stream in GZIP when the `1f 8b` magic is present. */
    fun maybeGunzip(input: InputStream): InputStream {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
    }

    /**
     * XMLTV timestamps look like `20261020140000 +0200`. Guides also emit ISO
     * separators (`2026-10-20T14:00:00Z`, milliseconds before the zone) and
     * 12/10/8-digit partial stamps. The offset describes the local clock, so
     * UTC = local − offset. A stamp with no offset is read as UTC, the
     * convention XMLTV uses for zoneless documents; a guide that publishes
     * local wall clock without a zone is corrected with the per-source shift.
     */
    fun parseXmlTvDate(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // Hand-rolled digit scan: regex matching per programme dominated guide
        // parsing on Android (two timestamps per programme).
        val digits = IntArray(15)
        val index = scanXmlTvDigits(text, digits)
        val count = digits[14]
        if (count < 8) return null
        fun number(from: Int, length: Int): Int {
            var value = 0
            repeat(length) { value = value * 10 + digits[from + it] }
            return value
        }
        val year = number(0, 4)
        val month = number(4, 2)
        val day = number(6, 2)
        val hour = if (count >= 10) number(8, 2) else 0
        val minute = if (count >= 12) number(10, 2) else 0
        val second = if (count >= 14) number(12, 2) else 0
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
            return null
        }
        val offsetMinutes = offsetMinutesAt(text, index) ?: 0
        val days = daysFromCivil(year, month, day)
        val localSeconds = days * 86_400L + hour * 3_600L + minute * 60L + second
        // Zoneless stamps are UTC; with an offset, UTC = local − offset.
        return (localSeconds - offsetMinutes * 60L) * 1_000L
    }

    /**
     * Scans the leading `YYYYMMDDhhmmss` fields, accepting the usual
     * separators, and stores the digit count in `digits[14]` so callers get
     * the end index without an extra allocation.
     */
    private fun scanXmlTvDigits(text: String, digits: IntArray): Int {
        var count = 0
        var index = 0
        while (count < 14 && index < text.length) {
            val c = text[index]
            if (c in '0'..'9') {
                digits[count++] = c - '0'
                index++
                continue
            }
            val next = text.getOrNull(index + 1)
            val separator = when {
                count < 8 -> c == '-' || c == '/' || c == '.'
                else -> c == ':' || c == 'T' || c == 't'
            }
            if (separator && next != null && next in '0'..'9') {
                index++
                continue
            }
            if (c == ' ' && count in 8..11) {
                var look = index + 1
                while (look < text.length && text[look] == ' ') look++
                if (look < text.length && text[look] in '0'..'9') {
                    index = look
                    continue
                }
            }
            break
        }
        digits[14] = count
        return index
    }

    /**
     * Parses the `Z` / `±hhmm` / `±hh:mm` marker at or after [from], skipping
     * an optional fractional-seconds part (`…14:00:00.000Z`) so an ISO stamp
     * does not lose its zone to the milliseconds.
     */
    private fun offsetMinutesAt(text: String, from: Int): Int? {
        var cursor = from
        while (cursor < text.length && text[cursor] == ' ') cursor++
        if (cursor < text.length && text[cursor] == '.') {
            cursor++
            while (cursor < text.length && text[cursor] in '0'..'9') cursor++
            while (cursor < text.length && text[cursor] == ' ') cursor++
        }
        if (cursor >= text.length) return null
        val marker = text[cursor]
        if (marker == 'Z' || marker == 'z') return 0
        if (marker != '+' && marker != '-') return null
        var hours = 0
        var minutes = 0
        var seen = 0
        var position = cursor + 1
        while (position < text.length && seen < 4) {
            val c = text[position]
            if (c in '0'..'9') {
                if (seen < 2) hours = hours * 10 + (c - '0') else minutes = minutes * 10 + (c - '0')
                seen++
                position++
            } else if (c == ':' && seen == 2) {
                position++
            } else {
                break
            }
        }
        if ((seen == 2 || seen == 4) && hours <= 18 && minutes < 60) {
            return (if (marker == '-') -1 else 1) * (hours * 60 + minutes)
        }
        return null
    }

    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val monthIndex = if (month > 2) month - 3 else month + 9
        val dayOfYear = (153 * monthIndex + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    private fun parseExtInf(line: String): ParsedExtInf {
        val commaIndex = firstCommaOutsideQuotes(line)
        val attrPart = if (commaIndex < 0) line else line.substring(0, commaIndex)
        val displayName = if (commaIndex < 0) "" else line.substring(commaIndex + 1).trim()
        val attributes = LinkedHashMap<String, String>()
        attributeRegex.findAll(attrPart).forEach {
            val value = it.groupValues[2].ifEmpty { it.groupValues[3] }
            attributes[it.groupValues[1].lowercase(Locale.US)] = value
        }
        unquotedAttributeRegex.findAll(attrPart).forEach {
            attributes.putIfAbsent(it.groupValues[1].lowercase(Locale.US), it.groupValues[2])
        }
        return ParsedExtInf(
            attributes = attributes,
            displayName = displayName,
            userAgent = attributes["http-user-agent"],
            referrer = attributes["http-referrer"] ?: attributes["referrer"]
        )
    }

    private fun parseVlcOption(option: String, headers: MutableMap<String, String>) {
        val key = option.substringBefore('=').trim().lowercase(Locale.US)
        val value = option.substringAfter('=', "").trim().removeSurrounding("\"")
        if (value.isEmpty()) return
        when (key) {
            "http-user-agent" -> headers["User-Agent"] = value
            "http-referrer", "http-referer" -> headers["Referer"] = value
            "http-origin" -> headers["Origin"] = value
            "http-cookie" -> headers["Cookie"] = value
        }
    }

    private fun firstCommaOutsideQuotes(line: String): Int {
        var quote: Char? = null
        line.forEachIndexed { index, char ->
            when {
                quote == null && (char == '"' || char == '\'') -> quote = char
                char == quote -> quote = null
                char == ',' && quote == null -> return index
            }
        }
        return -1
    }

    private fun resolveUrl(raw: String, baseUrl: String): String? {
        val candidate = raw.trim()
        if (candidate.isEmpty()) return null
        if (candidate.contains("://") || candidate.startsWith("content://")) return candidate
        val base = StreamUrls.clean(baseUrl)
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) return null
        return runCatching { java.net.URI(base).resolve(candidate).toString() }.getOrNull()
    }

    private fun uniqueId(raw: String, used: MutableMap<String, Int>): String {
        val base = raw.ifBlank { "channel" }
        val seen = used[base] ?: 0
        used[base] = seen + 1
        return if (seen == 0) base else "$base#${seen + 1}"
    }

    internal fun withPipeHeaders(url: String, headers: Map<String, String>): String {
        if (headers.isEmpty() || url.contains('|')) return url
        val encoded = headers.entries
            .filter { (name, value) -> name.isNotBlank() && value.isNotBlank() }
            .joinToString("&") { (name, value) ->
                "${encode(name)}=${encode(value)}"
            }
        return if (encoded.isEmpty()) url else "$url|$encoded"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun parseXml(input: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        val reader = factory.newSAXParser().xmlReader
        runCatching {
            reader.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true)
        }
        // XMLTV documents reference xmltv.dtd; block every external fetch while
        // keeping the DOCTYPE parseable.
        runCatching {
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        val source = scanForEntityBombs(input)
        reader.contentHandler = handler
        reader.parse(InputSource(InputStreamReader(source, Charsets.UTF_8)))
    }

    /**
     * Blocking external entities is not enough: internal entities declared in
     * the DOCTYPE are expanded in-process, and Android exposes no amplification
     * budget. A `billion laughs` guide therefore parses until the heap dies.
     * Entity declarations can only appear in the prolog, so scan a bounded head
     * (already decompressed by the caller) and refuse one whose value references
     * another entity. Returns the (possibly newly buffered) stream the parser
     * must read from — wrapping consumes bytes that must not be lost.
     */
    private fun scanForEntityBombs(input: InputStream): InputStream {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, PROLOG_SCAN_BYTES)
        buffered.mark(PROLOG_SCAN_BYTES)
        val head = ByteArray(PROLOG_SCAN_BYTES)
        var read = 0
        while (read < head.size) {
            val count = buffered.read(head, read, head.size - read)
            if (count < 0) break
            read += count
        }
        buffered.reset()
        if (read == 0) return buffered
        val prolog = String(head, 0, read, Charsets.ISO_8859_1)
        entityDeclRegex.findAll(prolog).forEach { declaration ->
            val value = declaration.groupValues[1].ifEmpty { declaration.groupValues[2] }
            val reference = entityRefRegex.find(value)?.groupValues?.getOrNull(1)
            if (reference != null && reference !in BUILT_IN_ENTITIES) {
                throw IOException("Guide declares a recursive XML entity")
            }
        }
        return buffered
    }

    private data class ParsedExtInf(
        val attributes: Map<String, String>,
        val displayName: String,
        val userAgent: String?,
        val referrer: String?
    )

    private class XspfHandler(private val sourceId: String) : DefaultHandler() {
        val channels = ArrayList<LiveChannel>()
        var playlistTitle: String? = null
        var epgUrl: String? = null

        private var inTrack = false
        private var title: String? = null
        private var location: String? = null
        private var annotation: String? = null
        private var image: String? = null
        private var group: String? = null
        private var capture: String? = null
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val name = (localName?.takeIf { it.isNotEmpty() } ?: qName ?: return)
                .substringAfter(':')
                .lowercase(Locale.US)
            text.setLength(0)
            when (name) {
                "track" -> {
                    inTrack = true
                    title = null
                    location = null
                    annotation = null
                    image = null
                    group = null
                }
                "location", "annotation", "image", "title", "group-title", "category", "group" -> {
                    capture = name.lowercase(Locale.US)
                }
                "link" -> {
                    val rel = attributes?.getValue("rel").orEmpty()
                    val href = attributes?.getValue("href").orEmpty()
                    if (!inTrack && href.startsWith("http") &&
                        (rel.contains("epg", true) || rel.contains("xmltv", true))
                    ) {
                        epgUrl = href
                    }
                }
                "extension" -> {
                    val application = attributes?.getValue("application").orEmpty()
                    if (application.contains("group", true) || application.contains("category", true)) {
                        capture = "group"
                    }
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            capture ?: return
            // Names and descriptions are short; an endless text node (broken
            // encoder, entity bomb) must not grow the heap without bound.
            val room = MAX_CAPTURE_CHARS - text.length
            if (room > 0) text.append(ch, start, minOf(length, room))
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = (localName?.takeIf { it.isNotEmpty() } ?: qName ?: return)
                .substringAfter(':')
                .lowercase(Locale.US)
            val value = text.toString().trim()
            if (value.isNotEmpty()) {
                when (name) {
                    "title" -> if (inTrack) title = title ?: value else playlistTitle = playlistTitle ?: value
                    "location" -> if (inTrack) location = location ?: value
                    "annotation" -> if (inTrack) annotation = annotation ?: value
                    "image" -> if (inTrack) image = image ?: value
                    "group-title", "category", "group" -> if (inTrack) group = group ?: value
                }
            }
            if (name == "track") {
                val url = location
                if (!url.isNullOrBlank() && channels.size < MAX_IPTV_CHANNELS) {
                    val name = title?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/')
                    channels += LiveChannel(
                        id = channels.size.toString(),
                        name = name,
                        type = "tv",
                        addonUrl = iptvAddonUrl(sourceId),
                        poster = image,
                        description = annotation,
                        category = group,
                        streamUrl = url,
                        sourceId = sourceId
                    )
                }
                inTrack = false
            }
            capture = null
        }
    }

    private class XmlTvHandler(
        private val nowMs: Long,
        private val maxPrograms: Int
    ) : DefaultHandler() {
        private val programs = HashMap<String, MutableList<EpgProgram>>()
        private val channels = HashMap<String, EpgChannelInfo>()
        private var total = 0
        private var skipped = 0
        private var malformed = 0

        private var channelId: String? = null
        private var displayName: String? = null
        private var lcn: Int? = null
        private var icon: String? = null

        private var programChannel: String? = null
        private var startMs = 0L
        private var stopMs = 0L
        private var title: String? = null
        private var subTitle: String? = null
        private var description: String? = null

        private var capture: String? = null
        private val text = StringBuilder()
        private val nameCache = HashMap<String, String>()
        private val offsetCounts = HashMap<Int, Int>()
        private val stampDigits = IntArray(15)

        private fun elementName(localName: String?, qName: String?): String? {
            val raw = localName?.takeIf { it.isNotEmpty() } ?: qName ?: return null
            nameCache[raw]?.let { return it }
            val normalized = raw.substringAfter(':').lowercase(Locale.US)
            nameCache[raw] = normalized
            return normalized
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val name = elementName(localName, qName) ?: return
            text.setLength(0)
            when (name) {
                "channel" -> {
                    channelId = attributes?.getValue("id")
                    displayName = null
                    lcn = null
                    icon = null
                }
                "programme" -> {
                    programChannel = attributes?.getValue("channel")
                    val startRaw = attributes?.getValue("start").orEmpty().trim()
                    startMs = parseXmlTvDate(startRaw) ?: 0L
                    stopMs = parseXmlTvDate(attributes?.getValue("stop").orEmpty()) ?: 0L
                    val stampEnd = scanXmlTvDigits(startRaw, stampDigits)
                    if (stampDigits[14] >= 10) {
                        offsetMinutesAt(startRaw, stampEnd)?.let { offset ->
                            offsetCounts[offset] = (offsetCounts[offset] ?: 0) + 1
                        }
                    }
                    title = null
                    subTitle = null
                    description = null
                }
                "display-name", "lcn", "title", "sub-title", "desc" -> capture = name
                "icon" -> if (channelId != null) icon = attributes?.getValue("src")
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            capture ?: return
            // A single guide can carry tens of MB in one <desc>; only the start
            // of the synopsis is ever displayed, so stop at the display bound.
            val room = MAX_CAPTURE_CHARS - text.length
            if (room > 0) text.append(ch, start, minOf(length, room))
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = elementName(localName, qName) ?: return
            // Avoid building a string for every element: a guide has hundreds
            // of thousands of nodes and only a handful carry text.
            val value = if (capture != null) text.toString().trim() else ""
            when (name) {
                "display-name" -> if (channelId != null && displayName == null && value.isNotEmpty()) {
                    displayName = value
                }
                "lcn" -> if (channelId != null) lcn = value.toIntOrNull()
                "title" -> if (programChannel != null && title == null && value.isNotEmpty()) title = value
                "sub-title" -> if (programChannel != null && subTitle == null && value.isNotEmpty()) subTitle = value
                "desc" -> if (programChannel != null && description == null && value.isNotEmpty()) description = value
                "channel" -> {
                    val id = channelId
                    if (id != null) {
                        val label = displayName ?: id
                        channels[id] = EpgChannelInfo(id, label, lcn, icon)
                    }
                    channelId = null
                }
                "programme" -> emit()
            }
            capture = null
        }

        private fun emit() {
            val id = programChannel
            programChannel = null
            if (id == null) {
                malformed++
                return
            }
            if (startMs <= 0L || stopMs <= startMs) {
                malformed++
                return
            }
            val windowStart = nowMs - WINDOW_PAST_MS
            val windowEnd = nowMs + WINDOW_FUTURE_MS
            if (stopMs <= windowStart || startMs >= windowEnd) {
                skipped++
                return
            }
            if (total >= maxPrograms) {
                skipped++
                return
            }
            val label = title
            if (label == null) {
                malformed++
                return
            }
            val program = EpgProgram(
                channelId = id,
                title = label,
                subTitle = subTitle,
                description = description,
                startMs = startMs,
                stopMs = stopMs
            )
            val list = programs.getOrPut(id) { ArrayList() }
            if (list.size < MAX_PROGRAMS_PER_CHANNEL) {
                list.add(program)
                total++
            } else {
                skipped++
            }
        }

        fun toGuide(): EpgGuide {
            val sorted = programs.mapValues { (_, list) -> list.sortedBy { it.startMs } }
            val declared = offsetCounts.maxByOrNull { it.value }?.key
            return EpgGuide(sorted, channels, declared)
        }

        fun toReport(): PlaylistReport = PlaylistReport(
            total = channels.size,
            skipped = skipped,
            malformed = malformed
        )
    }
}

internal fun iptvAddonUrl(sourceId: String): String = "iptv:$sourceId"

/**
 * Bounds how many bytes a consumer may pull. Wrapped around the decompressed
 * side of a guide so a small gzip file cannot expand until the parser runs out
 * of memory or time; the compressed-size cap says nothing about that ratio.
 */
internal class CappedInputStream(
    input: InputStream,
    private val maxBytes: Long,
    private val label: String = "document"
) : FilterInputStream(input) {

    private var total = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) count(1L)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) count(read.toLong())
        return read
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) count(skipped)
        return skipped
    }

    private fun count(bytes: Long) {
        total += bytes
        if (total > maxBytes) {
            throw IOException("$label exceeds $maxBytes bytes")
        }
    }
}

const val MAX_IPTV_CHANNELS = 10_000
const val MAX_EPG_PROGRAMS = 200_000
internal const val MAX_MERGED_EPG_PROGRAMS = 400_000
internal const val MAX_INDEX_PROGRAMS = 60_000
private const val MAX_PROGRAMS_PER_CHANNEL = 600
private const val MAX_CAPTURE_CHARS = 4 * 1024
private const val WINDOW_PAST_MS = 6 * 60 * 60 * 1000L
private const val WINDOW_FUTURE_MS = 48 * 60 * 60 * 1000L

/** Serialises a cached channel list; [JSONArray] is the on-disk cache format. */
fun channelsToJson(channels: List<LiveChannel>): JSONArray = JSONArray(channels.map { it.toJson() })
