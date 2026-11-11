package com.streamline.cinema.data

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TMDB_API_BASE = "https://api.themoviedb.org/3/"

// Genre and mood browsing reads several discover pages per media type (20
// titles each) so a selection returns a browsable shelf, not one screen.
private const val DISCOVER_PAGES = 5

data class Genre(val id: Int, val name: String)

/** Ordering for genre-discover results; newest is the Playroom default. */
enum class GenreSort { NEWEST, POPULAR }

data class GenreCatalog(
    val movie: List<Genre> = emptyList(),
    val tv: List<Genre> = emptyList()
) {
    val all: List<Genre> get() = (movie + tv).distinctBy { it.id }.sortedBy { it.name }

    fun forScope(type: MediaType?): List<Genre> = when (type) {
        MediaType.MOVIE -> movie
        MediaType.TV -> tv
        null -> all
    }

    fun nameFor(type: MediaType?, id: Int): String? {
        val primary = when (type) {
            MediaType.MOVIE -> movie
            MediaType.TV -> tv
            null -> all
        }
        return primary.firstOrNull { it.id == id }?.name
            ?: (movie + tv).firstOrNull { it.id == id }?.name
    }
}

data class TmdbGenre(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("name") val name: String? = null
)

data class TmdbGenreList(
    @SerializedName("genres") val genres: List<TmdbGenre>? = null
)

data class TmdbDiscoverItem(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("overview") val overview: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null,
    @SerializedName("vote_count") val voteCount: Int? = null,
    @SerializedName("genre_ids") val genreIds: List<Int>? = null,
    @SerializedName("original_language") val originalLanguage: String? = null,
    @SerializedName("popularity") val popularity: Double? = null
)

data class TmdbDiscoverPage(
    @SerializedName("page") val page: Int? = null,
    @SerializedName("results") val results: List<TmdbDiscoverItem>? = null,
    @SerializedName("total_pages") val totalPages: Int? = null,
    @SerializedName("total_results") val totalResults: Int? = null
)

/** TMDB's standard genre-list and discover endpoints, one method each. */
interface TmdbApi {

    @GET("genre/movie/list")
    suspend fun movieGenres(
        @Query("language") language: String
    ): TmdbGenreList

    @GET("genre/tv/list")
    suspend fun tvGenres(
        @Query("language") language: String
    ): TmdbGenreList

    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("with_genres") withGenres: String,
        @Query("include_adult") includeAdult: Boolean,
        @Query("sort_by") sortBy: String,
        @Query("primary_release_date.lte") releasedBy: String?,
        @Query("language") language: String,
        @Query("with_original_language") originalLanguage: String?,
        @Query("with_origin_country") originCountry: String?,
        @Query("page") page: Int
    ): TmdbDiscoverPage

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("with_genres") withGenres: String,
        @Query("include_adult") includeAdult: Boolean,
        @Query("sort_by") sortBy: String,
        @Query("first_air_date.lte") airedBy: String?,
        @Query("language") language: String,
        @Query("with_original_language") originalLanguage: String?,
        @Query("with_origin_country") originCountry: String?,
        @Query("page") page: Int
    ): TmdbDiscoverPage
}

/**
 * Retrofit-backed implementation of [TmdbApi]. The direct instance talks to
 * api.themoviedb.org; the proxy instance rewrites the same paths into the
 * Express bridge (`/api/tmdb?path=…`) and passes the key as X-TMDB-Key, so the
 * key never leaves the user's own server.
 */
class TmdbApiService(private val settingsStore: SettingsStore) {

    private val settings get() = settingsStore.settings.value

    @Volatile
    private var cachedCatalog: GenreCatalog? = null

    @Volatile
    private var cachedCatalogLanguage: String? = null

    private val converter = GsonConverterFactory.create()

    private val directApi: TmdbApi by lazy {
        retrofit(Http.client.newBuilder().addInterceptor(DirectAuthInterceptor()).build())
    }

    private val proxyApi: TmdbApi by lazy {
        retrofit(Http.client.newBuilder().addInterceptor(ProxyRewriteInterceptor()).build())
    }

    private fun retrofit(client: OkHttpClient): TmdbApi =
        Retrofit.Builder()
            .baseUrl(TMDB_API_BASE)
            .client(client)
            .addConverterFactory(converter)
            .build()
            .create(TmdbApi::class.java)

