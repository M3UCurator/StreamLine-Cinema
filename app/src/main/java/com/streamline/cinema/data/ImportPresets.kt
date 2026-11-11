package com.streamline.cinema.data

import java.net.URLDecoder
import java.net.URLEncoder

enum class PresetKind { TMDB_DISCOVER, MDBLIST }

data class ImportPreset(
    val name: String,
    val group: String,
    val url: String,
    val kind: PresetKind
)

object ImportPresets {
    const val TMDB_GROUP = "TMDB Discover Feeds"
    const val MDB_GROUP = "Public MDBList Collections"

    private fun tmdbPreset(name: String): ImportPreset = ImportPreset(
        name = name,
        group = TMDB_GROUP,
        url = "/api/tmdb-presets?category=" + URLEncoder.encode(name, "UTF-8"),
        kind = PresetKind.TMDB_DISCOVER
    )

    private fun mdbPreset(name: String, url: String): ImportPreset =
        ImportPreset(name = name, group = MDB_GROUP, url = url, kind = PresetKind.MDBLIST)

    val tmdbPresets: List<ImportPreset> = listOf(
        tmdbPreset("Popular Movies"),
        tmdbPreset("Top Rated Movies"),
        tmdbPreset("Upcoming Movies"),
        tmdbPreset("Now Playing Movies"),
        tmdbPreset("Popular TV Shows"),
        tmdbPreset("Top Rated TV Shows"),
        tmdbPreset("TV Shows Airing Today")
    )

    val mdblistPresets: List<ImportPreset> = listOf(
        mdbPreset(
            "Top Watched Movies of the Week (Linas)",
            "https://mdblist.com/lists/linaspurinis/top-watched-movies-of-the-week"
        ),
        mdbPreset(
            "Top Movies of the Week (Gary)",
            "https://mdblist.com/lists/garycrawfordgc/top-movies-of-the-week"
        ),
        mdbPreset(
            "Trending Movies (Snoak / Trakt)",
            "https://mdblist.com/lists/snoak/trending-movies"
        ),
        mdbPreset(
            "Latest Digital Releases (Snoak)",
            "https://mdblist.com/lists/snoak/latest-movies-digital-release"
        ),
        mdbPreset(
            "Netflix Movies (Gary)",
            "https://mdblist.com/lists/garycrawfordgc/netflix-movies"
        ),
        mdbPreset(
            "Netflix TV Shows (Gary)",
            "https://mdblist.com/lists/garycrawfordgc/netflix-shows"
        ),
        mdbPreset(
            "Trending TV Shows (Snoak)",
            "https://mdblist.com/lists/snoak/trakt-s-trending-shows"
        ),
        mdbPreset(
            "Latest TV Shows (Gary)",
            "https://mdblist.com/lists/garycrawfordgc/latest-tv-shows"
        ),
        mdbPreset(
            "Trending Anime (Snoak / Trakt)",
            "https://mdblist.com/lists/snoak/trending-anime-shows"
        ),
        mdbPreset(
            "Top Rated Horror Movies (HDLists)",
            "https://mdblist.com/lists/hdlists/latest-hd-horror-movies-top-rated-from-1980-to-today"
        ),
        mdbPreset(
            "IMDb Top 250 Movies",
            "https://mdblist.com/lists/snoak/imdb-top-250-movies"
        ),
        mdbPreset(
            "IMDb Top 250 TV Shows",
            "https://mdblist.com/lists/snoak/imdb-top-250-shows"
        )
    )

    val all: List<ImportPreset> = tmdbPresets + mdblistPresets

    fun tmdbPathFor(category: String): Pair<String, MediaType>? = when (category) {
        "Popular Movies" -> "movie/popular" to MediaType.MOVIE
        "Top Rated Movies" -> "movie/top_rated" to MediaType.MOVIE
        "Upcoming Movies" -> "movie/upcoming" to MediaType.MOVIE
        "Now Playing Movies" -> "movie/now_playing" to MediaType.MOVIE
        "Popular TV Shows" -> "tv/popular" to MediaType.TV
        "Top Rated TV Shows" -> "tv/top_rated" to MediaType.TV
        "TV Shows Airing Today" -> "tv/airing_today" to MediaType.TV
        else -> null
    }

    fun categoryOf(url: String): String? {
        val marker = "category="
        val index = url.indexOf(marker, ignoreCase = true)
        if (index < 0) return null
        val value = url.substring(index + marker.length).substringBefore('&')
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
    }

    private val TMDB_LIST_REGEX = Regex("""themoviedb\.org/list/(\d+)""", RegexOption.IGNORE_CASE)

    fun tmdbListId(url: String): String? =
        TMDB_LIST_REGEX.find(url)?.groupValues?.get(1)

    fun forUrl(url: String): ImportPreset? {
        val trimmed = url.trim()
        return all.firstOrNull { it.url.equals(trimmed, ignoreCase = true) }
            ?: all.firstOrNull { it.url.contains(trimmed, ignoreCase = true) && trimmed.length > 20 }
    }

    fun friendlyName(url: String): String {
        val preset = forUrl(url)
        if (preset != null) return preset.name
        tmdbListId(url)?.let { return "TMDB List #$it" }
        categoryOf(url)?.let { category ->
            return tmdbPathFor(category)?.let { category } ?: category
        }
        return url.substringAfterLast('/').ifBlank { "Custom source" }
            .replace('-', ' ')
            .replaceFirstChar { it.uppercase() }
    }
}
