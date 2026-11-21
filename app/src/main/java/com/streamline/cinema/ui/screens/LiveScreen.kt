package com.streamline.cinema.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.data.EpgGuide
import com.streamline.cinema.data.EpgNowNext
import com.streamline.cinema.data.EpgProgram
import com.streamline.cinema.data.LiveCatalog
import com.streamline.cinema.data.LiveChannel
import com.streamline.cinema.data.LiveSourceKind
import com.streamline.cinema.data.PlaybackOption
import com.streamline.cinema.ui.Chip
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.EmptyState
import com.streamline.cinema.ui.ErrorBanner
import com.streamline.cinema.ui.FocusScale
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.LocalFocusMemory
import com.streamline.cinema.ui.LocalUiSounds
import com.streamline.cinema.ui.PosterImage
import com.streamline.cinema.ui.RestoreSurfaceFocus
import com.streamline.cinema.ui.SelectionGroup
import com.streamline.cinema.ui.SkeletonBox
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.TvTextField
import com.streamline.cinema.ui.glassSurface
import com.streamline.cinema.ui.rememberScreenListState
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.delay

private const val CLOCK_TICK_MS = 10_000L
private val SIDEBAR_WIDTH = 220.dp

/** One addon or one IPTV playlist: the source a viewer browses before choosing a group. */
private data class LiveSource(
    val key: String,
    val label: String,
    val kind: LiveSourceKind,
    val catalogs: List<LiveCatalog>
)

