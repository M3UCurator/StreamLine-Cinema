package com.streamline.cinema.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.ui.Chip
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.LocalPosterStyle
import com.streamline.cinema.ui.LocalUiSounds
import com.streamline.cinema.ui.PosterImage
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.glassFrame
import com.streamline.cinema.ui.rememberScreenGridState
import kotlinx.coroutines.delay

private enum class CurationTab(val label: String) {
    LIBRARY("Movies & Series"),
    LIVE("Live Channels")
}

private val kidsGenres = listOf("Animation", "Family", "Adventure", "Kids")

/**
 * Parental whitelist editor. Opened from Settings ▸ Parental, which is itself
 * PIN-gated while Kids Mode is on. Pressing a card toggles approval.
 *
 * Runs in its own dialog window so the D-pad cannot wander into the Settings
 * screen rendered behind it, and so focus entry does not depend on the parent
 * window owning input focus.
 */
@Composable
fun KidsCurationScreen(
    vm: CinemaViewModel,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        KidsCurationContent(vm = vm, onClose = onClose)
    }
}

@Composable
private fun KidsCurationContent(
    vm: CinemaViewModel,
    onClose: () -> Unit
) {
    val view = LocalView.current
    val items by vm.allLibrary.collectAsState()
    val approved by vm.kidsMode.approved.collectAsState()
    val live by vm.liveAll.collectAsState()
    val posterStyle = LocalPosterStyle.current
    var tab by remember { mutableStateOf(CurationTab.LIBRARY) }
    var genreFilter by remember { mutableStateOf(kidsGenres.first()) }
    var channelCatalogKey by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        vm.refreshLive()
        delay(150)
        // In touch mode the window has no Android focus and Compose silently
        // drops focus requests, so hand the view focus before asking for D-pad
        // entry focus.
        if (!view.isFocused && view.isInTouchMode) view.requestFocusFromTouch()
        repeat(10) {
            if (runCatching { firstFocus.requestFocus() }.getOrDefault(false)) {
                return@LaunchedEffect
            }
            delay(120)
        }
    }
    // Each tab keeps its own retained grid state, so switching tabs or leaving
    // the screen returns to the same row instead of jumping to the top.

    val sortedItems = remember(items) { items.sortedBy { it.title.lowercase() } }
    val visibleItems = remember(sortedItems, genreFilter) {
        sortedItems.filter { item ->
            item.genres.any { it.equals(genreFilter, ignoreCase = true) }
        }
    }
    val visibleMovies = remember(visibleItems) { visibleItems.filter { !it.isSeries } }
    val visibleSeries = remember(visibleItems) { visibleItems.filter { it.isSeries } }
    val catalog = live.catalogs.firstOrNull { it.key == channelCatalogKey }
        ?: live.selected
        ?: live.catalogs.firstOrNull()
    val channels = if (catalog?.key == live.selected?.key) {
        live.channels
    } else {
        emptyList()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cinema.Ink)
            .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "KIDS LIBRARY",
                    color = Cinema.Gold,
                    style = CinemaType.Kicker
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Approve what the kids watch",
                    color = Cinema.TextPrimary,
                    style = CinemaType.PageTitle
                )
                Text(
                    "${approved.size} approved · these titles and channels live only in Kids profiles",
                    color = Cinema.TextMuted,
                    style = CinemaType.BodySmall
                )
            }
            TvButton("DONE", onClose, style = TvButtonStyle.PRIMARY, compact = true, glyph = "✓")
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CurationTab.entries.forEach { entry ->
                Chip(
                    label = entry.label,
                    selected = tab == entry,
                    onClick = { tab = entry }
                )
            }
            Spacer(Modifier.weight(1f))
            if (tab == CurationTab.LIBRARY) {
                kidsGenres.forEach { genre ->
                    Chip(
                        label = genre,
                        selected = genreFilter == genre,
                        onClick = { genreFilter = genre }
                    )
                }
            } else {
                Text(
                    "Press a card to approve or remove it",
                    color = Cinema.TextMuted,
                    fontSize = 10.sp
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        when (tab) {
            CurationTab.LIBRARY -> {
                if (sortedItems.isEmpty()) {
                    Text(
                        "Your Cinema Hall is empty. Import titles first, then approve them here.",
                        color = Cinema.TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else if (visibleItems.isEmpty()) {
                    Text(
                        "No ${genreFilter.lowercase()} movies or series in your Cinema Hall.",
                        color = Cinema.TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    LazyVerticalGrid(
                        state = rememberScreenGridState("kids.library.grid"),
                        columns = GridCells.Adaptive(posterStyle.gridCellWidth),
                        contentPadding = PaddingValues(bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (visibleMovies.isNotEmpty()) {
                            item(key = "header-movies", span = { GridItemSpan(maxLineSpan) }) {
                                CurationSectionHeader("MOVIES", visibleMovies.size)
                            }
                            itemsIndexed(visibleMovies, key = { _, item -> item.key }) { index, item ->
                                CurationCard(
                                    title = item.title,
                                    poster = item.posterUrl,
                                    approved = item.key in approved,
                                    landscape = false,
                                    focusRequester = if (index == 0) firstFocus else null,
                                    onClick = { vm.kidsMode.setApproved(item.key, item.key !in approved) }
                                )
                            }
                        }
                        if (visibleSeries.isNotEmpty()) {
                            item(key = "header-series", span = { GridItemSpan(maxLineSpan) }) {
                                CurationSectionHeader("TV SHOWS", visibleSeries.size)
                            }
                            itemsIndexed(visibleSeries, key = { _, item -> item.key }) { index, item ->
                                CurationCard(
                                    title = item.title,
                                    poster = item.posterUrl,
                                    approved = item.key in approved,
                                    landscape = false,
                                    focusRequester = if (index == 0 && visibleMovies.isEmpty()) firstFocus else null,
                                    onClick = { vm.kidsMode.setApproved(item.key, item.key !in approved) }
                                )
                            }
                        }
                    }
                }
            }
            CurationTab.LIVE -> {
                if (live.catalogs.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        live.catalogs.forEach { entry ->
                            Chip(
                                label = "${entry.addonName} · ${entry.name}",
                                selected = catalog?.key == entry.key,
                                onClick = {
                                    channelCatalogKey = entry.key
                                    vm.selectLiveCatalog(entry, live.filters)
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (live.loading && channels.isEmpty()) {
                    Text("Reading live channels…", color = Cinema.TextMuted, fontSize = 12.sp)
                } else if (channels.isEmpty()) {
                    Text(
                        "No live channels loaded. Add an IPTV playlist or a live addon in Settings first.",
                        color = Cinema.TextMuted,
                        fontSize = 13.sp
                    )
                } else {
                    LazyVerticalGrid(
                        state = rememberScreenGridState("kids.live.grid"),
                        columns = GridCells.Adaptive(posterStyle.channelWidth + 16.dp),
                        contentPadding = PaddingValues(bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(channels, key = { _, channel -> channel.key }) { index, channel ->
                            CurationCard(
                                title = channel.name,
                                poster = channel.poster,
                                approved = channel.key in approved,
                                landscape = true,
                                focusRequester = if (index == 0) firstFocus else null,
                                onClick = { vm.kidsMode.setApproved(channel.key, channel.key !in approved) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CurationSectionHeader(label: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Box(
            Modifier
                .width(18.dp)
                .height(2.dp)
                .background(Cinema.Gold)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = Cinema.Gold,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$count",
            color = Cinema.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CurationCard(
    title: String,
    poster: String?,
    approved: Boolean,
    landscape: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val style = LocalPosterStyle.current
    val titleBlock = with(LocalDensity.current) { (style.titleLineHeight * 2).toDp() }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(130), label = "curationScale")
    val shape = RoundedCornerShape(CinemaDimens.CardRadius)

    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .clickable {
                sounds.select()
                onClick()
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (landscape) 16f / 9f else style.aspectRatio)
                .glassFrame(shape, focused = focused || approved)
        ) {
            PosterImage(url = poster, title = title, modifier = Modifier.fillMaxSize())
            Text(
                text = if (approved) "✓ KIDS" else "+ ADD",
                color = if (approved) Cinema.Ink else Cinema.TextPrimary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (approved) Cinema.Gold else Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = title,
            color = if (focused) Cinema.Gold else Cinema.TextPrimary,
            fontSize = style.titleSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = style.titleLineHeight,
            modifier = Modifier.height(titleBlock)
        )
    }
}