    /** Genre names follow the content language; a language change refetches once. */
    suspend fun genres(force: Boolean = false): GenreCatalog {
        val language = settings.tmdbLanguageTag()
        cachedCatalog?.takeIf { !force && cachedCatalogLanguage == language }?.let { return it }
        val (moviePage, tvPage) = coroutineScope {
            val movie = async { request { api -> api.movieGenres(language) } }
            val tv = async { request { api -> api.tvGenres(language) } }
            movie.await() to tv.await()
        }
        val catalog = GenreCatalog(
            movie = moviePage.genres.orEmpty().toGenres(),
            tv = tvPage.genres.orEmpty().toGenres()
        )
        if (catalog.movie.isNotEmpty() || catalog.tv.isNotEmpty()) {
            cachedCatalog = catalog
            cachedCatalogLanguage = language
        }
        return catalog
    }

    /**
     * Discover titles by TMDB genre id. With no type filter both endpoints run
     * and the popularity-sorted pages are merged.
     */
    suspend fun discover(
        type: MediaType?,
        genreIds: Collection<Int>,
        adult: Boolean,
        sort: GenreSort = GenreSort.NEWEST
    ): List<CatalogItem> {
        if (genreIds.isEmpty()) return emptyList()
        val withGenres = genreIds.sorted().joinToString(",")
        val language = settings.tmdbLanguageTag()
        val onlyLanguage = settings.discoverLanguageFilter()
        val originCountry = settings.discoverCountryFilter()
        val types = when (type) {
            MediaType.MOVIE -> listOf(MediaType.MOVIE)
            MediaType.TV -> listOf(MediaType.TV)
            null -> listOf(MediaType.MOVIE, MediaType.TV)
        }
        fun call(mediaType: MediaType, page: Int): suspend (TmdbApi) -> TmdbDiscoverPage = { api ->
            when (mediaType) {
                MediaType.MOVIE -> api.discoverMovies(
                    withGenres, adult, movieSort(sort), releasedBy(sort),
                    language, onlyLanguage, originCountry, page
                )
                MediaType.TV -> api.discoverTv(
                    withGenres, adult, tvSort(sort), releasedBy(sort),
                    language, onlyLanguage, originCountry, page
                )
            }
        }
        val firstPages = coroutineScope {
            types.map { mediaType ->
                async { mediaType to request(call(mediaType, 1)) }
            }.awaitAll()
        }
        // Remaining pages run concurrently once the first page reports how many
        // exist. They are best effort: a failed page keeps the rest of the shelf.
        val extraPages = coroutineScope {
            firstPages.flatMap { (mediaType, first) ->
                val last = minOf(DISCOVER_PAGES, first.totalPages ?: 1)
                if (last <= 1) {
                    emptyList()
                } else {
                    (2..last).map { page ->
                        async {
                            mediaType to runCatching { request(call(mediaType, page)) }.getOrNull()
                        }
                    }
                }
            }.awaitAll()
        }
        val catalog = cachedCatalog
        val mapped = (firstPages + extraPages)
            .mapNotNull { (mediaType, page) -> page?.let { mediaType to it.results.orEmpty() } }
            .flatMap { (mediaType, items) -> items.map { item -> mediaType to item } }
            .sortedByDescending { (_, item) -> item.popularity ?: 0.0 }
            .mapNotNull { (mediaType, item) -> item.toCatalogItem(mediaType, catalog) }
            .distinctBy { it.key }
        val ordered = when (sort) {
            // The discover pages are already date-ordered per type; re-sort after
            // merging movies and shows so the newest title is genuinely first.
            GenreSort.NEWEST -> mapped.sortedWith(
                compareByDescending<CatalogItem> { it.releaseDate.orEmpty() }
                    .thenByDescending { it.rating ?: 0.0 }
            )
            GenreSort.POPULAR -> mapped
        }
        return ordered
    }

    private fun movieSort(sort: GenreSort): String = when (sort) {
        GenreSort.NEWEST -> "primary_release_date.desc"
        GenreSort.POPULAR -> "popularity.desc"
    }

    private fun tvSort(sort: GenreSort): String = when (sort) {
        GenreSort.NEWEST -> "first_air_date.desc"
        GenreSort.POPULAR -> "popularity.desc"
    }

    /**
     * Newest means "current month first, then the rest of the catalog in
     * descending order" — without an upper bound TMDB's date sort leads with
     * next-year announcements, which the Playroom drops as future releases,
     * leaving an empty page.
     */
    private fun releasedBy(sort: GenreSort): String? {
        if (sort != GenreSort.NEWEST) return null
        val endOfMonth = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(endOfMonth.time)
    }

    private fun List<TmdbGenre>.toGenres(): List<Genre> =
        mapNotNull { genre ->
            val id = genre.id ?: return@mapNotNull null
            val name = genre.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (id <= 0) null else Genre(id, name)
        }.sortedBy { it.name }

