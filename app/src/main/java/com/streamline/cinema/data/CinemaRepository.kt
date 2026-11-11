package com.streamline.cinema.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ImportResult(val sourceName: String, val total: Int, val added: Int)

data class SyncResult(val added: Int, val failedSources: Int)

/** Merged EPG data plus one readable line per guide URL that failed. */
data class EpgLoad(
    val guide: EpgGuide = EpgGuide.EMPTY,
    val failures: List<String> = emptyList(),
    /** Source id → shift that detection applied on this load, for the settings row. */
    val autoShifts: Map<String, Int> = emptyMap(),
    /** Combined keep/skip/malformed counts across every guide that answered. */
    val report: PlaylistReport? = null
)

private data class GuideRequest(val source: IptvSource, val url: String, val headers: Map<String, String>)

private data class GuideResult(
    val guide: EpgGuide? = null,
    val sourceId: String = "",
    val autoShift: Int? = null,
    val failure: String? = null,
    val report: PlaylistReport? = null
)

/** Upper bound for one addon's part of the playable-source search. */
private const val ADDON_SEARCH_TIMEOUT_MS = 20_000L
private const val GUIDE_TIMEOUT_MS = 120_000L
/** Probes per channel: dead links queue up to 10 s each before failover. */
private const val MAX_LIVE_PROBES = 6
private const val MAX_LIVE_PAGES = 2
private const val LIVE_RESOLVE_TIMEOUT_MS = 20_000L
private const val LIVE_PREFETCH_TTL_MS = 90_000L
private const val MAX_CACHED_LIVE_STREAMS = 6

