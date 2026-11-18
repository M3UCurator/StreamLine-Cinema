package com.streamline.cinema.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.ChartUiState
import com.streamline.cinema.data.ChartCountries
import com.streamline.cinema.data.ChartGenres
import com.streamline.cinema.data.ChartRequest
import com.streamline.cinema.data.ChartSelection
import com.streamline.cinema.data.ChartSource
import com.streamline.cinema.data.ChartTrack
import com.streamline.cinema.data.MusicTrack
import com.streamline.cinema.ui.Chip
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.EmptyState
import com.streamline.cinema.ui.ErrorBanner
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.LocalFocusMemory
import com.streamline.cinema.ui.LocalUiSounds
import com.streamline.cinema.ui.PosterImage
import com.streamline.cinema.ui.RestoreSurfaceFocus
import com.streamline.cinema.ui.SelectionGroup
import com.streamline.cinema.ui.ShimmerBox
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.TvTextField
import com.streamline.cinema.ui.glassSurface
import com.streamline.cinema.ui.rememberMusicPlayback
import com.streamline.cinema.ui.rememberScreenListState
import java.util.Locale
import kotlinx.coroutines.delay

private const val TRACK_SURFACE = "music.tracks"
private const val RESULT_SURFACE = "music.results"
private const val CHART_SURFACE = "music.chart"
private const val CHART_OPEN_HINT = "↗ OPEN"