    private fun TmdbDiscoverItem.toCatalogItem(type: MediaType, catalog: GenreCatalog?): CatalogItem? {
        val tmdbId = id ?: return null
        if (tmdbId <= 0) return null
        val date = (if (type == MediaType.MOVIE) releaseDate else firstAirDate)
            ?.takeIf { it.isNotBlank() }
        val genreIds = genreIds.orEmpty()
        val genreNames = if (catalog != null) {
            genreIds.mapNotNull { catalog.nameFor(type, it) }.distinct()
                .ifEmpty { Genres.names(type, genreIds) }
        } else {
            Genres.names(type, genreIds)
        }
        return CatalogItem(
            tmdbId = tmdbId,
            type = type,
            title = (if (type == MediaType.MOVIE) title else name)
                ?.takeIf { it.isNotBlank() } ?: "Untitled",
            year = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
            releaseDate = date,
            overview = overview.orEmpty(),
            posterUrl = TmdbImages.poster(posterPath),
            backdropUrl = TmdbImages.backdrop(backdropPath),
            rating = voteAverage?.takeIf { it > 0 },
            voteCount = voteCount,
            originalLanguage = originalLanguage.orEmpty(),
            genres = genreNames,
            source = "TMDB Discover",
            tmdbLoaded = false
        )
    }

    private suspend fun <T> request(block: suspend (TmdbApi) -> T): T {
        val current = settings
        val proxy = current.proxyBaseUrl.trim().trimEnd('/')
        var proxyFailure: Throwable? = null

        if (proxy.isNotEmpty()) {
            try {
                return retry429 { block(proxyApi) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                proxyFailure = t
                if (current.tmdbApiKey.isBlank()) {
                    throw TmdbException(
                        "Proxy unreachable (${friendly(t)}). Add a TMDB API key as a fallback."
                    )
                }
            }
        }

        if (current.tmdbApiKey.isBlank()) {
            throw TmdbException(
                if (proxyFailure != null) "Proxy unreachable (${friendly(proxyFailure)})."
                else "Add your TMDB API key (or a proxy URL) in Settings."
            )
        }

        return try {
            retry429 { block(directApi) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            throw TmdbException(friendly(t))
        }
    }

    private suspend fun <T> retry429(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (t: HttpException) {
                if (t.code() == 429 && attempt < 2) {
                    attempt++
                    delay(1_200L * attempt)
                } else {
                    throw t
                }
            }
        }
    }

    private inner class DirectAuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val apiKey = settings.tmdbApiKey
            val original = chain.request()
            val url = original.url.newBuilder()
                .apply { if (!apiKey.startsWith("eyJ")) addQueryParameter("api_key", apiKey) }
                .build()
            val request = original.newBuilder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", Http.BROWSER_USER_AGENT)
                .apply { if (apiKey.startsWith("eyJ")) header("Authorization", "Bearer $apiKey") }
                .build()
            return chain.proceed(request)
        }
    }

    private inner class ProxyRewriteInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val proxy = settings.proxyBaseUrl.trim().trimEnd('/')
            val original = chain.request()
            if (proxy.isEmpty()) throw IOException("No TMDB proxy configured")
            val url = "$proxy/api/tmdb".toHttpUrl().newBuilder()
                // The bridge prefixes TMDB's /3 itself, so drop our base path.
                .addQueryParameter(
                    "path",
                    original.url.encodedPath.trim('/').removePrefix("3/")
                )
                .apply {
                    original.url.queryParameterNames.forEach { name ->
                        original.url.queryParameterValues(name).forEach { value ->
                            if (value != null) addQueryParameter(name, value)
                        }
                    }
                }
                .build()
            val request = original.newBuilder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", Http.BROWSER_USER_AGENT)
                .apply {
                    val apiKey = settings.tmdbApiKey
                    if (apiKey.isNotBlank()) header("X-TMDB-Key", apiKey)
                }
                .build()
            return chain.proceed(request)
        }
    }

    private fun friendly(t: Throwable): String = when (t) {
        is TmdbException -> t.message ?: "TMDB request failed"
        is HttpException -> {
            val message = runCatching {
                t.response()?.errorBody()?.string()?.let { body ->
                    org.json.JSONObject(body).optString("status_message")
                }
            }.getOrNull()
            "TMDB HTTP ${t.code()}" + if (!message.isNullOrBlank()) ": $message" else ""
        }
        is UnknownHostException -> "no network or DNS failure"
        is SocketTimeoutException -> "request timed out"
        is IOException -> t.message ?: "network error"
        else -> t.message ?: t.javaClass.simpleName
    }
}
