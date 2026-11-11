package com.streamline.cinema.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.data.CatalogItem
import com.streamline.cinema.data.MediaItem
import com.streamline.cinema.data.WatchProgress
import com.streamline.cinema.data.tmdbGenresMap
import com.streamline.cinema.data.toMediaItem
import com.streamline.cinema.data.sortedByNewest
import com.streamline.cinema.ui.AppTab
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.Chip
import com.streamline.cinema.ui.ContinueCard
import com.streamline.cinema.ui.ErrorBanner
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.InfoBadge
import com.streamline.cinema.ui.InfoPill
import com.streamline.cinema.ui.LocalFocusMemory
import com.streamline.cinema.ui.rememberScreenGridState
import com.streamline.cinema.ui.rememberScreenListState
import com.streamline.cinema.ui.LocalPosterStyle
import com.streamline.cinema.ui.MediaCard
import com.streamline.cinema.ui.PosterCard
import com.streamline.cinema.ui.PosterImage
import com.streamline.cinema.ui.PosterRow
import com.streamline.cinema.ui.SectionHeader
import com.streamline.cinema.ui.SkeletonRail
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.countLabel
import com.streamline.cinema.ui.formatRuntime
import com.streamline.cinema.ui.glassSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class MediaTile(val media: MediaItem, val catalog: CatalogItem)

