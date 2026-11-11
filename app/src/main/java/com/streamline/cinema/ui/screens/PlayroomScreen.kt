package com.streamline.cinema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.data.CatalogItem
import com.streamline.cinema.data.ContentLanguage
import com.streamline.cinema.data.EpgProgramHit
import com.streamline.cinema.data.Episode
import com.streamline.cinema.data.GenreSort
import com.streamline.cinema.data.LanguageRule
import com.streamline.cinema.data.LiveChannel
import com.streamline.cinema.data.MediaType
import com.streamline.cinema.data.PlaybackOption
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.Chip
import com.streamline.cinema.ui.EmptyState
import com.streamline.cinema.ui.ErrorBanner
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.InfoPill
import com.streamline.cinema.ui.LocalFocusMemory
import com.streamline.cinema.ui.LocalPosterStyle
import com.streamline.cinema.ui.LocalUiSounds
import com.streamline.cinema.ui.PosterCard
import com.streamline.cinema.ui.PosterImage
import com.streamline.cinema.ui.SectionHeader
import com.streamline.cinema.ui.ShimmerBox
import com.streamline.cinema.ui.SkeletonPoster
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.TvTextField
import com.streamline.cinema.ui.formatRuntime
import com.streamline.cinema.ui.glassFrame
import com.streamline.cinema.ui.glassSurface
import com.streamline.cinema.ui.rememberScreenGridState
import com.streamline.cinema.ui.rememberScreenListState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlayroomScreen(
    vm: CinemaViewModel,
    onOpenDetail: (CatalogItem) -> Unit,
    onPlay: (CatalogItem, Int?, Int?, String?) -> Unit,
    onOpenSettings: () -> Unit,
    onPlayLive: (PlaybackOption, String, List<LiveChannel>, Int) -> Unit = { _, _, _, _ -> }
) {
    val settings by vm.settings.collectAsState()
    val search by vm.search.collectAsState()
    val live by vm.live.collectAsState()
    val genreState by vm.genres.collectAsState()
    val quickImport by vm.quickImport.collectAsState()
    val posterStyle = LocalPosterStyle.current
    val focusMemory = LocalFocusMemory.current
    val searchFocus = remember { FocusRequester() }
    var slot by remember { mutableStateOf<CatalogItem?>(null) }

    LaunchedEffect(Unit) {
        vm.loadGenres()
        if (search.query.isBlank() && search.results.isEmpty() && !search.loading && search.error == null) {
            vm.loadPlayroomDefault()
        }
        delay(260)
        if (focusMemory.lastSurface?.startsWith("playroom.") == true) {
            // Coming back from a detail page: re-claim the poster the viewer left.
            focusMemory.requestRestore("playroom.grid")
        } else {
            runCatching { searchFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Cinema.InkSoft, Cinema.Ink),
                    endY = 720f
                )
            )
    ) {
        Column(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = rememberScreenGridState("playroom.grid"),
                columns = GridCells.Adaptive(posterStyle.gridCellWidth),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 44.dp, end = 48.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
            item(key = "playroom-header", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(start = 4.dp, top = 22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "TMDB PLAYROOM",
                        color = Cinema.Gold,
                        style = CinemaType.Kicker
                    )
                    Spacer(Modifier.weight(1f))
                    if (search.genreIds.isNotEmpty()) {
                        Text(
                            "SORT",
                            color = Cinema.TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Chip(
                            label = "NEWEST",
                            selected = search.genreSort == GenreSort.NEWEST,
                            compact = true,
                            onClick = { vm.setGenreSort(GenreSort.NEWEST) }
                        )
                        Spacer(Modifier.width(6.dp))
                        Chip(
                            label = "POPULAR",
                            selected = search.genreSort == GenreSort.POPULAR,
                            compact = true,
                            onClick = { vm.setGenreSort(GenreSort.POPULAR) }
                        )
                        Spacer(Modifier.width(18.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (search.genreIds.isEmpty()) {
                                "${search.results.size} titles"
                            } else {
                                val count = search.genreIds.size
                                "${search.results.size} titles · $count genre${if (count == 1) "" else "s"}"
                            },
                            color = Cinema.TextMuted,
                            style = CinemaType.Meta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val smart = buildString {
                            if (settings.contentLanguage.isNotBlank()) {
                                append(ContentLanguage.from(settings.contentLanguage).label.uppercase())
                            }
                            if (settings.contentCountry.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(settings.contentCountry)
                            }
                        }
                        if (smart.isNotEmpty()) {
                            Text(
                                "  SMART · $smart",
                                color = Cinema.Gold,
                                style = CinemaType.Meta,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cinema Search Terminal",
                    color = Cinema.TextPrimary,
                    style = CinemaType.PageTitle
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Search the TMDB universe or browse by genre, preview instantly and import titles into your Cinema Hall.",
                    color = Cinema.TextSecondary,
                    style = CinemaType.Body
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.weight(1f).widthIn(max = 760.dp)) {
                        TvTextField(
                            value = search.query,
                            onValueChange = { value ->
                                vm.search(value, search.filter, settings.includeAdult)
                            },
                            placeholder = "Search movies and TV shows on TMDB…",
                            leadingGlyph = "⌕",
                            focusRequester = searchFocus
                        )
                    }
                    TvButton(
                        label = if (search.loading) "SEARCHING" else "SEARCH",
                        glyph = "⌕",
                        style = TvButtonStyle.PRIMARY,
                        enabled = !search.loading && settings.hasTmdbAccess,
                        onClick = { vm.search(search.query, search.filter, settings.includeAdult) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(
                        state = rememberScreenListState("playroom.typeFilter"),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item(key = "type-all") {
                            Chip("All", search.filter == null, onClick = {
                                vm.search(search.query, null, settings.includeAdult)
                            })
                        }
                        item(key = "type-movies") {
                            Chip("Movies", search.filter == MediaType.MOVIE, onClick = {
                                vm.search(search.query, MediaType.MOVIE, settings.includeAdult)
                            })
                        }
                        item(key = "type-tv") {
                            Chip("TV Shows", search.filter == MediaType.TV, onClick = {
                                vm.search(search.query, MediaType.TV, settings.includeAdult)
                            })
                        }
                        item(key = "divider") {
                            Box(
                                Modifier
                                    .padding(horizontal = 6.dp)
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(Color.White.copy(alpha = 0.10f))
                            )
                        }
                        item(key = "mood-label") {
                            Text(
                                "MOOD",
                                color = Cinema.TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp,
                                modifier = Modifier.padding(start = 4.dp, end = 2.dp)
                            )
                        }
                        items(moodsFor(search.filter), key = { "mood-${it.label}" }) { mood ->
                            Chip(
                                label = mood.label,
                                selected = mood.ids.isNotEmpty() &&
                                    mood.ids.all { it in search.genreIds },
                                onClick = {
                                    if (mood.ids.all { it in search.genreIds }) {
                                        vm.clearGenres()
                                    } else {
                                        vm.setGenres(mood.ids)
                                    }
                                }
                            )
                        }
                        item(key = "divider-2") {
                            Box(
                                Modifier
                                    .padding(horizontal = 6.dp)
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(Color.White.copy(alpha = 0.10f))
                            )
                        }
                        item(key = "genre-label") {
                            Text(
                                "GENRE",
                                color = Cinema.TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp,
                                modifier = Modifier.padding(start = 4.dp, end = 2.dp)
                            )
                        }
                        if (search.genreIds.isNotEmpty()) {
                            item(key = "genre-clear") {
                                Chip("CLEAR", false, onClick = { vm.clearGenres() })
                            }
                        }
                        items(
                            genreState.catalog.forScope(search.filter),
                            key = { "genre-${it.id}" }
                        ) { genre ->
                            Chip(
                                label = genre.name,
                                selected = genre.id in search.genreIds,
                                onClick = { vm.toggleGenre(genre.id) }
                            )
                        }
                        if (genreState.loading) {
                            item(key = "genres-loading") {
                                Text(
                                    "Loading genres…",
                                    color = Cinema.TextMuted,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
                genreState.error?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Genre list fallback · $error",
                        color = Cinema.Offline,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (search.query.isBlank() && settings.recentSearches.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "RECENT",
                            color = Cinema.TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.6.sp
                        )
                        Spacer(Modifier.width(10.dp))
                        LazyRow(
                            state = rememberScreenListState("playroom.recent"),
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(settings.recentSearches, key = { "recent-$it" }) { term ->
                                Chip(
                                    label = term,
                                    selected = false,
                                    compact = true,
                                    onClick = {
                                        vm.search(term, search.filter, settings.includeAdult)
                                        vm.rememberSearch(term)
                                    }
                                )
                            }
                            item(key = "recent-clear") {
                                Chip(
                                    label = "CLEAR",
                                    selected = false,
                                    compact = true,
                                    onClick = { vm.clearRecentSearches() }
                                )
                            }
                        }
                    }
                }
                if (search.query.isBlank() && search.genreIds.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        state = rememberScreenListState("playroom.az"),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item(key = "az-label") {
                            Text(
                                "A–Z",
                                color = Cinema.TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                modifier = Modifier.padding(end = 10.dp)
                            )
                        }
                        items(azLetters, key = { "az-$it" }) { letter ->
                            Chip(
                                label = letter,
                                selected = search.letter == letter,
                                compact = true,
                                onClick = {
                                    vm.browseLetter(if (search.letter == letter) null else letter)
                                }
                            )
                        }
                    }
                }
                if (!settings.hasTmdbAccess) {
                    Spacer(Modifier.height(14.dp))
                    ErrorBanner(
                        message = "TMDB access is offline — showing your Cinema Hall only. " +
                            "Add an API key or proxy URL in Settings to search TMDB live.",
                        onRetry = onOpenSettings
                    )
                }
                quickImport.message?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        message,
                        color = when {
                            quickImport.busy -> Cinema.TextSecondary
                            quickImport.success -> Cinema.Gold
                            else -> Cinema.Offline
                        },
                        fontSize = 12.sp
                    )
                }
                search.error?.let {
                    if (settings.hasTmdbAccess) {
                        Spacer(Modifier.height(10.dp))
                        ErrorBanner(message = it) {
                            if (search.query.isBlank()) vm.loadPlayroomDefault()
                            else vm.search(search.query, search.filter, settings.includeAdult)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            }

            when {
                search.loading && search.results.isEmpty() -> item(
                    key = "playroom-loading",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        repeat(2) {
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                repeat(6) { SkeletonPoster() }
                            }
                        }
                    }
                }
                search.results.isEmpty() && search.channels.isEmpty() && search.programs.isEmpty() -> item(
                    key = "playroom-empty",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    val browsingGenres = search.genreIds.isNotEmpty()
                    val strictLanguage = !browsingGenres && search.query.isNotBlank() &&
                        settings.languageRule == LanguageRule.ONLY &&
                        settings.contentLanguage.isNotBlank()
                    val languageLabel = ContentLanguage.from(settings.contentLanguage).label
                    EmptyState(
                        title = when {
                            browsingGenres -> "No genre matches"
                            strictLanguage -> "No $languageLabel titles"
                            else -> "No signals found"
                        },
                        message = when {
                            browsingGenres ->
                                "TMDB returned no titles for this genre combination. Remove a genre or widen the type filter."
                            strictLanguage ->
                                "The Only rule hides titles TMDB lists in another language. Show every language for this search."
                            else ->
                                "Try a different title, or disable the type filter to widen the search across TMDB and your playlists."
                        },
                        actionLabel = when {
                            browsingGenres -> "Clear genres"
                            strictLanguage -> "SHOW ALL"
                            else -> "Load trending"
                        },
                        onAction = {
                            when {
                                browsingGenres -> vm.clearGenres()
                                strictLanguage -> vm.search(
                                    search.query,
                                    search.filter,
                                    settings.includeAdult,
                                    ignoreLanguageRule = true
                                )
                                else -> vm.loadPlayroomDefault()
                            }
                        }
                    )
                }
                else -> {
                    if (search.channels.isNotEmpty()) {
                        item(key = "live-band", span = { GridItemSpan(maxLineSpan) }) {
                            LiveChannelBand(
                                channels = search.channels,
                                resolving = live.resolving,
                                onTune = { channel ->
                                    vm.resolveLive(channel) { option ->
                                        if (option != null) {
                                            onPlayLive(
                                                option,
                                                channel.name,
                                                search.channels,
                                                search.channels.indexOf(channel)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                    val epgHits = search.programs.filter { vm.channelForEpgId(it.channelId) != null }
                    if (epgHits.isNotEmpty()) {
                        item(key = "epg-band", span = { GridItemSpan(maxLineSpan) }) {
                            ProgrammeBand(
                                hits = epgHits,
                                resolving = live.resolving,
                                channelFor = { vm.channelForEpgId(it) },
                                onTune = { channel, lineup ->
                                    vm.resolveLive(channel) { option ->
                                        if (option != null) {
                                            onPlayLive(
                                                option,
                                                channel.name,
                                                lineup,
                                                lineup.indexOf(channel)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                    if (search.results.isEmpty()) {
                        val strictLanguage = search.query.isNotBlank() &&
                            settings.languageRule == LanguageRule.ONLY &&
                            settings.contentLanguage.isNotBlank()
                        item(key = "no-title-matches", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                title = "No title matches",
                                message = if (strictLanguage) {
                                    "Live channels and guide programmes above matched. " +
                                        "The Only rule hides TMDB titles in another language."
                                } else {
                                    "Live channels and guide programmes above matched your query. " +
                                        "Search a movie or series name, or clear the query to browse."
                                },
                                actionLabel = if (strictLanguage) "SHOW ALL" else "CLEAR SEARCH",
                                onAction = {
                                    if (strictLanguage) {
                                        vm.search(
                                            search.query,
                                            search.filter,
                                            settings.includeAdult,
                                            ignoreLanguageRule = true
                                        )
                                    } else {
                                        vm.search("", search.filter, settings.includeAdult)
                                    }
                                }
                            )
                        }
                    } else {
                        items(search.results, key = { it.key }) { item ->
                            PosterCard(
                                item = item,
                                onClick = {
                                    vm.rememberSearch(search.query)
                                    slot = item
                                },
                                focusSurface = "playroom.grid"
                            )
                        }
                    }
                }
            }
            }
        }

        val selected = slot
        if (selected != null) {
            PlayroomModal(
                vm = vm,
                seed = selected,
                onClose = {
                    slot = null
                    focusMemory.requestRestore("playroom.grid")
                },
                onOpenDetail = {
                    slot = null
                    onOpenDetail(it)
                },
                onPlay = { item, season, episode, title ->
                    slot = null
                    onPlay(item, season, episode, title)
                }
            )
        }
    }
}

@Composable
private fun PlayroomModal(
    vm: CinemaViewModel,
    seed: CatalogItem,
    onClose: () -> Unit,
    onOpenDetail: (CatalogItem) -> Unit,
    onPlay: (CatalogItem, Int?, Int?, String?) -> Unit
) {
    val settings by vm.settings.collectAsState()
    val saved by vm.library.collectAsState()
    val quickImport by vm.quickImport.collectAsState()
    val posterStyle = LocalPosterStyle.current
    val closeFocus = remember { FocusRequester() }
    // Every piece of preview state is keyed on the seed: the modal is reused for
    // another title, and a leftover season/episode list would launch the wrong
    // episode of the new one.
    var detail by remember(seed.key) { mutableStateOf(seed) }
    var loading by remember(seed.key) { mutableStateOf(true) }
    var error by remember(seed.key) { mutableStateOf<String?>(null) }
    var retry by remember(seed.key) { mutableStateOf(0) }
    var seasonNumber by remember(seed.key) {
        mutableStateOf(
            seed.seasons.firstOrNull { it.number > 0 }?.number
                ?: seed.seasons.firstOrNull()?.number
                ?: 1
        )
    }
    var episodes by remember(seed.key) { mutableStateOf<List<Episode>>(emptyList()) }
    var episodesLoading by remember(seed.key) { mutableStateOf(false) }

    val inLibrary = saved.any { it.key == seed.key }
    val importing = quickImport.busy && quickImport.key == detail.key
    val importStatus = quickImport.message?.takeIf { quickImport.key == detail.key }
    // Preview playback needs a loaded episode list; falling back to episode 1
    // while the list is still loading launched the wrong episode.
    val playableEpisode = episodes.firstOrNull { it.number > 0 }
    val playReady = !loading && (!detail.isSeries || (!episodesLoading && playableEpisode != null))

    LaunchedEffect(seed.key, retry) {
        loading = true
        error = null
        try {
            val loaded = vm.loadDetails(seed.tmdbId, seed.type)
            detail = loaded
            if (loaded.isSeries) {
                val first = loaded.seasons.firstOrNull { it.number > 0 } ?: loaded.seasons.firstOrNull()
                if (first != null) seasonNumber = first.number
                episodes = first?.episodes.orEmpty()
            } else {
                episodes = emptyList()
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            error = t.message ?: "Could not load TMDB details"
        }
        loading = false
        delay(180)
        runCatching { closeFocus.requestFocus() }
    }

    LaunchedEffect(detail.key, seasonNumber) {
        val current = detail
        if (!current.isSeries) return@LaunchedEffect
        val cached = current.seasons.firstOrNull { it.number == seasonNumber }?.episodes.orEmpty()
        if (cached.isNotEmpty()) {
            episodes = cached
            return@LaunchedEffect
        }
        episodes = emptyList()
        episodesLoading = true
        error = null
        try {
            episodes = vm.loadSeasonEpisodes(current, seasonNumber)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            error = t.message
        }
        episodesLoading = false
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Cinema.Ink)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 44.dp, end = 44.dp, top = 30.dp, bottom = 40.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "PLAYROOM PREVIEW",
                        color = Cinema.Gold,
                        style = CinemaType.Kicker
                    )
                    Spacer(Modifier.weight(1f))
                    TvButton(
                        label = "CLOSE",
                        glyph = "✕",
                        style = TvButtonStyle.GHOST,
                        compact = true,
                        focusRequester = closeFocus,
                        onClick = onClose
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.Top) {
                    PosterImage(
                        url = detail.posterUrl ?: seed.posterUrl,
                        title = detail.title,
                        modifier = Modifier
                            .width(posterStyle.featureWidth)
                            .aspectRatio(posterStyle.aspectRatio)
                            .glassFrame(RoundedCornerShape(CinemaDimens.CardRadius))
                    )
                    Spacer(Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            detail.title,
                            color = Cinema.TextPrimary,
                            style = CinemaType.DetailTitle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        detail.tagline.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, color = Cinema.GoldDim, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            detail.rating?.let {
                                InfoPill("★ %.1f".format(it), Cinema.Gold, Cinema.Gold.copy(alpha = 0.12f))
                            }
                            detail.certification?.takeIf { it.isNotBlank() }?.let {
                                InfoPill(it, Cinema.TextPrimary, Cinema.PanelRaised)
                            }
                            detail.yearLabel.takeIf { it.isNotBlank() }?.let {
                                InfoPill(it, Cinema.TextSecondary)
                            }
                            if (detail.isSeries) {
                                InfoPill("SERIES", Cinema.Gold, Cinema.PanelRaised)
                            } else {
                                formatRuntime(detail.runtimeMinutes)?.let {
                                    InfoPill(it, Cinema.TextSecondary)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            detail.genres.joinToString("  ·  "),
                            color = Cinema.TextMuted,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            detail.overview.ifBlank { seed.overview.ifBlank { "No synopsis available." } },
                            color = Cinema.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton(
                                label = "PLAY NOW",
                                glyph = "▶",
                                style = TvButtonStyle.PRIMARY,
                                enabled = playReady,
                                onClick = {
                                    if (detail.isSeries) {
                                        playableEpisode?.let {
                                            onPlay(detail, seasonNumber, it.number, it.title)
                                        }
                                    } else {
                                        onPlay(detail, null, null, null)
                                    }
                                }
                            )
                            TvButton(
                                label = when {
                                    inLibrary -> "IN LIBRARY"
                                    importing -> "IMPORTING…"
                                    else -> "1-CLICK IMPORT"
                                },
                                glyph = if (inLibrary) "✓" else "＋",
                                style = TvButtonStyle.SECONDARY,
                                enabled = !inLibrary && !importing,
                                onClick = { vm.importItem(detail) }
                            )
                            TvButton(
                                label = "FULL DETAILS",
                                style = TvButtonStyle.GHOST,
                                onClick = { onOpenDetail(detail) }
                            )
                        }
                        importStatus?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                it,
                                color = when {
                                    quickImport.busy -> Cinema.TextSecondary
                                    quickImport.success -> Cinema.Gold
                                    else -> Cinema.Offline
                                },
                                fontSize = 11.sp
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Instant playback streams direct links and Stremio addon sources · " +
                                "no library entry required",
                            color = Cinema.TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }

                if (loading) {
                    Spacer(Modifier.height(18.dp))
                    Text("Syncing TMDB metadata…", color = Cinema.TextMuted, fontSize = 12.sp)
                }
                error?.let {
                    Spacer(Modifier.height(14.dp))
                    ErrorBanner(message = it, onRetry = { retry++ })
                }

                if (detail.isSeries && detail.seasons.isNotEmpty()) {
                    Spacer(Modifier.height(26.dp))
                    Text(
                        "SEASONS",
                        color = Cinema.TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.6.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(detail.seasons, key = { it.number }) { season ->
                            Chip(
                                label = "${season.displayName} · ${season.episodeCount}",
                                selected = season.number == seasonNumber,
                                onClick = { seasonNumber = season.number }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (episodesLoading) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) {
                                ShimmerBox(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(66.dp),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            episodes.forEach { episode ->
                                EpisodePreviewRow(
                                    episode = episode,
                                    onClick = { onPlay(detail, seasonNumber, episode.number, episode.title) }
                                )
                            }
                        }
                        if (episodes.isEmpty()) {
                            Text(
                                "No episode data published for this season yet.",
                                color = Cinema.TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else if (!detail.isSeries && !loading) {
                    Spacer(Modifier.height(22.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Cinema.Panel, RoundedCornerShape(10.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("▶", color = Cinema.Gold, fontSize = 18.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Stream formats",
                                color = Cinema.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "MP4 · MKV · HLS · MPEG-DASH · adaptive bitrate",
                                color = Cinema.TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            "Direct stream · addon sources",
                            color = Cinema.Gold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveChannelBand(
    channels: List<LiveChannel>,
    resolving: String?,
    onTune: (LiveChannel) -> Unit
) {
    Column(Modifier.padding(bottom = 20.dp)) {
        SectionHeader(
            title = "Live Channels",
            subtitle = "${channels.size} matching playlist feed${if (channels.size == 1) "" else "s"}"
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(channels, key = { it.key }) { channel ->
                LiveChannelTile(
                    channel = channel,
                    busy = resolving == channel.key,
                    onClick = { onTune(channel) }
                )
            }
        }
    }
}

@Composable
private fun LiveChannelTile(channel: LiveChannel, busy: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val style = LocalPosterStyle.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .width(style.channelWidth)
            .glassSurface(
                shape = shape,
                focused = focused,
                base = Glass.Sheet,
                tint = if (focused) Glass.AccentWash else Color.Transparent,
                sheen = 0.55f
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .clickable {
                sounds.select()
                onClick()
            }
            .padding(8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .glassFrame(RoundedCornerShape(6.dp))
        ) {
            PosterImage(
                url = channel.poster,
                title = channel.name,
                description = null,
                modifier = Modifier.fillMaxSize()
            )
            Text(
                text = if (busy) "TUNING…" else "LIVE",
                color = Cinema.Ink,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (busy) Cinema.Pinging else Cinema.Crimson)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            channel.name,
            color = if (focused) Cinema.TextPrimary else Cinema.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        channel.category?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                color = Cinema.TextMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProgrammeBand(
    hits: List<EpgProgramHit>,
    resolving: String?,
    channelFor: (String) -> LiveChannel?,
    onTune: (LiveChannel, List<LiveChannel>) -> Unit
) {
    val lineup = remember(hits) {
        hits.mapNotNull { channelFor(it.channelId) }.distinctBy { it.key }
    }
    Column(Modifier.padding(bottom = 20.dp)) {
        SectionHeader(
            title = "On Now & Next",
            subtitle = "${hits.size} guide match${if (hits.size == 1) "" else "es"} · tune straight from the guide"
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(hits, key = { "${it.channelId}|${it.program.startMs}|${it.program.title}" }) { hit ->
                val channel = channelFor(hit.channelId)
                ProgrammeTile(
                    hit = hit,
                    busy = channel != null && resolving == channel.key,
                    onClick = { channel?.let { onTune(it, lineup) } }
                )
            }
        }
    }
}

@Composable
private fun ProgrammeTile(hit: EpgProgramHit, busy: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val style = LocalPosterStyle.current
    val shape = RoundedCornerShape(10.dp)
    val now = System.currentTimeMillis()
    val live = hit.program.overlaps(now)
    Column(
        modifier = Modifier
            .width(style.channelWidth)
            .glassSurface(
                shape = shape,
                focused = focused,
                base = Glass.Sheet,
                tint = if (focused) Glass.AccentWash else Color.Transparent,
                sheen = 0.55f
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .clickable {
                sounds.select()
                onClick()
            }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (busy) "TUNING…" else if (live) "ON NOW" else "UP NEXT",
                color = Cinema.Ink,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (busy) Cinema.Pinging else if (live) Cinema.Crimson else Cinema.Gold)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = programmeTimeRange(hit.program.startMs, hit.program.stopMs),
                color = Cinema.TextMuted,
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            hit.program.title,
            color = if (focused) Cinema.TextPrimary else Cinema.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        hit.program.subTitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                color = Cinema.TextMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            hit.channelName,
            color = Cinema.Gold,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun programmeTimeRange(startMs: Long, stopMs: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return "${formatter.format(Date(startMs))}–${formatter.format(Date(stopMs))}"
}

private data class MoodShortcut(val label: String, val ids: Set<Int>)
private fun moodsFor(filter: MediaType?): List<MoodShortcut> =
    if (filter == MediaType.TV) {
        listOf(
            MoodShortcut("Edge of seat", setOf(9648, 10765)),
            MoodShortcut("Feel-good", setOf(35, 10751)),
            MoodShortcut("Binge drama", setOf(18)),
            MoodShortcut("Real world", setOf(99, 10763))
        )
    } else {
        listOf(
            MoodShortcut("Edge of seat", setOf(53, 27)),
            MoodShortcut("Feel-good", setOf(35, 10751)),
            MoodShortcut("Epic", setOf(12, 14)),
            MoodShortcut("True story", setOf(99, 36))
        )
    }

private val azLetters: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

@Composable
private fun EpisodePreviewRow(episode: Episode, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = shape,
                focused = focused,
                base = if (focused) Glass.Control else Color.Transparent,
                tint = if (focused) Glass.AccentWash else Color.Transparent,
                sheen = 0.45f
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .clickable {
                sounds.select()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PosterImage(
            url = episode.stillUrl,
            title = episode.title,
            description = null,
            modifier = Modifier
                .width(118.dp)
                .aspectRatio(16f / 9f)
                .glassFrame(RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "E${episode.number}",
                    color = Cinema.Gold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    episode.title.ifBlank { "Episode ${episode.number}" },
                    color = Cinema.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (episode.overview.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    episode.overview,
                    color = Cinema.TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text("▶", color = if (focused) Cinema.Gold else Cinema.TextMuted, fontSize = 15.sp)
    }
}
