package com.streamline.cinema

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamline.cinema.data.AddonSource
import com.streamline.cinema.data.AppSettings
import com.streamline.cinema.data.CatalogExtra
import com.streamline.cinema.data.CatalogItem
import com.streamline.cinema.data.CinemaDns
import com.streamline.cinema.data.CinemaProfile
import com.streamline.cinema.data.DnsMode
import com.streamline.cinema.data.EpgGuide
import com.streamline.cinema.data.EpgProgramHit
import com.streamline.cinema.data.Episode
import com.streamline.cinema.data.ExternalIntents
import com.streamline.cinema.data.ExternalPlayerInfo
import com.streamline.cinema.data.Genre
import com.streamline.cinema.data.GenreCatalog
import com.streamline.cinema.data.ChannelHealth
import com.streamline.cinema.data.GenreSort
import com.streamline.cinema.data.Genres
import com.streamline.cinema.data.ImportSource
import com.streamline.cinema.data.IptvSource
import com.streamline.cinema.data.LanguageRule
import com.streamline.cinema.data.LiveCatalog
import com.streamline.cinema.data.LiveChannel
import com.streamline.cinema.data.LivePrefsStore
import com.streamline.cinema.data.LiveSourceKind
import com.streamline.cinema.data.MaturityLevel
import com.streamline.cinema.data.MediaType
import com.streamline.cinema.data.PlaybackOption
import com.streamline.cinema.data.PlaybackPlan
import com.streamline.cinema.data.Season
import com.streamline.cinema.data.StreamBadgeCatalog
import com.streamline.cinema.data.StreamBadgeState
import com.streamline.cinema.data.StreamUrls
import com.streamline.cinema.data.normalizeBadgeSource
import com.streamline.cinema.data.SyncCadence
import com.streamline.cinema.data.SyncTarget
import com.streamline.cinema.data.NoPublishedReleaseException
import com.streamline.cinema.data.UpdateChecker
import com.streamline.cinema.data.UpdateUiState
import com.streamline.cinema.data.WatchProgress
import com.streamline.cinema.data.allowedFor
import com.streamline.cinema.data.filterKnownOtherLanguages
import com.streamline.cinema.data.isNewerVersion
import com.streamline.cinema.data.rankByLanguage
import com.streamline.cinema.data.rankingLanguage
import com.streamline.cinema.data.todayStamp
import com.streamline.cinema.data.watchedEpisodesFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

data class GenreRail(val name: String, val items: List<CatalogItem>)