@Composable
fun HomeScreen(
    vm: CinemaViewModel,
    onOpenDetail: (CatalogItem) -> Unit,
    onPlay: (CatalogItem, Int?, Int?, String?) -> Unit,
    onNavigate: (AppTab) -> Unit,
    onOpenProfiles: () -> Unit,
    entryFocus: FocusRequester,
    focusSignal: Int,
    focusReady: Boolean
) {
    val home by vm.home.collectAsState()
    val library by vm.library.collectAsState()
    val progress by vm.progress.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val settings by vm.settings.collectAsState()
    val profile by vm.activeProfile.collectAsState()
    val enriching by vm.enrichingTitle.collectAsState()

    val favoriteItems = favorites.map { favorite ->
        library.firstOrNull { it.key == favorite.key } ?: favorite
    }

    // The hall is ordered newest release first; the hero picks the newest five from it.
    val newest = remember(library) { library.sortedByNewest() }
    val tiles = remember(newest) { newest.map { MediaTile(it.toMediaItem(), it) } }
    val heroPicks = remember(newest, home.trending) {
        (newest + home.trending).distinctBy { it.key }.take(5)
    }
    var heroIndex by remember(heroPicks) { mutableIntStateOf(0) }
    LaunchedEffect(heroPicks) {
        if (heroPicks.size <= 1) return@LaunchedEffect
        while (true) {
            delay(9_000)
            heroIndex = (heroIndex + 1) % heroPicks.size
        }
    }
    val heroFeatured = heroPicks.getOrNull(heroIndex)
    val heroLogos by vm.heroLogos.collectAsState()
    LaunchedEffect(heroIndex, heroPicks, settings.hasTmdbAccess) {
        if (!settings.hasTmdbAccess) return@LaunchedEffect
        heroPicks.getOrNull(heroIndex)?.let(vm::ensureHeroLogo)
        // Warm the next card so a swipe or the auto-advance keeps its logo.
        if (heroPicks.size > 1) {
            heroPicks.getOrNull((heroIndex + 1) % heroPicks.size)?.let(vm::ensureHeroLogo)
        }
    }
    val focusMemory = LocalFocusMemory.current
    // The backdrop pans a few points as the carousel advances, so the canvas
    // behind the rails feels alive rather than swapping static images.
    val heroShift by animateFloatAsState(heroIndex.toFloat(), tween(900), label = "heroShift")

    val continueWatching = remember(progress, library, profile.isKids) {
        progress.values
            .filter { it.isResumable }
            .sortedByDescending { it.updatedAt }
            // One card per title/series: the newest in-progress episode wins.
            .distinctBy { it.groupKey }
            .take(14)
            .mapNotNull { entry ->
                val item = library.firstOrNull { it.key == entry.itemKey }
                // Kids profiles never fall back to an unapproved progress row.
                if (item == null && profile.isKids) return@mapNotNull null
                entry to (item ?: CatalogItem(
                    tmdbId = entry.tmdbId,
                    type = entry.type,
                    title = entry.title,
                    posterUrl = entry.posterUrl
                ))
            }
    }

    val progressByKey = remember(progress) {
        progress.values.groupBy { it.groupKey }.mapValues { (_, rows) ->
            rows.maxByOrNull { it.updatedAt }
        }
    }

    val genreOptions = remember(tiles) {
        val present = tiles.flatMap { it.media.genreIds }.toSet()
        tmdbGenresMap.keys.filter { it in present }
    }

    var selectedGenreId by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedGenreId, genreOptions) {
        if (selectedGenreId != null && selectedGenreId !in genreOptions) selectedGenreId = null
    }

    val visible = remember(selectedGenreId, tiles) {
        tiles.filter { selectedGenreId == null || selectedGenreId in it.media.genreIds }
    }

    // The rail the viewer left is re-claimed when Home re-enters composition.
    // Only a forced signal (profile gate close, Kids Mode) resets to PLAY.
    val playFocus = entryFocus
    val hostView = LocalView.current
    val gridState = rememberScreenGridState("home.grid")
    val scope = rememberCoroutineScope()
    var handledSignal by remember { mutableIntStateOf(focusSignal) }
    LaunchedEffect(heroPicks.firstOrNull()?.key, focusSignal, focusReady) {
        if (!focusReady) return@LaunchedEffect
        val forced = focusSignal != handledSignal
        val returningSurface = focusMemory.lastSurface?.takeIf { it.startsWith("home.") }
        handledSignal = focusSignal
        if (returningSurface != null && !forced) {
            delay(160)
            focusMemory.requestRestore(returningSurface)
            return@LaunchedEffect
        }
        delay(220)
        val granted = runCatching { playFocus.requestFocus() }.getOrNull() == true
        if (!granted && !hostView.hasFocus()) {
            // Touch mode leaves the window without view focus, so Compose cannot
            // grant focus until the next key press - and that press would land on
            // the tab strip. Claim the window focus back the way a key press would.
            runCatching { hostView.requestFocusFromTouch() }
            runCatching { playFocus.requestFocus() }
        }
    }

    val posterStyle = LocalPosterStyle.current

    // Rails are rebuilt whenever the profile's library changes; the VM dedupes
    // the network-backed "Because you watched" rail by anchor and profile.
    LaunchedEffect(library.size, progress.size) { vm.refreshRecommendations() }

    Box(Modifier.fillMaxSize().background(Cinema.Ink)) {
        Crossfade(
            targetState = heroFeatured,
            animationSpec = tween(700),
            label = "heroBackdrop",
            modifier = Modifier.fillMaxSize()
        ) { featured ->
            if (featured?.backdropUrl.isNullOrBlank()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Cinema.Panel,
                                    Cinema.InkSoft,
                                    Cinema.Crimson.copy(alpha = 0.18f)
                                )
                            )
                        )
                )
            } else {
                PosterImage(
                    url = featured?.backdropUrl,
                    title = featured?.title.orEmpty(),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Overscan plus a gentle pan keeps the parallax from
                            // exposing the edges of the artwork.
                            scaleX = 1.06f
                            scaleY = 1.06f
                            translationX = -((heroShift % 3f) * 12f)
                        },
                    contentScale = ContentScale.Crop
                )
            }
        }
        // Shades run edge to edge so the artwork and its grading share the same
        // full-screen canvas. The gradient is front-loaded: the copy column keeps
        // a deep base, while the right half of the artwork stays near its native
        // brightness instead of sitting under a flat wash.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Cinema.Ink.copy(alpha = 0.88f),
                        0.30f to Cinema.Ink.copy(alpha = 0.70f),
                        0.58f to Cinema.Ink.copy(alpha = 0.26f),
                        0.82f to Color.Transparent
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Cinema.Ink.copy(alpha = 0.40f),
                        0.24f to Color.Transparent,
                        0.70f to Color.Transparent,
                        1f to Cinema.Ink.copy(alpha = 0.92f)
                    )
                )
        )
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(posterStyle.gridCellWidth),
            contentPadding = PaddingValues(
                start = 48.dp,
                top = CinemaDimens.TopBarHeight + 30.dp,
                end = 48.dp,
                bottom = 56.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                HeroCarousel(
                    picks = heroPicks,
                    index = heroIndex,
                    onIndexChange = { heroIndex = it },
                    logos = heroLogos,
                    hasTmdb = settings.hasTmdbAccess,
                    kidsMode = profile.isKids,
                    error = home.error,
                    playFocusRequester = playFocus,
                    onPlayFocused = {
                        scope.launch {
                            // Focusing a short button inside the tall hero scrolls
                            // it to the viewport edge, clipping the title behind
                            // the bar. Re-entering the hero should show it whole.
                            withFrameNanos { }
                            gridState.scrollToItem(0)
                        }
                    },
                    progressFor = { progressByKey[it.key] },
                    onPlay = { featured ->
                        // Continue where the viewer left this title instead of
                        // restarting every series at S1E1. A series with no
                        // resume point has no correct episode yet, so PLAY opens
                        // its detail page rather than guessing one.
                        val entry = progressByKey[featured.key]
                            ?.takeIf { it.isResumable && it.season != null && it.episode != null }
                        if (featured.isSeries && entry == null) {
                            onOpenDetail(featured)
                        } else {
                            onPlay(featured, entry?.season, entry?.episode, entry?.episodeTitle)
                        }
                    },
                    onDetails = onOpenDetail,
                    onSetup = { onNavigate(AppTab.SETTINGS) },
                    onProfiles = onOpenProfiles,
                    // Playroom and Import are hidden routes; Movies / TV Shows own their entry points.
                    onImport = { onNavigate(AppTab.MOVIES) }
                )
            }

        if (home.error != null && settings.hasTmdbAccess && library.isNotEmpty()) {
            item(key = "homeError", span = { GridItemSpan(maxLineSpan) }) {
                ErrorBanner(home.error ?: "", onRetry = { vm.refreshHome(force = true) })
            }
        }

        if (continueWatching.isNotEmpty()) {
            item(key = "continueHeader", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(
                    title = "Continue Watching",
                    subtitle = "${continueWatching.size} in progress · ✕ clears a card"
                )
            }
            item(key = "continueRow", span = { GridItemSpan(maxLineSpan) }) {
                val rowState = rememberScreenListState("home.continue.row")
                val snap = rememberSnapFlingBehavior(lazyListState = rowState)
                LazyRow(
                    state = rowState,
                    flingBehavior = snap,
                    contentPadding = PaddingValues(start = 6.dp, end = 56.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(continueWatching, key = { it.first.groupKey }) { (entry, item) ->
                        ContinueCard(
                            item = item,
                            progress = entry,
                            onClick = {
                                onPlay(item, entry.season, entry.episode, entry.episodeTitle)
                            },
                            onRemove = { vm.removeProgressForItem(entry.groupKey) },
                            focusSurface = "home.continue"
                        )
                    }
                }
            }
        }

        if (home.top10.isNotEmpty()) {
            item(key = "top10", span = { GridItemSpan(maxLineSpan) }) {
                PosterRow(
                    title = "Top 10 Today",
                    subtitle = "Trending now · ranked",
                    items = home.top10,
                    onItemClick = onOpenDetail,
                    ranked = true,
                    focusSurface = "home.top10"
                )
            }
        }

        if (home.becauseTitle != null && home.because.isNotEmpty()) {
            item(key = "because", span = { GridItemSpan(maxLineSpan) }) {
                PosterRow(
                    title = "Because you watched ${home.becauseTitle}",
                    subtitle = "Picked for this profile",
                    items = home.because,
                    onItemClick = onOpenDetail,
                    focusSurface = "home.because"
                )
            }
        }

        if (home.newReleases.isNotEmpty()) {
            item(key = "newReleases", span = { GridItemSpan(maxLineSpan) }) {
                PosterRow(
                    title = "New Releases",
                    subtitle = "Fresh in your catalog",
                    items = home.newReleases,
                    onItemClick = onOpenDetail,
                    focusSurface = "home.new"
                )
            }
        }

        home.genreRows.forEach { rail ->
            item(key = "genreRail-${rail.name}", span = { GridItemSpan(maxLineSpan) }) {
                PosterRow(
                    title = rail.name,
                    subtitle = "From your library",
                    items = rail.items,
                    onItemClick = onOpenDetail,
                    focusSurface = "home.genre-${rail.name}"
                )
            }
        }

        if (tiles.isEmpty()) {
            item(key = "emptyHint", span = { GridItemSpan(maxLineSpan) }) {
                when {
                    profile.isKids -> KidsLibraryHint(onOpenProfiles = onOpenProfiles)
                    home.loading -> SkeletonRail(title = "Building your hall")
                    else -> LibraryHint(onImport = { onNavigate(AppTab.MOVIES) })
                }
            }
        } else {
            item(key = "hallControls", span = { GridItemSpan(maxLineSpan) }) {
                HallControls(
                    genres = genreOptions,
                    selectedGenreId = selectedGenreId,
                    onSelectGenre = { selectedGenreId = it },
                    total = tiles.size,
                    shown = visible.size
                )
            }
            items(count = visible.size, key = { visible[it].catalog.key }) { index ->
                val tile = visible[index]
                MediaCard(
                    media = tile.media,
                    progress = progressByKey[tile.catalog.key],
                    backdropUrl = tile.catalog.backdropUrl,
                    onClick = { onOpenDetail(tile.catalog) },
                    focusSurface = "home.hall"
                )
            }
        }

        if (favoriteItems.isNotEmpty()) {
            item(key = "favorites", span = { GridItemSpan(maxLineSpan) }) {
                PosterRow(
                    title = "Favorites",
                    subtitle = "${favoriteItems.size} saved · one tap from Home",
                    items = favoriteItems,
                    onItemClick = onOpenDetail,
                    modifier = Modifier.padding(top = 8.dp),
                    focusSurface = "home.fav"
                )
            }
        }

        if (home.popularMovies.isNotEmpty()) {
            item(key = "popularMovies", span = { GridItemSpan(maxLineSpan) }) {
                PosterRow(
                    title = "Popular Movies",
                    subtitle = "TMDB discover",
                    items = home.popularMovies,
                    onItemClick = onOpenDetail,
                    focusSurface = "home.popularMovies"
                )
            }
        }
        if (home.popularShows.isNotEmpty()) {
            item(key = "popularShows", span = { GridItemSpan(maxLineSpan) }) {
                PosterRow(
                    title = "Popular TV Shows",
                    subtitle = "TMDB discover",
                    items = home.popularShows,
                    onItemClick = onOpenDetail,
                    focusSurface = "home.popularShows"
                )
            }
        }

        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
            HomeFooter(
                enriching = enriching,
                pending = library.count { !it.tmdbLoaded },
                total = library.size,
                onSettings = { onNavigate(AppTab.SETTINGS) }
            )
        }
        }
    }
}

