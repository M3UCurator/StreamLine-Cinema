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
    /** Releaser / supplier: `Frenchio · SUPPLY`. */
    val releaseGroup: String = "",
    /** `FR · MULTI`. */
    val languages: String = "",
    /** Detected tier used by the quality limit. */
    val quality: String = "",
    /** Every line joined, for badge pattern matching. */
    val haystack: String = ""
) {
    val hasDetails: Boolean
        get() = releaseTitle.isNotBlank() || format.isNotBlank() || size.isNotBlank() ||
            releaseGroup.isNotBlank() || languages.isNotBlank()

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
                releaseGroup = raw.optString("releaseGroup"),
                languages = raw.optString("languages"),
                quality = raw.optString("quality"),
                haystack = raw.optString("haystack")
            )
        }
    }
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
            val line = raw.trim()
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
            releaseGroup = releaseGroup,
            languages = languages,
            quality = quality,
            haystack = haystack
        )
    }

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