@Composable
fun MusicboxScreen(
    vm: CinemaViewModel,
    onOpenSettings: () -> Unit
) {
    val music by vm.music.collectAsState()
    val chart by vm.chart.collectAsState()
    val settings by vm.settings.collectAsState()
    val history by vm.musicHistory.collectAsState()
    val playback = rememberMusicPlayback()
    val chartsOn = settings.musicCharts
    val chartRequest = ChartSelection.request(
        sourceName = settings.musicChartSource,
        countryCode = settings.musicChartCountry,
        genreId = settings.musicChartGenre,
        youtubeKey = settings.youtubeApiKey
    )
    val chartSources = ChartSelection.available(settings.youtubeApiKey)
    val showCharts = chartsOn && chart.active
    // Chart, search and catalog rows keep separate scroll positions.
    val listSurface = when {
        showCharts -> CHART_SURFACE
        music.showingResults -> RESULT_SURFACE
        else -> TRACK_SURFACE
    }
    RestoreSurfaceFocus(listSurface)
    var picker by remember { mutableStateOf<ChartPicker?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }

    // Reuses an existing catalog list; a catalog whose tracks are already loaded
    // stays put, so returning to the tab never refetches the same rows.
    LaunchedEffect(Unit) { vm.refreshMusic() }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 48.dp, end = 48.dp, top = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("AUDIO", color = Cinema.Gold, style = CinemaType.Kicker)
                    Spacer(Modifier.height(4.dp))
                    Text("Musicbox", color = Cinema.TextPrimary, style = CinemaType.PageTitle)
                    Text(
                        text = if (showCharts) {
                            buildString {
                                append(chartRequest.describe())
                                if (chart.loading) append(" · loading")
                                else append(" · ${chart.rows.size} rows")
                            }
                        } else {
                            buildString {
                                val count = music.catalogs.size
                                append("$count catalog${if (count == 1) "" else "s"}")
                                music.selected?.let { append(" · ${it.addonName}") }
                                when {
                                    music.showingResults ->
                                        append(" · ${music.results.size} results")
                                    music.tracks.isNotEmpty() ->
                                        append(" · ${music.tracks.size} tracks")
                                }
                                if (music.searching) append(" · searching")
                                else if (music.loading) append(" · loading")
                            }
                        },
                        color = Cinema.TextMuted,
                        style = CinemaType.BodySmall
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (playback.currentKey != null) {
                        TvButton(
                            label = if (playback.isPlaying) "PAUSE" else "PLAY",
                            onClick = { playback.toggle() },
                            style = TvButtonStyle.PRIMARY,
                            compact = true,
                            glyph = if (playback.isPlaying) "❚❚" else "▶"
                        )
                    }
                    if (music.query.isNotBlank()) {
                        TvButton(
                            "CATALOG",
                            { vm.clearMusicSearch() },
                            style = TvButtonStyle.GHOST,
                            compact = true,
                            glyph = "▤"
                        )
                    }
                    TvButton(
                        "ADDONS",
                        onOpenSettings,
                        style = TvButtonStyle.GHOST,
                        compact = true,
                        glyph = "ⓘ"
                    )
                    TvButton(
                        "REFRESH",
                        {
                            if (showCharts) vm.ensureChart(force = true)
                            else vm.refreshMusic(force = true)
                        },
                        style = TvButtonStyle.SECONDARY,
                        compact = true,
                        glyph = "⟳",
                        enabled = !music.loading && !chart.loading
                    )
                }
            }
            if (chartsOn) {
                Spacer(Modifier.height(10.dp))
                ChartBand(
                    request = chartRequest,
                    sources = chartSources,
                    active = chart.active,
                    loading = chart.loading,
                    status = when {
                        chart.loading -> "Loading…"
                        chart.note != null -> chart.note
                        showCharts -> "${chart.rows.size} rows"
                        else -> null
                    },
                    onSource = { source ->
                        openError = null
                        vm.selectChartSource(source)
                    },
                    onCountry = { picker = ChartPicker.COUNTRY },
                    onGenre = { picker = ChartPicker.GENRE }
                )
            }
            Spacer(Modifier.height(10.dp))
            if (music.catalogs.isNotEmpty()) {
                LazyRow(
                    state = rememberScreenListState("music.catalogs"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(music.catalogs, key = { it.key }) { catalog ->
                        Chip(
                            label = "${catalog.addonName} · ${catalog.name}",
                            selected = !showCharts && music.selected?.key == catalog.key,
                            onClick = {
                                openError = null
                                vm.selectMusicCatalog(catalog)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            // A selection group belongs to the catalog chips; the band has its own pills.
            if (!showCharts) {
                music.selected?.selectableExtras.orEmpty().forEach { extra ->
                    SelectionGroup(
                        extra = extra,
                        selected = music.filters[extra.name],
                        onSelect = { value -> vm.setMusicFilter(extra.name, value) },
                        stateKeyPrefix = "music.group"
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (music.supportsSearch && !showCharts) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.weight(1f).widthIn(max = 760.dp)) {
                        TvTextField(
                            value = music.query,
                            onValueChange = vm::setMusicQuery,
                            placeholder = "Search tracks across your music addons…",
                            leadingGlyph = "⌕"
                        )
                    }
                    TvButton(
                        label = if (music.searching) "SEARCHING" else "SEARCH",
                        glyph = "⌕",
                        style = TvButtonStyle.PRIMARY,
                        enabled = !music.searching && music.query.isNotBlank(),
                        onClick = { vm.runMusicSearch() }
                    )
                }
            }
            music.message?.let { message ->
                Spacer(Modifier.height(12.dp))
                ErrorBanner(message)
            }
            playback.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                ErrorBanner(message)
            }
            openError?.let { message ->
                Spacer(Modifier.height(12.dp))
                ErrorBanner(message)
            }
            if (history.recent.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("RECENT", color = Cinema.Gold, style = CinemaType.Kicker)
                Spacer(Modifier.height(6.dp))
                LazyRow(
                    state = rememberScreenListState("music.recent"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val resume = history.resume
                    val resumeTrack = resume?.current
                    if (resume != null && resumeTrack != null && resume.isResumable) {
                        item(key = "resume") {
                            Chip(
                                label = "▶ ${shortTrackTitle(resumeTrack.title)} · " +
                                    trackDuration(resume.positionMs),
                                selected = playback.currentKey == resumeTrack.key,
                                onClick = {
                                    playback.play(
                                        resume.queue,
                                        resume.index,
                                        resume.positionMs
                                    )
                                }
                            )
                        }
                    }
                    itemsIndexed(
                        history.recent,
                        key = { _, track -> "recent|${track.key}" }
                    ) { index, track ->
                        Chip(
                            label = shortTrackTitle(track.title),
                            selected = playback.currentKey == track.key,
                            onClick = { playback.play(history.recent, index) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        when {
            showCharts -> ChartBody(
                chart = chart,
                request = chartRequest,
                onOpen = { track -> openError = vm.openChartTrack(track) },
                onRetry = { vm.ensureChart(force = true) }
            )
            // The first manifest pass has not reported yet; avoid flashing the
            // "no catalogs" state before it does.
            !music.loaded && !music.available -> MusicSkeleton()
            music.loading && music.tracks.isEmpty() -> MusicSkeleton()
            !music.available -> EmptyState(
                title = "No music catalogs",
                message = "Add a Stremio addon that publishes a `music` catalog in Settings. " +
                    "Its catalogs, selection groups, search and tracks appear here.",
                actionLabel = "OPEN SETTINGS",
                onAction = onOpenSettings
            )
            music.searching -> MusicSkeleton()
            music.showingResults && music.results.isEmpty() -> EmptyState(
                title = "No tracks matched",
                message = "Nothing in your music addons matched \"${music.query.trim()}\".",
                actionLabel = "BACK TO CATALOG",
                onAction = { vm.clearMusicSearch() }
            )
            music.visibleTracks.isEmpty() -> EmptyState(
                title = "No tracks here",
                message = "Pick another catalog or selection group, or refresh the addon.",
                actionLabel = "REFRESH",
                onAction = { vm.refreshMusic(force = true) }
            )
            else -> LazyColumn(
                state = rememberScreenListState(listSurface),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(music.visibleTracks, key = { _, track -> track.key }) { index, track ->
                    MusicTrackRow(
                        track = track,
                        surface = listSurface,
                        playing = playback.currentKey == track.key,
                        onPlay = { playback.play(music.visibleTracks, index) }
                    )
                }
            }
        }
    }

    picker?.let { which ->
        when (which) {
            ChartPicker.COUNTRY -> ChartPickerDialog(
                title = "Chart country",
                selectedKey = chartRequest.country.code,
                options = ChartCountries.all.map { "${it.name} (${it.code})" to it.code },
                onPick = { code ->
                    picker = null
                    ChartCountries.find(code)?.let(vm::setChartCountry)
                },
                onDismiss = { picker = null }
            )
            ChartPicker.GENRE -> ChartPickerDialog(
                title = "Chart genre",
                selectedKey = chartRequest.genre.id,
                options = ChartGenres.all.map { it.label to it.id },
                onPick = { id ->
                    picker = null
                    ChartGenres.find(id)?.let(vm::setChartGenre)
                },
                onDismiss = { picker = null }
            )
        }
    }
}

private enum class ChartPicker { COUNTRY, GENRE }

/**
 * The CHARTS band. Only the dimensions the source can honour get a pill, so
 * Deezer shows no country control and YouTube shows no genre control.
 */
@Composable
private fun ChartBand(
    request: ChartRequest,
    sources: List<ChartSource>,
    active: Boolean,
    loading: Boolean,
    status: String?,
    onSource: (ChartSource) -> Unit,
    onCountry: () -> Unit,
    onGenre: () -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "CHARTS",
                color = Cinema.Gold,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp,
                modifier = Modifier.width(64.dp)
            )
            sources.forEach { source ->
                Chip(
                    label = source.label,
                    selected = active && request.source == source,
                    onClick = { onSource(source) },
                    compact = true
                )
            }
            if (request.source.supportsCountry) {
                TvButton(
                    label = request.country.name.uppercase(Locale.US),
                    onClick = onCountry,
                    style = TvButtonStyle.GHOST,
                    compact = true,
                    glyph = "▾"
                )
            }
            if (request.source.supportsGenre) {
                TvButton(
                    label = request.genre.label.uppercase(Locale.US),
                    onClick = onGenre,
                    style = TvButtonStyle.GHOST,
                    compact = true,
                    glyph = "▾"
                )
            }
            if (loading) {
                Spacer(Modifier.width(2.dp))
                Text("…", color = Cinema.TextMuted, style = CinemaType.Meta)
            }
        }
        status?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                color = Cinema.TextMuted,
                style = CinemaType.Meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChartBody(
    chart: ChartUiState,
    request: ChartRequest,
    onOpen: (ChartTrack) -> Unit,
    onRetry: () -> Unit
) {
    when {
        chart.loading && chart.rows.isEmpty() -> MusicSkeleton("Loading ${request.describe()}…")
        chart.rows.isEmpty() -> EmptyState(
            title = "No chart rows",
            message = chart.message ?: "Nothing came back for ${request.describe()}.",
            actionLabel = "RETRY",
            onAction = onRetry
        )
        else -> LazyColumn(
            state = rememberScreenListState(CHART_SURFACE),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(chart.rows, key = { _, track -> track.id }) { index, track ->
                ChartTrackRow(track = track, rank = index + 1, onOpen = { onOpen(track) })
            }
        }
    }
}

/**
 * A chart row is a destination, not a queue item: ENTER hands it to whatever app
 * owns it, so the row says OPEN instead of the playback affordances.
 */
@Composable
private fun ChartTrackRow(
    track: ChartTrack,
    rank: Int,
    onOpen: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusMemory = LocalFocusMemory.current
    val sounds = LocalUiSounds.current
    val rowFocus = remember { FocusRequester() }

    LaunchedEffect(focusMemory.restoreTick(CHART_SURFACE), track.id) {
        if (focusMemory.recall(CHART_SURFACE) == track.id) {
            delay(90)
            runCatching { rowFocus.requestFocus() }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(rowFocus)
            .glassSurface(
                shape = RoundedCornerShape(12.dp),
                focused = focused,
                base = Glass.Control,
                tint = if (focused) Glass.AccentWash else Color.Transparent
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    focusMemory.record(CHART_SURFACE, track.id)
                    sounds.focus()
                }
            }
            .combinedClickable(
                onClick = {
                    sounds.select()
                    onOpen()
                }
            )
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$rank",
            color = if (focused) Cinema.Gold else Cinema.TextMuted,
            style = CinemaType.Meta,
            modifier = Modifier.width(26.dp)
        )
        PosterImage(
            url = track.artwork,
            title = track.title,
            description = null,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = Cinema.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOf(track.artist, track.album, track.genreLabel)
                .filter { !it.isNullOrBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    color = Cinema.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (track.durationMs > 0) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = trackDuration(track.durationMs),
                color = Cinema.TextMuted,
                style = CinemaType.Meta
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = CHART_OPEN_HINT,
            color = if (focused) Cinema.Gold else Cinema.TextMuted,
            fontSize = 10.sp
        )
    }
}

/** A row tap starts the visible list as the queue, playing this track first. */
@Composable
private fun MusicTrackRow(
    track: MusicTrack,
    surface: String,
    playing: Boolean,
    onPlay: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusMemory = LocalFocusMemory.current
    val sounds = LocalUiSounds.current
    val rowFocus = remember { FocusRequester() }

    LaunchedEffect(focusMemory.restoreTick(surface), track.key) {
        if (focusMemory.recall(surface) == track.key) {
            delay(90)
            runCatching { rowFocus.requestFocus() }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(rowFocus)
            .glassSurface(
                shape = RoundedCornerShape(12.dp),
                focused = focused,
                base = Glass.Control,
                tint = when {
                    focused -> Glass.AccentWash
                    playing -> Glass.AccentWash.copy(alpha = 0.4f)
                    else -> Color.Transparent
                }
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    focusMemory.record(surface, track.key)
                    sounds.focus()
                }
            }
            .combinedClickable(
                onClick = {
                    sounds.select()
                    onPlay()
                }
            )
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (playing) stateDescription = "Playing"
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PosterImage(
            url = track.artwork,
            title = track.title,
            description = null,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (playing) Cinema.Gold else Cinema.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOf(track.artist, track.album)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    color = Cinema.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (playing) {
            Spacer(Modifier.width(12.dp))
            Text("▶", color = Cinema.Gold, fontSize = 11.sp)
        }
        if (track.durationMs > 0) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = trackDuration(track.durationMs),
                color = Cinema.TextMuted,
                style = CinemaType.Meta
            )
        }
    }
}

@Composable
private fun MusicSkeleton(label: String = "Reading music catalogs…") {
    Column(Modifier.padding(horizontal = 48.dp)) {
        Text(label, color = Cinema.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))
        repeat(6) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShimmerBox(Modifier.size(52.dp), shape = RoundedCornerShape(8.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    ShimmerBox(
                        Modifier.fillMaxWidth(0.45f).height(12.dp),
                        shape = RoundedCornerShape(4.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    ShimmerBox(
                        Modifier.fillMaxWidth(0.25f).height(10.dp),
                        shape = RoundedCornerShape(4.dp)
                    )
                }
            }
        }
    }
}

private fun shortTrackTitle(title: String): String =
    if (title.length <= 40) title else title.take(39).trimEnd() + "…"

private fun trackDuration(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
