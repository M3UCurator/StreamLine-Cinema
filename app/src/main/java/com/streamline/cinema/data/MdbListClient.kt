package com.streamline.cinema.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Scrapes MDBList pages with plain regex parsing.
 *
 * Deliberately avoids a DOM library: jsoup pulls `java.util.function` / `java.util.stream`
 * call sites that abort with `NoSuchMethodError` on Android boxes below API 24, which
 * surfaced as "No static method Initial…" during catalog imports.
 */
class MdbListClient {

    private val tmdbLink = Regex("""(?:themoviedb\.org|tmdb\.org)/(movie|tv)/(\d+)""")
    private val anchorRegex = Regex(
        """<a\b([^>]*href\s*=\s*["']([^"']+)["'][^>]*)>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val imgRegex = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attrRegex = Regex("""(src|data-src|alt|title)\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")
    private val yearRegex = Regex("""\b(19|20)\d{2}\b""")
    private val ratingRegex = Regex("""\b(\d{1,2}\.\d)\b""")

    // Card layout: `<div class="card" ...>` … `<div class="header movie-title" title="Dune (2021)">`.
    // The closing quote keeps `<div class="card-score-container">` and friends from splitting cards.
    private val cardStartRegex = Regex("""<div\s+class="card"""", RegexOption.IGNORE_CASE)
    private val cardTitleRegex = Regex("""<div\s+class="header[^"]*"\s+title="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val headerRegex = Regex(
        """<div\s+class="header[^"]*"[^>]*>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val trailingYearRegex = Regex("""\((\d{4})\)\s*$""")
    private val scoreRegex = Regex(
        """class="idscore"[^>]*>\s*(\d+(?:\.\d+)?)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val descriptionRegex = Regex(
        """class="description[^"]*"[^>]*>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    suspend fun parse(url: String, includeAdult: Boolean = false): List<CatalogItem> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Http.BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val html = Http.client.await(request).use { response ->
                if (!response.isSuccessful) throw IOException("MDBList HTTP ${response.code}")
                response.bodyStringCapped()
            }
            if (html.isBlank()) throw IOException("MDBList returned an empty document")
            val lowered = html.take(4_000).lowercase()
            if (lowered.contains("just a moment") || lowered.contains("challenge-platform") ||
                lowered.contains("enable javascript and cookies")
            ) {
                throw IOException(
                    "MDBList served a browser challenge. Set a proxy server URL in Settings " +
                        "(/api/mdblist/parse scrapes server-side) or import a JSON manifest."
                )
            }
            parseHtml(html, url)
        }

    fun parseHtml(html: String, baseUrl: String = "https://mdblist.com/"): List<CatalogItem> {
        val items = parseCards(html, baseUrl).ifEmpty { parseAnchors(html, baseUrl) }
        if (items.isEmpty()) {
            throw IOException("No TMDB entries were found in the MDBList page")
        }
        return items
    }

    /**
     * MDBList renders one `<div class="card">` per title, with the real title in
     * `div.header[title]`, the poster on an `img.poster-card`, the score in `div.idscore`
     * and the TMDB link somewhere further down the card.
     */
    private fun parseCards(html: String, baseUrl: String): List<CatalogItem> {
        val starts = cardStartRegex.findAll(html).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        val seen = LinkedHashMap<String, CatalogItem>()
        starts.forEachIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: (start + 24_000).coerceAtMost(html.length)
            val block = html.substring(start, end)
            val match = tmdbLink.find(block) ?: return@forEachIndexed
            val type = MediaType.from(match.groupValues[1])
            val tmdbId = match.groupValues[2].toIntOrNull() ?: return@forEachIndexed
            val key = "${type.name.lowercase()}-$tmdbId"
            if (seen.containsKey(key)) return@forEachIndexed

            val rawTitle = cardTitleRegex.find(block)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
                ?: headerRegex.find(block)?.groupValues?.get(1)?.let(::cleanText)?.ifBlank { null }
            val yearMatch = rawTitle?.let { trailingYearRegex.find(it) }
            val year = yearMatch?.groupValues?.get(1)

            val poster = imgRegex.findAll(block).map { it.value }
                .let { tags ->
                    tags.firstOrNull { it.contains("poster-card", ignoreCase = true) }
                        ?: tags.firstOrNull { it.contains("image.tmdb.org", ignoreCase = true) }
                }
                ?.let { tag ->
                    val attrs = anchorAttributes(tag)
                    absoluteUrl(attrs["src"].orEmpty().ifBlank { attrs["data-src"].orEmpty() }, baseUrl)
                        ?.let { TmdbImages.poster(it) }
                }

            val rating = scoreRegex.find(block)?.groupValues?.get(1)?.toDoubleOrNull()
            val overview = descriptionRegex.find(block)?.groupValues?.get(1)?.let(::cleanText).orEmpty()

            val title = rawTitle
                ?.replace(trailingYearRegex, "")
                ?.trim()
                ?.trim('(', ')', '-', '·', ' ')
                ?.ifBlank { null }
                ?: "TMDB #$tmdbId"

            seen[key] = CatalogItem(
                tmdbId = tmdbId,
                type = type,
                title = title,
                year = year,
                overview = overview,
                posterUrl = poster,
                rating = rating,
                // Card blocks are inferred from offsets, so an IMDb link can belong
                // to a neighbouring card and would send stream addons to another
                // title. Imports stay TMDB-identified and the IMDb id is resolved
                // from TMDB when playback needs it.
                imdbId = "",
                source = "MDBList",
                tmdbLoaded = false
            )
            if (seen.size >= 500) return seen.values.toList()
        }
        return seen.values.toList()
    }

    /** Older MDBList layouts only expose the TMDB link, so fall back to link windows. */
    private fun parseAnchors(html: String, baseUrl: String): List<CatalogItem> {
        val seen = LinkedHashMap<String, CatalogItem>()
        val titles = collectTitles(html)
        for (anchor in anchorRegex.findAll(html)) {
            val href = decodeEntities(anchor.groupValues[2])
            val match = tmdbLink.find(href) ?: continue
            val type = MediaType.from(match.groupValues[1])
            val tmdbId = match.groupValues[2].toIntOrNull() ?: continue
            val key = "${type.name.lowercase()}-$tmdbId"
            if (seen.containsKey(key)) continue

            val attrs = anchorAttributes(anchor.groupValues[1])
            val innerText = cleanText(anchor.groupValues[3])

            // Titles and artwork live on sibling nodes, so inspect a window around the link.
            val start = (anchor.range.first - 1_600).coerceAtLeast(0)
            val end = (anchor.range.last + 900).coerceAtMost(html.length)
            val window = html.substring(start, end)
            val windowStart = anchor.range.first - start
            val windowAnchorEnd = anchor.range.last - start
            val poster = windowPoster(window, windowStart, windowAnchorEnd, baseUrl)
            // Metadata sits in the same card, so stay close to the link to avoid bleeding
            // into the neighbouring list entry.
            val trailing = window.substring(windowAnchorEnd, (windowAnchorEnd + 420).coerceAtMost(window.length))
            val leading = window.substring((windowStart - 300).coerceAtLeast(0), windowStart)
            val context = cleanText(trailing).ifBlank { cleanText(leading) }

            val rawTitle = sequenceOf(attrs["title"], attrs["alt"], innerText, titles[key])
                .map { it?.trim().orEmpty() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("http") }

            val year = yearRegex.find(context)?.value
            val rating = ratingRegex.find(context)?.groupValues?.get(1)?.toDoubleOrNull()

            val title = rawTitle
                ?.removeSuffix(year.orEmpty())
                ?.trim()
                ?.trim('(', ')', '-', '·', ' ')
                ?.ifBlank { null }
                ?: "TMDB #$tmdbId"

            seen[key] = CatalogItem(
                tmdbId = tmdbId,
                type = type,
                title = title,
                year = year,
                overview = "",
                posterUrl = poster,
                rating = rating,
                source = "MDBList",
                tmdbLoaded = false
            )
            if (seen.size >= 500) break
        }
        return seen.values.toList()
    }

    /** Title text often sits on a sibling anchor or on the poster's `alt`, not on the link we key on. */
    private fun collectTitles(html: String): Map<String, String> {
        val titles = HashMap<String, String>()
        for (anchor in anchorRegex.findAll(html)) {
            val href = decodeEntities(anchor.groupValues[2])
            val match = tmdbLink.find(href) ?: continue
            val type = MediaType.from(match.groupValues[1])
            val tmdbId = match.groupValues[2].toIntOrNull() ?: continue
            val key = "${type.name.lowercase()}-$tmdbId"
            if (titles.containsKey(key)) continue
            val attrs = anchorAttributes(anchor.groupValues[1])
            val title = attrs["title"]?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("http") }
            val alt = imgRegex.find(anchor.groupValues[3])
                ?.let { anchorAttributes(it.value)["alt"]?.trim() }
                ?.takeIf { it.isNotEmpty() }
            (title ?: alt)?.let { titles[key] = it }
        }
        return titles
    }

    private fun windowPoster(window: String, anchorStart: Int, anchorEnd: Int, baseUrl: String): String? {
        val images = imgRegex.findAll(window).toList()
        if (images.isEmpty()) return null
        val inside = images.firstOrNull { it.range.first >= anchorStart && it.range.last <= anchorEnd }
        val before = images.lastOrNull { it.range.last < anchorStart }
        val after = images.firstOrNull { it.range.first > anchorEnd }
        val chosen = (inside ?: before ?: after)?.value ?: return null
        val attrs = anchorAttributes(chosen)
        val raw = attrs["src"].orEmpty().ifBlank { attrs["data-src"].orEmpty() }
        return absoluteUrl(raw, baseUrl)?.let { TmdbImages.poster(it) }
    }

    private fun anchorAttributes(tagBody: String): Map<String, String> =
        attrRegex.findAll(tagBody).associate { it.groupValues[1].lowercase() to decodeEntities(it.groupValues[2]) }

    private fun absoluteUrl(raw: String, baseUrl: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.startsWith("data:")) return null
        if (value.startsWith("http")) return value
        if (value.startsWith("//")) return "https:$value"
        val base = baseUrl.trimEnd('/')
        return if (value.startsWith("/")) "$base$value" else "$base/$value"
    }

    private fun cleanText(fragment: String): String =
        decodeEntities(tagRegex.replace(fragment, " "))
            .replace('\u00A0', ' ')
            .replace(whitespaceRegex, " ")
            .trim()

    private fun decodeEntities(value: String): String {
        if (!value.contains('&')) return value
        return value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
    }
}
