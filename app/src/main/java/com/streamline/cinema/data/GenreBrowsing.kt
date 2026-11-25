package com.streamline.cinema.data

/**
 * The Playroom's genre bar. TMDB's own lists differ per media type and leave
 * out Biography and Sport entirely, so every chip carries the ids of the
 * chosen scope and falls back to a keyword when that scope has no such genre.
 */
data class BrowseGenre(
    val name: String,
    val movieIds: Set<Int> = emptySet(),
    val tvIds: Set<Int> = emptySet(),
    val keyword: String? = null
) {
    fun genreIds(type: MediaType): Set<Int> = if (type == MediaType.MOVIE) movieIds else tvIds
    fun needsKeyword(type: MediaType): Boolean = genreIds(type).isEmpty() && keyword != null
}

/**
 * Split genres (Action & Adventure, Sci-Fi & Fantasy, War & Politics) map to
 * the combined TV genre TMDB publishes; the keyword-only entries are the
 * labels TMDB indexes for TV exclusively by keyword.
 */
internal val BROWSE_GENRES: List<BrowseGenre> = listOf(
    BrowseGenre("Action", setOf(28), setOf(10759)),
    BrowseGenre("Adventure", setOf(12), setOf(10759)),
    BrowseGenre("Animation", setOf(16), setOf(16)),
    BrowseGenre("Biography", keyword = "biography"),
    BrowseGenre("Comedy", setOf(35), setOf(35)),
    BrowseGenre("Crime", setOf(80), setOf(80)),
    BrowseGenre("Documentary", setOf(99), setOf(99)),
    BrowseGenre("Drama", setOf(18), setOf(18)),
    BrowseGenre("Family", setOf(10751), setOf(10751)),
    BrowseGenre("Fantasy", setOf(14), setOf(10765)),
    BrowseGenre("History", setOf(36), keyword = "history"),
    BrowseGenre("Horror", setOf(27), keyword = "horror"),
    BrowseGenre("Mystery", setOf(9648), setOf(9648)),
    BrowseGenre("Romance", setOf(10749), keyword = "romance"),
    BrowseGenre("Sci-Fi", setOf(878), setOf(10765)),
    BrowseGenre("Sport", keyword = "sport"),
    BrowseGenre("Thriller", setOf(53), keyword = "thriller"),
    BrowseGenre("War", setOf(10752), setOf(10768)),
    BrowseGenre("Western", setOf(37), setOf(37))
)

internal fun browseGenreIds(names: Collection<String>, type: MediaType): Set<Int> =
    BROWSE_GENRES.filter { it.name in names }
        .flatMapTo(mutableSetOf()) { it.genreIds(type) }

internal fun browseKeywordNames(names: Collection<String>, type: MediaType): List<String> =
    BROWSE_GENRES.filter { it.name in names && it.needsKeyword(type) }
        .mapNotNull { it.keyword }

/** Genre labels as they appear on catalog items, plus the chip's own label. */
internal fun BrowseGenre.localNames(type: MediaType?): Set<String> {
    val movie = movieIds.mapNotNull { Genres.movie[it] }
    val tv = tvIds.mapNotNull { Genres.tv[it] }
    val names = when (type) {
        MediaType.MOVIE -> movie
        MediaType.TV -> tv
        null -> movie + tv
    }
    return if (keyword != null) (names + name).toSet() else names.toSet()
}