/** A left-rail line: "All channels", "Favorites", one group, or a separator. */
private data class LiveSidebarEntry(
    val key: String,
    val label: String = "",
    val selected: Boolean = false,
    val divider: Boolean = false,
    val onClick: () -> Unit = {}
)

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
    var searching by remember { mutableStateOf(false) }
    var channelQuery by remember { mutableStateOf("") }
    var searchTick by remember { mutableIntStateOf(0) }
    val searchFocus = remember { FocusRequester() }
    var arranging by remember { mutableStateOf(false) }

    // Addon manifests and IPTV playlists each become one source in the strip,
    // so a 190-catalog live addon cannot bury the rest of the lists.
    val sources = remember(live.catalogs) { groupLiveSources(live.catalogs) }
    val activeSource = remember(sources, live.selected) {
        sources.firstOrNull { source -> source.catalogs.any { it.key == live.selected?.key } }
            ?: sources.firstOrNull()
    }
    val iptvGroup = live.filters["group-title"]?.takeIf { it.isNotBlank() }
    val activeGroupLabel = when {
        activeSource == null -> null
        activeSource.kind == LiveSourceKind.IPTV -> iptvGroup
        else -> live.selected?.name
    }
    val iptvCatalog = activeSource
        ?.takeIf { it.kind == LiveSourceKind.IPTV }
        ?.catalogs
        ?.firstOrNull()

    // Kids profiles see only channels a parent approved for the Kids library.
    // The search runs over the loaded list, so it stays inside the active playlist.
    val channels = remember(
        live.channels, profile.isKids, approvedIds, favoritesOnly, livePrefs.favorites,
        searching, channelQuery
    ) {
        visibleLiveChannels(
            channels = live.channels,
            query = channelQuery,
            searching = searching,
            favoritesOnly = favoritesOnly,
            favorites = livePrefs.favorites,
            isKids = profile.isKids,
            approved = approvedIds
        )
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
    RestoreSurfaceFocus("live.rows")

    // Entering Search should land the cursor in the field ready to type, but
    // only after the row exists, or the requester has nothing to attach to.
    LaunchedEffect(searching, searchTick) {
        if (!searching) return@LaunchedEffect
        delay(120)
        runCatching { searchFocus.requestFocus() }
    }

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

    // One reload path per rail action: selecting a source loads its first
    // group, All clears the group filter, and Favorites keeps the whole
    // playlist so it spans every IPTV group.
    val selectSource: (LiveSource) -> Unit = { source ->
        if (source.key != activeSource?.key) {
            favoritesOnly = false
            searching = false
            channelQuery = ""
            source.catalogs.firstOrNull()?.let { vm.selectLiveCatalog(it) }
        }
    }
    val selectIptvGroup: (String?) -> Unit = { group ->
        favoritesOnly = false
        searching = false
        channelQuery = ""
        val catalog = iptvCatalog
        if (catalog != null) {
            if (live.selected?.key != catalog.key) {
                vm.selectLiveCatalog(
                    catalog,
                    group?.let { mapOf("group-title" to it) } ?: emptyMap()
                )
            } else {
                vm.setLiveFilter("group-title", group.orEmpty())
            }
        }
    }
    val selectFavorites: () -> Unit = {
        if (activeSource?.kind == LiveSourceKind.IPTV && iptvGroup != null) {
            selectIptvGroup(null)
        }
        searching = false
        channelQuery = ""
        favoritesOnly = true
    }
    // Search stays inside the active playlist: it only filters the loaded list,
    // so an addon search can never reach another source's channels.
    val selectSearch: () -> Unit = {
        if (activeSource?.kind == LiveSourceKind.IPTV && iptvGroup != null) {
            selectIptvGroup(null)
        }
        favoritesOnly = false
        searching = true
        searchTick += 1
    }

    val sidebarEntries = buildList {
        val source = activeSource ?: return@buildList
        if (source.kind == LiveSourceKind.IPTV) {
            val groups = source.catalogs.firstOrNull()
                ?.selectableExtras
                ?.firstOrNull { it.name == "group-title" }
                ?.options
                .orEmpty()
            add(
                LiveSidebarEntry(
                    key = "all",
                    label = "All channels",
                    selected = !favoritesOnly && !searching && iptvGroup == null,
                    onClick = { selectIptvGroup(null) }
                )
            )
            add(
                LiveSidebarEntry(
                    key = "favorites",
                    label = "Favorites",
                    selected = favoritesOnly,
                    onClick = selectFavorites
                )
            )
            add(
                LiveSidebarEntry(
                    key = "search",
                    label = "Search",
                    selected = searching,
                    onClick = selectSearch
                )
            )
            if (groups.isNotEmpty()) {
                add(LiveSidebarEntry(key = "divider", divider = true))
                groups.forEach { group ->
                    add(
                        LiveSidebarEntry(
                            key = "group:$group",
                            label = group,
                            selected = !favoritesOnly && !searching && iptvGroup == group,
                            onClick = { selectIptvGroup(group) }
                        )
                    )
                }
            }
        } else {
            add(
                LiveSidebarEntry(
                    key = "favorites",
                    label = "Favorites",
                    selected = favoritesOnly,
                    onClick = selectFavorites
                )
            )
            add(
                LiveSidebarEntry(
                    key = "search",
                    label = "Search",
                    selected = searching,
                    onClick = selectSearch
                )
            )
            if (source.catalogs.size > 1) {
                add(LiveSidebarEntry(key = "divider", divider = true))
            }
            source.catalogs.forEach { catalog ->
                add(
                    LiveSidebarEntry(
                        key = "catalog:${catalog.key}",
                        label = catalog.name,
                        selected = !favoritesOnly && !searching && live.selected?.key == catalog.key,
                        onClick = {
                            favoritesOnly = false
                            searching = false
                            channelQuery = ""
                            if (live.selected?.key != catalog.key) {
                                vm.selectLiveCatalog(catalog)
                            }
                        }
                    )
                )
            }
        }
    }

    // A group-title rail entry already covers IPTV filtering; addon catalogs
    // keep their remaining extras (country, sub-category…) as compact chips.
    val extraGroups = live.selected?.selectableExtras.orEmpty()
        .filterNot { extra ->
            live.selected?.sourceKind == LiveSourceKind.IPTV && extra.name == "group-title"
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
                                if (sources.size > 1) {
                                    activeSource?.label?.let { append(" · $it") }
                                }
                                activeGroupLabel?.takeIf { it.isNotBlank() }?.let {
                                    append(" · $it")
                                }
                                if (favoritesOnly) {
                                    append(" · saved only")
                                } else if (livePrefs.favorites.isNotEmpty()) {
                                    append(" · ${livePrefs.favorites.size} saved")
                                }
                                if (searching) append(" · search")
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
                        if (live.channels.isNotEmpty()) {
                            TvButton(
                                if (arranging) "DONE" else "ARRANGE",
                                { arranging = !arranging },
                                style = if (arranging) TvButtonStyle.PRIMARY else TvButtonStyle.GHOST,
                                compact = true,
                                glyph = if (arranging) "✓" else "⇅"
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
                if (sources.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    val labelWidth = (64f * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)).dp
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "SOURCE",
                            color = Cinema.TextFaint,
                            style = CinemaType.Overline,
                            maxLines = 1,
                            modifier = Modifier.width(labelWidth)
                        )
                        LazyRow(
                            state = rememberScreenListState("live.sources"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(sources, key = { it.key }) { source ->
                                Chip(
                                    label = source.label,
                                    selected = source.key == activeSource?.key,
                                    onClick = { selectSource(source) },
                                    compact = true
                                )
                            }
                        }
                    }
                }
                if (extraGroups.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    extraGroups.forEach { extra ->
                        SelectionGroup(
                            extra = extra,
                            selected = live.filters[extra.name],
                            onSelect = { value -> vm.setLiveFilter(extra.name, value) },
                            stateKeyPrefix = "live.group"
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
                live.message?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    ErrorBanner(message)
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 48.dp, end = 48.dp)
            ) {
                Column(Modifier.width(SIDEBAR_WIDTH).fillMaxHeight()) {
                    Text(
                        text = activeSource?.label?.uppercase(Locale.US) ?: "LISTS",
                        color = Cinema.TextFaint,
                        style = CinemaType.Overline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    LazyColumn(
                        state = rememberScreenListState("live.sidebar"),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        items(sidebarEntries, key = { it.key }) { entry ->
                            if (entry.divider) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .height(1.dp)
                                        .background(Glass.Hairline)
                                )
                            } else {
                                LiveSidebarRow(
                                    label = entry.label,
                                    selected = entry.selected,
                                    onClick = entry.onClick
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(18.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize()) {
                        if (searching) {
                            TvTextField(
                                value = channelQuery,
                                onValueChange = { channelQuery = it },
                                placeholder = "Search this playlist's channels…",
                                leadingGlyph = "⌕",
                                focusRequester = searchFocus,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        Box(Modifier.weight(1f)) {
                            // The header plus a search field leave too little room
                            // for these tall states on a 1080p panel, so they scroll
                            // and focus bringIntoView keeps the action reachable.
                            val emptyModifier = Modifier.verticalScroll(rememberScrollState())
                            when {
                                live.loading && channels.isEmpty() -> LiveSkeletonRows()
                                channels.isEmpty() && live.message != null -> EmptyState(
                                    title = "Live feed unavailable",
                                    message = live.message ?: "The live catalogs could not be reached.",
                                    modifier = emptyModifier,
                                    actionLabel = "RETRY",
                                    onAction = { vm.refreshLive(force = true) }
                                )
                                channels.isEmpty() && profile.isKids -> EmptyState(
                                    title = "No approved channels",
                                    message = "A parent can approve live channels from Settings ▸ " +
                                        "Parental ▸ Manage Kids Library. Only approved channels appear here.",
                                    modifier = emptyModifier,
                                    actionLabel = "OPEN SETTINGS",
                                    onAction = onOpenSettings
                                )
                                channels.isEmpty() && searching && live.channels.isNotEmpty() -> EmptyState(
                                    title = "No matching channels",
                                    message = "Nothing in this playlist matches " +
                                        "“${channelQuery.trim()}”.",
                                    modifier = emptyModifier,
                                    actionLabel = "CLEAR",
                                    onAction = { channelQuery = "" }
                                )
                                channels.isEmpty() && favoritesOnly && live.channels.isNotEmpty() -> EmptyState(
                                    title = "No saved channels",
                                    message = "Hold OK on a channel to star it. Saved channels are kept " +
                                        "across restarts and playlists.",
                                    modifier = emptyModifier,
                                    actionLabel = "SHOW ALL",
                                    onAction = { favoritesOnly = false }
                                )
                                channels.isEmpty() -> EmptyState(
                                    title = "No live channels yet",
                                    message = "Add a live Stremio addon such as FayaTV, or an IPTV playlist " +
                                        "(M3U/M3U8/XSPF) in Settings. Channels appear here and open straight " +
                                        "into the native player.",
                                    modifier = emptyModifier,
                                    actionLabel = "OPEN SETTINGS",
                                    onAction = onOpenSettings
                                )
                                else -> Column(Modifier.fillMaxSize()) {
                                    LiveTableHeader()
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(Glass.Hairline)
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    LazyColumn(
                                        state = rememberScreenListState("live.rows"),
                                        contentPadding = PaddingValues(bottom = 40.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        itemsIndexed(channels, key = { _, channel -> channel.key }) { index, channel ->
                                            LiveChannelRow(
                                                number = channel.channelNumber ?: (index + 1),
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
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showGuide) {
            GuideOverlay(
                title = listOfNotNull(activeSource?.label, activeGroupLabel)
                    .distinct()
                    .joinToString(" · "),
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

/**
 * The rows the table shows: Kids approval, then Favorites, then the name search.
 * The search only ever sees the loaded catalog, which is what keeps it scoped
 * to the active playlist.
 */
internal fun visibleLiveChannels(
    channels: List<LiveChannel>,
    query: String,
    searching: Boolean,
    favoritesOnly: Boolean,
    favorites: Set<String>,
    isKids: Boolean,
    approved: Set<String>
): List<LiveChannel> {
    val needle = query.trim()
    return channels
        .filter { !isKids || it.key in approved }
        .filter { !favoritesOnly || it.key in favorites }
        .filter { !searching || needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
}

private fun groupLiveSources(catalogs: List<LiveCatalog>): List<LiveSource> {
    val sources = ArrayList<LiveSource>()
    catalogs.forEach { catalog ->
        val key = when (catalog.sourceKind) {
            LiveSourceKind.IPTV -> "iptv|${catalog.iptvSourceId.orEmpty()}"
            LiveSourceKind.ADDON -> catalog.addonUrl
        }
        val index = sources.indexOfFirst { it.key == key }
        if (index >= 0) {
            val source = sources[index]
            sources[index] = source.copy(catalogs = source.catalogs + catalog)
        } else {
            sources += LiveSource(
                key = key,
                label = when (catalog.sourceKind) {
                    LiveSourceKind.IPTV -> catalog.name.ifBlank { "IPTV" }
                    LiveSourceKind.ADDON -> catalog.addonName.ifBlank { "Addon" }
                },
                kind = catalog.sourceKind,
                catalogs = listOf(catalog)
            )
        }
    }
    return sources
}

@Composable
private fun LiveSidebarRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .glassSurface(
                shape = RoundedCornerShape(8.dp),
                focused = focused,
                base = if (selected) Glass.AccentWash else Color.Transparent,
                // A flat rail: the sheen is a focus cue here, not a fill.
                sheen = if (focused) 0.8f else 0f,
                scale = FocusScale.ROW
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = label
            }
            .clickable {
                sounds.select()
                onClick()
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (selected) Cinema.Gold else Color.Transparent)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = if (selected) Cinema.Gold else Cinema.TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LiveTableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiveColumnLabel("#", Modifier.width(44.dp))
        // The logo column sits between the number and the name.
        Spacer(Modifier.width(82.dp))
        LiveColumnLabel("CHANNEL", Modifier.weight(1.15f))
        LiveColumnLabel("NOW", Modifier.weight(1.5f))
        LiveColumnLabel("TIME", Modifier.width(104.dp), alignEnd = true)
    }
}

@Composable
private fun LiveColumnLabel(text: String, modifier: Modifier, alignEnd: Boolean = false) {
    Text(
        text = text,
        color = Cinema.TextFaint,
        style = CinemaType.Overline,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        maxLines = 1,
        modifier = modifier
    )
}

@Composable
private fun LiveSkeletonRows() {
    Column(Modifier.fillMaxSize()) {
        Text("Reading live catalogs…", color = Cinema.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        repeat(8) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBox(
                    Modifier.width(24.dp).height(10.dp),
                    shape = RoundedCornerShape(4.dp)
                )
                Spacer(Modifier.width(20.dp))
                SkeletonBox(
                    Modifier.width(68.dp).height(38.dp),
                    shape = RoundedCornerShape(6.dp)
                )
                Spacer(Modifier.width(14.dp))
                SkeletonBox(
                    Modifier.width(180.dp).height(12.dp),
                    shape = RoundedCornerShape(4.dp)
                )
                Spacer(Modifier.width(24.dp))
                SkeletonBox(
                    Modifier.weight(1f).height(12.dp),
                    shape = RoundedCornerShape(4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveChannelRow(
    number: Int,
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
    val shape = RoundedCornerShape(8.dp)

    LaunchedEffect(focusMemory.restoreTick("live.rows"), channel.key) {
        if (focusMemory.recall("live.rows") == channel.key) {
            delay(90)
            runCatching { rowFocus.requestFocus() }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
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
                    focusMemory.record("live.rows", channel.key)
                    sounds.focus()
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = buildString {
                    append("$number, ${channel.name}")
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
            .glassSurface(
                shape = shape,
                focused = focused,
                base = if (focused) Cinema.PanelRaised.copy(alpha = 0.92f) else Color.Transparent,
                sheen = if (focused) 0.8f else 0f,
                scale = FocusScale.ROW
            )
            .padding(horizontal = 12.dp)
    ) {
        Text(
            text = number.toString(),
            color = if (focused) Cinema.Gold else Cinema.TextFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.width(44.dp)
        )
        Box(
            Modifier
                .width(68.dp)
                .height(38.dp)
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
        }
        Spacer(Modifier.width(14.dp))
        Row(Modifier.weight(1.15f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = channel.name,
                color = if (focused) Cinema.Gold else Cinema.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (dead) LiveRowTag("DEAD", Cinema.Offline)
            if (busy) LiveRowTag("TUNING", Cinema.Pinging)
            if (favorite) {
                Text(
                    text = "★",
                    color = Cinema.Gold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
        Column(Modifier.weight(1.5f).padding(end = 16.dp)) {
            Text(
                text = now?.title ?: if (busy) "Tuning…" else "No data",
                color = if (now != null) Cinema.TextSecondary else Cinema.TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (now != null) {
                Spacer(Modifier.height(4.dp))
                EpgProgress(now, nowMs, height = 2.dp)
            }
        }
        Text(
            text = now?.let { "${clock(it.startMs)} – ${clock(it.stopMs)}" } ?: "—",
            color = Cinema.TextMuted,
            fontSize = 11.sp,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(104.dp)
        )
    }
}

@Composable
private fun LiveRowTag(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(CinemaDimens.PillRadius))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

@Composable
private fun EpgProgress(
    program: EpgProgram,
    atMs: Long,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp
) {
    val span = (program.stopMs - program.startMs).coerceAtLeast(1L)
    val fraction = ((atMs - program.startMs).toFloat() / span).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.12f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(height)
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
                base = Glass.Frame,
                scale = FocusScale.ROW,
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
