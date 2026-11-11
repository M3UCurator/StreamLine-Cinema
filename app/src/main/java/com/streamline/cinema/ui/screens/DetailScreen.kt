package com.streamline.cinema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.data.CatalogItem
import com.streamline.cinema.data.Episode
import com.streamline.cinema.data.MediaType
import com.streamline.cinema.data.Season
import com.streamline.cinema.data.WatchProgress
import com.streamline.cinema.data.isSeasonWatched
import com.streamline.cinema.data.newEpisodeCount
import com.streamline.cinema.data.nextUpEpisode
import com.streamline.cinema.data.todayStamp
import com.streamline.cinema.data.trackedSeasons
import com.streamline.cinema.data.watchedEpisodesFor
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.Chip
import com.streamline.cinema.ui.ErrorBanner
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.InfoPill
import com.streamline.cinema.ui.LocalPosterStyle
import com.streamline.cinema.ui.LocalUiSounds
import com.streamline.cinema.ui.PosterImage
import com.streamline.cinema.ui.PosterRow
import com.streamline.cinema.ui.ResumeBadge
import com.streamline.cinema.ui.SectionHeader
import com.streamline.cinema.ui.ShimmerBox
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.formatRuntime
import com.streamline.cinema.ui.glassFrame
import com.streamline.cinema.ui.glassSurface
import com.streamline.cinema.ui.rememberScreenListState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.streamline.cinema.data.formatEpisodeAirDate
import java.util.Locale