class CinemaRepository(
    context: Context,
    val settingsStore: SettingsStore,
    val libraryStore: LibraryStore,
    val iptvStore: IptvStore,
    val guideIndexStore: GuideIndexStore
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tmdb = TmdbClient(settingsStore)
    private val tmdbApi = TmdbApiService(settingsStore)
    private val mdbList = MdbListClient()
    private val iptvClient = IptvClient(appContext)
    private val mergedEpgCap = DeviceProfile.mergedEpgProgramCap(DeviceProfile.isLowRam(appContext))
    private val failedItems: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // IMDb ids confirmed against the item's own TMDB id. Stream lookups never use
    // an unconfirmed id: a wrong `tt…` resolves to a different title's episodes.
    private val verifiedImdbIds = ConcurrentHashMap<String, String>()

    // A resolved live channel stays warm for a short window so OSD prefetch and
    // the next tune share one round trip. Provider tokens expire quickly, so a
    // stale entry is dropped and re-resolved instead of handed to the player.
    private val liveStreamCache = object : LinkedHashMap<String, Pair<Long, LiveStreamResult>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, LiveStreamResult>>?): Boolean =
            size > MAX_CACHED_LIVE_STREAMS
    }

    private val _enrichingTitle = MutableStateFlow<String?>(null)
    val enrichingTitle: StateFlow<String?> = _enrichingTitle.asStateFlow()

    private val _lastEnrichmentError = MutableStateFlow<String?>(null)
    val lastEnrichmentError: StateFlow<String?> = _lastEnrichmentError.asStateFlow()

    /** Addon manifests fetched this session, keyed by normalized addon url. */
    private val addonManifestCache = ConcurrentHashMap<String, AddonManifest>()

    init {
        startEnrichmentQueue()
    }

    private fun startEnrichmentQueue() {
        scope.launch {
            while (isActive) {
                val pending = libraryStore.items.value.firstOrNull {
                    !it.tmdbLoaded && it.tmdbId > 0 && !failedItems.contains(it.key)
                }
                if (pending == null) {
                    delay(2_000)
                    continue
                }
                if (!settingsStore.settings.value.hasTmdbAccess) {
                    delay(5_000)
                    continue
                }
                _enrichingTitle.value = pending.title
                try {
                    libraryStore.replace(enrichItem(pending))
                    failedItems.remove(pending.key)
                    _lastEnrichmentError.value = null
                } catch (t: Throwable) {
                    failedItems.add(pending.key)
                    _lastEnrichmentError.value = "${pending.title}: ${t.message ?: "metadata lookup failed"}"
                } finally {
                    _enrichingTitle.value = null
                }
                delay(180)
            }
        }
    }

    private suspend fun enrichItem(item: CatalogItem): CatalogItem {
        val detail = tmdb.details(item.tmdbId, item.type)
        var enriched = detail.copy(
            source = item.source,
            addedAt = item.addedAt,
            streamUrl = item.streamUrl
        )
        if (item.type == MediaType.TV) {
            val storedUrls = libraryStore.items.value
                .firstOrNull { it.key == item.key }
                ?.seasons.orEmpty()
                .flatMap { season ->
                    season.episodes.mapNotNull { episode ->
                        episode.streamUrl.takeIf { it.isNotBlank() }
                            ?.let { (season.number to episode.number) to it }
                    }
                }
                .toMap()
            val seasons = mutableListOf<Season>()
            for (season in detail.seasons.take(30)) {
                val episodes = runCatching {
                    tmdb.seasonEpisodes(item.tmdbId, season.number)
                }.getOrDefault(emptyList())
                seasons += season.copy(
                    episodes = episodes.map { episode ->
                        episode.copy(streamUrl = storedUrls[season.number to episode.number].orEmpty())
                    },
                    episodeCount = if (season.episodeCount > 0) season.episodeCount else episodes.size
                )
                delay(90)
            }
            enriched = enriched.copy(seasons = seasons.ifEmpty { detail.seasons })
        }
        return enriched.copy(tmdbLoaded = true)
    }

    suspend fun loadDetails(tmdbId: Int, type: MediaType): CatalogItem {
        val detail = tmdb.details(tmdbId, type)
        val stored = libraryStore.findByTmdbId(tmdbId, type)
        return if (stored != null) mergeWithStored(detail, stored) else detail
    }

    suspend fun loadSeasonEpisodes(item: CatalogItem, seasonNumber: Int): List<Episode> {
        val cached = item.seasons.firstOrNull { it.number == seasonNumber }?.episodes.orEmpty()
        if (cached.isNotEmpty()) {
            return cached
        }
        val fetched = tmdb.seasonEpisodes(item.tmdbId, seasonNumber)
        if (libraryStore.findByKey(item.key) != null) {
            val existing = item.seasons.firstOrNull { it.number == seasonNumber }
            val season = (existing ?: Season(seasonNumber, "Season $seasonNumber", fetched.size))
                .copy(episodes = fetched, episodeCount = fetched.size)
            libraryStore.updateSeasons(item.key, listOf(season))
        }
        return fetched
    }

    private fun mergeWithStored(detail: CatalogItem, stored: CatalogItem): CatalogItem {
        val seasons = detail.seasons.map { season ->
            val cached = stored.seasons.firstOrNull { it.number == season.number }
            if (cached != null && cached.episodes.isNotEmpty()) {
                season.copy(episodes = cached.episodes, episodeCount = maxOf(season.episodeCount, cached.episodeCount))
            } else season
        }
        return detail.copy(
            source = stored.source,
            addedAt = stored.addedAt,
            seasons = seasons.ifEmpty { stored.seasons },
            streamUrl = detail.streamUrl.ifBlank { stored.streamUrl }
        )
    }

    suspend fun search(query: String, filter: MediaType?, adult: Boolean): List<CatalogItem> =
        tmdb.search(query, filter, adult)

    suspend fun recommendations(tmdbId: Int, type: MediaType): List<CatalogItem> =
        tmdb.recommendations(tmdbId, type)

    suspend fun trending(): List<CatalogItem> = tmdb.trending()

    suspend fun genres(force: Boolean = false): GenreCatalog = tmdbApi.genres(force)

    suspend fun discoverByGenres(
        filter: MediaType?,
        genreIds: Set<Int>,
        adult: Boolean,
        sort: GenreSort
    ): List<CatalogItem> = tmdbApi.discover(filter, genreIds, adult, sort)

    suspend fun discover(presetCategory: String): List<CatalogItem> = tmdb.preset(presetCategory)

    suspend fun topMovies(): List<CatalogItem> = tmdb.discover("movie/popular", MediaType.MOVIE)

    suspend fun topShows(): List<CatalogItem> = tmdb.discover("tv/popular", MediaType.TV)

    suspend fun topRatedShows(): List<CatalogItem> = tmdb.discover("tv/top_rated", MediaType.TV)

    suspend fun testTmdb(): String = tmdb.testConnection()

    suspend fun importUrl(
        rawUrl: String,
        recordSource: Boolean = true,
        onStatus: (String) -> Unit = {}
    ): ImportResult {
        val url = rawUrl.trim()
        if (url.isBlank()) throw IOException("Enter an import URL or choose a preset")
        val settings = settingsStore.settings.value
        val proxy = settings.proxyBaseUrl.trim().trimEnd('/')
        val preset = ImportPresets.forUrl(url)
        val category = ImportPresets.categoryOf(url)
        val sourceName = preset?.name ?: ImportPresets.friendlyName(url)

        onStatus("Fetching $sourceName…")
        val items = when {
            url.contains("/api/tmdb-presets") -> {
                val resolvedCategory = category
                    ?: throw IOException("TMDB preset URL is missing ?category=")
                val hint = ImportPresets.tmdbPathFor(resolvedCategory)?.second
                if (proxy.isNotEmpty()) {
                    val body = fetchText(
                        "$proxy/api/tmdb-presets?category=" + URLEncoder.encode(resolvedCategory, "UTF-8")
                    )
                    CatalogNormalizer.parseJson(body, hint)
                } else {
                    tmdb.preset(resolvedCategory)
                }
            }

            ImportPresets.tmdbListId(url) != null -> {
                if (!settings.hasTmdbAccess) {
                    throw IOException(
                        "TMDB access is offline. Add an API key or proxy URL in Settings to import lists."
                    )
                }
                tmdb.list(ImportPresets.tmdbListId(url)!!)
            }

            url.contains("mdblist.com/lists/") -> {
                if (proxy.isNotEmpty()) {
                    val body = fetchText(
                        "$proxy/api/mdblist/parse?url=" + URLEncoder.encode(url, "UTF-8")
                    )
                    CatalogNormalizer.parseJson(body)
                } else {
                    mdbList.parse(url, settings.includeAdult)
                }
            }

            url.startsWith("http") -> {
                val body = if (proxy.isNotEmpty()) {
                    fetchText("$proxy/api/proxy?url=" + URLEncoder.encode(url, "UTF-8"))
                } else {
                    fetchText(url)
                }
                if (body.trimStart().startsWith("<")) {
                    throw IOException(
                        "That address returned a web page, not a catalog. Use a TMDB list " +
                            "(themoviedb.org/list/…), an MDBList link, or a JSON manifest."
                    )
                }
                CatalogNormalizer.parseJson(body, ImportPresets.tmdbPathFor(category.orEmpty())?.second)
            }

            else -> throw IOException("Unsupported import URL. Use a preset, MDBList link, or https JSON manifest.")
        }

        if (items.isEmpty()) throw IOException("No titles were found at this source")

        onStatus("Normalizing ${items.size} titles…")
        val normalized = items.map { it.copy(source = sourceName) }
        val added = libraryStore.addAll(normalized)

        val syncable = url.startsWith("http") || url.contains("/api/tmdb-presets")
        if (recordSource && syncable) {
            val kind = when {
                preset?.kind == PresetKind.MDBLIST || url.contains("mdblist.com") -> "MDBList"
                url.contains("/api/tmdb-presets") || ImportPresets.tmdbListId(url) != null -> "TMDB"
                else -> "JSON"
            }
            libraryStore.upsertSource(
                ImportSource(
                    name = sourceName,
                    url = url,
                    kind = kind,
                    lastSync = System.currentTimeMillis(),
                    itemCount = normalized.size
                )
            )
        }
        return ImportResult(sourceName, normalized.size, added)
    }

    suspend fun importSingle(item: CatalogItem): CatalogItem {
        val detailed = runCatching {
            tmdb.details(item.tmdbId, item.type)
        }.getOrDefault(item)
        val prepared = detailed.copy(
            source = "Cinema Playroom",
            addedAt = System.currentTimeMillis(),
            tmdbLoaded = detailed.type == MediaType.MOVIE
        )
        libraryStore.addAll(listOf(prepared))
        return prepared
    }

    suspend fun importPastedJson(text: String): ImportResult {
        val items = CatalogNormalizer.parseJson(text)
        if (items.isEmpty()) throw IOException("No TMDB items were found in this JSON")
        val normalized = items.map { it.copy(source = "JSON Manifest") }
        val added = libraryStore.addAll(normalized)
        return ImportResult("JSON Manifest", normalized.size, added)
    }

    suspend fun syncAllSources(onStatus: (String) -> Unit = {}): SyncResult {
        var added = 0
        var failures = 0
        val sources = libraryStore.sources.value.filter {
            it.url.startsWith("http") || it.url.contains("/api/tmdb-presets")
        }
        for (source in sources) {
            try {
                onStatus("Syncing ${source.name}…")
                val result = importUrl(source.url, recordSource = false)
                added += result.added
                libraryStore.upsertSource(
                    source.copy(
                        lastSync = System.currentTimeMillis(),
                        itemCount = result.total
                    )
                )
            } catch (t: Throwable) {
                failures++
            }
            delay(120)
        }
        onStatus("Sync complete · +$added titles")
        return SyncResult(added, failures)
    }

    fun playbackPlan(
        item: CatalogItem,
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null
    ): PlaybackPlan {
        // Never invent S1E1 for a series: an unknown episode means no stream
        // and no resume row, not the first episode of the show. The player's
        // 0/0 "nothing selected" sentinel collapses to null here.
        val resolvedSeason = if (item.isSeries) season?.takeIf { it >= 0 } else null
        val resolvedEpisode = if (item.isSeries) episode?.takeIf { it >= 1 } else null
        val options = playbackOptions(item, resolvedSeason, resolvedEpisode)
        val progressKey = item.playbackKey(resolvedSeason, resolvedEpisode)
        val start = if (item.isSeries && (resolvedSeason == null || resolvedEpisode == null)) 0L
        else libraryStore.progressFor(progressKey)?.positionMs ?: 0L
        val subtitle = when {
            item.type == MediaType.MOVIE -> listOfNotNull(item.yearLabel.takeIf { it.isNotBlank() },
                item.runtimeMinutes?.let { "${it} min" }).joinToString(" · ")
            resolvedSeason != null && resolvedEpisode != null -> buildString {
                append("S${resolvedSeason} E${resolvedEpisode}")
                if (!episodeTitle.isNullOrBlank()) append(" · $episodeTitle")
            }
            else -> "Episode not selected"
        }
        return PlaybackPlan(
            itemKey = item.key,
            tmdbId = item.tmdbId,
            type = item.type,
            title = item.title,
            subtitle = subtitle,
            posterUrl = item.posterUrl,
            season = resolvedSeason,
            episode = resolvedEpisode,
            progressKey = progressKey,
            startPositionMs = start,
            options = options
        )
    }

    fun playbackOptions(item: CatalogItem, season: Int?, episode: Int?): List<PlaybackOption> {
        val own = ownStreamUrl(item, season, episode) ?: return emptyList()
        // Catalog links may carry `|Header=value&…`; split it before playback.
        val parsed = StreamUrls.parse(own)
        return if (parsed.url.isNotBlank() && StreamUrls.isDirectMedia(parsed.url)) {
            val fileName = parsed.url.substringAfterLast('/').substringBefore('?')
            listOf(
                PlaybackOption(
                    name = "Catalog stream",
                    url = parsed.url,
                    headers = parsed.headers,
                    meta = StreamPresentation.parse("Catalog", null, fileName)
                )
            )
        } else {
            emptyList()
        }
    }

    private fun ownStreamUrl(item: CatalogItem, season: Int?, episode: Int?): String? {
        if (!item.isSeries) return item.streamUrl.takeIf { it.isNotBlank() }
        // No episode context means no single correct stream; guessing S1E1 is
        // what silently played the wrong episode.
        if (season == null || episode == null) return null
        return item.seasons.firstOrNull { it.number == season }
            ?.episodes
            ?.firstOrNull { it.number == episode }
            ?.streamUrl
            ?.takeIf { it.isNotBlank() }
    }

    fun primaryStreamFor(item: CatalogItem, season: Int? = null, episode: Int? = null): String? =
        playbackOptions(item, season, episode).firstOrNull()?.url

    /**
     * IMDb id belonging to exactly this title. Ids scraped from list pages can
     * carry a neighbouring card's `tt…`, so an id is only accepted when TMDB
     * supplied it; otherwise it is re-resolved from the item's own TMDB id.
     * When that fails the search stays TMDB-identified instead of querying
     * another title's streams.
     */
    private suspend fun verifiedImdbId(item: CatalogItem): String {
        verifiedImdbIds[item.key]?.let { return it }
        if (item.tmdbId <= 0) return ""
        val own = item.imdbId.trim().lowercase()
        if (StremioAddons.canQueryStoredImdb(own, item.tmdbLoaded, item.source)) return own
        val resolved = runCatching { tmdb.details(item.tmdbId, item.type) }
            .getOrNull()
            ?.takeIf { it.tmdbId == item.tmdbId && it.type == item.type }
            ?.imdbId
            ?.trim()
            ?.lowercase()
            ?.takeIf(StremioAddons::isImdbId)
            .orEmpty()
        if (resolved.isNotEmpty()) verifiedImdbIds[item.key] = resolved
        return resolved
    }

    /**
     * Full playable-source search. Every source the app can reach — the catalog's
     * own direct link plus each enabled addon — is probed and reported as a
     * [StreamSearchStep]; only after all of them answer does the flow emit
     * [StreamSearchEvent.Finished], so the player never declares "No playable
     * streams" while a source is still being searched.
     */
    fun searchPlayableStreams(
        item: CatalogItem,
        season: Int? = null,
        episode: Int? = null
    ): Flow<StreamSearchEvent> = channelFlow {
        val steps = Collections.synchronizedList(mutableListOf<StreamSearchStep>())
        fun report(step: StreamSearchStep) {
            steps += step
            trySend(StreamSearchEvent.Step(step))
        }

        val own = ownStreamUrl(item, season, episode)
        val ownMedia = own?.let { StreamUrls.parse(it) }
        if (ownMedia != null && ownMedia.url.isNotBlank() && StreamUrls.isDirectMedia(ownMedia.url)) {
            report(
                StreamSearchStep(
                    key = "catalog",
                    label = "Catalog stream",
                    phase = StreamSearchPhase.FOUND,
                    found = 1,
                    detail = Uri.parse(ownMedia.url).host ?: "direct link"
                )
            )
        } else {
            report(
                StreamSearchStep(
                    key = "catalog",
                    label = "Catalog stream",
                    phase = StreamSearchPhase.EMPTY,
                    detail = when {
                        item.isSeries && (season == null || season < 0 || episode == null || episode < 1) ->
                            "no episode selected"
                        own.isNullOrBlank() -> "no media link attached"
                        else -> "link is not a direct media URL"
                    }
                )
            )
        }

        val addons = settingsStore.settings.value.addons.filter { it.enabled && it.url.isNotBlank() }
        if (addons.isEmpty()) {
            report(
                StreamSearchStep(
                    key = "addons",
                    label = "Stremio addons",
                    phase = StreamSearchPhase.EMPTY,
                    detail = "none registered in Settings"
                )
            )
            send(StreamSearchEvent.Finished(emptyList(), steps.toList()))
            return@channelFlow
        }

        val type = if (item.isSeries) "series" else "movie"
        val searchItem = item.copy(imdbId = verifiedImdbId(item))
        val ids = StremioAddons.contentIds(searchItem, season, episode)
        // A series without an episode cannot be searched without inventing S1E1.
        if (ids.isEmpty()) {
            report(
                StreamSearchStep(
                    key = "addons",
                    label = "Stremio addons",
                    phase = StreamSearchPhase.EMPTY,
                    detail = "series episode not selected"
                )
            )
            send(StreamSearchEvent.Finished(emptyList(), steps.toList()))
            return@channelFlow
        }
        val sources = addons.mapIndexed { index, addon ->
            Triple("addon:$index:${addon.url}", addon.name.ifBlank { "Addon ${index + 1}" }, addon)
        }
        sources.forEach { (key, label, _) ->
            report(StreamSearchStep(key = key, label = label, phase = StreamSearchPhase.SEARCHING))
        }

        val foundOptions = Collections.synchronizedList(mutableListOf<PlaybackOption>())
        coroutineScope {
            sources.map { (key, label, addon) ->
                launch(Dispatchers.IO) {
                    var lastError: Throwable? = null
                    var mismatched = 0
                    val playable = mutableListOf<PlaybackOption>()
                    val completed = withTimeoutOrNull(ADDON_SEARCH_TIMEOUT_MS) {
                        for (id in ids) {
                            try {
                                val streams = StremioAddons.fetchStreams(addon.url, type, id)
                                // A source that names another season/episode is a
                                // different title's link and never reaches the player.
                                val matched = streams.filter {
                                    StremioAddons.streamMatchesRequest(it, item.isSeries, season, episode)
                                }
                                mismatched += streams.size - matched.size
                                if (matched.isNotEmpty()) {
                                    playable += matched.mapNotNull {
                                        it.toPlaybackOption(label, addon.logo)
                                    }
                                    if (playable.isNotEmpty()) break
                                }
                            } catch (c: CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                lastError = t
                            }
                        }
                    } != null
                    val step = when {
                        playable.isNotEmpty() -> {
                            foundOptions += playable
                            StreamSearchStep(
                                key = key,
                                label = label,
                                phase = StreamSearchPhase.FOUND,
                                found = playable.size,
                                detail = buildString {
                                    append("${playable.size} playable")
                                    if (mismatched > 0) append(" · $mismatched wrong episode")
                                }
                            )
                        }
                        lastError != null -> StreamSearchStep(
                            key = key,
                            label = label,
                            phase = StreamSearchPhase.FAILED,
                            detail = friendlySearchError(lastError!!)
                        )
                        !completed -> StreamSearchStep(
                            key = key,
                            label = label,
                            phase = StreamSearchPhase.FAILED,
                            detail = "no answer within ${ADDON_SEARCH_TIMEOUT_MS / 1000} s"
                        )
                        else -> StreamSearchStep(
                            key = key,
                            label = label,
                            phase = StreamSearchPhase.EMPTY,
                            // Naming the rejection keeps "no streams" from hiding
                            // an addon that only had another episode's links.
                            detail = if (mismatched > 0) {
                                "no playable streams · $mismatched wrong episode"
                            } else {
                                "no playable streams"
                            }
                        )
                    }
                    report(step)
                }
            }.joinAll()
        }

        send(
            StreamSearchEvent.Finished(
                // Every distinct stream each source answered with reaches the
                // picker; only the exact same link found twice is collapsed.
                options = foundOptions.distinctBy { it.url },
                steps = steps.toList()
            )
        )
    }

    private fun friendlySearchError(t: Throwable): String {
        val raw = (t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName).trim()
        // Addon error bodies are HTML/JSON noise; the status line is the useful part.
        return if (raw.startsWith("HTTP ") && raw.contains(" from addon")) {
            raw.substringBefore(" from addon") + " from addon"
        } else {
            raw.take(90)
        }
    }

    /** Online subtitles offered by enabled addons for this title. */
    suspend fun addonSubtitles(
        item: CatalogItem,
        season: Int? = null,
        episode: Int? = null
    ): List<AddonSubtitle> {
        val addons = settingsStore.settings.value.addons.filter { it.enabled && it.url.isNotBlank() }
        if (addons.isEmpty()) return emptyList()
        val type = if (item.isSeries) "series" else "movie"
        // Subtitle ids must belong to this title too; an unverified stored `tt…`
        // would list another title's tracks over the video.
        val searchItem = item.copy(imdbId = verifiedImdbId(item))
        val ids = StremioAddons.contentIds(searchItem, season, episode)
        if (ids.isEmpty()) return emptyList()
        val collected = coroutineScope {
            addons.map { addon ->
                async(Dispatchers.IO) {
                    ids.firstNotNullOfOrNull { id ->
                        runCatching { StremioAddons.fetchSubtitles(addon.url, type, id) }
                            .getOrDefault(emptyList())
                            .takeIf { it.isNotEmpty() }
                    }.orEmpty()
                }
            }.awaitAll().flatten()
        }
        return collected
            .distinctBy { it.url }
            .sortedBy { if (it.lang.startsWith("en", ignoreCase = true)) 0 else 1 }
            .take(60)
    }

    suspend fun liveCatalogs(refreshIptv: Boolean = false): LiveCatalogLoad {
        val addons = settingsStore.settings.value.addons.filter { it.enabled && it.url.isNotBlank() }
        val iptvSources = iptvStore.sources.value.filter { it.enabled }
        return coroutineScope {
            val addonResults = addons.map { addon ->
                async(Dispatchers.IO) {
                    val addonLabel = addon.name.ifBlank { addon.url }
                    runCatching { cachedManifest(addon) }
                        .fold(
                            onSuccess = { manifest ->
                                val label = addon.name.ifBlank { manifest.name }
                                manifest.catalogs
                                    .filter { it.type !in setOf("movie", "series") }
                                    .map { catalog ->
                                        LiveCatalog(
                                            addonName = label,
                                            addonUrl = addon.url,
                                            id = catalog.id,
                                            name = catalog.name,
                                            type = catalog.type,
                                            extras = catalog.extras
                                        )
                                    } to null
                            },
                            onFailure = { error ->
                                emptyList<LiveCatalog>() to
                                    "$addonLabel: ${error.message ?: "manifest failed"}"
                            }
                        )
                }
            }.awaitAll()
            val iptvResults = iptvSources.map { source ->
                async(Dispatchers.IO) {
                    val failure = if (refreshIptv) {
                        try {
                            refreshIptvSource(source.id)
                            null
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            "${source.name}: ${t.message ?: "playlist failed"}"
                        }
                    } else {
                        null
                    }
                    val current = iptvStore.source(source.id) ?: source
                    val channels = iptvStore.cachedChannels(current.id).orEmpty()
                    listOf(
                        LiveCatalog(
                            addonName = "IPTV",
                            addonUrl = iptvAddonUrl(current.id),
                            id = current.id,
                            name = current.name,
                            type = "tv",
                            extras = iptvExtras(channels),
                            sourceKind = LiveSourceKind.IPTV,
                            iptvSourceId = current.id
                        )
                    ) to failure
                }
            }.awaitAll()
            LiveCatalogLoad(
                // User-added playlists come first: addon manifests can publish 100+
                // catalogs, which would otherwise bury the IPTV sources at the end.
                catalogs = (iptvResults.flatMap { it.first } + addonResults.flatMap { it.first })
                    .distinctBy { it.key },
                failures = addonResults.mapNotNull { it.second } +
                    iptvResults.mapNotNull { it.second }
            )
        }
    }

    private fun iptvExtras(channels: List<LiveChannel>): List<CatalogExtra> {
        val groups = channels.mapNotNull { it.category?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
        return if (groups.isEmpty()) emptyList() else listOf(CatalogExtra(name = "group-title", options = groups))
    }

    fun iptvGroups(sourceId: String): List<String> = iptvStore.cachedChannels(sourceId)
        .orEmpty()
        .mapNotNull { it.category?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
        .sorted()

    /**
     * Fetches a user-supplied playlist (or single stream) once so validation is
     * real: the source only lands in storage when at least one channel parsed.
     */
    suspend fun addIptvSource(
        location: String,
        name: String = "",
        epgUrl: String = ""
    ): IptvSource = withContext(Dispatchers.IO) {
        val source = IptvSource(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            location = validateIptvLocation(location),
            epgUrl = epgUrl.trim(),
            addedAt = System.currentTimeMillis()
        )
        fetchAndStoreIptv(source)
    }

    /**
     * Applies edited name, playlist address and guide override. A new address is
     * fetched before anything is stored, so a mistyped URL leaves the working
     * source and its cached channel list in place.
     */
    suspend fun updateIptvSource(
        id: String,
        location: String,
        name: String,
        epgUrl: String
    ): IptvSource = withContext(Dispatchers.IO) {
        val current = iptvStore.source(id) ?: throw IOException("This IPTV source was removed")
        val trimmed = validateIptvLocation(location)
        val updated = current.copy(
            name = name.trim(),
            location = trimmed,
            epgUrl = epgUrl.trim()
        )
        if (trimmed == current.location) {
            iptvStore.put(updated)
            return@withContext updated
        }
        iptvClient.clearGuideCache()
        synchronized(liveStreamCache) { liveStreamCache.clear() }
        fetchAndStoreIptv(updated)
    }

    suspend fun refreshIptvSource(id: String): IptvSource? = withContext(Dispatchers.IO) {
        val source = iptvStore.source(id) ?: throw IOException("This IPTV source was removed")
        fetchAndStoreIptv(source)
    }

    private fun validateIptvLocation(location: String): String {
        val trimmed = location.trim()
        if (trimmed.isBlank()) throw IOException("Enter a playlist URL or pick a local file")
        val scheme = trimmed.substringBefore("://", "").lowercase(Locale.US)
        val allowed = setOf("http", "https", "content", "file", "udp", "rtp", "rtsp", "rtmp", "mms")
        if (scheme.isNotEmpty() && scheme !in allowed) {
            throw IOException("Unsupported scheme \"$scheme\": use http(s)://, content://, file://, udp:// or rtp://")
        }
        if (scheme.isEmpty() && !File(trimmed).exists()) {
            throw IOException("Start the address with http(s)://, content://, file://, udp:// or rtp://")
        }
        if (trimmed.startsWith("content://")) {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    Uri.parse(trimmed),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        return trimmed
    }

    fun removeIptvSource(id: String) {
        iptvStore.remove(id)
        iptvClient.clearGuideCache()
        synchronized(liveStreamCache) { liveStreamCache.clear() }
    }

    private suspend fun fetchAndStoreIptv(source: IptvSource): IptvSource {
        val playlist = iptvClient.load(source)
        iptvStore.saveChannels(source.id, playlist.channels)
        val stored = source.copy(
            name = source.name.ifBlank {
                playlist.title?.takeIf { it.isNotBlank() } ?: hostLabel(source.cleanLocation)
            },
            kind = playlist.kind,
            extractedEpgUrl = playlist.epgUrl.orEmpty(),
            channelCount = playlist.channels.size,
            lastLoadedAt = System.currentTimeMillis(),
            lastReport = playlist.report?.summary.orEmpty()
        )
        iptvStore.put(stored)
        return stored
    }

    private fun hostLabel(location: String): String = location
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .ifBlank { "IPTV playlist" }

    /** Persists a per-source guide correction and stops automatic detection. */
    fun setIptvEpgShift(id: String, minutes: Int): Boolean {
        val source = iptvStore.source(id) ?: return false
        val clamped = minutes.coerceIn(-12 * 60, 12 * 60)
        if (!source.epgShiftAuto && source.epgShiftMinutes == clamped) return false
        iptvStore.put(source.copy(epgShiftMinutes = clamped, epgShiftAuto = false))
        return true
    }

    /** Hands the source back to detection, discarding any manual correction. */
    fun resetIptvEpgShift(id: String): Boolean {
        val source = iptvStore.source(id) ?: return false
        if (source.epgShiftAuto) return false
        iptvStore.put(source.copy(epgShiftAuto = true))
        return true
    }

    /**
     * Every enabled source's guide is fetched once, corrected (detected or
     * manual) and merged.
     */
    suspend fun iptvEpg(force: Boolean = false): EpgLoad = withContext(Dispatchers.IO) {
        val requests = iptvStore.sources.value
            .filter { it.enabled }
            .mapNotNull { source ->
                val parsed = StreamUrls.parse(source.effectiveEpgUrl.trim())
                parsed.url.takeIf { it.isNotBlank() }
                    ?.let { GuideRequest(source, it, parsed.headers) }
            }
            .distinctBy {
                listOf(it.url, it.headers.toString(), it.source.epgShiftAuto, it.source.epgShiftMinutes)
            }
        if (requests.isEmpty()) return@withContext EpgLoad()
        if (force) iptvClient.clearGuideCache()
        coroutineScope {
            val results = requests.map { request ->
                async(Dispatchers.IO) {
                    val (source, url, headers) = request
                    val startedAt = SystemClock.elapsedRealtime()
                    val result = try {
                        Result.success(
                            withTimeoutOrNull(GUIDE_TIMEOUT_MS) {
                                iptvClient.loadGuide(url, headers, force)
                            }
                        )
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }
                    result.fold(
                        onSuccess = { raw ->
                            val elapsed = SystemClock.elapsedRealtime() - startedAt
                            if (raw == null) {
                                Log.w(
                                    "StreamLine:epg",
                                    "${hostLabel(url)} · guide exceeded ${GUIDE_TIMEOUT_MS / 1000}s"
                                )
                                GuideResult(
                                    sourceId = source.id,
                                    failure = "${hostLabel(url)}: guide took longer than ${GUIDE_TIMEOUT_MS / 1000}s"
                                )
                            } else {
                                val parsed = raw.guide
                                val detected = parsed.autoShiftMinutes()
                                val shift = if (source.epgShiftAuto) detected else source.epgShiftMinutes
                                val guide = parsed.shifted(shift)
                                Log.i(
                                    "StreamLine:epg",
                                    "${hostLabel(url)} · ${guide.channelCount} channels · " +
                                        "${guide.programCount} programs · ${elapsed}ms" +
                                        when {
                                            shift == 0 -> ""
                                            source.epgShiftAuto ->
                                                " · auto-shift ${shift}min (declared ${parsed.declaredOffsetMinutes}min)"
                                            else -> " · shifted ${shift}min"
                                        }
                                )
                                GuideResult(
                                    guide = guide,
                                    sourceId = source.id,
                                    autoShift = detected.takeIf { source.epgShiftAuto },
                                    report = raw.report
                                )
                            }
                        },
                        onFailure = {
                            Log.w("StreamLine:epg", "${hostLabel(url)} · ${it.message}")
                            GuideResult(
                                sourceId = source.id,
                                failure = "${hostLabel(url)}: ${it.message ?: "guide failed"}"
                            )
                        }
                    )
                }
            }.awaitAll()
            val merged = EpgGuide.merge(results.mapNotNull { it.guide }, mergedEpgCap)
            // Keep a compact index for the next cold start; the full document
            // is never persisted, only the timestamps NOW/NEXT needs.
            if (!merged.isEmpty) guideIndexStore.save(merged)
            val reports = results.mapNotNull { it.report }
            EpgLoad(
                guide = merged,
                failures = results.mapNotNull { it.failure },
                autoShifts = results.mapNotNull { result ->
                    result.autoShift?.let { result.sourceId to it }
                }.toMap(),
                report = reports.takeIf { it.isNotEmpty() }?.let { list ->
                    PlaylistReport(
                        total = list.sumOf { it.total },
                        skipped = list.sumOf { it.skipped },
                        malformed = list.sumOf { it.malformed }
                    )
                }
            )
        }
    }

    /** Guide restored from disk before the first network refresh completes. */
    fun cachedEpgIndex(): EpgGuide? = guideIndexStore.load()

    private suspend fun cachedManifest(addon: AddonSource): AddonManifest {
        val key = addon.url.trim().trimEnd('/').lowercase()
        addonManifestCache[key]?.let { return it }
        return fetchManifest(addon)
    }

    private suspend fun fetchManifest(addon: AddonSource): AddonManifest {
        val key = addon.url.trim().trimEnd('/').lowercase()
        val manifest = StremioAddons.loadManifest(addon.url)
        addonManifestCache[key] = manifest
        return manifest
    }

    /**
     * Startup addon sync: re-fetches every enabled addon manifest in the background
     * so the Live tab and playback queries use fresh catalogs without a first-tap
     * stall. Runs silently — an offline addon just keeps its previous cache entry.
     */
    suspend fun syncAddonsOnStartup() {
        val addons = settingsStore.settings.value.addons.filter { it.enabled && it.url.isNotBlank() }
        if (addons.isEmpty()) return
        val manifests = coroutineScope {
            addons.map { addon ->
                async(Dispatchers.IO) {
                    runCatching { fetchManifest(addon) }.getOrNull()?.let { addon.id to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        if (manifests.isEmpty()) return
        // Provider name and artwork only ever come from the manifest, so the
        // source cards can show the logo the provider publishes.
        settingsStore.update { settings ->
            settings.copy(
                addons = settings.addons.map { addon ->
                    val manifest = manifests[addon.id] ?: return@map addon
                    addon.copy(
                        name = addon.name.ifBlank { manifest.name.trim() },
                        logo = manifest.logo?.takeIf { it.isNotBlank() } ?: addon.logo
                    )
                }
            )
        }
    }

    /** Throws on addon/HTTP failure so the Live tab can show the real reason. */
    suspend fun liveChannels(
        catalog: LiveCatalog,
        extras: Map<String, String> = emptyMap()
    ): List<LiveChannel> = withContext(Dispatchers.IO) {
        if (catalog.sourceKind == LiveSourceKind.IPTV) {
            val id = catalog.iptvSourceId ?: return@withContext emptyList()
            val source = iptvStore.source(id) ?: throw IOException("This IPTV source was removed")
            val all = iptvStore.cachedChannels(id) ?: run {
                val loaded = fetchAndStoreIptv(source)
                iptvStore.cachedChannels(loaded.id).orEmpty()
            }
            val group = extras["group-title"]
            return@withContext if (group.isNullOrBlank()) {
                all
            } else {
                all.filter { it.category.equals(group, ignoreCase = true) }
            }
        }
        StremioAddons.fetchCatalog(catalog.addonUrl, catalog.type, catalog.id, extras)
    }

    /**
     * Resolves a live channel to a source the native player can open. Every direct
     * candidate is probed first: live addons keep dead CDN links in their stream
     * list, and an expired HLS token (HTTP 403) otherwise fails after the player
     * has already opened. When only an external watch page exists (DRM/premium
     * feeds) the page is returned so the UI can explain why nothing plays.
     *
     * Addons can publish dozens of dead links, so the probe list is capped and
     * the whole lookup is bounded: tuning must fail into the UI, not leave the
     * player on "Tuning…" while probes queue up.
     */
    suspend fun liveStream(channel: LiveChannel): LiveStreamResult {
        takePrefetchedLive(channel.key)?.let { return it }
        val result = withTimeoutOrNull(LIVE_RESOLVE_TIMEOUT_MS) { resolveLiveStream(channel) }
            ?: LiveStreamResult(error = "stream lookup timed out")
        if (result.option != null) cacheLive(channel.key, result)
        return result
    }

    /**
     * Warms a neighbouring channel while the OSD is open. Channel surfing is
     * the dominant Live interaction, and without this every zap pays the full
     * addon lookup plus probe round trip. Failures are not cached, so a skipped
     * dead entry can still be retried deliberately.
     */
    suspend fun prefetchLive(channel: LiveChannel) {
        val key = channel.key
        if (key.isBlank()) return
        synchronized(liveStreamCache) {
            val cached = liveStreamCache[key]
            if (cached != null && SystemClock.elapsedRealtime() - cached.first <= LIVE_PREFETCH_TTL_MS) return
        }
        if (!livePrefetchInFlight.add(key)) return
        try {
            val result = withTimeoutOrNull(LIVE_RESOLVE_TIMEOUT_MS) { resolveLiveStream(channel) } ?: return
            if (result.option != null) cacheLive(key, result)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // A prefetch is best effort; the real tune reports the failure.
        } finally {
            livePrefetchInFlight.remove(key)
        }
    }

    private val livePrefetchInFlight: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private fun cacheLive(key: String, result: LiveStreamResult) {
        synchronized(liveStreamCache) { liveStreamCache[key] = SystemClock.elapsedRealtime() to result }
    }

    private fun takePrefetchedLive(key: String): LiveStreamResult? {
        if (key.isBlank()) return null
        synchronized(liveStreamCache) {
            val cached = liveStreamCache.remove(key) ?: return null
            return if (SystemClock.elapsedRealtime() - cached.first <= LIVE_PREFETCH_TTL_MS) {
                cached.second
            } else {
                null
            }
        }
    }

    /**
     * Addon that published a live channel, for card branding. IPTV lineups are
     * not addons, so they fall back to the generic "Direct" provider.
     */
    private fun liveAddonIdentity(addonUrl: String): Pair<String, String> {
        val target = AddonSource.normalize(addonUrl) ?: return "Direct" to ""
        val addons = settingsStore.settings.value.addons
        val index = addons.indexOfFirst { AddonSource.normalize(it.url) == target }
        val addon = addons.getOrNull(index) ?: return "Direct" to ""
        return addon.name.trim().ifBlank { "Addon ${index + 1}" } to addon.logo.trim()
    }

    private suspend fun resolveLiveStream(channel: LiveChannel): LiveStreamResult =
        withContext(Dispatchers.IO) {
        val userAgent = settingsStore.settings.value.userAgentString
        val (liveProvider, liveProviderLogo) = liveAddonIdentity(channel.addonUrl)
        // IPTV playlist entries already point at the stream; no addon lookup.
        channel.streamUrl?.takeIf { it.isNotBlank() }?.let { raw ->
            val parsed = StreamUrls.parse(raw)
            if (parsed.url.isBlank()) {
                return@withContext LiveStreamResult(error = "empty stream URL")
            }
            val throughMedia3 = parsed.url.startsWith("http://", true) ||
                parsed.url.startsWith("https://", true)
            return@withContext if (throughMedia3) {
                // Short links (jmp2.uk/…) hide the manifest URL; Media3 resolves
                // HLS variants against the URL it is handed, so swap in the target.
                val playableUrl = if (needsRedirectResolution(parsed.url)) {
                    StremioAddons.resolveFinalUrl(parsed.url, parsed.headers, userAgent).also { resolved ->
                        if (resolved != parsed.url) {
                            Log.i("StreamLine:live", "${channel.name} resolved · ${resolved.take(160)}")
                        }
                    }
                } else {
                    parsed.url
                }
                LiveStreamResult(
                    option = PlaybackOption(
                        name = channel.name,
                        url = playableUrl,
                        headers = parsed.headers
                    )
                )
            } else {
                // udp://, rtp://, rtsp:// have no Media3 data source.
                LiveStreamResult(externalUrl = parsed.url, externalOnly = true)
            }
        }
        val streams = try {
            StremioAddons.fetchStreams(channel.addonUrl, channel.type, channel.id)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return@withContext LiveStreamResult(
                error = t.message ?: "stream lookup failed"
            )
        }
        val external = streams.firstNotNullOfOrNull { it.externalUrl }
        var lastError: String? = null
        var deviceLimited = false
        var denied: PlaybackOption? = null
        val liveOptions = streams.mapNotNull { it.toPlaybackOption(liveProvider, liveProviderLogo) }
        for (option in liveOptions.take(MAX_LIVE_PROBES)) {
            val probe = StremioAddons.probeStream(option.url, option.headers, userAgent)
            if (probe.error == null) {
                var playable = if (probe.url != option.url) option.copy(url = probe.url) else option
                // Direct manifests can carry their own protection. Media3 ignores
                // <ms:laurl>/SESSION-KEY, so the DRM session must come from here.
                val manifest = StremioAddons.manifestDrm(probe, option.headers)
                val drm = when {
                    manifest?.drm != null -> manifest.drm
                    manifest?.protected == true -> {
                        lastError = "${manifest.detail ?: "this feed"} is DRM-protected " +
                            "and publishes no licence endpoint"
                        deviceLimited = false
                        Log.i("StreamLine:live", "${channel.name} ${lastError}")
                        continue
                    }
                    else -> option.drm
                }
                if (drm != null && !drm.scheme.supportedOnDevice()) {
                    lastError = "${drm.scheme} DRM is not available on this device"
                    deviceLimited = true
                    Log.i("StreamLine:live", "${channel.name} ${lastError}")
                    continue
                }
                if (drm != null) {
                    playable = playable.copy(drm = drm)
                    Log.i("StreamLine:live", "${channel.name} drm=${drm.scheme} · ${playable.url.take(160)}")
                }
                Log.i("StreamLine:live", "${channel.name} direct · ${playable.url.take(160)}")
                return@withContext LiveStreamResult(option = playable, externalUrl = external)
            }
            // A probe deny is not proof the link is dead: providers answer 403
            // when the probe's own session hits the account limit, 5xx during a
            // transient outage, and one-time tokens can be spent by the probe.
            // Only 404/410 name a gone link, so anything else still reaches the
            // player, whose watchdog and failover handle a real failure.
            if (denied == null && !probe.linkGone) denied = option
            lastError = probe.error
            deviceLimited = false
        }
        // Some addons only publish a watch page. Those pages often inline the
        // direct manifest and, for encrypted feeds, the ClearKey key pair.
        for (pageUrl in streams.mapNotNull { it.externalUrl }.distinct().take(MAX_LIVE_PAGES)) {
            val resolved = try {
                StremioAddons.resolveWatchPage(pageUrl)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                null
            } ?: continue
            Log.i(
                "StreamLine:live",
                "${channel.name} page · drm=${resolved.drm?.scheme} · ${resolved.url.take(160)}"
            )
            var option = PlaybackOption(
                name = "${channel.name} · ${if (resolved.drm?.scheme == DrmScheme.CLEARKEY) "ClearKey DASH" else "web DASH"}",
                url = resolved.url,
                headers = resolved.headers,
                drm = resolved.drm
            )
            val probe = StremioAddons.probeStream(option.url, option.headers, userAgent)
            if (probe.error == null) {
                val manifest = StremioAddons.manifestDrm(probe, option.headers)
                val pageDrm = option.drm
                val drm = when {
                    // The page handed out the actual ClearKey pair; a DASH
                    // ContentProtection would just re-advertise Widevine.
                    pageDrm?.scheme == DrmScheme.CLEARKEY && !pageDrm.licenseJson.isNullOrBlank() ->
                        pageDrm
                    manifest?.drm != null -> manifest.drm
                    pageDrm != null -> pageDrm
                    manifest?.protected == true -> {
                        lastError = "${manifest.detail ?: "this feed"} is DRM-protected " +
                            "and publishes no licence endpoint"
                        deviceLimited = false
                        Log.i("StreamLine:live", "${channel.name} ${lastError}")
                        continue
                    }
                    else -> null
                }
                if (drm != null && !drm.scheme.supportedOnDevice()) {
                    lastError = "${drm.scheme} DRM is not available on this device"
                    deviceLimited = true
                    Log.i("StreamLine:live", "${channel.name} ${lastError}")
                    continue
                }
                option = if (probe.url != option.url) option.copy(url = probe.url, drm = drm) else option.copy(drm = drm)
                if (drm != null) Log.i("StreamLine:live", "${channel.name} drm=${drm.scheme} · ${option.url.take(160)}")
                return@withContext LiveStreamResult(option = option, externalUrl = pageUrl)
            }
            if (lastError == null) lastError = probe.error
        }
        denied?.let { option ->
            Log.i("StreamLine:live", "${channel.name} probe denied (${lastError}) · handing to player")
            return@withContext LiveStreamResult(option = option, externalUrl = external)
        }
        when {
            lastError != null -> LiveStreamResult(
                externalUrl = external,
                error = lastError,
                deviceLimited = deviceLimited
            )
            external != null -> LiveStreamResult(externalUrl = external)
            else -> LiveStreamResult(error = "the addon returned no playable links")
        }
    }

    /**
     * Pre-resolving costs a round trip, so only links whose last path segment
     * cannot name a media file are treated as short links
     * (`jmp2.uk/stvp-FR200016Y5`). Direct `.m3u8`/`.ts`/`.mp4` URLs let Media3
     * follow redirects itself.
     */
    private fun needsRedirectResolution(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val last = parsed.pathSegments.lastOrNull().orEmpty()
        return last.isEmpty() || !last.contains('.')
    }

    fun installedExternalPlayers(): List<ExternalPlayerInfo> {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("https://example.com/video.mp4"), "video/*")
        val manager = appContext.packageManager
        val resolved = runCatching {
            manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())
        val knownOrder = listOf(
            "com.brouken.player", "org.videolan.vlc", "is.xyz.mpv", "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro", "com.bsplayer.bspandroid.free", "com.archos.mediacenter.videoplayerfree"
        )
        return resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == appContext.packageName) return@mapNotNull null
            val label = runCatching { info.loadLabel(manager).toString() }.getOrDefault(packageName)
            ExternalPlayerInfo(packageName, label)
        }.distinctBy { it.packageName }
            .sortedWith(compareBy(
                { info -> knownOrder.indexOf(info.packageName).let { if (it < 0) Int.MAX_VALUE else it } },
                { it.label.lowercase() }
            ))
    }

    fun openExternally(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): Boolean {
        val settings = settingsStore.settings.value
        val intent = ExternalIntents.intent(
            url = url,
            referer = referer,
            headers = headers,
            userAgent = settings.userAgentString
        )
        val manager = appContext.packageManager
        val target = if (intent.resolveActivity(manager) != null) {
            intent
        } else {
            Intent(intent).setDataAndType(Uri.parse(url), "video/*")
        }
        return runCatching {
            appContext.startActivity(ExternalIntents.chooser(target))
            true
        }.getOrDefault(false)
    }

    fun openInBrowser(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { appContext.startActivity(intent); true }.getOrDefault(false)
    }

    fun recordProgress(progress: WatchProgress) = libraryStore.saveProgress(progress)

    fun resumeProgress(playbackKey: String): WatchProgress? = libraryStore.progressFor(playbackKey)

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val settings = settingsStore.settings.value
        val proxy = settings.proxyBaseUrl.trim().trimEnd('/')
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", Http.BROWSER_USER_AGENT)
            .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
        // Only hand the TMDB key to the configured proxy, never to third-party hosts.
        if (proxy.isNotEmpty() && url.startsWith(proxy, ignoreCase = true) && settings.tmdbApiKey.isNotBlank()) {
            builder.header("X-TMDB-Key", settings.tmdbApiKey.trim())
        }
        val response = Http.client.await(builder.build())
        val code = response.code
        val body = response.bodyStringCapped()
        response.close()
        if (code !in 200..299) {
            val hint = runCatching { JSONObject(body).optString("error") }.getOrNull()
            throw IOException("HTTP $code" + if (!hint.isNullOrBlank()) " · $hint" else "")
        }
        body
    }
}
