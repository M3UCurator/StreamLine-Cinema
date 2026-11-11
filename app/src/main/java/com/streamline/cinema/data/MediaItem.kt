package com.streamline.cinema.data

/** Movie and TV genre ids share one namespace in the hall grid and its filters. */
val tmdbGenresMap: Map<Int, String> = Genres.movie + Genres.tv

data class MediaItem(
    val id: Int,
    val title: String,
    val genreIds: List<Int>,
    val posterUrl: String,
    val voteAverage: Double
)

fun CatalogItem.toMediaItem(): MediaItem = MediaItem(
    id = tmdbId,
    title = title,
    genreIds = Genres.idsFor(type, genres),
    posterUrl = posterUrl.orEmpty(),
    voteAverage = rating ?: 0.0
)
