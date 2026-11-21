package com.streamline.cinema.data

import org.json.JSONObject

/**
 * Display-ready breakdown of one addon stream. Stremio addons describe a stream
 * inside `name` and `title` as free text (Torrentio, AIOStreams, Hyper and
 * friends each use their own layout), so the fields below are a best-effort
 * split of what the card shows: headline, release name and spec rows.
 */
data class StreamMeta(
    val provider: String = "",
    val providerLogo: String = "",
    /** Bold first line, usually the quality/source tag: `4K (web)`. */
    val headline: String = "",
    /** Remaining tag line under the headline: `(Web-DL)`. */
    val subhead: String = "",
    /** Release name as published: `Avatar Fire and Ash (2025)`. */
    val releaseTitle: String = "",
    /** Codec and dynamic range: `HEVC · HDR`. */
    val format: String = "",
    /** `39.2 GB`, already normalised. */
    val size: String = "",
    /** `26.4 Mbps`. */
    val bitrate: String = "",
    /** Release file name when the addon publishes one: `Show.S01E01.2160p.mkv`. */
    val fileName: String = "",
    /** Container from the file name, upper case: `MKV`. */
    val fileType: String = "",
    /** Releaser / supplier: `Frenchio · SUPPLY`. */
    val releaseGroup: String = "",
    /** `FR · MULTI`. */
    val languages: String = "",
    /** Detected tier used by the quality limit. */
    val quality: String = "",
    /** Every line joined, for badge pattern matching. */
    val haystack: String = ""
) {
    /** Live lookups resolve inside another screen; the card fields ride the intent. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("provider", provider)
        put("providerLogo", providerLogo)
        put("headline", headline)
        put("subhead", subhead)
        put("releaseTitle", releaseTitle)
        put("format", format)
        put("size", size)
        put("bitrate", bitrate)
        put("fileName", fileName)
        put("fileType", fileType)
        put("releaseGroup", releaseGroup)
        put("languages", languages)
        put("quality", quality)
        put("haystack", haystack)
    }

    companion object {
        fun fromJson(raw: JSONObject?): StreamMeta {
            if (raw == null) return StreamMeta()
            return StreamMeta(
                provider = raw.optString("provider"),
                providerLogo = raw.optString("providerLogo"),
                headline = raw.optString("headline"),
                subhead = raw.optString("subhead"),
                releaseTitle = raw.optString("releaseTitle"),
                format = raw.optString("format"),
                size = raw.optString("size"),
                bitrate = raw.optString("bitrate"),
                fileName = raw.optString("fileName"),
                fileType = raw.optString("fileType"),
                releaseGroup = raw.optString("releaseGroup"),
                languages = raw.optString("languages"),
                quality = raw.optString("quality"),
                haystack = raw.optString("haystack")
            )
        }
    }
}

/**
 * The compact row's pill cluster in front of the file name: what the stream is,
 * how big it is, what it carries and what container it lands in. Every field is
 * display-ready and blank when the addon never said.
 */
data class StreamFacts(
    val resolution: String = "",
    val size: String = "",
    val bitrate: String = "",
    val dynamicRange: String = "",
    val audio: String = "",
    /** Source and codec tags in the order the addon wrote them: `BLU-RAY · REMUX · HEVC`. */
    val type: String = "",
    val container: String = "",
    val fileName: String = ""
) {
    val hasPills: Boolean
        get() = resolution.isNotBlank() || size.isNotBlank() || bitrate.isNotBlank() ||
            dynamicRange.isNotBlank() || audio.isNotBlank() || type.isNotBlank() ||
            container.isNotBlank()
}

object StreamPresentation {

    private val SIZE = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(tib|tb|gib|gb|mib|mb)\\b")
    private val BITRATE = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(gbps|mbps|mb/s|kbps|kb/s)\\b")
    private val QUALITY = Regex("(?i)\\b(8k|4320p|4k|2160p|uhd|1440p|1080p|720p|576p|480p|360p|240p|sd)\\b")
    private val SOURCE = Regex(
        "(?i)\\b(remux|blu[\\s._-]?ray|bdremux|bdrip|web[\\s._-]?dl|webdl|web[\\s._-]?rip|webrip|hdtv|dvd[\\s._-]?rip|hdcam)\\b"
    )
    private val CODEC = Regex("(?i)\\b(hevc|h[\\s._-]?265|x265|avc|h[\\s._-]?264|x264|av1|vp9|mpeg-?2)\\b")
    private val DYNAMIC = Regex("(?i)\\b(hdr10\\+|hdr10|hdr|dolby\\s*vision|dovi|dv|hlg|sdr)\\b")
    private val AUDIO = Regex(
        "(?i)\\b(atmos|truehd|ddp|dd\\+|dolby\\s*digital(?:\\s*plus)?|e-?ac-?3|ac-?3|dts(?:[\\s._-]*hd(?:[\\s._-]*ma)?|[\\s._-]*x)?|flac|aac|opus)\\b"
    )
    private val YEAR = Regex("\\b(?:19|20)\\d{2}\\b")
    private val HTML_TAG = Regex("<[^>]*>")
    private val LINE_BREAK = Regex("(?i)<br\\s*/?>")
    private val LINK = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val FILE_NAME = Regex(
        "(?i)([A-Za-z0-9][A-Za-z0-9 ._()&+'\\-]{2,180}\\.(?:mkv|mp4|m4v|avi|m2ts|mov|webm|wmv|flv|ts|m3u8))(?!\\w)"
    )
    private val DYNAMIC_TOKEN = Regex(
        "(?i)(dolby[\\s._-]*vision|dovi|\\bdv\\b|hdr10\\+|hdr10plus|hdr10|hdr|hlg|sdr)"
    )
    private val AUDIO_TOKEN = Regex(
        "(?i)(atmos|truehd|dts[\\s._:-]*(?:hd[\\s._:-]*ma|x|hd)?|ddp|dd\\+|" +
            "dolby[\\s._-]*digital[\\s._-]*plus|dolby[\\s._-]*digital|e-?ac-?3|ac-?3|flac|aac|opus)"
    )
    private val CHANNELS = Regex(
        "(?<![0-9])([1-9])[.·]([01])(?![0-9])(?!\\s*[gmt]b\\b)",
        RegexOption.IGNORE_CASE
    )
    private val SEPARATORS = Regex("[\\s._-]+")
    private val DYNAMIC_PRIORITY = listOf("DV", "HDR10+", "HDR10", "HDR", "HLG")
    private val KNOWN_LANG_TOKENS = setOf(
        "MULTI", "MULTI-SUB", "MULTISUB", "VOSTFR", "TRUEFRENCH", "VFF", "VFQ", "VF2",
        "VF", "VO", "VOST", "AUDIO", "ORIGINAL", "DUAL"
    )

    /**
     * Splits a Stremio `name`/`title` pair into card fields. [label] is the addon
     * display name (also used for grouping and as the badge haystack prefix).
     */
    fun parse(label: String, name: String?, title: String?, logo: String = ""): StreamMeta {
        val provider = label.trim()
        val nameLines = cleanLines(name)
        val titleLines = cleanLines(title)
        // Torrent layouts publish the release file name as one of their lines;
        // it is the most precise thing the row can show, so it is lifted out
        // before the remaining text is split into fields.
        val fileName = findFileName(titleLines).ifBlank { findFileName(nameLines) }
        val haystack = buildString {
            append(provider).append(' ')
            append(name.orEmpty()).append(' ')
            append(title.orEmpty())
        }

        // `name` carries the addon name on its own line in most layouts, then
        // one or two tag lines such as "4K (web)" / "(Web-DL)".
        val tagLines = nameLines.mapNotNull { raw ->
            var text = raw
            if (provider.isNotBlank() && text.equals(provider, ignoreCase = true)) return@mapNotNull null
            if (fileName.isNotBlank() && text.equals(fileName, ignoreCase = true)) return@mapNotNull null
            if (provider.isNotBlank() && text.startsWith(provider, ignoreCase = true)) {
                text = text.substring(provider.length).trim(' ', '·', '|', '-', ':', '—')
            }
            text.takeIf { it.isNotBlank() && !it.equals("stream", ignoreCase = true) }
        }
        var headline = tagLines.firstOrNull().orEmpty()
        var subhead = tagLines.drop(1).joinToString(" · ")

        val formats = mutableListOf<String>()
        val sizes = mutableListOf<String>()
        var bitrate = ""
        var releaseTitle = ""
        var releaseGroup = ""
        var languages = ""

        for (raw in titleLines) {
            // The file name is shown on its own row; whatever shares its line
            // (size, seeder notes) still goes through the normal split below.
            val line = if (fileName.isBlank()) raw.trim() else withoutFileName(raw, fileName)
            if (line.isEmpty()) continue
            if (provider.isNotBlank() && line.equals(provider, ignoreCase = true)) continue
            if (line == headline || line == subhead) continue
            if (LINK.containsMatchIn(line)) continue

            val sizeMatch = SIZE.find(line)
            val bitrateMatch = BITRATE.find(line)
            if (sizeMatch != null || bitrateMatch != null) {
                sizeMatch?.let {
                    val unit = it.groupValues[2].uppercase().replace("IB", "B")
                    sizes += "${it.groupValues[1].replace(',', '.')} $unit"
                }
                if (bitrateMatch != null) {
                    bitrate = "${bitrateMatch.groupValues[1].replace(',', '.')} " +
                        bitrateMatch.groupValues[2]
                }
                // Seeder/cache notes share the line; badges still see them.
                continue
            }

            val isTechLine = CODEC.containsMatchIn(line) ||
                DYNAMIC.containsMatchIn(line) ||
                (AUDIO.containsMatchIn(line) && !line.contains('('))
            if (isTechLine) {
                if (formats.size < 2) formats += line
                continue
            }

            // A tag-only line ("4K", "(Web-DL)") is a second headline, not the
            // release name; addons that skip `name` details put it there.
            if (isTagLine(line)) {
                if (headline.isBlank()) headline = line
                else if (subhead.isBlank()) subhead = line
                continue
            }

            if (releaseTitle.isBlank() && YEAR.containsMatchIn(line) && !isLanguageLine(line)) {
                releaseTitle = line
                continue
            }
            if (languages.isBlank() && isLanguageLine(line)) {
                languages = line
                continue
            }
            if (releaseTitle.isBlank() && line.length >= 3) {
                releaseTitle = line
                continue
            }
            if (releaseGroup.isBlank() && line.length <= 48) {
                releaseGroup = line
            }
        }

        val quality = normalizeQuality(QUALITY.find(haystack)?.value)
        if (headline.isBlank()) {
            val source = SOURCE.find(haystack)?.value?.let(::normalizeSource)
            headline = listOfNotNull(
                quality.takeIf { it.isNotBlank() },
                source?.let { "($it)" }
            ).joinToString(" ")
        }
        if (subhead.isBlank() && headline.isNotBlank() &&
            !headline.contains('(') && !headline.contains("·")
        ) {
            SOURCE.find(haystack)?.value?.let(::normalizeSource)?.let { subhead = "($it)" }
        }

        return StreamMeta(
            provider = provider,
            providerLogo = logo,
            headline = headline,
            subhead = subhead,
            releaseTitle = releaseTitle,
            format = formats.joinToString(" · "),
            size = sizes.distinct().joinToString(" · "),
            bitrate = bitrate,
            fileName = fileName,
            fileType = containerLabel(fileName),
            releaseGroup = releaseGroup,
            languages = languages,
            quality = quality,
            haystack = haystack
        )
    }

    /**
     * Collapses a parsed stream into the pills of the compact row. Anything the
     * addon spread across free-text lines is re-derived from the haystack, so
     * layouts that only publish a release string still gain the full cluster.
     */
    fun facts(meta: StreamMeta): StreamFacts = StreamFacts(
        resolution = meta.quality,
        // Sizes can arrive as a list of variants; the row shows the first.
        size = meta.size.substringBefore(" · ").trim(),
        bitrate = meta.bitrate,
        dynamicRange = dynamicLabel(meta.haystack),
        audio = audioLabel(meta.haystack),
        type = typeLabel(meta.haystack),
        container = meta.fileType,
        fileName = meta.fileName
    )

    private fun cleanLines(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val text = raw
            .replace(LINE_BREAK, "\n")
            .replace(HTML_TAG, " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        val seen = LinkedHashSet<String>()
        text.lines().forEach { line ->
            val clean = stripLeadingSymbols(line.trim()).trim('·', '|', '•', '-', '—', ' ').trim()
            if (clean.isNotEmpty()) seen += clean
        }
        return seen.toList()
    }

    private fun stripLeadingSymbols(text: String): String {
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            val symbol = cp in 0x1F000..0x1FAFF ||
                cp in 0x2600..0x27BF ||
                cp in 0x2B00..0x2BFF ||
                cp in 0xFE00..0xFE0F ||
                cp == 0x200D || cp == 0x20E3
            if (symbol || Character.isWhitespace(cp)) {
                index += Character.charCount(cp)
            } else {
                break
            }
        }
        return text.substring(index)
    }

    /** A line made only of language/channel tags, e.g. `FR · MULTI` or `EN / FR`. */
    private fun isLanguageLine(line: String): Boolean {
        val tokens = line.split('·', '/', ',', '|', '+', '&')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 5) return false
        if (line.any(Char::isDigit)) return false
        return tokens.all { token ->
            val upper = token.uppercase()
            upper in KNOWN_LANG_TOKENS ||
                (upper.length in 2..3 && upper.all { it in 'A'..'Z' }) ||
                upper in setOf("FRENCH", "ENGLISH", "SPANISH", "GERMAN", "ITALIAN", "JAPANESE")
        }
    }

    /** A line made only of quality/source tags, e.g. `4K (web)` or `WEB-DL`. */
    private fun isTagLine(line: String): Boolean {
        val cleaned = line.replace(Regex("[()\\[\\]]"), " ").trim()
        val tokens = cleaned.split(' ', '·', '|', '/', '+').filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > 4) return false
        return tokens.all { token ->
            QUALITY.matches(token) ||
                SOURCE.matches(token) ||
                token.lowercase() in setOf("web", "hd", "sd", "hdr", "sdr")
        }
    }

    private fun findFileName(lines: List<String>): String =
        lines.mapNotNull { line ->
            val text = line.trim().trim('[', ']', '(', ')').trim()
            val match = FILE_NAME.find(text) ?: return@mapNotNull null
            // Only a line that opens with the name is the release file name; a
            // stray mention inside prose is not what the addon published.
            if (match.range.first != 0) return@mapNotNull null
            match.groupValues[1].trim()
        }.maxByOrNull { it.length }
            .orEmpty()

    /** Removes the file name from a line, keeping whatever note shared it. */
    private fun withoutFileName(line: String, fileName: String): String {
        val index = line.indexOf(fileName, ignoreCase = true)
        if (index < 0) return line.trim()
        val rest = line.substring(0, index) + " " + line.substring(index + fileName.length)
        return rest.trim(' ', '·', '|', '•', '-', '—', '(', ')', '[', ']').trim()
    }

    private fun containerLabel(fileName: String): String {
        if (fileName.isBlank()) return ""
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "" -> ""
            "m3u8" -> "HLS"
            else -> ext.uppercase()
        }
    }

    private fun dynamicLabel(text: String): String {
        val found = DYNAMIC_TOKEN.findAll(text)
            .map { dynamicTag(it.value) }
            .filter { it.isNotEmpty() }
            .distinct()
        return found.minByOrNull { tag ->
            DYNAMIC_PRIORITY.indexOf(tag).let { if (it < 0) DYNAMIC_PRIORITY.size else it }
        }.orEmpty()
    }

    private fun dynamicTag(raw: String): String {
        val token = raw.lowercase()
        return when {
            token.startsWith("dolby") || token == "dovi" || token == "dv" -> "DV"
            token == "hdr10+" || token == "hdr10plus" -> "HDR10+"
            token == "hdr10" -> "HDR10"
            token == "hdr" -> "HDR"
            token == "hlg" -> "HLG"
            else -> ""
        }
    }

    private fun audioLabel(text: String): String {
        val labels = AUDIO_TOKEN.findAll(text)
            .map { audioTag(it.value) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (labels.isEmpty()) return ""
        // `DTS` next to `DTS-HD MA` is the same track family, not a second one.
        val kept = labels.filterNot { label ->
            labels.any { other -> other != label && other.startsWith(label) }
        }.take(2)
        val channels = CHANNELS.find(text)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
        // Reference order: codec, channel layout, then the Atmos badge last.
        val parts = kept.filter { it != "Atmos" } + listOfNotNull(channels) +
            kept.filter { it == "Atmos" }
        return parts.joinToString(" · ")
    }

    private fun audioTag(raw: String): String {
        val token = raw.lowercase().replace(SEPARATORS, " ").trim()
        return when {
            token == "atmos" -> "Atmos"
            token == "truehd" -> "TrueHD"
            token.startsWith("dts hd ma") -> "DTS-HD MA"
            token == "dts hd" -> "DTS-HD"
            token == "dts x" -> "DTS:X"
            token == "dts" -> "DTS"
            token == "ddp" || token == "dd+" -> "DDP"
            token.contains("digital") && token.contains("plus") -> "DDP"
            token.contains("digital") -> "DD"
            token.startsWith("eac3") || token.startsWith("e ac 3") ||
                token.startsWith("ec3") || token.startsWith("ac3") -> "DD"
            token == "flac" -> "FLAC"
            token == "aac" -> "AAC"
            token == "opus" -> "Opus"
            else -> ""
        }
    }

    private fun typeLabel(text: String): String {
        val hits = buildList {
            SOURCE.findAll(text).forEach { add(it.range.first to sourceLabel(it.value)) }
            CODEC.findAll(text).forEach { add(it.range.first to codecLabel(it.value)) }
        }
        return hits.sortedBy { it.first }
            .map { it.second }
            .distinct()
            .take(3)
            .joinToString(" · ")
    }

    private fun sourceLabel(raw: String): String =
        when (raw.uppercase().replace(SEPARATORS, "")) {
            "REMUX", "BDREMUX" -> "REMUX"
            "BLURAY" -> "BLU-RAY"
            "BDRIP" -> "BDRIP"
            "WEBDL" -> "WEB-DL"
            "WEBRIP" -> "WEBRIP"
            "HDTV" -> "HDTV"
            "DVDRIP" -> "DVD-RIP"
            "HDCAM" -> "HDCAM"
            else -> raw.uppercase()
        }

    private fun codecLabel(raw: String): String =
        when (raw.uppercase().replace(SEPARATORS, "")) {
            "HEVC", "H265", "X265" -> "HEVC"
            "AVC", "H264", "X264" -> "H.264"
            "AV1" -> "AV1"
            "VP9" -> "VP9"
            "MPEG2" -> "MPEG-2"
            else -> raw.uppercase()
        }

    private fun normalizeQuality(raw: String?): String = when (raw?.lowercase()) {
        null -> ""
        "8k", "4320p" -> "8K"
        "4k", "2160p", "uhd" -> "4K"
        "sd" -> "SD"
        else -> raw.lowercase()
    }

    private fun normalizeSource(raw: String): String = when (raw.uppercase().replace(" ", "")) {
        "WEBDL", "WEB-DL" -> "WEB-DL"
        "WEBRIP", "WEB-RIP" -> "WEBRip"
        "BLURAY", "BLU-RAY" -> "BluRay"
        "BDRIP" -> "BDRip"
        "BDREMUX" -> "REMUX"
        "HDTV" -> "HDTV"
        "DVDRIP", "DVD-RIP" -> "DVD-Rip"
        "HDCAM" -> "HDCAM"
        else -> raw.uppercase()
    }
}