@Composable
private fun HallControls(
    genres: List<Int>,
    selectedGenreId: Int?,
    onSelectGenre: (Int?) -> Unit,
    total: Int,
    shown: Int
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "The Hall",
            subtitle = "${countLabel(total, "title")} · newest releases first"
        )
        Spacer(Modifier.height(2.dp))
        LazyRow(
            state = rememberScreenListState("home.genres"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
        ) {
            item(key = "genre-all") {
                Chip(
                    label = "All${if (total > 0) " · $total" else ""}",
                    selected = selectedGenreId == null,
                    onClick = { onSelectGenre(null) }
                )
            }
            items(genres, key = { "genre-$it" }) { id ->
                Chip(
                    label = tmdbGenresMap[id] ?: "Genre $id",
                    selected = selectedGenreId == id,
                    onClick = { onSelectGenre(id) }
                )
            }
        }
        if (shown != total) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$shown of ${countLabel(total, "title")}",
                color = Cinema.TextMuted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun HeroCarousel(
    picks: List<CatalogItem>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    logos: Map<String, String>,
    hasTmdb: Boolean,
    kidsMode: Boolean,
    error: String?,
    playFocusRequester: FocusRequester,
    onPlayFocused: () -> Unit,
    progressFor: (CatalogItem) -> WatchProgress?,
    onPlay: (CatalogItem) -> Unit,
    onDetails: (CatalogItem) -> Unit,
    onSetup: () -> Unit,
    onProfiles: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val featured = picks.getOrNull(index)
    val tracked = featured?.let { progressFor(it) }
    var swipeDrag by remember(picks.size) { mutableFloatStateOf(0f) }
    val logoUrl = featured?.let { item ->
        item.logoUrl?.takeIf { it.isNotBlank() } ?: logos[item.key]
    }
    // Bright artwork sits behind the copy now, so the type carries its own shade
    // instead of the whole backdrop being graded down for it.
    val heroTextShadow = Shadow(Cinema.Ink.copy(alpha = 0.9f), Offset(0f, 1f), 10f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(400.dp)
            // Touch devices can swipe the hero; the pagination dashes are the remote path.
            .pointerInput(picks.size) {
                detectHorizontalDragGestures(
                    onDragStart = { swipeDrag = 0f },
                    onHorizontalDrag = { _, amount -> swipeDrag += amount },
                    onDragEnd = {
                        if (picks.size > 1) {
                            when {
                                swipeDrag <= -70f -> onIndexChange((index + 1) % picks.size)
                                swipeDrag >= 70f -> onIndexChange((index - 1 + picks.size) % picks.size)
                            }
                        }
                        swipeDrag = 0f
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                // The grid's content padding already supplies the screen gutter;
                // a second one here would knock the hero out of line with the rails.
                .padding(end = 24.dp)
                .widthIn(max = 760.dp)
                .padding(bottom = 24.dp)
        ) {
            if (featured != null && !logoUrl.isNullOrBlank()) {
                // Studio logo art when TMDB has one; the name stays the fallback.
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(logoUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = featured.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier
                        .widthIn(max = 470.dp)
                        .height(118.dp)
                )
            } else {
                Text(
                    text = featured?.title ?: if (kidsMode) "Kids Mode" else "Your cinema. Your remote.",
                    color = Cinema.TextPrimary,
                    style = CinemaType.HeroTitle.copy(shadow = heroTextShadow),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
            if (featured != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    featured.rating?.let {
                        InfoBadge("TMDB", "%.1f".format(it), Cinema.Tmdb)
                    }
                    featured.certification?.takeIf { it.isNotBlank() }?.let {
                        InfoPill("⛨ $it", Cinema.TextPrimary, Cinema.PanelRaised)
                    }
                    val runtime = formatRuntime(featured.runtimeMinutes)
                    if (featured.isSeries) {
                        val seasonTrack = tracked?.season
                        val episodeTrack = tracked?.episode
                        if (seasonTrack != null && episodeTrack != null) {
                            InfoPill(
                                "Season $seasonTrack • Episode $episodeTrack",
                                Cinema.Gold,
                                Cinema.Gold.copy(alpha = 0.12f)
                            )
                        } else {
                            runtime?.let { InfoPill("$it / ep", Cinema.TextSecondary) }
                        }
                    } else {
                        runtime?.let { InfoPill(it, Cinema.TextSecondary) }
                    }
                    featured.yearLabel.takeIf { it.isNotBlank() }?.let {
                        InfoPill(it, Cinema.TextSecondary)
                    }
                    featured.genres.firstOrNull()?.let {
                        InfoPill(it, Cinema.TextSecondary)
                    }
                }
                Spacer(Modifier.height(11.dp))
                Text(
                    text = featured.overview.ifBlank {
                        featured.tagline.ifBlank { "Press play to start streaming." }
                    },
                    color = Cinema.TextSecondary,
                    style = CinemaType.HeroSynopsis.copy(shadow = heroTextShadow),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 640.dp)
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(
                        label = "PLAY",
                        glyph = "▶",
                        onClick = { onPlay(featured) },
                        style = TvButtonStyle.PRIMARY,
                        focusRequester = playFocusRequester,
                        modifier = Modifier.onFocusChanged { state ->
                            if (state.isFocused) onPlayFocused()
                        }
                    )
                    TvButton(
                        label = "DETAILS",
                        onClick = { onDetails(featured) },
                        style = TvButtonStyle.SECONDARY
                    )
                }
            } else {
                Text(
                    text = if (kidsMode) {
                        "No approved titles yet. A parent can approve movies, series and live " +
                            "channels from Settings ▸ Parental, or switch profiles to watch the full catalog."
                    } else {
                        "Connect your TMDB key, import a catalog and stream movies and TV shows " +
                            "with a fast-start player built for D-pad remotes."
                    },
                    color = Cinema.TextSecondary,
                    style = CinemaType.HeroSynopsis,
                    maxLines = 3
                )
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(
                        label = if (hasTmdb) "OPEN SETTINGS" else "ADD TMDB KEY",
                        glyph = "⚙",
                        onClick = onSetup,
                        style = TvButtonStyle.PRIMARY,
                        focusRequester = playFocusRequester
                    )
                    TvButton(
                        label = if (kidsMode) "PROFILES" else "IMPORT FROM MOVIES",
                        glyph = if (kidsMode) "◉" else "↧",
                        onClick = if (kidsMode) onProfiles else onImport,
                        style = TvButtonStyle.SECONDARY
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(error, color = Cinema.Offline, fontSize = 12.sp)
                }
            }
        }

        if (picks.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                picks.indices.forEach { pickIndex ->
                    val active = pickIndex == index
                    val dashWidth by animateDpAsState(
                        targetValue = if (active) 26.dp else 8.dp,
                        animationSpec = tween(260),
                        label = "heroDash"
                    )
                    Box(
                        Modifier
                            .width(dashWidth)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (active) Cinema.Gold else Color.White.copy(alpha = 0.35f))
                            // Touch-only: a 3 dp dash must never become a D-pad stop.
                            .pointerInput(pickIndex) {
                                detectTapGestures { onIndexChange(pickIndex) }
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun KidsLibraryHint(onOpenProfiles: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(CinemaDimens.ControlRadius),
                base = Glass.Sheet,
                sheen = 0.8f
            )
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .glassSurface(shape = CircleShape, base = Glass.Control)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text("⛨", color = Cinema.Gold, fontSize = 18.sp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Nothing approved yet",
                color = Cinema.TextPrimary,
                style = CinemaType.SectionTitle
            )
            Text(
                "A parent can approve movies, series and live channels from Settings ▸ Parental, " +
                    "or switch to another profile with the parental PIN.",
                color = Cinema.TextMuted,
                style = CinemaType.BodySmall
            )
        }
        Spacer(Modifier.width(16.dp))
        TvButton("PROFILES", onOpenProfiles, style = TvButtonStyle.SECONDARY, compact = true)
    }
}

@Composable
private fun LibraryHint(onImport: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(CinemaDimens.ControlRadius),
                base = Glass.Sheet,
                sheen = 0.8f
            )
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .glassSurface(shape = CircleShape, base = Glass.Control)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text("↧", color = Cinema.Gold, fontSize = 18.sp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Build your Cinema Hall",
                color = Cinema.TextPrimary,
                style = CinemaType.SectionTitle
            )
            Text(
                "Import a TMDB preset or an MDBList collection — series are scaffolded into seasons and episodes automatically.",
                color = Cinema.TextMuted,
                style = CinemaType.BodySmall
            )
        }
        Spacer(Modifier.width(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("IMPORT TERMINAL", onImport, style = TvButtonStyle.SECONDARY, compact = true)
        }
    }
}

@Composable
private fun HomeFooter(
    enriching: String?,
    pending: Int,
    total: Int,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(6.dp)
                .height(6.dp)
                .clip(CircleShape)
                .background(if (pending > 0) Cinema.Pinging else Cinema.Online)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                enriching != null -> "Lazy metadata · enriching “$enriching” · $pending queued"
                pending > 0 -> "Lazy metadata queue · $pending titles waiting"
                else -> "Catalog · $total titles · all metadata synced"
            },
            color = Cinema.TextMuted,
            fontSize = 11.sp
        )
        Spacer(Modifier.weight(1f))
        TvButton("SETTINGS", onSettings, style = TvButtonStyle.GHOST, compact = true)
    }
}
