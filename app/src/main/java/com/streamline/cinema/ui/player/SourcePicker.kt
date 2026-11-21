package com.streamline.cinema.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.streamline.cinema.data.PlaybackOption
import com.streamline.cinema.data.StreamBadgeCatalog
import com.streamline.cinema.data.StreamPresentation
import com.streamline.cinema.data.StreamSearchPhase
import com.streamline.cinema.data.StreamSearchStep
import com.streamline.cinema.ui.Chip
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.FocusScale
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.InfoPill
import com.streamline.cinema.ui.LocalUiSounds
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.glassSurface
import kotlinx.coroutines.delay

/**
 * Source picker grouped by provider. Each addon is a chip that filters the list;
 * every row is one compact pair of lines — spec pills plus the release file name
 * and container — with the badge artwork the configured badge document matches
 * against the stream text. The list is lazy and unbounded: every distinct stream
 * the search discovered is listed.
 */
@Composable
internal fun SourcePicker(
    options: List<PlaybackOption>,
    steps: List<StreamSearchStep>,
    searchInProgress: Boolean,
    hiddenByLimits: Int,
    selectedIndex: Int,
    badges: StreamBadgeCatalog,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val providers = remember(options) { options.map(::providerOf).distinct() }
    var providerFilter by remember(options) { mutableStateOf<String?>(null) }
    val visible = remember(options, providerFilter) {
        options.withIndex().filter { (_, option) ->
            providerFilter == null || providerOf(option) == providerFilter
        }
    }
    val firstCardFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // Re-anchor a filtered list at the top so the first card is composed and
    // can take the focus below; a lazy list drops off-screen items.
    LaunchedEffect(providerFilter) {
        if (visible.isNotEmpty()) listState.scrollToItem(0)
    }
    LaunchedEffect(providerFilter, visible.size) {
        delay(140)
        if (visible.isEmpty()) runCatching { closeFocus.requestFocus() }
        else runCatching { firstCardFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .widthIn(max = 1180.dp)
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.96f)
                    .glassSurface(
                        shape = RoundedCornerShape(CinemaDimens.CardRadius),
                        base = Glass.Sheet
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Playback sources",
                            color = Cinema.TextPrimary,
                            style = CinemaType.SectionTitle
                        )
                    }
                    if (searchInProgress) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            color = Cinema.Gold,
                            strokeWidth = 1.8.dp
                        )
                    }
                    InfoPill(
                        if (options.isEmpty()) "NO SOURCES"
                        else "${options.size} ${if (options.size == 1) "SOURCE" else "SOURCES"}",
                        Cinema.Gold,
                        Cinema.PanelRaised
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    Chip(
                        label = "All",
                        selected = providerFilter == null,
                        compact = true,
                        onClick = { providerFilter = null }
                    )
                    providers.forEach { provider ->
                        Chip(
                            label = provider,
                            selected = providerFilter == provider,
                            compact = true,
                            onClick = {
                                providerFilter = if (providerFilter == provider) null else provider
                            }
                        )
                    }
                }
                val failed = steps.filter { it.phase == StreamSearchPhase.FAILED }
                if (!searchInProgress && failed.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Not answering · ${failed.joinToString { it.label }}",
                        color = Cinema.Offline,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (visible.isEmpty()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (searchInProgress) {
                                    CircularProgressIndicator(
                                        Modifier.size(14.dp),
                                        color = Cinema.Gold,
                                        strokeWidth = 1.8.dp
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "Searching every source…",
                                        color = Cinema.TextSecondary,
                                        fontSize = 12.sp
                                    )
                                } else {
                                    Text(
                                        if (options.isEmpty())
                                            "No playable sources were found for this title."
                                        else "This provider returned no playable sources.",
                                        color = Cinema.TextMuted,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    } else {
                        val firstIndex = visible.first().index
                        val groups = visible.groupBy { providerOf(it.value) }
                        groups.forEach { (provider, entries) ->
                            if (providerFilter == null) {
                                item(key = "provider:$provider") {
                                    ProviderHeader(
                                        provider = provider,
                                        logo = entries.first().value.meta.providerLogo,
                                        count = entries.size
                                    )
                                }
                            }
                            items(entries, key = { "source:${it.index}" }) { entry ->
                                SourceCard(
                                    option = entry.value,
                                    badges = badges,
                                    selected = entry.index == selectedIndex,
                                    focusRequester = if (entry.index == firstIndex) firstCardFocus else null,
                                    onSelect = { onSelect(entry.index) }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (hiddenByLimits > 0) {
                            "${options.size} of ${options.size + hiddenByLimits} playable " +
                                "sources · $hiddenByLimits skipped by the quality or size " +
                                "cap in Settings"
                        } else {
                            "Every playable source is listed · link details are available " +
                                "in the player info panel"
                        },
                        color = Cinema.TextMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    TvButton(
                        label = "CLOSE",
                        style = TvButtonStyle.GHOST,
                        compact = true,
                        onClick = onDismiss,
                        focusRequester = closeFocus
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderHeader(provider: String, logo: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (logo.isNotBlank()) {
            AsyncImage(
                model = logo,
                contentDescription = provider,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(16.dp)
            )
        }
        Text(
            provider.uppercase(),
            color = Cinema.Gold,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        InfoPill("$count", Cinema.TextSecondary, Cinema.PanelRaised)
    }
}

/**
 * One source, two lines: the spec pills and the release file name. The pills
 * carry the same facts the reference list shows — resolution, size, bitrate,
 * dynamic range, source/codec and audio — so a viewer picks by glancing at the
 * row instead of opening it. Badge artwork and the addon logo sit on the right.
 */
@Composable
private fun SourceCard(
    option: PlaybackOption,
    badges: StreamBadgeCatalog,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onSelect: () -> Unit
) {
    val meta = option.meta
    val facts = remember(meta) { StreamPresentation.facts(meta) }
    val sounds = LocalUiSounds.current
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val matched = remember(option.url, badges) { badges.matches(meta.haystack) }
    // Transport that is not a file still deserves a type pill (live HLS feeds).
    val container = facts.container.ifBlank {
        if (facts.fileName.isBlank()) option.streamKind?.name.orEmpty() else ""
    }
    val title = facts.fileName.ifBlank {
        meta.releaseTitle.ifBlank { option.name }
    }
    // Size and bitrate share one pill, like the reference list.
    val spec = when {
        facts.size.isNotBlank() && facts.bitrate.isNotBlank() ->
            "${facts.size} · ≈${facts.bitrate}"
        facts.size.isNotBlank() -> facts.size
        facts.bitrate.isNotBlank() -> "≈${facts.bitrate}"
        else -> ""
    }

    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .glassSurface(
                shape = RoundedCornerShape(CinemaDimens.CardRadius),
                focused = focused,
                base = if (selected) Glass.Control else Glass.Sheet,
                scale = FocusScale.ROW,
                sheen = 0.85f
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .clickable(interactionSource = interaction, indication = null) {
                sounds.select()
                onSelect()
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (selected || facts.hasPills || container.isNotBlank()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (selected) InfoPill("PLAYING", Cinema.Ink, Cinema.Gold, solid = true)
                    if (facts.resolution.isNotBlank()) {
                        InfoPill(facts.resolution, Cinema.Gold, Glass.AccentWash)
                    }
                    if (spec.isNotBlank()) {
                        InfoPill(spec, Cinema.TextPrimary, Glass.Control)
                    }
                    if (facts.dynamicRange.isNotBlank()) {
                        InfoPill(
                            facts.dynamicRange,
                            Cinema.Tmdb,
                            Cinema.Tmdb.copy(alpha = 0.14f)
                        )
                    }
                    if (facts.type.isNotBlank()) {
                        InfoPill(facts.type, Cinema.TextSecondary, Glass.Control)
                    }
                    if (facts.audio.isNotBlank()) {
                        InfoPill(facts.audio, Cinema.Online, Cinema.Online.copy(alpha = 0.14f))
                    }
                    if (container.isNotBlank()) {
                        InfoPill(container, Cinema.TextMuted, Glass.Control)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = Cinema.TextPrimary.copy(alpha = 0.92f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    providerOf(option).uppercase(),
                    color = Cinema.TextFaint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp)
                )
            }
        }
        if (matched.isNotEmpty() || meta.providerLogo.isNotBlank()) {
            Spacer(Modifier.width(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                matched.take(4).forEach { badge ->
                    AsyncImage(
                        model = badge.imageUrl,
                        contentDescription = badge.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.height(16.dp)
                    )
                }
                if (meta.providerLogo.isNotBlank()) {
                    AsyncImage(
                        model = meta.providerLogo,
                        contentDescription = meta.provider,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(18.dp)
                            .widthIn(max = 90.dp)
                    )
                }
            }
        }
    }
}

private fun providerOf(option: PlaybackOption): String =
    option.meta.provider.ifBlank { "Direct" }
