package com.streamline.cinema.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.data.CatalogItem
import com.streamline.cinema.data.JsonBackup
import com.streamline.cinema.data.MediaType
import com.streamline.cinema.data.Season
import com.streamline.cinema.data.newEpisodeCount
import com.streamline.cinema.data.trackedSeasons
import com.streamline.cinema.data.watchedEpisodesFor
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.Chip
import com.streamline.cinema.ui.EmptyState
import com.streamline.cinema.ui.LocalPosterStyle
import com.streamline.cinema.ui.PosterCard
import com.streamline.cinema.ui.RestoreSurfaceFocus
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.countLabel
import com.streamline.cinema.ui.rememberScreenGridState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private enum class FavoriteFilter(val label: String) {
    ALL("All"),
    MOVIES("Movies"),
    SHOWS("TV Shows")
}

@Composable
fun FavoritesScreen(
    vm: CinemaViewModel,
    onOpenDetail: (CatalogItem) -> Unit
) {
    val favorites by vm.favorites.collectAsState()
    val progress by vm.progress.collectAsState()
    val sniffedSeasons by vm.sniffedSeasons.collectAsState()
    val watchedMarks by vm.watchedEpisodes.collectAsState()

    var filter by rememberSaveable { mutableStateOf(FavoriteFilter.ALL) }
    var managing by rememberSaveable { mutableStateOf(false) }
    val saved = favorites
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val text = JsonBackup.readText(context, uri)
                        vm.importFavorites(parseFavoritesJson(text))
                    }
                }
                val message = result.fold(
                    onSuccess = { count ->
                        if (count == 0) "No new favorites in that file"
                        else "Imported ${countLabel(count, "favorite")}"
                    },
                    onFailure = { "Import failed: ${it.message ?: "invalid JSON"}" }
                )
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(Unit) { vm.sniffFavoriteEpisodes() }
    RestoreSurfaceFocus("favorites.grid")

    val filtered = remember(saved, filter) {
        when (filter) {
            FavoriteFilter.ALL -> saved
            FavoriteFilter.MOVIES -> saved.filter { it.type == MediaType.MOVIE }
            FavoriteFilter.SHOWS -> saved.filter { it.type == MediaType.TV }
        }
    }
    val progressByKey = remember(progress) {
        progress.values.groupBy { it.groupKey }.mapValues { (_, entries) ->
            entries.maxByOrNull { it.updatedAt }
        }
    }
    val playedByItem = remember(progress) {
        progress.values
            .filter { it.season != null && it.episode != null && it.fraction >= 0.9f }
            .groupBy { it.itemKey }
            .mapValues { (_, entries) ->
                entries.map { (it.season ?: 0) to (it.episode ?: 0) }.toSet()
            }
    }
    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val episodeStatus = remember(saved, sniffedSeasons, playedByItem, watchedMarks, today) {
        saved
            .filter { it.type == MediaType.TV }
            .mapNotNull { item ->
                newEpisodeLabel(
                    seasons = trackedSeasons(item, sniffedSeasons[item.key]),
                    watched = playedByItem[item.key].orEmpty() +
                        watchedEpisodesFor(item.key, watchedMarks),
                    today = today
                )?.let { item.key to it }
            }
            .toMap()
    }
    val posterStyle = LocalPosterStyle.current
    val movieCount = saved.count { it.type == MediaType.MOVIE }
    val showCount = saved.count { it.type == MediaType.TV }
    // The inline hint only fits beside the chips at the default font size.
    val dense = LocalDensity.current.fontScale > 1.15f

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
                        text = "Favorites",
                        color = Cinema.TextPrimary,
                        style = CinemaType.PageTitle
                    )
                    Text(
                        text = "${countLabel(saved.size, "starred title")} · " +
                            "${countLabel(movieCount, "movie")} · " +
                            countLabel(showCount, "series", "series"),
                        color = Cinema.TextMuted,
                        style = CinemaType.BodySmall
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton(
                        label = "IMPORT .JSON",
                        style = TvButtonStyle.SECONDARY,
                        compact = true,
                        glyph = "⇧",
                        onClick = {
                            importPicker.launch(
                                arrayOf("application/json", "text/plain", "*/*")
                            )
                        }
                    )
                    TvButton(
                        label = "EXPORT .JSON",
                        style = TvButtonStyle.SECONDARY,
                        compact = true,
                        glyph = "⇩",
                        enabled = saved.isNotEmpty(),
                        onClick = {
                            val json = savedJson(saved)
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        JsonBackup.writeToDownloads(
                                            context,
                                            "cinema-favorites-${JsonBackup.timestamp()}.json",
                                            json
                                        )
                                    }
                                }
                                val message = result.fold(
                                    onSuccess = { "Exported → $it" },
                                    onFailure = { "Export failed: ${it.message ?: "unknown error"}" }
                                )
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                    if (saved.isNotEmpty()) {
                        TvButton(
                            label = if (managing) "DONE" else "REMOVE TITLES",
                            style = if (managing) TvButtonStyle.PRIMARY else TvButtonStyle.DANGER,
                            compact = true,
                            glyph = if (managing) "✓" else "✕",
                            onClick = { managing = !managing }
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FILTER", color = Cinema.TextMuted, fontSize = 10.sp, letterSpacing = 1.4.sp)
                Spacer(Modifier.width(12.dp))
                FavoriteFilter.entries.forEach { option ->
                    Chip(
                        label = option.label,
                        selected = filter == option,
                        onClick = { filter = option }
                    )
                }
                Spacer(Modifier.weight(1f))
                if (!dense) {
                    Text(
                        text = "← moves between posters · hold OK to star · new = aired episodes you have not watched",
                        color = Cinema.TextMuted,
                        fontSize = 10.sp
                    )
                }
            }
            if (dense) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "← moves between posters · hold OK to star · new = aired episodes you have not watched",
                    color = Cinema.TextMuted,
                    fontSize = 10.sp
                )
            }
            if (managing) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Remove mode · press a poster to delete it from your library.",
                    color = Cinema.Offline,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(18.dp))
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val spacing = 18.dp
            val columns = remember(maxWidth, posterStyle.gridCellWidth) {
                val available = maxWidth - 96.dp
                (((available + spacing) / (posterStyle.gridCellWidth + spacing)).toInt()).coerceAtLeast(1)
            }
            val requesters = remember(filtered) {
                filtered.associate { it.key to FocusRequester() }
            }
            LazyVerticalGrid(
                state = rememberScreenGridState("favorites.grid"),
                columns = GridCells.Adaptive(posterStyle.gridCellWidth),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (filtered.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val (title, message) = when {
                            saved.isEmpty() -> "No favorites yet" to
                                "Star a movie or TV show with a long OK press, or press ☆ FAVORITE on a detail page."
                            filter == FavoriteFilter.MOVIES -> "No favorite movies" to
                                "Open Movies and add one to this list."
                            else -> "No favorite series" to
                                "Open TV Shows and add one to this list."
                        }
                        EmptyState(
                            title = title,
                            message = message,
                            actionLabel = if (saved.isNotEmpty()) "SHOW ALL FAVORITES" else null,
                            onAction = if (saved.isNotEmpty()) {
                                { filter = FavoriteFilter.ALL }
                            } else {
                                null
                            }
                        )
                    }
                } else {
                    itemsIndexed(filtered, key = { _, item -> item.key }) { index, item ->
                        val rowStart = index % columns == 0
                        val status = episodeStatus[item.key]
                        PosterCard(
                            item = item,
                            onClick = { onOpenDetail(item) },
                            focusRequester = requesters.getValue(item.key),
                            leftFocus = if (rowStart) {
                                null
                            } else {
                                filtered.getOrNull(index - 1)?.let { requesters[it.key] }
                            },
                            starOnLeft = rowStart,
                            progress = progressByKey[item.key],
                            favorite = favorites.any { it.key == item.key },
                            onRemove = if (!managing) {
                                null
                            } else {
                                ({ vm.removeFromLibrary(item) })
                            },
                            onToggleFavorite = if (managing) null else ({ vm.toggleFavorite(item) }),
                            statusText = status,
                            focusSurface = "favorites.grid"
                        )
                    }
                }
            }
        }
    }
}

private fun newEpisodeLabel(seasons: List<Season>, watched: Set<Pair<Int, Int>>, today: String): String? {
    val count = newEpisodeCount(seasons, watched, today) ?: return null
    return "$count NEW EPISODE${if (count == 1) "" else "S"}"
}

private fun savedJson(items: List<CatalogItem>): String {
    val root = JSONObject()
    root.put("exportedAt", JsonBackup.isoTimestamp())
    root.put("count", items.size)
    root.put("favorites", JSONArray(items.map { it.toJson() }))
    return root.toString(2)
}

private fun parseFavoritesJson(text: String): List<CatalogItem> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) error("The selected file is empty")
    val array = if (trimmed.startsWith("[")) {
        JSONArray(trimmed)
    } else {
        val root = JSONObject(trimmed)
        root.optJSONArray("favorites")
            ?: root.optJSONArray("items")
            ?: error("No favorites[] array found in that file")
    }
    return (0 until array.length())
        .mapNotNull { index -> array.optJSONObject(index)?.let { CatalogItem.fromJson(it) } }
        .filter { it.tmdbId > 0 }
}
