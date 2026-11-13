package com.streamline.cinema.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.data.CatalogExtra
import com.streamline.cinema.data.DEAD_CHANNEL_FAILURES
import com.streamline.cinema.data.EpgGuide
import com.streamline.cinema.data.EpgNowNext
import com.streamline.cinema.data.EpgProgram
import com.streamline.cinema.data.LiveChannel
import com.streamline.cinema.data.LiveSourceKind
import com.streamline.cinema.data.PlaybackOption
import com.streamline.cinema.ui.Chip
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.EmptyState
import com.streamline.cinema.ui.ErrorBanner
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.InfoPill
import com.streamline.cinema.ui.LocalFocusMemory
import com.streamline.cinema.ui.LocalPosterStyle
import com.streamline.cinema.ui.LocalUiSounds
import com.streamline.cinema.ui.PosterImage
import com.streamline.cinema.ui.RestoreSurfaceFocus
import com.streamline.cinema.ui.ShimmerBox
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.glassFrame
import com.streamline.cinema.ui.glassSurface
import com.streamline.cinema.ui.rememberScreenGridState
import com.streamline.cinema.ui.rememberScreenListState
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.delay

private const val CLOCK_TICK_MS = 10_000L

@Composable
fun LiveScreen(
    vm: CinemaViewModel,
    onPlay: (PlaybackOption, String, List<LiveChannel>, Int) -> Unit,
    onOpenSettings: () -> Unit
) {
    val live by vm.live.collectAsState()
    val epg by vm.epg.collectAsState()
    val health by vm.channelHealth.collectAsState()
    val livePrefs by vm.livePrefs.collectAsState()
    val iptvSources by vm.iptvSources.collectAsState()
    val profile by vm.activeProfile.collectAsState()
    val approvedIds by vm.kidsMode.approved.collectAsState()
    var showGuide by remember { mutableStateOf(false) }
    var showGuideStatus by remember { mutableStateOf(false) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var arranging by remember { mutableStateOf(false) }

    // Kids profiles see only channels a parent approved for the Kids library.
    val channels = remember(live.channels, profile.isKids, approvedIds, favoritesOnly, livePrefs.favorites) {
        live.channels
            .filter { !profile.isKids || it.key in approvedIds }
            .filter { !favoritesOnly || it.key in livePrefs.favorites }
    }
    val deadKeys = remember(channels, health) {
        channels.filter { health[it.key]?.dead == true }.map { it.key }.toSet()
    }

    // Only playlists that actually lost entries are worth surfacing; a clean
    // "142 channels" report would just add another line of noise.
    val playlistProblems = remember(iptvSources, live.selected) {
        if (live.selected?.sourceKind == LiveSourceKind.IPTV) {
            iptvSources.filter {
                it.lastReport.contains(" skipped") || it.lastReport.contains(" malformed")
            }.take(2)
        } else {
            emptyList()
        }
    }
    val statusDetails = remember(epg, playlistProblems) {
        buildList {
            epg.message?.takeIf { it.isNotBlank() }?.let { add("EPG · $it") }
            epg.report?.takeIf { it.isNotBlank() }?.let { add("GUIDE · $it") }
            epg.shiftNotices.forEach { add("◷ $it") }
            playlistProblems.forEach { source ->
                add("PLAYLIST · ${source.name.ifBlank { "IPTV" }} · ${source.lastReport}")
            }
        }
    }

    // now/next and the progress bars are evaluated against the device clock,
    // so refresh that reading while the screen stays open.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = System.currentTimeMillis()
        }
    }

    LaunchedEffect(Unit) { vm.refreshLive() }
    RestoreSurfaceFocus("live.grid")
    val posterStyle = LocalPosterStyle.current

    // Closing the guide should put the viewer back on the GUIDE button rather
    // than on whatever control the Compose focus traversal picks next.
    val view = LocalView.current
    val guideFocus = remember { FocusRequester() }
    var guideFocusTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(guideFocusTick) {
        if (guideFocusTick == 0) return@LaunchedEffect
        delay(170)
        if (!view.hasFocus()) runCatching { view.requestFocusFromTouch() }
        runCatching { guideFocus.requestFocus() }
    }
    val closeGuide = {
        showGuide = false
        guideFocusTick += 1
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 48.dp, end = 48.dp, top = 16.dp)) {
                // At larger font scales the action strip is wider than the title
                // column can spare, so it drops to its own line instead of clipping.
                val stackActions = LocalDensity.current.fontScale > 1.15f
                val titleBlock: @Composable () -> Unit = {
                    Column {
                        Text(
                            text = "ON AIR",
                            color = Cinema.Offline,
                            style = CinemaType.Kicker
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Live",
                            color = Cinema.TextPrimary,
                            style = CinemaType.PageTitle
                        )
                        Text(
                            text = buildString {
                                append("${channels.size} channels")
                                if (live.catalogs.size > 0) {
                                    append(
                                        if (live.catalogs.size == 1) " · 1 catalog"
                                        else " · ${live.catalogs.size} catalogs"
                                    )
                                }
                                if (favoritesOnly) {
                                    append(" · saved only")
                                } else if (livePrefs.favorites.isNotEmpty()) {
                                    append(" · ${livePrefs.favorites.size} saved")
                                }
                                if (epg.loading && epg.guide.isEmpty) append(" · guide loading")
                            },
                            color = Cinema.TextMuted,
                            style = CinemaType.BodySmall
                        )
                    }
                }
                val actions: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (deadKeys.isNotEmpty()) {
                            TvButton(
                                "CLEAR DEAD (${deadKeys.size})",
                                { vm.forgetDeadChannels(deadKeys) },
                                style = TvButtonStyle.GHOST,
                                compact = true,
                                glyph = "✕"
                            )
                        }
                        if (livePrefs.favorites.isNotEmpty() || favoritesOnly) {
                            TvButton(
                                "SAVED",
                                { favoritesOnly = !favoritesOnly },
                                style = if (favoritesOnly) TvButtonStyle.PRIMARY else TvButtonStyle.SECONDARY,
                                compact = true,
                                glyph = "★"
                            )
                        }
                        if (live.channels.isNotEmpty()) {
                            TvButton(
                                if (livePrefs.listView) "GRID" else "LIST",
                                { vm.setLiveListView(!livePrefs.listView) },
                                style = TvButtonStyle.GHOST,
                                compact = true,
                                glyph = if (livePrefs.listView) "▦" else "☰"
                            )
                            TvButton(
                                if (arranging) "DONE" else "ARRANGE",
                                { arranging = !arranging },
                                style = if (arranging) TvButtonStyle.PRIMARY else TvButtonStyle.GHOST,
                                compact = true,
                                glyph = if (arranging) "✓" else "⇄"
                            )
                        }
                        TvButton(
                            "GUIDE",
                            { showGuide = true },
                            style = TvButtonStyle.SECONDARY,
                            compact = true,
                            glyph = "≡",
                            enabled = !epg.guide.isEmpty && channels.isNotEmpty(),
                            focusRequester = guideFocus
                        )
                        TvButton(
                            "REFRESH",
                            { vm.refreshLive(force = true) },
                            style = TvButtonStyle.SECONDARY,
                            compact = true,
                            glyph = "⟳",
                            enabled = !live.loading
                        )
                        TvButton(
                            "DETAILS",
                            { showGuideStatus = true },
                            style = TvButtonStyle.GHOST,
                            compact = true,
                            glyph = "ⓘ",
                            enabled = statusDetails.isNotEmpty()
                        )
                    }
                }
                if (stackActions) {
                    titleBlock()
                    Spacer(Modifier.height(10.dp))
                    actions()
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        titleBlock()
                        Spacer(Modifier.weight(1f))
                        actions()
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (live.catalogs.isNotEmpty()) {
                    LazyRow(
                        state = rememberScreenListState("live.catalogs"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(live.catalogs.size) { index ->
                            val catalog = live.catalogs[index]
                            Chip(
                                label = "${catalog.addonName} · ${catalog.name}",
                                selected = live.selected?.key == catalog.key,
                                onClick = { vm.selectLiveCatalog(catalog) },
                                modifier = if (arranging) {
                                    Modifier.onKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                        when (event.key) {
                                            Key.DirectionLeft -> {
                                                vm.moveLiveCatalog(catalog, -1); true
                                            }
                                            Key.DirectionRight -> {
                                                vm.moveLiveCatalog(catalog, 1); true
                                            }
                                            else -> false
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (arranging) {
                    Text(
                        "ARRANGE · ▶ ◀ catalogs and groups · ▲ ▼ channels in list · hold OK stars a channel",
                        color = Cinema.Gold,
                        fontSize = 10.sp,
                        letterSpacing = 0.3.sp
                    )
                    Spacer(Modifier.height(8.dp))
                }
                live.message?.let { message ->
                    ErrorBanner(message)
                    Spacer(Modifier.height(12.dp))
                }
                val extras = live.selected?.selectableExtras.orEmpty()
                if (extras.isNotEmpty()) {
                    extras.forEach { extra ->
                        SelectionGroup(
                            extra = extra,
                            selected = live.filters[extra.name],
                            onSelect = { value -> vm.setLiveFilter(extra.name, value) },
                            arranging = arranging,
                            onMove = { group, delta -> vm.moveLiveGroup(extra.name, group, delta) }
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    if (live.filters.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "FILTERED · ${live.filters.values.joinToString(" · ")}",
                                color = Cinema.Gold,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(Modifier.width(10.dp))
                            Chip(label = "Clear all", selected = false, onClick = { vm.clearLiveFilters() })
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(2.dp))
                }
                live.resolving?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InfoPill("Tuning…", Cinema.Pinging, Cinema.PanelRaised)
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }

            when {
                live.loading && channels.isEmpty() -> LiveSkeletonGrid()
                channels.isEmpty() && live.message != null -> EmptyState(
                    title = "Live feed unavailable",
                    message = live.message ?: "The live catalogs could not be reached.",
                    actionLabel = "RETRY",
                    onAction = { vm.refreshLive(force = true) }
                )
                channels.isEmpty() && profile.isKids -> EmptyState(
                    title = "No approved channels",
                    message = "A parent can approve live channels from Settings ▸ Parental ▸ " +
                        "Manage Kids Library. Only approved channels appear here.",
                    actionLabel = "OPEN SETTINGS",
                    onAction = onOpenSettings
                )
                channels.isEmpty() && favoritesOnly && live.channels.isNotEmpty() -> EmptyState(
                    title = "No saved channels",
                    message = "Hold OK on a channel to star it. Saved channels stay at the " +
                        "top of the list across restarts and playlists.",
                    actionLabel = "SHOW ALL",
                    onAction = { favoritesOnly = false }
                )
                channels.isEmpty() -> EmptyState(
                    title = "No live channels yet",
                    message = "Add a live Stremio addon such as FayaTV, or an IPTV playlist " +
                        "(M3U/M3U8/XSPF) in Settings. Channels appear here and open straight " +
                        "into the native player.",
                    actionLabel = "OPEN SETTINGS",
                    onAction = onOpenSettings
                )
                else -> if (livePrefs.listView) {
                    LazyColumn(
                        state = rememberScreenListState("live.list"),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(channels, key = { it.key }) { channel ->
                            LiveChannelRow(
                                channel = channel,
                                busy = live.resolving == channel.key,
                                dead = channel.key in deadKeys,
                                now = epg.guide.nowNext(channel.epgId, channel.name, nowMs)?.now,
                                nowMs = nowMs,
                                favorite = channel.key in livePrefs.favorites,
                                arranging = arranging,
                                onToggleFavorite = { vm.toggleLiveFavorite(channel) },
                                onMove = { delta -> vm.moveLiveChannel(channel, delta) },
                                onClick = {
                                    vm.resolveLive(channel) { option ->
                                        if (option != null) {
                                            // Hand the whole list to the player so arrows can surf channels.
                                            onPlay(
                                                option,
                                                channel.name,
                                                channels,
                                                channels.indexOf(channel)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        item {
                            Text(
                                "Streams resolve through the addon or play straight from your playlist.",
                                color = Cinema.TextMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                } else LazyVerticalGrid(
                    state = rememberScreenGridState("live.grid"),
                    columns = GridCells.Adaptive(posterStyle.channelWidth + 16.dp),
                    contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(channels, key = { it.key }) { channel ->
                        ChannelCard(
                            channel = channel,
                            busy = live.resolving == channel.key,
                            dead = channel.key in deadKeys,
                            now = epg.guide.nowNext(channel.epgId, channel.name, nowMs)?.now,
                            nowMs = nowMs,
                            favorite = channel.key in livePrefs.favorites,
                            arranging = arranging,
                            onToggleFavorite = { vm.toggleLiveFavorite(channel) },
                            onMove = { delta -> vm.moveLiveChannel(channel, delta) },
                            onClick = {
                                vm.resolveLive(channel) { option ->
                                    if (option != null) {
                                        // Hand the whole grid to the player so arrows can surf channels.
                                        onPlay(
                                            option,
                                            channel.name,
                                            channels,
                                            channels.indexOf(channel)
                                        )
                                    }
                                }
                            }
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Streams resolve through the addon or play straight from your playlist.",
                            color = Cinema.TextMuted,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        if (showGuide) {
            GuideOverlay(
                title = live.selected?.let { "${it.addonName} · ${it.name}" }.orEmpty(),
                channels = channels,
                epg = epg.guide,
                nowMs = nowMs,
                resolving = live.resolving,
                onTune = { channel ->
                    vm.resolveLive(channel) { option ->
                        if (option != null) {
                            showGuide = false
                            onPlay(
                                option,
                                channel.name,
                                channels,
                                channels.indexOf(channel)
                            )
                        }
                    }
                },
                onRefresh = { vm.refreshEpg(force = true) },
                onClose = closeGuide
            )
        }

        if (showGuideStatus) {
            GuideStatusDialog(
                details = statusDetails,
                onClose = { showGuideStatus = false }
            )
        }
    }
}

@Composable
private fun SelectionGroup(
    extra: CatalogExtra,
    selected: String?,
    onSelect: (String) -> Unit,
    arranging: Boolean = false,
    onMove: (String, Int) -> Unit = { _, _ -> }
) {
    // The group label is a fixed gutter, so it has to widen with the font or
    // "GROUP TITLE" gets clipped at 1.5x.
    val labelWidth = (88f * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)).dp
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = extra.name.replace('-', ' ').uppercase(),
            color = Cinema.TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
            maxLines = 1,
            modifier = Modifier.width(labelWidth)
        )
        LazyRow(
            state = rememberScreenListState("live.group.${extra.name}"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            item(key = "all") {
                Chip(label = "All", selected = selected == null, onClick = { onSelect("") })
            }
            items(extra.options.size, key = { index -> extra.options[index] }) { index ->
                val value = extra.options[index]
                Chip(
                    label = value,
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    modifier = if (arranging) {
                        Modifier.onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> {
                                    onMove(value, -1); true
                                }
                                Key.DirectionRight -> {
                                    onMove(value, 1); true
                                }
                                else -> false
                            }
                        }
                    } else {
                        Modifier
                    }
                )
            }
        }
    }
}

@Composable
private fun LiveSkeletonGrid() {
    Column(Modifier.padding(horizontal = 48.dp)) {
        Text("Reading live catalogs…", color = Cinema.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(5) {
                    Column(Modifier.width(220.dp)) {
                        ShimmerBox(
                            Modifier
                                .fillMaxWidth()
                                .height(124.dp),
                            shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        ShimmerBox(
                            Modifier
                                .fillMaxWidth(0.7f)
                                .height(12.dp),
                            shape = RoundedCornerShape(4.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(
    channel: LiveChannel,
    busy: Boolean,
    dead: Boolean,
    now: EpgProgram?,
    nowMs: Long,
    favorite: Boolean,
    arranging: Boolean,
    onToggleFavorite: () -> Unit,
    onMove: (Int) -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusMemory = LocalFocusMemory.current
    val sounds = LocalUiSounds.current
    val cardFocus = remember { FocusRequester() }
    val posterStyle = LocalPosterStyle.current
    val cardShape = RoundedCornerShape(12.dp)
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, tween(120), label = "channelScale")

    LaunchedEffect(focusMemory.restoreTick("live.grid"), channel.key) {
        if (focusMemory.recall("live.grid") == channel.key) {
            delay(90)
            runCatching { cardFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .width(posterStyle.channelWidth)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(cardFocus)
            .onKeyEvent { event ->
                if (!arranging || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onMove(-1); true
                    }
                    Key.DirectionRight -> {
                        onMove(1); true
                    }
                    else -> false
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    focusMemory.record("live.grid", channel.key)
                    sounds.focus()
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = buildString {
                    append(channel.name)
                    if (favorite) append(", saved")
                    if (dead) append(", marked dead")
                    now?.let { append(", now ${it.title}") }
                }
            }
            .combinedClickable(
                onClick = {
                    sounds.select()
                    onClick()
                },
                onLongClick = {
                    sounds.select()
                    onToggleFavorite()
                }
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Cinema.Panel)
                .glassFrame(cardShape, focused = focused)
        ) {
            PosterImage(
                url = channel.poster,
                title = channel.name,
                description = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.24f to Color.Transparent,
                            0.62f to Color.Black.copy(alpha = 0.38f),
                            1f to Color.Black.copy(alpha = 0.96f)
                        )
                    )
            )
            Text(
                text = if (busy) "TUNING…" else "LIVE",
                color = if (busy) Cinema.Pinging else Cinema.Ink,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (busy) Cinema.Panel else Cinema.Offline)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
            if (favorite || arranging) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (favorite) Cinema.Gold else Color.Black.copy(alpha = 0.68f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (favorite) "★" else "☆",
                        color = if (favorite) Cinema.Ink else Cinema.Gold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (dead) {
                Text(
                    text = "DEAD · ${DEAD_CHANNEL_FAILURES}×",
                    color = Cinema.Offline,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = if (favorite || arranging) 40.dp else 8.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Cinema.Ink.copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
            now?.let { program ->
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = program.title,
                            color = Cinema.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${clock(program.startMs)} – ${clock(program.stopMs)}",
                            color = Cinema.TextSecondary,
                            fontSize = 9.sp
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    EpgProgress(program, nowMs)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        // One caption row stays aligned whether or not the guide knows the
        // channel; the programme itself lives over the artwork.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = channel.name,
                color = if (focused) Cinema.Gold else Cinema.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val meta = listOfNotNull(
                channel.category?.takeIf { it.isNotBlank() },
                channel.releaseInfo?.takeIf { it.isNotBlank() }
            ).distinct().joinToString(" · ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = meta,
                    color = Cinema.TextMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 104.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveChannelRow(
    channel: LiveChannel,
    busy: Boolean,
    dead: Boolean,
    now: EpgProgram?,
    nowMs: Long,
    favorite: Boolean,
    arranging: Boolean,
    onToggleFavorite: () -> Unit,
    onMove: (Int) -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusMemory = LocalFocusMemory.current
    val sounds = LocalUiSounds.current
    val rowFocus = remember { FocusRequester() }
    val shape = RoundedCornerShape(10.dp)

    LaunchedEffect(focusMemory.restoreTick("live.list"), channel.key) {
        if (focusMemory.recall("live.list") == channel.key) {
            delay(90)
            runCatching { rowFocus.requestFocus() }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .focusRequester(rowFocus)
            .onKeyEvent { event ->
                if (!arranging || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        onMove(-1); true
                    }
                    Key.DirectionDown -> {
                        onMove(1); true
                    }
                    else -> false
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    focusMemory.record("live.list", channel.key)
                    sounds.focus()
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = buildString {
                    append(channel.name)
                    if (favorite) append(", saved")
                    if (dead) append(", marked dead")
                    now?.let { append(", now ${it.title}") }
                }
            }
            .combinedClickable(
                onClick = {
                    sounds.select()
                    onClick()
                },
                onLongClick = {
                    sounds.select()
                    onToggleFavorite()
                }
            )
            .background(
                if (focused) Cinema.PanelRaised else Cinema.Panel.copy(alpha = 0.55f),
                shape
            )
            .glassFrame(shape, focused = focused)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(104.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(Cinema.Ink)
        ) {
            PosterImage(
                url = channel.poster,
                title = channel.name,
                description = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            Text(
                text = if (busy) "TUNING…" else "LIVE",
                color = if (busy) Cinema.Pinging else Cinema.Ink,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (busy) Cinema.Panel else Cinema.Offline)
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.name,
                    color = if (focused) Cinema.Gold else Cinema.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val meta = listOfNotNull(
                    channel.category?.takeIf { it.isNotBlank() },
                    channel.releaseInfo?.takeIf { it.isNotBlank() }
                ).distinct().joinToString(" · ")
                if (meta.isNotBlank()) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = meta,
                        color = Cinema.TextMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
                }
                if (dead) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "DEAD",
                        color = Cinema.Offline,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                }
                if (favorite) {
                    Spacer(Modifier.width(8.dp))
                    Text("★", color = Cinema.Gold, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            if (now != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = now.title,
                        color = Cinema.TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = "${clock(now.startMs)} – ${clock(now.stopMs)}",
                        color = Cinema.TextMuted,
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.height(5.dp))
                EpgProgress(now, nowMs)
            } else {
                Text(
                    text = if (dead) "Tune failed · marked dead" else "No guide data",
                    color = Cinema.TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun EpgProgress(program: EpgProgram, atMs: Long, modifier: Modifier = Modifier) {
    val span = (program.stopMs - program.startMs).coerceAtLeast(1L)
    val fraction = ((atMs - program.startMs).toFloat() / span).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.12f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(3.dp)
                .background(Cinema.Gold)
        )
    }
}

@Composable
private fun GuideOverlay(
    title: String,
    channels: List<LiveChannel>,
    epg: EpgGuide,
    nowMs: Long,
    resolving: String?,
    onTune: (LiveChannel) -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    Column(
        Modifier
            .fillMaxSize()
            .background(Cinema.Ink.copy(alpha = 0.98f))
            .padding(48.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "TV GUIDE",
                color = Cinema.Gold,
                style = CinemaType.Kicker
            )
            Spacer(Modifier.width(14.dp))
            Text(
                title,
                color = Cinema.TextPrimary,
                style = CinemaType.PageTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TvButton(
                "REFRESH EPG",
                onRefresh,
                style = TvButtonStyle.GHOST,
                compact = true,
                glyph = "⌁"
            )
            Spacer(Modifier.width(10.dp))
            TvButton(
                "CLOSE",
                onClose,
                style = TvButtonStyle.SECONDARY,
                compact = true,
                glyph = "✕"
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (epg.isEmpty) {
                "No guide data for this selection yet. Add an EPG URL in Settings or retry refresh."
            } else {
                "${epg.channelCount} channels · ${epg.programCount} programs"
            },
            color = Cinema.TextMuted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            GuideHeader("TIME", Modifier.width(96.dp))
            GuideHeader("CHANNEL", Modifier.width(190.dp))
            GuideHeader("NOW", Modifier.weight(1f))
            GuideHeader("NEXT", Modifier.width(230.dp))
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(channels, key = { it.key }) { channel ->
                GuideRow(
                    channel = channel,
                    nowNext = epg.nowNext(channel.epgId, channel.name, nowMs),
                    nowMs = nowMs,
                    busy = resolving == channel.key,
                    onClick = { onTune(channel) }
                )
            }
        }
    }
}

@Composable
private fun GuideStatusDialog(
    details: List<String>,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(160)
        runCatching { closeFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth(0.9f)
                .glassSurface(
                    shape = RoundedCornerShape(CinemaDimens.CardRadius),
                    base = Glass.Sheet
                )
                .padding(26.dp)
        ) {
            Text(
                "Guide status",
                color = Cinema.TextPrimary,
                style = CinemaType.SectionTitle
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "What the last sync kept, corrected or skipped.",
                color = Cinema.TextMuted,
                style = CinemaType.BodySmall
            )
            Spacer(Modifier.height(14.dp))
            details.forEach { line ->
                Text(
                    "•  $line",
                    color = Cinema.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(7.dp))
            }
            Row(Modifier.padding(top = 10.dp)) {
                TvButton(
                    "CLOSE",
                    onClose,
                    style = TvButtonStyle.SECONDARY,
                    compact = true,
                    glyph = "✕",
                    focusRequester = closeFocus
                )
            }
        }
    }
}

@Composable
private fun GuideHeader(text: String, modifier: Modifier) {
    Text(
        text = text,
        color = Cinema.TextMuted,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.9.sp,
        modifier = modifier
    )
}

@Composable
private fun GuideRow(
    channel: LiveChannel,
    nowNext: EpgNowNext?,
    nowMs: Long,
    busy: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val rowShape = RoundedCornerShape(8.dp)
    val now = nowNext?.now
    val next = nowNext?.next
    Row(
        Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = rowShape,
                focused = focused,
                base = if (focused) Glass.Control else Glass.Frame,
                tint = if (focused) Glass.AccentWash else Color.Transparent,
                sheen = 0.45f
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .semantics {
                role = Role.Button
                contentDescription = buildString {
                    append(channel.name)
                    if (now != null) {
                        append(", now ${now.title}, ${clock(now.startMs)} to ${clock(now.stopMs)}")
                    } else {
                        append(", no guide data")
                    }
                    next?.let { append(", next ${it.title} at ${clock(it.startMs)}") }
                }
            }
            .clickable {
                sounds.select()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (now != null) "${clock(now.startMs)} – ${clock(now.stopMs)}" else "—",
            color = if (focused) Cinema.Gold else Cinema.TextSecondary,
            fontSize = 10.sp,
            modifier = Modifier.width(96.dp)
        )
        Column(Modifier.width(190.dp)) {
            Text(
                text = channel.name,
                color = Cinema.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            channel.category?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Cinema.TextMuted, fontSize = 9.sp, maxLines = 1)
            }
        }
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = now?.title ?: if (busy) "Tuning…" else "No guide data",
                color = if (now != null) Cinema.TextPrimary else Cinema.TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            now?.let {
                Spacer(Modifier.height(4.dp))
                EpgProgress(it, nowMs)
            }
        }
        Text(
            text = next?.let { "${clock(it.startMs)}  ${it.title}" }.orEmpty(),
            color = Cinema.TextMuted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(230.dp)
        )
    }
}

private fun clock(ms: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(
        Locale.US,
        "%02d:%02d",
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE)
    )
}