data class HomeFeed(
    val trending: List<CatalogItem> = emptyList(),
    val popularMovies: List<CatalogItem> = emptyList(),
    val popularShows: List<CatalogItem> = emptyList(),
    val top10: List<CatalogItem> = emptyList(),
    val newReleases: List<CatalogItem> = emptyList(),
    val becauseTitle: String? = null,
    val because: List<CatalogItem> = emptyList(),
    val genreRows: List<GenreRail> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

/** Drops every rail entry that a parent has not approved for the Kids profile. */
private fun HomeFeed.kidsFiltered(approved: Set<String>): HomeFeed {
    fun List<CatalogItem>.keep() = filter { it.key in approved }
    val cleanBecause = because.keep()
    return copy(
        trending = trending.keep(),
        popularMovies = popularMovies.keep(),
        popularShows = popularShows.keep(),
        top10 = top10.keep(),
        newReleases = newReleases.keep(),
        becauseTitle = becauseTitle.takeIf { cleanBecause.isNotEmpty() },
        because = cleanBecause,
        genreRows = genreRows.mapNotNull { rail ->
            rail.items.keep().takeIf { it.isNotEmpty() }?.let { GenreRail(rail.name, it) }
        }
    )
}

private fun epgShiftLabel(minutes: Int): String {
    val sign = if (minutes > 0) "+" else "-"
    val total = kotlin.math.abs(minutes)
    val hours = total / 60
    val rest = total % 60
    return when {
        hours == 0 -> "$sign${rest}m"
        rest == 0 -> "$sign${hours}h"
        else -> "$sign${hours}h${rest}m"
    }
}

data class SearchUiState(
    val query: String = "",
    val filter: MediaType? = null,
    val results: List<CatalogItem> = emptyList(),
    /** Matching IPTV channels from every enabled playlist, searched in the same pass. */
    val channels: List<LiveChannel> = emptyList(),
    /** EPG programmes matching the query, currently airing first. */
    val programs: List<EpgProgramHit> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Non-empty means the Playroom is browsing discover results for these TMDB genre ids. */
    val genreIds: Set<Int> = emptySet(),
    val genreSort: GenreSort = GenreSort.NEWEST,
    /** A–Z strip selection; filters the hall alphabetically while the query is empty. */
    val letter: String? = null
)

data class GenreUiState(
    val catalog: GenreCatalog = GenreCatalog(),
    val loading: Boolean = false,
    val error: String? = null
)

data class QuickImportState(
    val key: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val success: Boolean = false
)

data class TmdbTestState(val busy: Boolean = false, val message: String? = null, val success: Boolean = false)

enum class TmdbVerifyStatus { NONE, VERIFYING, OK, FAILED }

data class TmdbVerifyState(
    val status: TmdbVerifyStatus = TmdbVerifyStatus.NONE,
    val message: String? = null
)

data class DnsTestState(val busy: Boolean = false, val message: String? = null, val success: Boolean = false)

data class LiveUiState(
    val catalogs: List<LiveCatalog> = emptyList(),
    val selected: LiveCatalog? = null,
    /** Active selection groups of the catalog (`country-name` → `Nepal`, …). */
    val filters: Map<String, String> = emptyMap(),
    val channels: List<LiveChannel> = emptyList(),
    val loading: Boolean = false,
    val resolving: String? = null,
    val message: String? = null
)

data class EpgUiState(
    val guide: EpgGuide = EpgGuide.EMPTY,
    val loading: Boolean = false,
    val message: String? = null,
    val loadedAt: Long = 0L,
    /** Source id → shift detection applied on the last load. */
    val autoShifts: Map<String, Int> = emptyMap(),
    /** Human-readable "guide is +2h off — adjusted" lines for the Live tab. */
    val shiftNotices: List<String> = emptyList(),
    /** Kept/skipped/malformed counts from the last guide parse. */
    val report: String? = null,
    /** True when NOW/NEXT came from the persisted index before the live fetch. */
    val fromCache: Boolean = false
)

enum class ImportPhase { IDLE, RUNNING, SUCCESS, ERROR }

data class ImportUiState(
    val phase: ImportPhase = ImportPhase.IDLE,
    val message: String = "",
    val pendingEnrichment: Int = 0
)

data class SyncUiState(
    val running: Set<SyncTarget> = emptySet(),
    val lastRun: Map<SyncTarget, Long> = emptyMap(),
    val errors: Map<SyncTarget, String> = emptyMap()
)

class CinemaViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as StreamLineApp).container
    private val repository = container.repository

    val settings: StateFlow<AppSettings> = container.settingsStore.settings
    val profiles: StateFlow<List<CinemaProfile>> = container.profileStore.profiles
    val activeProfile: StateFlow<CinemaProfile> = container.profileStore.active
    val kidsMode = container.kidsMode

    // Visibility is resolved once here so every screen sees the same catalog.
    // Kids profiles see only parent-approved keys; other profiles keep the
    // maturity filter. The store itself always keeps the full library.
    val library: StateFlow<List<CatalogItem>> = combine(
        container.libraryStore.items,
        container.profileStore.active,
        container.kidsMode.approved
    ) { items, profile, approved ->
        items.filter { if (profile.isKids) it.key in approved else it.allowedFor(profile.maturity) }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        container.libraryStore.items.value.filter {
            val profile = container.profileStore.active.value
            if (profile.isKids) {
                it.key in container.kidsMode.approved.value
            } else {
                it.allowedFor(profile.maturity)
            }
        }
    )
    /** Unfiltered store contents, used by the parental curation screen. */
    val allLibrary: StateFlow<List<CatalogItem>> = container.libraryStore.items
    val sources: StateFlow<List<ImportSource>> = container.libraryStore.sources
    val progress: StateFlow<Map<String, WatchProgress>> = container.libraryStore.progress
    val favorites: StateFlow<List<CatalogItem>> = combine(
        container.libraryStore.favorites,
        container.profileStore.active,
        container.kidsMode.approved
    ) { favorites, profile, approved ->
        favorites.filter { if (profile.isKids) it.key in approved else it.allowedFor(profile.maturity) }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        container.libraryStore.favorites.value.filter {
            val profile = container.profileStore.active.value
            if (profile.isKids) {
                it.key in container.kidsMode.approved.value
            } else {
                it.allowedFor(profile.maturity)
            }
        }
    )
    val watchedEpisodes: StateFlow<Set<String>> = container.libraryStore.watchedEpisodes
    val iptvSources: StateFlow<List<IptvSource>> = container.iptvStore.sources
    val channelHealth: StateFlow<Map<String, ChannelHealth>> = container.channelHealth.health
    val livePrefs: StateFlow<LivePrefsStore.Prefs> = container.livePrefs.prefs
    val enrichingTitle: StateFlow<String?> = repository.enrichingTitle
    /** Badge document shared by the source cards and the Settings preview. */
    val badges: StateFlow<StreamBadgeCatalog> = container.badges.catalog
    val badgeState: StateFlow<StreamBadgeState> = container.badges.state

    private val _sniffedSeasons = MutableStateFlow<Map<String, List<Season>>>(emptyMap())
    val sniffedSeasons: StateFlow<Map<String, List<Season>>> = _sniffedSeasons.asStateFlow()
    private var sniffJob: Job? = null
    private var lastSniffAt = 0L

    private val _home = MutableStateFlow(HomeFeed())
    // Kids profiles only ever see approved titles in every rail. Filtering the
    // published feed here keeps Home, Movies and TV discover rows consistent.
    val home: StateFlow<HomeFeed> = combine(
        _home,
        container.profileStore.active,
        container.kidsMode.approved
    ) { feed, profile, approved ->
        if (profile.isKids) feed.kidsFiltered(approved) else feed
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _home.value)

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _genres = MutableStateFlow(GenreUiState())
    val genres: StateFlow<GenreUiState> = _genres.asStateFlow()

    private val _importState = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    private val _quickImport = MutableStateFlow(QuickImportState())
    val quickImport: StateFlow<QuickImportState> = _quickImport.asStateFlow()

    private val _tmdbTest = MutableStateFlow(TmdbTestState())
    val tmdbTest: StateFlow<TmdbTestState> = _tmdbTest.asStateFlow()

    private val _tmdbVerify = MutableStateFlow(TmdbVerifyState())
    val tmdbVerify: StateFlow<TmdbVerifyState> = _tmdbVerify.asStateFlow()

    private val _dnsTest = MutableStateFlow(DnsTestState())
    val dnsTest: StateFlow<DnsTestState> = _dnsTest.asStateFlow()

    private val _externalPlayers = MutableStateFlow<List<ExternalPlayerInfo>>(emptyList())
    val externalPlayers: StateFlow<List<ExternalPlayerInfo>> = _externalPlayers.asStateFlow()

    private val _live = MutableStateFlow(LiveUiState())
    val live: StateFlow<LiveUiState> = _live.asStateFlow()
    /** Unfiltered channels for the parental curation screen. */
    val liveAll: StateFlow<LiveUiState> = _live.asStateFlow()

    private val _epg = MutableStateFlow(EpgUiState())
    val epg: StateFlow<EpgUiState> = _epg.asStateFlow()

    private val _sync = MutableStateFlow(
        SyncUiState(
            lastRun = SyncTarget.entries.associateWith { container.settingsStore.lastRefresh(it) }
        )
    )
    val sync: StateFlow<SyncUiState> = _sync.asStateFlow()
    private val syncGuard = Any()
    private val activeSyncs = mutableSetOf<SyncTarget>()

    // GitHub release sync: the startup check only reads the latest release; the
    // APK is downloaded and handed to PackageInstaller on explicit request.
    private val updateChecker = UpdateChecker(application)
    private val _update = MutableStateFlow(UpdateUiState())
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()
    private var updateJob: Job? = null

    private var searchJob: Job? = null
    private var epgSearchJob: Job? = null
    private var genresJob: Job? = null
    private var genresLoadedFromApi = false
    private var quickImportDismissJob: Job? = null
    private var homeLoaded = false
    private var railsJob: Job? = null
    private var becauseAnchorKey: String? = null

    init {
        // Cold start: paint NOW/NEXT from the persisted guide index before the
        // first network refresh so the Live tab is never blank while a large
        // XMLTV document is being re-parsed. loadedAt stays 0 so the normal
        // refresh still runs as soon as the tab asks for it.
        viewModelScope.launch(Dispatchers.IO) {
            val cached = repository.cachedEpgIndex() ?: return@launch
            if (cached.isEmpty || _epg.value.guide.programCount > 0) return@launch
            _epg.value = EpgUiState(guide = cached, loadedAt = 0L, fromCache = true)
        }
        // The library loads off-main; setActiveProfile waits for that lock, so a
        // profile restored from the last session never sees the wrong bucket.
        container.libraryStore.setActiveProfile(container.profileStore.active.value.id)
        viewModelScope.launch {
            container.profileStore.active.drop(1).collect { profile ->
                container.libraryStore.setActiveProfile(profile.id)
                refreshRecommendations(force = true)
                if (_search.value.query.isBlank() && _search.value.genreIds.isEmpty()) {
                    loadPlayroomDefault()
                }
            }
        }
        refreshHome()
        loadExternalPlayers()
        viewModelScope.launch {
            // Keep the import queue badge in sync without polling the filesystem.
            container.libraryStore.items.collect { items ->
                val pending = items.count { !it.tmdbLoaded }
                _importState.update { it.copy(pendingEnrichment = pending) }
            }
        }
        viewModelScope.launch {
            // App Sync: honour each type's cadence once the UI has settled, then
            // keep a slow timer so 12/24-hour schedules fire while the app is open.
            delay(2_000)
            runDueSyncs(startup = true)
        }
        viewModelScope.launch {
            while (true) {
                delay(SYNC_CHECK_INTERVAL_MS)
                runDueSyncs(startup = false)
            }
        }
        viewModelScope.launch {
            // TMDB key verification on startup: prove the stored key/proxy is
            // actually valid, not just present, and reflect it in the status pill.
            delay(1_200)
            verifyTmdb()
        }
        viewModelScope.launch {
            // Badges ride the same 24-hour cache; a stored link is refreshed once
            // the UI is idle so source cards never wait on the document.
            delay(3_000)
            val target = settings.value.badgeSourceUrl.trim()
            if (target.isNotBlank()) container.badges.refresh(target)
        }
        viewModelScope.launch {
            // Sync with the repository once the UI has settled. A release page
            // with no published release yet is not an error the user must see.
            delay(4_000)
            if (settings.value.autoUpdateCheck) checkForUpdates()
        }
    }

    fun refreshHome(force: Boolean = false) {
        if (!settings.value.hasTmdbAccess) {
            _home.update { it.copy(loading = false, error = "Add a TMDB API key to load the discovery catalog") }
            refreshRecommendations()
            return
        }
        if (_home.value.loading) return
        if (homeLoaded && !force) return
        viewModelScope.launch {
            _home.update { it.copy(loading = true, error = null) }
            val result = runCatching {
                coroutineScope {
                    val trending = async { repository.trending() }
                    val movies = async { repository.topMovies() }
                    val shows = async { repository.topShows() }
                    Triple(trending.await(), movies.await(), shows.await())
                }
            }
            result.onSuccess { (trending, movies, shows) ->
                homeLoaded = true
                val language = settings.value.rankingLanguage()
                _home.update {
                    it.copy(
                        trending = rankByLanguage(trending, language),
                        popularMovies = rankByLanguage(movies, language),
                        popularShows = rankByLanguage(shows, language),
                        loading = false,
                        error = null
                    )
                }
                refreshRecommendations(force = true)
            }.onFailure { t ->
                _home.update {
                    it.copy(loading = false, error = t.message ?: "Could not reach TMDB")
                }
                refreshRecommendations()
            }
        }
    }

    /**
     * Rebuilds the local rails (Top 10, New Releases, genre rows) and, when the
     * anchor title changed, fetches "Because you watched" from TMDB. Local work
     * is cheap enough to run on every library/profile change.
     */
    fun refreshRecommendations(force: Boolean = false) {
        val profile = activeProfile.value
        val storeItems = container.libraryStore.items.value.filter { kidsMode.allows(it, profile) }
        val trending = _home.value.trending.filter { kidsMode.allows(it, profile) }
        val top10 = trending.distinctBy { it.key }.take(10)
        val newReleases = (storeItems + trending)
            .distinctBy { it.key }
            .filter { !it.releaseDate.isNullOrBlank() }
            .sortedByDescending { it.releaseDate }
            .take(14)
        val genreRows = storeItems
            .flatMap { item -> item.genres.map { genre -> genre to item } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.distinctBy { it.key } }
            .filter { it.value.size >= 4 }
            .entries
            .sortedByDescending { it.value.size }
            .take(3)
            .map { GenreRail(it.key, it.value.take(14)) }
        _home.update {
            it.copy(top10 = top10, newReleases = newReleases, genreRows = genreRows)
        }

        val anchor = recommendationAnchor() ?: run {
            becauseAnchorKey = null
            _home.update { it.copy(becauseTitle = null, because = emptyList()) }
            return
        }
        val anchorKey = "${anchor.key}:${profile.id}"
        if (!force && anchorKey == becauseAnchorKey) return
        becauseAnchorKey = anchorKey

        val localFallback = storeItems
            .filter { item -> item.key != anchor.key && item.genres.any { it in anchor.genres } }
            .take(14)
        if (!settings.value.hasTmdbAccess) {
            _home.update { it.copy(becauseTitle = anchor.title, because = localFallback) }
            return
        }
        railsJob?.cancel()
        railsJob = viewModelScope.launch {
            runCatching { repository.recommendations(anchor.tmdbId, anchor.type) }
                .onSuccess { remote ->
                    val filtered = rankByLanguage(remote, settings.value.rankingLanguage())
                        .filter { kidsMode.allows(it, profile) }
                        .take(14)
                    _home.update {
                        it.copy(
                            becauseTitle = anchor.title,
                            because = filtered.ifEmpty { localFallback }
                        )
                    }
                }
                .onFailure {
                    _home.update { it.copy(becauseTitle = anchor.title, because = localFallback) }
                }
        }
    }

    private fun recommendationAnchor(): CatalogItem? {
        val profile = activeProfile.value
        val resume = progress.value.values
            .filter { it.fraction > 0f && (!profile.isKids || kidsMode.isApproved(it.itemKey)) }
            .sortedByDescending { it.updatedAt }
            .firstOrNull()
        if (resume != null) {
            return library.value.firstOrNull { it.key == resume.itemKey }
                ?: CatalogItem(
                    tmdbId = resume.tmdbId,
                    type = resume.type,
                    title = resume.title,
                    posterUrl = resume.posterUrl
                )
        }
        return favorites.value.firstOrNull() ?: library.value.firstOrNull()
    }

    fun switchProfile(profileId: String) {
        container.profileStore.select(profileId)
    }

    /** Kids Mode is the Kids profile: this switches to it (creating one if needed). */
    fun enableKidsMode(): CinemaProfile = kidsMode.enable()

    /** Leaves Kids Mode by switching back to the first adult profile. */
    fun disableKidsMode(): Boolean = kidsMode.disable()

    fun setKidsPin(pin: String) = kidsMode.setPin(pin)

    fun clearKidsPin() = kidsMode.clearPin()

    fun saveProfile(profile: CinemaProfile) {
        val saved = container.profileStore.upsert(profile)
        if (saved.isKids && saved.id == activeProfile.value.id) {
            container.libraryStore.setActiveProfile(saved.id)
        }
        refreshRecommendations(force = true)
    }

    fun addProfile(name: String, maturity: MaturityLevel): CinemaProfile =
        container.profileStore.add(name, maturity)

    fun deleteProfile(profileId: String): Boolean = container.profileStore.remove(profileId)

    fun onTmdbSettingsChanged() {
        homeLoaded = false
        genresLoadedFromApi = false
        refreshHome(force = true)
        loadGenres(force = true)
        search(_search.value.query, _search.value.filter, settings.value.includeAdult)
        verifyTmdb()
    }

    /** Re-fetches shelves and search so a language/country change lands immediately. */
    fun onContentPreferencesChanged() {
        homeLoaded = false
        refreshHome(force = true)
        loadGenres(force = true)
        if (_search.value.query.isBlank() && _search.value.genreIds.isEmpty()) {
            loadPlayroomDefault()
        } else {
            search(_search.value.query, _search.value.filter, settings.value.includeAdult)
        }
    }

    fun search(
        query: String,
        filter: MediaType?,
        adult: Boolean,
        ignoreLanguageRule: Boolean = false
    ) {
        val typed = query.isNotBlank()
        val liveMatches = if (typed) iptvMatches(query) else emptyList()
        _search.update { current ->
            // Typing switches the Playroom back to text search; a blank query
            // keeps the genre selection, pruned to the selected media type.
            val kept = if (typed) emptySet() else pruneGenres(filter, current.genreIds)
            current.copy(
                query = query,
                filter = filter,
                genreIds = kept,
                channels = liveMatches,
                programs = if (typed) emptyList() else current.programs,
                letter = if (typed) null else current.letter
            )
        }
        searchJob?.cancel()
        epgSearchJob?.cancel()
        if (typed) {
            val guide = _epg.value.guide
            val typedQuery = query.trim()
            epgSearchJob = viewModelScope.launch {
                val hits = if (guide.isEmpty) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) { guide.searchPrograms(typedQuery) }
                }
                _search.update { if (it.query == query) it.copy(programs = hits) else it }
            }
        } else {
            _search.update { it.copy(programs = emptyList()) }
        }
        if (query.isBlank()) {
            val letter = _search.value.letter
            if (letter != null) browseLetter(letter) else loadPlayroomDefault()
            return
        }
        val local = localMatches(query, filter)
        if (!settings.value.hasTmdbAccess) {
            _search.update {
                it.copy(
                    results = local,
                    loading = false,
                    error = if (local.isEmpty() && liveMatches.isEmpty()) {
                        "TMDB access is offline. Add an API key or proxy URL in Settings."
                    } else null
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            _search.update { it.copy(loading = true, error = null) }
            try {
                val remote = repository.search(query, filter, adult)
                val language = settings.value.rankingLanguage()
                val visible = if (!ignoreLanguageRule && settings.value.languageRule == LanguageRule.ONLY) {
                    filterKnownOtherLanguages(remote, language)
                } else {
                    remote
                }
                _search.update {
                    it.copy(
                        query = query,
                        filter = filter,
                        results = mergeWithLibrary(rankByLanguage(visible, language), query, filter),
                        loading = false,
                        error = null
                    )
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                _search.update {
                    it.copy(
                        results = local,
                        loading = false,
                        error = t.message ?: "Search failed"
                    )
                }
            }
        }
    }

    fun loadPlayroomDefault() {
        searchJob?.cancel()
        val state = _search.value
        if (state.genreIds.isNotEmpty()) {
            loadGenreResults(state.filter, state.genreIds)
            return
        }
        // The type pills must also narrow the default page, not just text searches.
        val filter = state.filter
        val local = playroomResults(library.value.filter { filter == null || it.type == filter })
        if (!settings.value.hasTmdbAccess) {
            _search.update {
                it.copy(
                    query = "",
                    results = local,
                    channels = emptyList(),
                    loading = false,
                    error = if (local.isEmpty()) {
                        "Add a TMDB API key in Settings, or import a list to fill the Playroom"
                    } else null
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            _search.update { it.copy(query = "", channels = emptyList(), letter = null, loading = true, error = null) }
            try {
                val remote = when (filter) {
                    MediaType.MOVIE -> repository.topMovies()
                    MediaType.TV -> repository.topShows()
                    null -> repository.trending()
                }
                val results = mergeWithLibrary(
                    rankByLanguage(remote, settings.value.rankingLanguage()), "", filter
                )
                _search.update { it.copy(results = results, loading = false, error = null) }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                _search.update {
                    it.copy(
                        results = local,
                        loading = false,
                        error = if (local.isEmpty()) t.message ?: "Could not load trending" else null
                    )
                }
            }
        }
    }

    /** Alphabetical browse of the Cinema Hall, independent of TMDB availability. */
    fun browseLetter(letter: String?) {
        searchJob?.cancel()
        val state = _search.value
        if (letter == null) {
            _search.update { it.copy(letter = null, query = "") }
            loadPlayroomDefault()
            return
        }
        val matches = playroomResults(library.value)
            .filter { state.filter == null || it.type == state.filter }
            .filter { item ->
                val first = item.title.trim().firstOrNull()?.uppercaseChar()
                if (letter == "#") first == null || !first.isLetter() else first == letter.first()
            }
            .sortedBy { it.title.lowercase() }
        _search.update {
            it.copy(
                query = "",
                letter = letter,
                genreIds = emptySet(),
                channels = emptyList(),
                results = matches,
                loading = false,
                error = null
            )
        }
    }

    fun rememberSearch(query: String) {
        val clean = query.trim()
        if (clean.length < 2) return
        container.settingsStore.update { settings ->
            settings.copy(
                recentSearches = (listOf(clean) + settings.recentSearches)
                    .distinct()
                    .take(8)
            )
        }
    }

    fun clearRecentSearches() {
        container.settingsStore.update { it.copy(recentSearches = emptyList()) }
    }

    /** IPTV search runs against the cached playlists so it works while providers are offline. */
    private fun iptvMatches(query: String): List<LiveChannel> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return container.iptvStore.sources.value
            .filter { it.enabled }
            .flatMap { container.iptvStore.cachedChannels(it.id).orEmpty() }
            .filter { channel ->
                channel.name.lowercase().contains(needle) ||
                    channel.category?.lowercase()?.contains(needle) == true
            }
            .distinctBy { it.key }
            .take(24)
    }

    /** The playlist entry an EPG hit belongs to, so a programme result can be tuned. */
    fun channelForEpgId(epgId: String): LiveChannel? = container.iptvStore.sources.value
        .asSequence()
        .filter { it.enabled }
        .flatMap { container.iptvStore.cachedChannels(it.id).orEmpty().asSequence() }
        .firstOrNull { it.epgId.equals(epgId, ignoreCase = true) }

    private fun localMatches(query: String, filter: MediaType?): List<CatalogItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return playroomResults(library.value.filter { item ->
            (filter == null || item.type == filter) && item.title.lowercase().contains(needle)
        })
    }

    /**
     * The Playroom only surfaces titles that already exist: anything dated
     * after the current calendar year is dropped, even when TMDB lists it as
     * an upcoming release.
     */
    private fun playroomResults(items: List<CatalogItem>): List<CatalogItem> {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val level = activeProfile.value.maturity
        return items.filter { item ->
            val year = item.releaseDate?.take(4)?.toIntOrNull()
            item.allowedFor(level) && (year == null || year <= currentYear)
        }
    }

    /**
     * Loads the TMDB genre lists that back the Playroom chips. The bundled
     * [Genres] tables are only a fallback while the API is unreachable.
     */
    fun loadGenres(force: Boolean = false) {
        if (force) genresJob?.cancel()
        else if (genresJob?.isActive == true) return
        if (!force && genresLoadedFromApi) return
        if (!settings.value.hasTmdbAccess) {
            if (_genres.value.catalog.movie.isEmpty() && _genres.value.catalog.tv.isEmpty()) {
                _genres.value = GenreUiState(catalog = fallbackGenres())
            }
            return
        }
        genresJob = viewModelScope.launch {
            _genres.update { it.copy(loading = true, error = null) }
            runCatching { repository.genres(force) }
                .onSuccess { catalog ->
                    if (catalog.movie.isNotEmpty() || catalog.tv.isNotEmpty()) {
                        genresLoadedFromApi = true
                        _genres.value = GenreUiState(catalog = catalog, loading = false)
                    } else {
                        _genres.value = GenreUiState(
                            catalog = currentGenresOrFallback(),
                            loading = false,
                            error = "TMDB returned an empty genre list"
                        )
                    }
                }
                .onFailure { t ->
                    if (t is CancellationException) throw t
                    _genres.value = GenreUiState(
                        catalog = currentGenresOrFallback(),
                        loading = false,
                        error = t.message ?: "Could not load genres"
                    )
                }
        }
    }

    private fun currentGenresOrFallback(): GenreCatalog {
        val current = _genres.value.catalog
        return if (current.movie.isEmpty() && current.tv.isEmpty()) fallbackGenres() else current
    }

    private fun fallbackGenres(): GenreCatalog = GenreCatalog(
        movie = Genres.movie.map { Genre(it.key, it.value) }.sortedBy { it.name },
        tv = Genres.tv.map { Genre(it.key, it.value) }.sortedBy { it.name }
    )

    fun toggleGenre(id: Int) {
        val state = _search.value
        val next = if (id in state.genreIds) state.genreIds - id else state.genreIds + id
        _search.update { it.copy(genreIds = next, query = "", channels = emptyList(), letter = null) }
        if (next.isEmpty()) loadPlayroomDefault() else loadGenreResults(state.filter, next)
    }

    fun clearGenres() {
        if (_search.value.genreIds.isEmpty()) return
        _search.update { it.copy(genreIds = emptySet(), query = "", channels = emptyList(), letter = null) }
        loadPlayroomDefault()
    }

    /** Replaces the genre selection outright, used by the mood shortcuts. */
    fun setGenres(ids: Set<Int>) {
        val filter = _search.value.filter
        val next = pruneGenres(filter, ids)
        if (next == _search.value.genreIds) {
            clearGenres()
            return
        }
        _search.update { it.copy(genreIds = next, query = "", channels = emptyList(), letter = null) }
        if (next.isEmpty()) loadPlayroomDefault() else loadGenreResults(filter, next)
    }

    /** Genre results default to newest-first; popularity is the alternate order. */
    fun setGenreSort(sort: GenreSort) {
        if (_search.value.genreSort == sort) return
        _search.update { it.copy(genreSort = sort) }
        val state = _search.value
        if (state.genreIds.isNotEmpty()) loadGenreResults(state.filter, state.genreIds)
    }

    private fun loadGenreResults(filter: MediaType?, requested: Set<Int>) {
        searchJob?.cancel()
        val genreIds = pruneGenres(filter, requested)
        if (genreIds.isEmpty()) {
            _search.update { it.copy(genreIds = emptySet(), results = emptyList(), loading = false, error = null) }
            loadPlayroomDefault()
            return
        }
        if (genreIds != requested) _search.update { it.copy(genreIds = genreIds) }
        if (!settings.value.hasTmdbAccess) {
            _search.update {
                it.copy(
                    query = "",
                    results = localGenreMatches(filter, genreIds),
                    loading = false,
                    error = null
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            val sort = _search.value.genreSort
            _search.update { it.copy(query = "", channels = emptyList(), letter = null, loading = true, error = null) }
            try {
                val remote = repository.discoverByGenres(
                    filter,
                    genreIds,
                    settings.value.includeAdult,
                    sort
                )
                _search.update {
                    it.copy(
                        results = playroomResults(rankByLanguage(remote, settings.value.rankingLanguage())),
                        loading = false,
                        error = null
                    )
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                _search.update {
                    it.copy(
                        results = emptyList(),
                        loading = false,
                        error = t.message ?: "Genre browse failed"
                    )
                }
            }
        }
    }

    /** Drops ids that the selected media type does not publish. */
    private fun pruneGenres(filter: MediaType?, ids: Set<Int>): Set<Int> {
        if (ids.isEmpty()) return emptySet()
        val allowed = _genres.value.catalog.forScope(filter)
        if (allowed.isEmpty()) return ids
        val allowedIds = allowed.mapTo(mutableSetOf()) { it.id }
        return ids.filterTo(mutableSetOf()) { it in allowedIds }
    }

    private fun localGenreMatches(filter: MediaType?, ids: Set<Int>): List<CatalogItem> {
        val names = ids.mapNotNull { _genres.value.catalog.nameFor(filter, it) }.toSet()
        if (names.isEmpty()) return emptyList()
        return playroomResults(library.value.filter { item ->
            (filter == null || item.type == filter) && item.genres.any { it in names }
        })
    }

    private fun mergeWithLibrary(remote: List<CatalogItem>, query: String, filter: MediaType?): List<CatalogItem> {
        val local = if (query.isBlank()) {
            playroomResults(library.value.filter { filter == null || it.type == filter })
        } else {
            localMatches(query, filter)
        }
        if (local.isEmpty()) return playroomResults(remote)
        val localKeys = local.map { it.key }.toSet()
        return playroomResults(local + remote.filterNot { it.key in localKeys })
    }

    suspend fun loadDetails(tmdbId: Int, type: MediaType): CatalogItem =
        repository.loadDetails(tmdbId, type)

    /** Title logos resolved for hero picks that arrived from list endpoints. */
    private val _heroLogos = MutableStateFlow<Map<String, String>>(emptyMap())
    val heroLogos: StateFlow<Map<String, String>> = _heroLogos.asStateFlow()
    private val heroLogoLookups = ConcurrentHashMap<String, Boolean>()

    /**
     * List rows carry no title logo, so the hero stays on the text title until
     * the detail record is fetched once per title. A miss is not retried in a
     * loop; a failed fetch is, since the next carousel visit may succeed.
     */
    fun ensureHeroLogo(item: CatalogItem) {
        if (item.tmdbId <= 0 || !item.logoUrl.isNullOrBlank()) return
        if (!settings.value.hasTmdbAccess) return
        if (heroLogoLookups.putIfAbsent(item.key, true) != null) return
        viewModelScope.launch {
            val detail = runCatching { loadDetails(item.tmdbId, item.type) }.getOrNull()
                ?: run {
                    heroLogoLookups.remove(item.key)
                    return@launch
                }
            val logo = detail.logoUrl?.takeIf { it.isNotBlank() }
            if (logo != null) _heroLogos.update { it + (item.key to logo) }
        }
    }

    suspend fun loadSeasonEpisodes(item: CatalogItem, seasonNumber: Int) =
        repository.loadSeasonEpisodes(item, seasonNumber)

    /** "More like this" for one title, filtered through the active profile's rules. */
    suspend fun loadRelated(item: CatalogItem): List<CatalogItem> =
        runCatching { repository.recommendations(item.tmdbId, item.type) }
            .getOrDefault(emptyList())
            .let { rankByLanguage(it, settings.value.rankingLanguage()) }
            .filter { it.key != item.key && kidsMode.allows(it, activeProfile.value) }
            .take(14)

    fun importUrl(url: String) {
        if (_importState.value.phase == ImportPhase.RUNNING) return
        // Fetch, JSON parsing and catalog merging are all CPU/IO heavy; keeping
        // them off the main dispatcher prevents an ANR on large manifests.
        viewModelScope.launch(Dispatchers.IO) {
            _importState.value = ImportUiState(ImportPhase.RUNNING, "Starting import…")
            runCatching {
                repository.importUrl(url) { message ->
                    _importState.update { it.copy(phase = ImportPhase.RUNNING, message = message) }
                }
            }.onSuccess { result ->
                _importState.update {
                    it.copy(
                        phase = ImportPhase.SUCCESS,
                        message = "${result.sourceName}: ${result.total} titles scanned, ${result.added} new"
                    )
                }
            }.onFailure { t ->
                _importState.update {
                    it.copy(phase = ImportPhase.ERROR, message = t.message ?: "Import failed")
                }
            }
        }
    }

    fun importJson(text: String) {
        if (_importState.value.phase == ImportPhase.RUNNING) return
        viewModelScope.launch(Dispatchers.IO) {
            _importState.value = ImportUiState(ImportPhase.RUNNING, "Parsing JSON manifest…")
            runCatching { repository.importPastedJson(text) }
                .onSuccess { result ->
                    _importState.update {
                        it.copy(
                            phase = ImportPhase.SUCCESS,
                            message = "JSON Manifest: ${result.total} titles, ${result.added} new"
                        )
                    }
                }
                .onFailure { t ->
                    _importState.update {
                        it.copy(phase = ImportPhase.ERROR, message = t.message ?: "JSON import failed")
                    }
                }
        }
    }

    fun syncAllSources() {
        if (_importState.value.phase == ImportPhase.RUNNING) return
        viewModelScope.launch(Dispatchers.IO) {
            _importState.value = ImportUiState(ImportPhase.RUNNING, "Syncing sources…")
            runCatching {
                repository.syncAllSources { message ->
                    _importState.update { it.copy(phase = ImportPhase.RUNNING, message = message) }
                }
            }.onSuccess { result ->
                _importState.update {
                    it.copy(
                        phase = ImportPhase.SUCCESS,
                        message = "Sync complete · ${result.added} new titles" +
                            if (result.failedSources > 0) " · ${result.failedSources} sources failed" else ""
                    )
                }
            }.onFailure { t ->
                _importState.update {
                    it.copy(phase = ImportPhase.ERROR, message = t.message ?: "Sync failed")
                }
            }
        }
    }

    fun syncCadence(target: SyncTarget): SyncCadence = when (target) {
        SyncTarget.IMPORTS -> settings.value.importsSync
        SyncTarget.IPTV -> settings.value.iptvSync
        SyncTarget.ADDONS -> settings.value.addonsSync
    }

    fun setSyncCadence(target: SyncTarget, cadence: SyncCadence) {
        if (syncCadence(target) == cadence) return
        saveSettings {
            when (target) {
                SyncTarget.IMPORTS -> it.copy(importsSync = cadence)
                SyncTarget.IPTV -> it.copy(iptvSync = cadence)
                SyncTarget.ADDONS -> it.copy(addonsSync = cadence)
            }
        }
        if (cadence.shouldRun(startup = true, container.settingsStore.lastRefresh(target))) {
            launchSync(target)
        }
    }

    /** Forces one refresh for [target], ignoring its cadence. */
    fun syncNow(target: SyncTarget) = launchSync(target)

    private fun runDueSyncs(startup: Boolean) {
        val now = System.currentTimeMillis()
        SyncTarget.entries.forEach { target ->
            val cadence = syncCadence(target)
            if (cadence.shouldRun(startup, container.settingsStore.lastRefresh(target), now)) {
                launchSync(target)
            }
        }
    }

    private fun launchSync(target: SyncTarget) {
        synchronized(syncGuard) {
            if (!activeSyncs.add(target)) return
        }
        _sync.update { it.copy(running = it.running + target, errors = it.errors - target) }
        viewModelScope.launch(Dispatchers.IO) {
            val failure = runCatching { performSync(target) }.exceptionOrNull()
            val stamp = System.currentTimeMillis()
            container.settingsStore.recordRefresh(target, stamp)
            synchronized(syncGuard) { activeSyncs.remove(target) }
            _sync.update {
                it.copy(
                    running = it.running - target,
                    lastRun = it.lastRun + (target to stamp),
                    errors = if (failure == null) {
                        it.errors - target
                    } else {
                        it.errors + (target to (failure.message ?: "Refresh failed"))
                    }
                )
            }
        }
    }

    private suspend fun performSync(target: SyncTarget) {
        when (target) {
            SyncTarget.IMPORTS -> {
                val result = repository.syncAllSources()
                if (result.failedSources > 0) {
                    throw IOException("${result.failedSources} imported list(s) failed to refresh")
                }
            }
            SyncTarget.ADDONS -> repository.syncAddonsOnStartup()
            SyncTarget.IPTV -> {
                val enabled = container.iptvStore.sources.value.filter { it.enabled }
                var failures = 0
                enabled.forEach { source ->
                    runCatching { repository.refreshIptvSource(source.id) }
                        .onFailure { failures++ }
                }
                reloadLive(force = true, refreshPlaylists = false)
                refreshEpg(force = true)
                if (failures > 0) {
                    throw IOException("$failures of ${enabled.size} playlist(s) failed to refresh")
                }
            }
        }
    }

    fun importItem(item: CatalogItem) {
        if (_quickImport.value.busy) return
        publishQuickImport(
            QuickImportState(key = item.key, busy = true, message = "Importing ${item.title}…")
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val imported = repository.importSingle(item)
                publishQuickImport(
                    QuickImportState(
                        key = item.key,
                        busy = false,
                        message = "${imported.title} added to your Cinema Hall",
                        success = true
                    )
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                publishQuickImport(
                    QuickImportState(
                        key = item.key,
                        busy = false,
                        message = "Import failed: ${t.message ?: "unknown error"}",
                        success = false
                    )
                )
            }
        }
    }

    private fun publishQuickImport(state: QuickImportState) {
        quickImportDismissJob?.cancel()
        _quickImport.value = state
        if (!state.busy && state.message != null) {
            quickImportDismissJob = viewModelScope.launch {
                delay(6_000)
                _quickImport.update { if (it.busy) it else it.copy(message = null) }
            }
        }
    }

    fun removeFromLibrary(item: CatalogItem) {
        container.libraryStore.remove(item.key)
    }

    fun toggleFavorite(item: CatalogItem): Boolean = container.libraryStore.toggleFavorite(item)

    fun importFavorites(items: List<CatalogItem>): Int {
        val added = container.libraryStore.addFavorites(items)
        if (added > 0) sniffFavoriteEpisodes()
        return added
    }

    /**
     * Fetches aired-episode lists for favorited series only, so the Favorites page can
     * report the new-episode count without re-querying on every recomposition.
     */
    fun sniffFavoriteEpisodes(force: Boolean = false) {
        if (sniffJob?.isActive == true) return
        if (!settings.value.hasTmdbAccess) return
        val shows = favorites.value.filter { it.type == MediaType.TV }
        if (shows.isEmpty()) return
        val cacheFresh = shows.all { show ->
            _sniffedSeasons.value[show.key]?.any { it.episodes.isNotEmpty() } == true
        }
        if (!force && cacheFresh && System.currentTimeMillis() - lastSniffAt < SNIFF_TTL_MS) return
        lastSniffAt = System.currentTimeMillis()
        sniffJob = viewModelScope.launch {
            for (show in shows) {
                runCatching { loadTrackedSeasons(show) }
                    .onSuccess { seasons ->
                        if (seasons.any { it.episodes.isNotEmpty() }) {
                            _sniffedSeasons.update { current ->
                                val merged = current + (show.key to seasons)
                                if (merged.size <= MAX_SNIFF_CACHE) {
                                    merged
                                } else {
                                    // Long sessions over big favourite lists would
                                    // otherwise retain every episode tree forever.
                                    merged.entries
                                        .drop(merged.size - MAX_SNIFF_CACHE)
                                        .associate { it.key to it.value }
                                }
                            }
                        }
                    }
                delay(120)
            }
        }
    }

    /**
     * Older seasons are cached once; the newest season is refetched on every pass so
     * freshly aired episodes show up. Missing seasons are loaded on demand.
     */
    private suspend fun loadTrackedSeasons(show: CatalogItem): List<Season> {
        val detail = repository.loadDetails(show.tmdbId, show.type)
        val cached = _sniffedSeasons.value[show.key].orEmpty().associateBy { it.number }
        val latest = detail.seasons.filter { it.number > 0 }.maxOfOrNull { it.number } ?: 0
        return detail.seasons.map { season ->
            val previous = cached[season.number]
            when {
                previous != null && previous.episodes.isNotEmpty() && season.number != latest ->
                    season.copy(
                        episodes = previous.episodes,
                        episodeCount = maxOf(season.episodeCount, previous.episodeCount)
                    )
                season.episodes.isNotEmpty() -> season
                else -> {
                    delay(80)
                    val episodes = runCatching { repository.loadSeasonEpisodes(detail, season.number) }
                        .getOrDefault(emptyList())
                    when {
                        episodes.isNotEmpty() -> season.copy(
                            episodes = episodes,
                            episodeCount = maxOf(season.episodeCount, episodes.size)
                        )
                        previous != null -> previous
                        else -> season
                    }
                }
            }
        }
    }

    fun toggleSeasonWatched(item: CatalogItem, season: Season, episodes: List<Episode>) {
        val today = todayStamp()
        val aired = (episodes.ifEmpty { season.episodes })
            .filter { it.airDate.isNullOrBlank() || it.airDate <= today }
        if (aired.isEmpty()) return
        val pairs = aired.map { season.number to it.number }.toSet()
        val watched = watchedPairs(item.key)
        container.libraryStore.setEpisodesWatched(item, pairs, watched = !pairs.all { it in watched })
    }

    /**
     * Marks every aired episode of the seasons before [beforeSeason] as watched, or
     * clears those marks when the whole back catalogue is already marked.
     */
    fun togglePastSeasonsWatched(
        item: CatalogItem,
        beforeSeason: Int,
        onDone: (marked: Boolean, count: Int) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            val today = todayStamp()
            val pairs = mutableSetOf<Pair<Int, Int>>()
            for (season in item.seasons.filter { it.number in 1 until beforeSeason }) {
                val cached = _sniffedSeasons.value[item.key]
                    ?.firstOrNull { it.number == season.number }
                    ?.episodes
                    .orEmpty()
                val episodes = when {
                    cached.isNotEmpty() -> cached
                    season.episodes.isNotEmpty() -> season.episodes
                    else -> runCatching { repository.loadSeasonEpisodes(item, season.number) }
                        .getOrDefault(emptyList())
                }
                val aired = episodes.filter { it.airDate.isNullOrBlank() || it.airDate <= today }
                when {
                    aired.isNotEmpty() -> aired.forEach { pairs += season.number to it.number }
                    // Without episode metadata a past season is assumed fully aired.
                    episodes.isEmpty() && season.episodeCount > 0 ->
                        (1..season.episodeCount).forEach { pairs += season.number to it }
                }
            }
            if (pairs.isEmpty()) {
                onDone(false, 0)
                return@launch
            }
            val watched = watchedPairs(item.key)
            val alreadyMarked = pairs.all { it in watched }
            container.libraryStore.setEpisodesWatched(item, pairs, watched = !alreadyMarked)
            onDone(!alreadyMarked, pairs.size)
        }
    }

    private fun watchedPairs(itemKey: String): Set<Pair<Int, Int>> {
        val played = progress.value.values
            .filter { it.itemKey == itemKey && it.season != null && it.episode != null && it.fraction >= 0.9f }
            .mapTo(mutableSetOf()) { (it.season ?: 0) to (it.episode ?: 0) }
        return played + watchedEpisodesFor(itemKey, watchedEpisodes.value)
    }

    private companion object {
        const val SNIFF_TTL_MS = 30 * 60 * 1000L
        const val MAX_SNIFF_CACHE = 64
        const val EPG_TTL_MS = 15 * 60 * 1000L
        const val SYNC_CHECK_INTERVAL_MS = 10 * 60 * 1000L
    }

    fun removeSource(source: ImportSource, deleteItems: Boolean = false): Int =
        container.libraryStore.removeSource(source.id, deleteItems)

    private var liveJob: Job? = null
    private var epgJob: Job? = null

    fun refreshLive(force: Boolean = false) = reloadLive(force = force, refreshPlaylists = force)

    /**
     * [force] rebuilds the catalog list; [refreshPlaylists] also re-fetches every
     * IPTV source. Source edits pass `refreshPlaylists = false` because the
     * playlist was just downloaded and cached.
     */
    private fun reloadLive(force: Boolean, refreshPlaylists: Boolean) {
        val state = _live.value
        if (state.loading && !force) return
        if (!force && state.catalogs.isNotEmpty()) {
            val selected = state.selected ?: state.catalogs.first()
            loadLiveCatalog(selected, state.filters, reload = false)
            refreshEpg()
            return
        }
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            _live.update { it.copy(loading = true, message = null) }
            runCatching { repository.liveCatalogs(refreshIptv = refreshPlaylists) }
                .onSuccess { load ->
                    val ordered = container.livePrefs.applyCatalogOrder(load.catalogs)
                    val previous = _live.value
                    val selected = ordered.firstOrNull { it.key == previous.selected?.key }
                        ?: ordered.firstOrNull()
                    val keepFilters = selected?.key == previous.selected?.key
                    val filters = if (keepFilters) previous.filters else emptyMap()
                    _live.update {
                        it.copy(
                            catalogs = ordered,
                            selected = selected,
                            filters = filters,
                            channels = emptyList(),
                            loading = selected == null,
                            message = when {
                                load.failures.isNotEmpty() -> load.failures.joinToString(" · ")
                                load.catalogs.isEmpty() ->
                                    "No live catalogs found. Add a live addon or an IPTV playlist in Settings."
                                else -> null
                            }
                        )
                    }
                    if (selected != null) {
                        loadLiveCatalog(selected, filters, reload = true)
                    }
                    refreshEpg()
                }
                .onFailure { t ->
                    _live.update { it.copy(loading = false, message = t.message ?: "Live catalogs failed") }
                }
        }
    }

    fun refreshEpg(force: Boolean = false) {
        if (epgJob?.isActive == true) return
        val current = _epg.value
        val stale = current.loadedAt == 0L ||
            System.currentTimeMillis() - current.loadedAt >= EPG_TTL_MS
        if (!force && !stale) return
        loadEpg(force)
    }

    /**
     * Applies a per-source guide correction and rebuilds now/next from the
     * guides already in the client cache, so each tap is instant.
     */
    fun setIptvEpgShift(id: String, minutes: Int) {
        if (!repository.setIptvEpgShift(id, minutes)) return
        loadEpg(force = false)
    }

    /** Returns a source to automatic detection after a manual correction. */
    fun resetIptvEpgShift(id: String) {
        if (!repository.resetIptvEpgShift(id)) return
        loadEpg(force = false)
    }

    private fun loadEpg(force: Boolean) {
        val hasGuide = iptvSources.value.any { it.enabled && it.effectiveEpgUrl.isNotBlank() }
        if (!hasGuide) {
            if (_epg.value.guide.isEmpty) _epg.update { it.copy(loading = false, message = null) }
            return
        }
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            _epg.update { it.copy(loading = true, message = null) }
            runCatching { repository.iptvEpg(force) }
                .onSuccess { load ->
                    val sources = iptvSources.value.associateBy { it.id }
                    _epg.value = EpgUiState(
                        guide = load.guide,
                        loading = false,
                        message = load.failures.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                        loadedAt = System.currentTimeMillis(),
                        autoShifts = load.autoShifts,
                        shiftNotices = load.autoShifts
                            .filterValues { it != 0 }
                            .map { (id, shift) ->
                                val name = sources[id]?.name?.takeIf { it.isNotBlank() } ?: "Guide"
                                "$name: guide is ${epgShiftLabel(shift)} off — adjusted"
                            },
                        report = load.report?.summary
                    )
                }
                .onFailure { t ->
                    _epg.update { it.copy(loading = false, message = t.message ?: "EPG failed") }
                }
        }
    }

    suspend fun addIptvSource(location: String, name: String, epgUrl: String): String? =
        runCatching { repository.addIptvSource(location, name, epgUrl) }
            .fold(
                onSuccess = {
                    reloadLive(force = true, refreshPlaylists = false)
                    refreshEpg(force = true)
                    null
                },
                onFailure = { t -> t.message ?: "Could not add this IPTV source" }
            )

    suspend fun updateIptvSource(
        id: String,
        location: String,
        name: String,
        epgUrl: String
    ): String? =
        runCatching { repository.updateIptvSource(id, location, name, epgUrl) }
            .fold(
                onSuccess = {
                    reloadLive(force = true, refreshPlaylists = false)
                    refreshEpg(force = true)
                    null
                },
                onFailure = { t -> t.message ?: "Could not update this IPTV source" }
            )

    suspend fun refreshIptvSourceNow(id: String): String? =
        runCatching { repository.refreshIptvSource(id) }
            .fold(
                onSuccess = {
                    reloadLive(force = true, refreshPlaylists = false)
                    refreshEpg(force = true)
                    null
                },
                onFailure = { t -> t.message ?: "Could not refresh this IPTV source" }
            )

    fun removeIptvSource(id: String) {
        repository.removeIptvSource(id)
        _epg.value = EpgUiState()
        reloadLive(force = true, refreshPlaylists = false)
        refreshEpg(force = true)
    }

    /** Pauses a playlist without deleting it; its cached channels drop out of Live. */
    fun setIptvEnabled(id: String, enabled: Boolean) {
        val source = container.iptvStore.source(id) ?: return
        if (source.enabled == enabled) return
        container.iptvStore.put(source.copy(enabled = enabled))
        reloadLive(force = true, refreshPlaylists = false)
        refreshEpg(force = true)
    }

    fun selectLiveCatalog(catalog: LiveCatalog, filters: Map<String, String> = emptyMap()) {
        loadLiveCatalog(catalog, filters, reload = false)
    }

    /** Sets (or clears, with a blank value) one selection group and reloads the catalog. */
    fun setLiveFilter(name: String, value: String) {
        val state = _live.value
        val catalog = state.selected ?: return
        val filters = if (value.isBlank()) state.filters - name else state.filters + (name to value)
        if (filters == state.filters) return
        loadLiveCatalog(catalog, filters, reload = true)
    }

    fun clearLiveFilters() {
        val state = _live.value
        val catalog = state.selected ?: return
        if (state.filters.isEmpty()) return
        loadLiveCatalog(catalog, emptyMap(), reload = true)
    }

    private fun loadLiveCatalog(
        catalog: LiveCatalog,
        filters: Map<String, String>,
        reload: Boolean
    ) {
        val state = _live.value
        if (!reload && state.selected?.key == catalog.key &&
            state.filters == filters && state.channels.isNotEmpty()
        ) {
            return
        }
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            _live.update {
                it.copy(
                    selected = catalog,
                    filters = filters,
                    loading = true,
                    channels = emptyList(),
                    message = null
                )
            }
            runCatching { repository.liveChannels(catalog, filters) }
                .onSuccess { channels ->
                    val resolved = withLiveOrder(withLiveExtras(catalog))
                    val orderedChannels = container.livePrefs.applyChannelOrder(catalog.key, channels)
                    _live.update {
                        it.copy(
                            catalogs = it.catalogs.map { entry ->
                                if (entry.key == resolved.key) resolved else entry
                            },
                            selected = if (it.selected?.key == resolved.key) resolved else it.selected,
                            channels = orderedChannels,
                            loading = false,
                            message = if (orderedChannels.isEmpty()) {
                                "${resolved.name}: this selection returned no channels."
                            } else null
                        )
                    }
                    refreshEpg()
                }
                .onFailure { t ->
                    _live.update {
                        it.copy(
                            loading = false,
                            message = "${catalog.name}: ${t.message ?: "catalog failed"}"
                        )
                    }
                }
        }
    }

    /** IPTV categories come from the parsed playlist, not the (empty) manifest. */
    private fun withLiveExtras(catalog: LiveCatalog): LiveCatalog {
        if (catalog.sourceKind != LiveSourceKind.IPTV) return catalog
        val groups = repository.iptvGroups(catalog.iptvSourceId.orEmpty())
        if (groups.isEmpty()) return catalog
        return catalog.copy(
            extras = catalog.extras.filterNot { it.name == "group-title" } +
                CatalogExtra(name = "group-title", options = groups)
        )
    }

    /** Re-applies the viewer's group order after a catalog (re)load. */
    private fun withLiveOrder(catalog: LiveCatalog): LiveCatalog = catalog.copy(
        extras = catalog.extras.map { container.livePrefs.applyGroupOrder(catalog, it) }
    )

    fun toggleLiveFavorite(channel: LiveChannel): Boolean =
        container.livePrefs.toggleFavorite(channel.key)

    fun moveLiveChannel(channel: LiveChannel, delta: Int) {
        val state = _live.value
        val catalog = state.selected ?: return
        val index = state.channels.indexOfFirst { it.key == channel.key }
        val target = index + delta
        if (index < 0 || target !in state.channels.indices) return
        val moved = state.channels.toMutableList().apply { add(target, removeAt(index)) }
        container.livePrefs.setChannelOrder(catalog.key, moved.map { it.key })
        _live.update { it.copy(channels = moved) }
    }

    fun moveLiveGroup(extraName: String, group: String, delta: Int) {
        val state = _live.value
        val catalog = state.selected ?: return
        val extra = catalog.extras.firstOrNull { it.name == extraName } ?: return
        val index = extra.options.indexOf(group)
        val target = index + delta
        if (index < 0 || target !in extra.options.indices) return
        val moved = extra.options.toMutableList().apply { add(target, removeAt(index)) }
        container.livePrefs.setGroupOrder(catalog.key, extraName, moved)
        val updated = catalog.copy(
            extras = catalog.extras.map {
                if (it.name == extraName) it.copy(options = moved) else it
            }
        )
        _live.update {
            it.copy(
                catalogs = it.catalogs.map { entry ->
                    if (entry.key == catalog.key) updated else entry
                },
                selected = if (it.selected?.key == catalog.key) updated else it.selected
            )
        }
    }

    fun moveLiveCatalog(catalog: LiveCatalog, delta: Int) {
        val state = _live.value
        val index = state.catalogs.indexOfFirst { it.key == catalog.key }
        val target = index + delta
        if (index < 0 || target !in state.catalogs.indices) return
        val moved = state.catalogs.toMutableList().apply { add(target, removeAt(index)) }
        container.livePrefs.setCatalogOrder(moved.map { it.key })
        _live.update { it.copy(catalogs = moved) }
    }

    fun resolveLive(channel: LiveChannel, onReady: (PlaybackOption?) -> Unit) {
        if (_live.value.resolving != null) return
        viewModelScope.launch {
            _live.update { it.copy(resolving = channel.key, message = null) }
            val result = runCatching { repository.liveStream(channel) }.getOrNull()
            val option = result?.option
            var externalScheme: String? = null
            if (option == null && result?.externalOnly == true) {
                result.externalUrl?.takeIf { it.isNotBlank() }?.let { externalUrl ->
                    val parsed = StreamUrls.parse(externalUrl)
                    val launched = ExternalIntents.launch(
                        getApplication<Application>(),
                        parsed.url,
                        headers = parsed.headers,
                        userAgent = settings.value.userAgentString
                    )
                    if (launched) externalScheme = parsed.url.substringBefore("://").uppercase()
                }
            }
            when {
                option != null || externalScheme != null -> recordChannelSuccess(channel)
                // The link itself is fine; this device just lacks the DRM module.
                result?.deviceLimited == true -> Unit
                else -> recordChannelFailure(channel)
            }
            _live.update {
                it.copy(
                    resolving = null,
                    message = when {
                        option != null -> null
                        externalScheme != null ->
                            "${channel.name}: opening this $externalScheme stream in an external player."
                        result?.externalOnly == true ->
                            "${channel.name}: no external player accepted this stream."
                        result?.error != null -> "${channel.name}: ${result.error}"
                        result?.externalUrl != null ->
                            "${channel.name}: no direct stream — the addon only links a watch page."
                        else -> "${channel.name}: the addon returned no playable links."
                    }
                )
            }
            onReady(option)
        }
    }

    /** Returns true when this failure just pushed the channel over the dead threshold. */
    fun recordChannelFailure(channel: LiveChannel): Boolean =
        container.channelHealth.recordFailure(channel.key)

    fun recordChannelSuccess(channel: LiveChannel) =
        container.channelHealth.recordSuccess(channel.key)

    fun forgetDeadChannels(keys: Collection<String>) = container.channelHealth.forget(keys)

    fun saveSettings(transform: (AppSettings) -> AppSettings) {
        container.settingsStore.update(transform)
    }

    /**
     * Reads the latest GitHub release and keeps it only when its tag is newer
     * than the installed iteration. Called at startup (when enabled) and by the
     * About → Updates row.
     */
    fun checkForUpdates() {
        if (_update.value.checking || _update.value.downloading) return
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _update.update { it.copy(checking = true, error = null, installStarted = false) }
            runCatching { updateChecker.latest() }
                .onSuccess { release ->
                    val newer = release.asset != null &&
                        isNewerVersion(release.tag, BuildConfig.ITERATION)
                    Log.d(
                        "StreamLine:update",
                        "latest=${release.tag} current=${BuildConfig.ITERATION} newer=$newer"
                    )
                    _update.update {
                        it.copy(
                            checking = false,
                            release = if (newer) release else null,
                            latestTag = release.tag,
                            upToDate = !newer,
                            noRelease = false
                        )
                    }
                }
                .onFailure { error ->
                    Log.w("StreamLine:update", "check failed: ${error.message}")
                    val missing = error is NoPublishedReleaseException
                    _update.update {
                        it.copy(
                            checking = false,
                            noRelease = missing,
                            error = if (missing) null else
                                error.message ?: "Could not reach GitHub"
                        )
                    }
                }
        }
    }

    /** Downloads the release APK for this flavor and opens the system installer. */
    fun downloadAndInstallUpdate() {
        val asset = _update.value.release?.asset ?: return
        if (_update.value.downloading) return
        viewModelScope.launch {
            _update.update {
                it.copy(downloading = true, progress = 0f, error = null, installStarted = false)
            }
            runCatching {
                val apk = updateChecker.download(asset) { fraction ->
                    _update.update { it.copy(progress = fraction) }
                }
                updateChecker.install(apk)
            }.onSuccess {
                _update.update { it.copy(downloading = false, progress = 1f, installStarted = true) }
            }.onFailure { error ->
                Log.w("StreamLine:update", "download failed: ${error.message}")
                _update.update {
                    it.copy(downloading = false, error = error.message ?: "Download failed")
                }
            }
        }
    }

    /**
     * Stores the badge document link and pulls it immediately so the next source
     * list already has badges. Blank clears the document and the setting.
     */
    fun saveBadgeSource(raw: String): String? {
        val target = normalizeBadgeSource(raw) ?: return "Enter an http(s) JSON badge link"
        saveSettings { it.copy(badgeSourceUrl = target) }
        viewModelScope.launch { container.badges.refresh(target, force = true) }
        return null
    }

    fun refreshBadges() {
        val target = settings.value.badgeSourceUrl.trim()
        if (target.isBlank()) return
        viewModelScope.launch { container.badges.refresh(target, force = true) }
    }

    fun setIncludeAdult(include: Boolean) {
        if (settings.value.includeAdult == include) return
        saveSettings { it.copy(includeAdult = include) }
        val state = _search.value
        when {
            state.query.isNotBlank() -> search(state.query, state.filter, include)
            state.genreIds.isNotEmpty() -> loadGenreResults(state.filter, state.genreIds)
            else -> loadPlayroomDefault()
        }
    }

    fun setDnsMode(mode: DnsMode) = saveSettings { it.copy(dnsMode = mode) }

    fun addCustomDnsServer(raw: String): String? {
        val entry = raw.trim()
        if (entry.isEmpty()) return "Enter a DNS server"
        if (CinemaDns.parseServer(entry) == null) {
            return "Use an IP (1.1.1.1), IP:port or an https:// DoH URL"
        }
        if (settings.value.customDnsServers.any { it.equals(entry, ignoreCase = true) }) {
            return "That DNS server is already in the list"
        }
        if (settings.value.customDnsServers.size >= 6) {
            return "Limit reached · remove a server first"
        }
        saveSettings {
            it.copy(
                dnsMode = DnsMode.CUSTOM,
                customDnsServers = it.customDnsServers + entry
            )
        }
        return null
    }

    fun removeCustomDnsServer(entry: String) = saveSettings {
        it.copy(customDnsServers = it.customDnsServers.filterNot { server -> server == entry })
    }

    fun testDns() {
        if (_dnsTest.value.busy) return
        viewModelScope.launch {
            _dnsTest.value = DnsTestState(busy = true)
            val outcome = withContext(Dispatchers.IO) { CinemaDns.test(settings.value) }
            _dnsTest.value = DnsTestState(
                busy = false,
                message = outcome.message,
                success = outcome.success
            )
        }
    }

    fun testTmdb() {
        if (_tmdbTest.value.busy) return
        viewModelScope.launch {
            _tmdbTest.value = TmdbTestState(busy = true)
            runCatching { repository.testTmdb() }
                .onSuccess { message ->
                    _tmdbTest.value = TmdbTestState(busy = false, message = message, success = true)
                    _tmdbVerify.value = TmdbVerifyState(status = TmdbVerifyStatus.OK, message = message)
                }
                .onFailure { t ->
                    _tmdbTest.value = TmdbTestState(
                        busy = false,
                        message = t.message ?: "Connection failed",
                        success = false
                    )
                    _tmdbVerify.value = TmdbVerifyState(
                        status = TmdbVerifyStatus.FAILED,
                        message = t.message ?: "Verification failed"
                    )
                }
        }
    }

    /**
     * Proves the stored TMDB key (or proxy) is valid with a lightweight
     * `configuration` call. Runs on startup and after every TMDB settings change;
     * the result drives the top-bar status pill.
     */
    fun verifyTmdb() {
        if (!settings.value.hasTmdbAccess) {
            _tmdbVerify.value = TmdbVerifyState(status = TmdbVerifyStatus.NONE)
            return
        }
        if (_tmdbVerify.value.status == TmdbVerifyStatus.VERIFYING) return
        viewModelScope.launch {
            _tmdbVerify.value = TmdbVerifyState(status = TmdbVerifyStatus.VERIFYING)
            runCatching { repository.testTmdb() }
                .onSuccess { message ->
                    _tmdbVerify.value = TmdbVerifyState(status = TmdbVerifyStatus.OK, message = message)
                }
                .onFailure { t ->
                    _tmdbVerify.value = TmdbVerifyState(
                        status = TmdbVerifyStatus.FAILED,
                        message = t.message ?: "Verification failed"
                    )
                }
        }
    }

    fun addAddon(name: String, baseUrl: String, logo: String = ""): String? {
        val normalized = AddonSource.normalize(baseUrl)
            ?: return "Enter an addon URL (https://host or …/manifest.json)"
        val id = normalized.trimEnd('/').lowercase()
        if (settings.value.addons.any { it.id == id }) return "That addon is already registered"
        if (settings.value.addons.size >= 8) return "Limit reached · remove an addon first"
        saveSettings {
            it.copy(
                addons = it.addons + AddonSource(
                    url = normalized,
                    name = name.trim(),
                    logo = logo.trim()
                )
            )
        }
        return null
    }

    fun toggleAddon(id: String) = saveSettings {
        it.copy(
            addons = it.addons.map { addon ->
                if (addon.id == id) addon.copy(enabled = !addon.enabled) else addon
            }
        )
    }

    /** Reorders addons; the list order is the stream-search priority order. */
    fun moveAddon(id: String, delta: Int) = saveSettings { current ->
        val list = current.addons.toMutableList()
        val index = list.indexOfFirst { addon -> addon.id == id }
        val target = index + delta
        if (index < 0 || target !in list.indices) {
            current
        } else {
            val moved = list.removeAt(index)
            list.add(target, moved)
            current.copy(addons = list.toList())
        }
    }

    fun deleteAddon(id: String) = saveSettings {
        it.copy(addons = it.addons.filterNot { addon -> addon.id == id })
    }

    fun removeProgressForItem(itemKey: String) = container.libraryStore.removeProgressForItem(itemKey)

    fun loadExternalPlayers() {
        viewModelScope.launch {
            val players = withContext(Dispatchers.IO) { repository.installedExternalPlayers() }
            _externalPlayers.value = players
        }
    }

    fun playbackPlan(
        item: CatalogItem,
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null
    ): PlaybackPlan = repository.playbackPlan(item, season, episode, episodeTitle)

    fun openExternal(url: String): Boolean = repository.openExternally(url)

    fun openBrowser(url: String): Boolean = repository.openInBrowser(url)

    fun resumeEntryFor(item: CatalogItem, season: Int?, episode: Int?): WatchProgress? {
        // Without an episode there is no resume row — only another slot's.
        if (item.isSeries && (season == null || episode == null)) return null
        return progress.value[item.playbackKey(season, episode)]
    }
}