@Composable
fun DetailScreen(
    vm: CinemaViewModel,
    seed: CatalogItem,
    onBack: () -> Unit,
    onPlay: (CatalogItem, Int?, Int?, String?) -> Unit,
    onOpenRelated: (CatalogItem) -> Unit = {}
) {
    val settings by vm.settings.collectAsState()
    val profile by vm.activeProfile.collectAsState()
    val library by vm.library.collectAsState()
    val progress by vm.progress.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val watchedMarks by vm.watchedEpisodes.collectAsState()
    val sniffedSeasons by vm.sniffedSeasons.collectAsState()
    val quickImport by vm.quickImport.collectAsState()
    val kidsApproved by vm.kidsMode.approved.collectAsState()
    val posterStyle = LocalPosterStyle.current

    var item by remember(seed.key) { mutableStateOf(seed) }
    var loading by remember(seed.key) { mutableStateOf(true) }
    var error by remember(seed.key) { mutableStateOf<String?>(null) }
    var detailRetry by remember(seed.key) { mutableStateOf(0) }
    var related by remember(seed.key) { mutableStateOf<List<CatalogItem>>(emptyList()) }
    var relatedLoading by remember(seed.key) { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var selectedSeason by remember(seed.key) {
        val resumeSeason = progress.values
            .filter { it.itemKey == seed.key && it.isResumable && it.season != null }
            .maxByOrNull { it.updatedAt }?.season
        mutableStateOf(
            resumeSeason
                ?: seed.seasons.firstOrNull { it.number > 0 }?.number
                ?: seed.seasons.firstOrNull()?.number
                ?: 1
        )
    }
    var episodes by remember(seed.key) { mutableStateOf<List<Episode>>(emptyList()) }
    var episodesLoading by remember(seed.key) { mutableStateOf(false) }
    var episodesError by remember(seed.key) { mutableStateOf<String?>(null) }
    var episodeRetry by remember(seed.key) { mutableStateOf(0) }
    var markingPast by remember(seed.key) { mutableStateOf(false) }
    var trackerNote by remember(seed.key) { mutableStateOf<String?>(null) }

    val savedItem = library.firstOrNull { it.key == item.key }
    val isFavorite = favorites.any { it.key == item.key }
    val importingItem = quickImport.busy && quickImport.key == item.key
    val importStatus = quickImport.message?.takeIf { quickImport.key == item.key }
    val playFocus = remember { FocusRequester() }
    val episodeFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    // Playback runs in its own activity, so this window loses focus while it is
    // open and comes back with nothing focused. Remember the control that started
    // playback and hand focus back on resume, or the D-pad is dead on return.
    var lastDetailFocus by remember(seed.key) { mutableStateOf("play") }
    val lifecycleOwner = LocalLifecycleOwner.current
    var focusReseed by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        var wasPaused = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> wasPaused = true
                Lifecycle.Event.ON_RESUME -> if (wasPaused) {
                    wasPaused = false
                    focusReseed++
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(focusReseed) {
        if (focusReseed == 0) return@LaunchedEffect
        delay(120)
        val episodeNumber = lastDetailFocus.removePrefix("episode-").toIntOrNull()
        val restored = lastDetailFocus.startsWith("episode-") && episodeNumber != null &&
            runCatching { episodeFocusRequesters[episodeNumber]?.requestFocus() }.getOrNull() == true
        if (!restored) runCatching { playFocus.requestFocus() }
    }
    val trackedResume = remember(progress, seed.key) {
        progress.values
            .filter { it.itemKey == seed.key && it.isResumable }
            .maxByOrNull { it.updatedAt }
    }
    val today = remember { todayStamp() }
    val trackerSeasons = remember(item.seasons, sniffedSeasons[item.key]) {
        trackedSeasons(item, sniffedSeasons[item.key])
    }
    val watchedPairs = remember(progress, watchedMarks, item.key) {
        progress.values
            .filter { it.itemKey == item.key && it.season != null && it.episode != null && it.fraction >= 0.9f }
            .mapTo(mutableSetOf()) { (it.season ?: 0) to (it.episode ?: 0) } +
            watchedEpisodesFor(item.key, watchedMarks)
    }
    val recentPlayed = remember(progress, seed.key) { mostRecentPlayed(progress, seed.key) }
    val nextUp = remember(trackerSeasons, watchedPairs, recentPlayed, settings.nextUpFurthestWatched, today) {
        if (item.isSeries) {
            nextUpEpisode(
                trackerSeasons,
                watchedPairs,
                recentPlayed,
                settings.nextUpFurthestWatched,
                today
            )
        } else null
    }
    // Furthest-watched mode ignores a resume that sits behind the next up episode
    // (that is a rewatch), while most-recent mode keeps resuming where you stopped.
    val resumeHere = trackedResume?.takeIf { !item.isSeries || it.season == selectedSeason }
    val nextHere = nextUp?.takeIf { item.isSeries && it.first == selectedSeason }
    val (playNumber, playResume) = when {
        !item.isSeries -> null to resumeHere
        resumeHere != null && (nextHere == null || nextHere.second == resumeHere.episode) ->
            resumeHere.episode to resumeHere
        settings.nextUpFurthestWatched && nextHere != null -> nextHere.second to null
        resumeHere != null -> resumeHere.episode to resumeHere
        nextHere != null -> nextHere.second to null
        // No known episode means no button: episode 1 is only correct when it is
        // actually in the loaded list, never as a blind fallback.
        else -> episodes.firstOrNull { it.number > 0 }?.number to null
    }
    val playEpisode = episodes.firstOrNull { it.number == playNumber }
    val playSeason = if (item.isSeries) selectedSeason else null
    val playLabel = when {
        !item.isSeries -> if (playResume != null) "RESUME MOVIE" else "PLAY MOVIE"
        playNumber == null -> if (episodesLoading) "LOADING EPISODES…" else "NO EPISODES"
        playResume != null -> "RESUME S$selectedSeason E$playNumber"
        nextHere != null && playNumber == nextHere.second -> "NEXT UP S$selectedSeason E$playNumber"
        else -> "PLAY S$selectedSeason E$playNumber"
    }
    val playEnabled = !item.isSeries || playNumber != null

    LaunchedEffect(seed.key, detailRetry) {
        loading = true
        error = null
        if (settings.hasTmdbAccess) {
            try {
                val loaded = vm.loadDetails(seed.tmdbId, seed.type)
                item = loaded
                if (loaded.seasons.isNotEmpty()) {
                    val preferredSeason = if (loaded.isSeries) {
                        nextUpEpisode(
                            trackedSeasons(loaded, sniffedSeasons[seed.key]),
                            watchedPairs,
                            mostRecentPlayed(progress, seed.key),
                            settings.nextUpFurthestWatched,
                            todayStamp()
                        )?.first
                    } else null
                    selectedSeason = preferredSeason
                        ?.takeIf { season -> loaded.seasons.any { it.number == season } }
                        ?: trackedResume?.season
                            ?.takeIf { season -> loaded.seasons.any { it.number == season } }
                        ?: loaded.seasons.firstOrNull { it.number > 0 }?.number
                        ?: loaded.seasons.first().number
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                error = t.message
                library.firstOrNull { it.key == seed.key }?.let { item = it }
            }
        } else {
            library.firstOrNull { it.key == seed.key }?.let { item = it }
        }
        loading = false
        if (detailRetry == 0) {
            delay(220)
            runCatching { playFocus.requestFocus() }
        }
    }

    LaunchedEffect(seed.key, settings.hasTmdbAccess) {
        if (!settings.hasTmdbAccess) return@LaunchedEffect
        relatedLoading = true
        related = vm.loadRelated(seed)
        relatedLoading = false
    }

    LaunchedEffect(item.key, item.seasons, selectedSeason) {
        if (!item.isSeries || item.seasons.isEmpty()) return@LaunchedEffect
        // A season that disappeared (failed refresh, different item) must not
        // keep driving the episode query while another season is displayed.
        if (item.seasons.none { it.number == selectedSeason }) {
            selectedSeason = item.seasons.firstOrNull { it.number > 0 }?.number
                ?: item.seasons.first().number
        }
    }

    val selectedSeasonObj: Season? =
        item.seasons.firstOrNull { it.number == selectedSeason } ?: item.seasons.firstOrNull()

    val newEpisodes = remember(trackerSeasons, watchedPairs, today) {
        newEpisodeCount(trackerSeasons, watchedPairs, today)
    }
    val trackedSelectedSeason = selectedSeasonObj?.copy(
        episodes = episodes.ifEmpty { selectedSeasonObj.episodes }
    )
    val selectedSeasonWatched = trackedSelectedSeason?.let {
        isSeasonWatched(it, watchedPairs, today)
    } == true

    LaunchedEffect(selectedSeason, item.key, episodeRetry) {
        val season = item.seasons.firstOrNull { it.number == selectedSeason }
        if (season != null && season.episodes.isNotEmpty()) {
            episodes = season.episodes
            episodesError = null
            return@LaunchedEffect
        }
        // The previous season's list must never stand in for this one: its
        // episode numbers would be played against the newly selected season.
        episodes = emptyList()
        episodesError = null
        if (item.isSeries && settings.hasTmdbAccess) {
            episodesLoading = true
            try {
                val loaded = vm.loadSeasonEpisodes(item, selectedSeason)
                episodes = loaded
                item = item.copy(
                    seasons = item.seasons.map {
                        if (it.number == selectedSeason) {
                            it.copy(episodes = loaded, episodeCount = if (it.episodeCount > 0) it.episodeCount else loaded.size)
                        } else it
                    }
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                episodesError = t.message
            }
            episodesLoading = false
        }
    }

    val progressByPlaybackKey: Map<String, WatchProgress> = progress

    Box(Modifier.fillMaxSize().background(Cinema.Ink)) {
        PosterImage(
            url = item.backdropUrl ?: item.posterUrl,
            title = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Cinema.Ink.copy(alpha = 0.55f),
                        0.45f to Cinema.Ink.copy(alpha = 0.85f),
                        1f to Cinema.Ink
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Cinema.Ink.copy(alpha = 0.75f),
                        0.55f to Color.Transparent,
                        1f to Cinema.Ink.copy(alpha = 0.25f)
                    )
                )
        )

        LazyColumn(
            state = rememberScreenListState("detail.${seed.key}"),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 60.dp)
        ) {
            item(key = "topbar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvButton("BACK", onBack, style = TvButtonStyle.GHOST, glyph = "←", compact = true)
                    Spacer(Modifier.width(20.dp))
                    Text(
                        text = if (item.isSeries) "SERIES THEATER" else "MOVIE THEATER",
                        color = Cinema.TextMuted,
                        fontSize = 10.sp,
                        letterSpacing = 1.8.sp
                    )
                    Spacer(Modifier.weight(1f))
                    importStatus?.let {
                        Text(
                            it,
                            color = when {
                                quickImport.busy -> Cinema.TextSecondary
                                quickImport.success -> Cinema.Gold
                                else -> Cinema.Offline
                            },
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            item(key = "heroInfo") {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 48.dp)
                        .padding(top = 24.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        Modifier
                            .width(posterStyle.featureWidth)
                            .aspectRatio(posterStyle.aspectRatio)
                            .glassFrame(RoundedCornerShape(CinemaDimens.CardRadius))
                    ) {
                        PosterImage(
                            url = item.posterUrl,
                            title = item.title,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.width(30.dp))
                    Column(Modifier.weight(1f).padding(top = 4.dp)) {
                        playResume?.let {
                            ResumeBadge(it.resumeLabel)
                            Spacer(Modifier.height(10.dp))
                        }
                        if (!item.logoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(item.logoUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = item.title,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.BottomStart,
                                modifier = Modifier
                                    .widthIn(max = 440.dp)
                                    .height(76.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                        } else {
                            Text(
                                text = item.title,
                                color = Cinema.TextPrimary,
                                style = CinemaType.DetailTitle,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (item.tagline.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = item.tagline,
                                color = Cinema.GoldDim,
                                fontSize = 13.sp,
                                fontStyle = FontStyle.Italic
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item.rating?.let {
                                InfoPill("★ %.1f".format(it), Cinema.Gold, Cinema.Gold.copy(alpha = 0.14f))
                            }
                            item.certification?.takeIf { it.isNotBlank() }?.let {
                                InfoPill("⛨ $it", Cinema.TextPrimary, Cinema.PanelRaised)
                            }
                            item.yearLabel.takeIf { it.isNotBlank() }?.let {
                                InfoPill(it, Cinema.TextSecondary)
                            }
                            if (!item.isSeries) {
                                formatRuntime(item.runtimeMinutes)?.let {
                                    InfoPill(it, Cinema.TextSecondary)
                                }
                            } else {
                                formatRuntime(item.runtimeMinutes)?.let {
                                    InfoPill("$it / ep", Cinema.TextSecondary)
                                }
                                val seasonCount = item.seasons.count { it.number > 0 }
                                if (seasonCount > 0) InfoPill("$seasonCount seasons", Cinema.TextSecondary)
                                if (playNumber != null) {
                                    InfoPill(
                                        "Season $selectedSeason • Episode $playNumber",
                                        Cinema.Gold,
                                        Cinema.Gold.copy(alpha = 0.12f)
                                    )
                                }
                            }
                            item.voteCount?.takeIf { it > 0 }?.let {
                                InfoPill("${"%,d".format(it)} votes", Cinema.TextMuted)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (item.genres.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item.genres.take(3).forEach { genre ->
                                    InfoPill(genre, Cinema.TextSecondary, Cinema.Panel)
                                }
                                InfoPill(
                                    if (profile.audioLanguage.isBlank()) {
                                        "AUDIO · ORIGINAL"
                                    } else {
                                        "AUDIO · ${profile.audioLanguage.uppercase(Locale.ROOT)}"
                                    },
                                    Cinema.TextSecondary,
                                    Cinema.Panel
                                )
                                InfoPill(
                                    if (profile.subtitleLanguage.isBlank()) {
                                        "SUBS · OFF"
                                    } else {
                                        "SUBS · ${profile.subtitleLanguage.uppercase(Locale.ROOT)}"
                                    },
                                    Cinema.TextSecondary,
                                    Cinema.Panel
                                )
                            }
                        }
                        val creditLine = buildList {
                            if (item.cast.isNotEmpty()) {
                                add(("Cast: " + item.cast.take(4).joinToString(", ")))
                            }
                            if (item.director.isNotBlank()) {
                                add((if (item.isSeries) "Created by: " else "Director: ") + item.director)
                            }
                        }.joinToString("   ·   ")
                        if (creditLine.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = creditLine,
                                color = Cinema.TextMuted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 720.dp)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = item.overview.ifBlank { "No synopsis is available for this title yet." },
                            color = Cinema.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 720.dp)
                        )
                        if (error != null) {
                            Spacer(Modifier.height(12.dp))
                            ErrorBanner(
                                message = error ?: "",
                                modifier = Modifier.widthIn(max = 640.dp),
                                onRetry = { detailRetry++ }
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvButton(
                                label = playLabel,
                                glyph = "▶",
                                enabled = playEnabled,
                                onClick = {
                                    onPlay(
                                        item,
                                        playSeason,
                                        playNumber,
                                        playEpisode?.title ?: playResume?.episodeTitle
                                    )
                                },
                                modifier = Modifier.onFocusChanged {
                                    if (it.isFocused) lastDetailFocus = "play"
                                },
                                style = TvButtonStyle.PRIMARY,
                                dense = true,
                                focusRequester = playFocus
                            )
                            TvButton(
                                label = if (isFavorite) "FAVORITED" else "FAVORITE",
                                glyph = if (isFavorite) "★" else "☆",
                                style = TvButtonStyle.SECONDARY,
                                dense = true,
                                onClick = { vm.toggleFavorite(item) }
                            )
                            if (savedItem == null) {
                                TvButton(
                                    label = if (importingItem) "IMPORTING…" else "ADD TO LIBRARY",
                                    glyph = "＋",
                                    style = TvButtonStyle.SECONDARY,
                                    dense = true,
                                    enabled = !importingItem,
                                    onClick = { vm.importItem(item) }
                                )
                            }
                            // Only titles in the hall can appear in a Kids profile, so the
                            // approval lives next to them, and only adults can change it.
                            if (!profile.isKids) {
                                val inKids = item.key in kidsApproved
                                TvButton(
                                    label = if (inKids) "IN KIDS LIBRARY" else "ADD TO KIDS",
                                    glyph = if (inKids) "✓" else "⛨",
                                    style = TvButtonStyle.SECONDARY,
                                    dense = true,
                                    onClick = {
                                        if (!inKids && savedItem == null) vm.importItem(item)
                                        vm.kidsMode.setApproved(item.key, !inKids)
                                    }
                                )
                            }
                            if (savedItem != null) {
                                TvButton(
                                    label = "REMOVE",
                                    style = TvButtonStyle.DANGER,
                                    compact = true,
                                    onClick = { confirmRemove = true }
                                )
                            }
                            if (loading) {
                                Text("Refreshing metadata…", color = Cinema.TextMuted, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Native playback · direct catalog links and Stremio addon sources",
                            color = Cinema.TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            if (relatedLoading) {
                item(key = "relatedLoading") {
                    Column(Modifier.padding(start = 48.dp, end = 48.dp, top = 30.dp)) {
                        SectionHeader(title = "More Like This", subtitle = "Finding titles with the same pull")
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            repeat(6) {
                                ShimmerBox(
                                    Modifier
                                        .width(150.dp)
                                        .height(225.dp),
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }
                    }
                }
            } else if (related.isNotEmpty()) {
                item(key = "related") {
                    Box(Modifier.padding(top = 26.dp)) {
                        PosterRow(
                            title = "More Like This",
                            subtitle = "Picked from TMDB recommendations",
                            items = related,
                            onItemClick = onOpenRelated,
                            focusSurface = "detail.related"
                        )
                    }
                }
            }

            if (item.isSeries) {
                item(key = "seasonHeader") {
                    Column(Modifier.padding(start = 48.dp, end = 48.dp, top = 30.dp)) {
                        SectionHeader(
                            title = "Seasons & Episodes",
                            subtitle = if (item.seasons.isEmpty()) "No season data yet" else "${item.seasons.size} seasons"
                        )
                        if (isFavorite) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TvButton(
                                    label = if (selectedSeasonWatched) {
                                        "UNMARK S$selectedSeason"
                                    } else {
                                        "MARK S$selectedSeason WATCHED"
                                    },
                                    glyph = "✓",
                                    style = TvButtonStyle.SECONDARY,
                                    compact = true,
                                    enabled = trackedSelectedSeason != null,
                                    onClick = {
                                        trackedSelectedSeason?.let { season ->
                                            vm.toggleSeasonWatched(item, season, episodes)
                                            trackerNote = null
                                        }
                                    }
                                )
                                TvButton(
                                    label = if (markingPast) "MARKING…" else "MARK PAST SEASONS",
                                    glyph = "⏮",
                                    style = TvButtonStyle.SECONDARY,
                                    compact = true,
                                    enabled = !markingPast && selectedSeason > 1,
                                    onClick = {
                                        markingPast = true
                                        trackerNote = null
                                        vm.togglePastSeasonsWatched(item, selectedSeason) { marked, count ->
                                            markingPast = false
                                            trackerNote = when {
                                                count == 0 -> "No aired episodes in earlier seasons"
                                                marked -> "Marked $count episodes watched"
                                                else -> "Cleared $count watched episodes"
                                            }
                                        }
                                    }
                                )
                                Spacer(Modifier.width(6.dp))
                                newEpisodes?.let {
                                    Text(
                                        text = "$it NEW EPISODE${if (it == 1) "" else "S"}",
                                        color = Cinema.Gold,
                                        fontSize = 11.sp,
                                        letterSpacing = 1.2.sp
                                    )
                                }
                            }
                            trackerNote?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, color = Cinema.GoldDim, fontSize = 11.sp)
                            }
                        }
                    }
                }
                item(key = "seasonChips") {
                    LazyRow(
                        state = rememberScreenListState("detail.seasons.${seed.key}"),
                        contentPadding = PaddingValues(horizontal = 54.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(item.seasons, key = { "season-${it.number}" }) { season ->
                            Chip(
                                label = if (season.number == 0) "Specials" else season.displayName +
                                    (if (season.episodeCount > 0) " · ${season.episodeCount} ep" else ""),
                                selected = season.number == selectedSeason,
                                onClick = { selectedSeason = season.number }
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (episodesLoading) {
                    item(key = "episodesLoading") {
                        Column(
                            Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            repeat(3) {
                                ShimmerBox(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(84.dp),
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }
                    }
                }
                if (episodesError != null) {
                    item(key = "episodesError") {
                        Box(Modifier.padding(horizontal = 48.dp)) {
                            ErrorBanner(
                                message = episodesError ?: "",
                                onRetry = { episodeRetry++ }
                            )
                        }
                    }
                }
                if (!episodesLoading && episodes.isEmpty() && item.seasons.isNotEmpty()) {
                    item(key = "episodesEmpty") {
                        Box(Modifier.padding(horizontal = 48.dp, vertical = 8.dp)) {
                            Text(
                                "Episode titles and stills will load when the queue finishes enriching this series.",
                                color = Cinema.TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                items(
                    episodes,
                    key = { episode -> "episode-${selectedSeason}-${episode.number}" }
                ) { episode ->
                    val playbackKey = item.playbackKey(selectedSeason, episode.number)
                    val episodeFocus = remember(selectedSeason, episode.number) {
                        episodeFocusRequesters.getOrPut(episode.number) { FocusRequester() }
                    }
                    EpisodeRow(
                        episode = episode,
                        seasonNumber = selectedSeason,
                        progress = progressByPlaybackKey[playbackKey],
                        watched = (selectedSeason to episode.number) in watchedPairs,
                        focusRequester = episodeFocus,
                        onFocused = { lastDetailFocus = "episode-${episode.number}" },
                        onPlay = {
                            onPlay(item, selectedSeason, episode.number, episode.title)
                        }
                    )
                }
            } else {
                item(key = "about") {
                    Box(Modifier.padding(start = 48.dp, end = 48.dp, top = 30.dp)) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 760.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Cinema.Surface.copy(alpha = 0.75f))
                                .padding(20.dp)
                        ) {
                            Text(
                                "STREAM FORMATS",
                                color = Cinema.Gold,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Native playback supports MP4, MKV, WebM, HLS (.m3u8) and MPEG-DASH (.mpd) " +
                                    "with ABR, fast-start buffering and a D-pad control overlay. " +
                                    "Direct catalog links and Stremio addon sources fail over automatically.",
                                color = Cinema.TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        Dialog(onDismissRequest = { confirmRemove = false }) {
            Column(
                modifier = Modifier
                    .width(460.dp)
                    .glassSurface(
                        shape = RoundedCornerShape(CinemaDimens.CardRadius),
                        base = Glass.Sheet
                    )
                    .padding(24.dp)
            ) {
                Text(
                    "Remove from library?",
                    color = Cinema.TextPrimary,
                    style = CinemaType.SectionTitle
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "“${item.title}” will be removed from your catalog. It can be imported again at any time.",
                    color = Cinema.TextSecondary,
                    style = CinemaType.Body
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton(
                        "REMOVE",
                        style = TvButtonStyle.DANGER,
                        onClick = {
                            vm.removeFromLibrary(item)
                            confirmRemove = false
                            onBack()
                        }
                    )
                    TvButton("KEEP", style = TvButtonStyle.SECONDARY, onClick = { confirmRemove = false })
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    seasonNumber: Int,
    progress: WatchProgress?,
    watched: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onPlay: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 5.dp)
            .focusRequester(focusRequester)
            .glassSurface(
                shape = shape,
                focused = focused,
                base = if (focused) Glass.Control else Glass.Frame,
                tint = if (focused) Glass.AccentWash else Color.Transparent,
                sheen = 0.55f
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    sounds.focus()
                    onFocused()
                }
            }
            .clickable {
                sounds.select()
                onPlay()
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(148.dp)
                .aspectRatio(16f / 9f)
                .glassFrame(RoundedCornerShape(6.dp))
        ) {
            PosterImage(
                url = episode.stillUrl,
                title = episode.title,
                description = null,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                progress?.let {
                    Box(
                        Modifier
                            .fillMaxWidth(it.fraction)
                            .height(3.dp)
                            .background(Cinema.Gold)
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoPill(
                    "S$seasonNumber E${episode.number}",
                    Cinema.Gold,
                    Cinema.Gold.copy(alpha = 0.12f)
                )
                progress?.takeIf { it.isResumable }?.let {
                    Spacer(Modifier.width(8.dp))
                    ResumeBadge(it.resumeLabel, compact = true)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = episode.title,
                    color = if (focused) Cinema.Gold else Cinema.TextPrimary,
                    style = CinemaType.CardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            val airLabel = formatEpisodeAirDate(episode.airDate)
            val runtimeLabel = formatRuntime(episode.runtimeMinutes)
            if (airLabel != null || runtimeLabel != null) {
                Spacer(Modifier.height(5.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    airLabel?.let {
                        Text(
                            text = it.uppercase(Locale.US),
                            color = Cinema.GoldDim,
                            fontSize = 9.sp,
                            letterSpacing = 1.2.sp,
                            maxLines = 1
                        )
                    }
                    if (airLabel != null && runtimeLabel != null) {
                        Text("·", color = Cinema.TextMuted, fontSize = 10.sp)
                    }
                    runtimeLabel?.let {
                        Text(it, color = Cinema.TextMuted, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            if (episode.overview.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = episode.overview,
                    color = Cinema.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (watched) "✓ WATCHED" else "▶",
            color = if (watched || focused) Cinema.Gold else Cinema.TextMuted,
            fontSize = if (watched) 10.sp else 16.sp,
            letterSpacing = if (watched) 1.2.sp else 0.sp,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}

/** Last episode that was actually played, used when next up follows recency. */
private fun mostRecentPlayed(progress: Map<String, WatchProgress>, itemKey: String): Pair<Int, Int>? =
    progress.values
        .filter { it.itemKey == itemKey && it.season != null && it.episode != null && it.positionMs > 0 }
        .maxByOrNull { it.updatedAt }
        ?.let { (it.season ?: 0) to (it.episode ?: 0) }
