package com.streamline.cinema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.data.CatalogItem
import com.streamline.cinema.data.MediaType
import com.streamline.cinema.data.SortMode
import com.streamline.cinema.data.sortedFor
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.Chip
import com.streamline.cinema.ui.EmptyState
import com.streamline.cinema.ui.LocalFocusMemory
import com.streamline.cinema.ui.LocalPosterStyle
import com.streamline.cinema.ui.PosterCard
import com.streamline.cinema.ui.PosterRow
import com.streamline.cinema.ui.RestoreSurfaceFocus
import com.streamline.cinema.ui.SectionHeader
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.countLabel
import com.streamline.cinema.ui.rememberScreenGridState

@Composable
fun LibraryScreen(
    vm: CinemaViewModel,
    type: MediaType,
    onOpenDetail: (CatalogItem) -> Unit,
    onImport: () -> Unit
) {
    val library by vm.library.collectAsState()
    val profile by vm.activeProfile.collectAsState()
    val settings by vm.settings.collectAsState()
    val home by vm.home.collectAsState()
    val progress by vm.progress.collectAsState()
    val favorites by vm.favorites.collectAsState()

    val items = library.filter { it.type == type }.sortedFor(settings.sortMode)
    var managing by remember(type) { mutableStateOf(false) }
    val discover = when (type) {
        MediaType.MOVIE -> home.popularMovies
        MediaType.TV -> home.popularShows
    }
    val progressByKey = remember(progress) {
        progress.values.groupBy { it.groupKey }.mapValues { (_, entries) ->
            entries.maxByOrNull { it.updatedAt }
        }
    }
    val favoriteKeys = remember(favorites) { favorites.map { it.key }.toSet() }
    val posterStyle = LocalPosterStyle.current
    val isMoviePage = type == MediaType.MOVIE
    val focusMemory = LocalFocusMemory.current
    val gridSurface = if (isMoviePage) "movies.grid" else "shows.grid"
    val restoring = focusMemory.recall(gridSurface) != null
    RestoreSurfaceFocus(gridSurface)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 48.dp, end = 48.dp, top = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        text = "LIBRARY",
                        color = Cinema.Gold,
                        style = CinemaType.Kicker
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (isMoviePage) "Movies" else "TV Shows",
                        color = Cinema.TextPrimary,
                        style = CinemaType.PageTitle
                    )
                    Text(
                        text = if (isMoviePage) {
                            "${countLabel(items.size, "film")} in your cinema hall · sources resolve at playback"
                        } else {
                            "${countLabel(items.size, "series", "series")} · seasons and episodes scaffolded from TMDB"
                        },
                        color = Cinema.TextMuted,
                        style = CinemaType.BodySmall
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (items.isNotEmpty()) {
                        TvButton(
                            label = if (managing) "DONE" else "REMOVE TITLES",
                            style = if (managing) TvButtonStyle.PRIMARY else TvButtonStyle.DANGER,
                            compact = true,
                            glyph = if (managing) "✓" else "✕",
                            onClick = { managing = !managing }
                        )
                    }
                    // Kids have no Playroom or Import route: the parent curates
                    // their library, so there is nothing for them to add here.
                    if (!profile.isKids) {
                        TvButton("IMPORT", onImport, style = TvButtonStyle.SECONDARY, compact = true, glyph = "↧")
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        Column(Modifier.padding(start = 48.dp, end = 48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SORT", color = Cinema.TextMuted, fontSize = 10.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.width(12.dp))
                SortMode.entries.forEach { mode ->
                    Chip(
                        label = when (mode) {
                            SortMode.RECENT -> "Recently added"
                            SortMode.TITLE -> "A – Z"
                            SortMode.RATING -> "Rating"
                            SortMode.YEAR -> "Year"
                        },
                        selected = settings.sortMode == mode,
                        onClick = { vm.saveSettings { it.copy(sortMode = mode) } }
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Catalogue sorted · ${settings.sortMode.label} · ← on a poster stars it",
                    color = Cinema.TextMuted,
                    fontSize = 10.sp
                )
            }
            if (managing) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Remove mode · press a poster to delete it. Titles can be re-imported at any time.",
                    color = Cinema.Offline,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(18.dp))
        }

        if (items.isEmpty()) {
            LazyVerticalGrid(
                state = rememberScreenGridState("$gridSurface.discover"),
                columns = GridCells.Adaptive(posterStyle.gridCellWidth),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        title = if (isMoviePage) "No movies yet" else "No series yet",
                        message = if (profile.isKids) {
                            "A parent can add approved movies and series to this profile."
                        } else {
                            "Import a TMDB discover feed or an MDBList collection. Every TV import is " +
                                "split into seasons and episodes with stills, titles and air dates."
                        },
                        actionLabel = if (profile.isKids) null else "OPEN IMPORT TERMINAL",
                        onAction = if (profile.isKids) null else onImport
                    )
                }
                if (discover.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(
                            title = "Discover ${if (isMoviePage) "Movies" else "TV Shows"}",
                            subtitle = "Live from TMDB · press a title to import"
                        )
                    }
                    itemsIndexed(discover, key = { _, item -> "d-${item.key}" }) { _, item ->
                        PosterCard(
                            item = item,
                            onClick = { onOpenDetail(item) },
                            favorite = item.key in favoriteKeys,
                            onToggleFavorite = { vm.toggleFavorite(item) }
                        )
                    }
                }
            }
        } else {
            // The retained state keeps the exact row the viewer left; the old
            // index-based jump fought that restore on every library refresh.
            val gridState = rememberScreenGridState(gridSurface)
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(posterStyle.gridCellWidth),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "myRow", span = { GridItemSpan(maxLineSpan) }) {
                    PosterRow(
                        title = if (isMoviePage) "My Movies" else "My TV Shows",
                        subtitle = if (managing) {
                            "press a poster to remove it"
                        } else {
                            "${countLabel(
                                items.size,
                                if (isMoviePage) "title" else "series",
                                if (isMoviePage) "titles" else "series"
                            )} · ${settings.sortMode.label}"
                        },
                        items = items,
                        onItemClick = onOpenDetail,
                        progressFor = { progressByKey[it.key] },
                        focusFirst = !restoring,
                        favoriteKeys = favoriteKeys,
                        actionLabel = if (managing) "DONE" else "REMOVE",
                        onAction = { managing = !managing },
                        onItemRemove = if (managing) ({ vm.removeFromLibrary(it) }) else null,
                        onItemFavorite = if (managing) null else ({ vm.toggleFavorite(it) }),
                        focusSurface = gridSurface
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(
                        title = if (isMoviePage) "All Movies" else "All TV Shows",
                        subtitle = "${countLabel(items.size, "title")} · ${settings.sortMode.label}"
                    )
                }
                itemsIndexed(items, key = { _, item -> item.key }) { _, item ->
                    PosterCard(
                        item = item,
                        onClick = { onOpenDetail(item) },
                        progress = progressByKey[item.key],
                        favorite = item.key in favoriteKeys,
                        onRemove = if (managing) ({ vm.removeFromLibrary(item) }) else null,
                        onToggleFavorite = if (managing) null else ({ vm.toggleFavorite(item) }),
                        focusSurface = gridSurface
                    )
                }
                if (discover.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(top = 26.dp)) {
                            SectionHeader(
                                title = "Popular ${if (isMoviePage) "Movies" else "TV Shows"}",
                                subtitle = "TMDB discover · press a title to open it"
                            )
                        }
                    }
                    itemsIndexed(discover, key = { _, item -> "d-${item.key}" }) { _, item ->
                        PosterCard(
                            item = item,
                            onClick = { onOpenDetail(item) },
                            favorite = item.key in favoriteKeys,
                            onToggleFavorite = { vm.toggleFavorite(item) }
                        )
                    }
                }
            }
        }
    }
}
