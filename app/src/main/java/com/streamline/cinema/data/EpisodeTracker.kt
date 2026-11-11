package com.streamline.cinema.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Newest episode list available: sniffed seasons win, else the stored series. */
fun trackedSeasons(item: CatalogItem, sniffed: List<Season>?): List<Season> =
    sniffed?.takeIf { list -> list.any { it.episodes.isNotEmpty() } } ?: item.seasons

fun todayStamp(now: Date = Date()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)

/** Blank air dates count as aired; specials are not part of tracking. */
fun Season.airedEpisodes(today: String): List<Episode> =
    episodes.filter { it.airDate.isNullOrBlank() || it.airDate <= today }

fun airedEpisodeList(seasons: List<Season>, today: String): List<Pair<Int, Episode>> =
    seasons.filter { it.number > 0 }
        .sortedBy { it.number }
        .flatMap { season -> season.airedEpisodes(today).sortedBy { it.number }.map { season.number to it } }

/**
 * Aired episodes with no watch record — played past 90% or marked by hand.
 * Null means no episode data has been sniffed yet.
 */
fun newEpisodeCount(seasons: List<Season>, watched: Set<Pair<Int, Int>>, today: String): Int? {
    if (seasons.none { it.episodes.isNotEmpty() }) return null
    return airedEpisodeList(seasons, today).count { (season, episode) -> (season to episode.number) !in watched }
}

fun isSeasonWatched(season: Season, watched: Set<Pair<Int, Int>>, today: String): Boolean {
    val aired = season.airedEpisodes(today)
    return aired.isNotEmpty() && aired.all { (season.number to it.number) in watched }
}

/**
 * The aired episode to offer next. [useFurthest] anchors on the highest watched
 * episode so finishing a rewatch never rewinds the suggestion; otherwise the
 * most recently played episode ([recent]) wins. Watching nothing suggests the
 * first aired episode; null means everything aired has been watched.
 */
fun nextUpEpisode(
    seasons: List<Season>,
    watched: Set<Pair<Int, Int>>,
    recent: Pair<Int, Int>?,
    useFurthest: Boolean,
    today: String
): Pair<Int, Int>? {
    val aired = airedEpisodeList(seasons, today)
    if (aired.isEmpty()) return null
    val furthest = aired.lastOrNull { (season, episode) -> (season to episode.number) in watched }
        ?.let { it.first to it.second.number }
    val anchor = if (useFurthest) furthest ?: recent else recent ?: furthest
    if (anchor == null) return aired.first().let { it.first to it.second.number }
    return aired.firstOrNull { (season, episode) ->
        season > anchor.first || (season == anchor.first && episode.number > anchor.second)
    }?.let { it.first to it.second.number }
}

/** Parses the persisted marker keys (see [watchedEpisodeKey]) for one title. */
fun watchedEpisodesFor(itemKey: String, keys: Set<String>): Set<Pair<Int, Int>> {
    val prefix = "$itemKey|"
    return keys.asSequence()
        .filter { it.startsWith(prefix) }
        .mapNotNull { key ->
            val parts = key.split('|')
            if (parts.size != 3) return@mapNotNull null
            val season = parts[1].toIntOrNull() ?: return@mapNotNull null
            val episode = parts[2].toIntOrNull() ?: return@mapNotNull null
            season to episode
        }
        .toSet()
}
