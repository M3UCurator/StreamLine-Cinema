package com.streamline.cinema.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.ImportPhase
import com.streamline.cinema.data.ImportPreset
import com.streamline.cinema.data.ImportPresets
import com.streamline.cinema.data.ImportSource
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.Divider
import com.streamline.cinema.ui.ErrorBanner
import com.streamline.cinema.ui.FieldLabel
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.InfoPill
import com.streamline.cinema.ui.LocalUiSounds
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.TvTextField
import com.streamline.cinema.ui.glassSurface
import com.streamline.cinema.ui.rememberScreenScrollState
import kotlinx.coroutines.delay

@Composable
fun ImportScreen(vm: CinemaViewModel, onOpenPlayroom: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val importState by vm.importState.collectAsState()
    val sources by vm.sources.collectAsState()
    val library by vm.library.collectAsState()

    var url by remember { mutableStateOf("") }
    var pickedPreset by remember { mutableStateOf<ImportPreset?>(null) }
    var presetPanelOpen by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }
    var pendingRemove by remember { mutableStateOf<ImportSource?>(null) }
    var removeNotice by remember { mutableStateOf<String?>(null) }
    val busy = importState.phase == ImportPhase.RUNNING

    // The remove dialog's buttons live in a separate window; when it closes the
    // viewer lands back on the DELETE button they pressed, not on the top bar.
    val view = LocalView.current
    val formFocus = remember { FocusRequester() }
    val deleteFocus = remember { mutableMapOf<String, FocusRequester>() }
    var focusReturnId by remember { mutableStateOf<String?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusTick) {
        if (focusTick == 0) return@LaunchedEffect
        // Retry until the window has focus back; a single late attempt can be lost.
        delay(170)
        repeat(10) { attempt ->
            if (!view.hasFocus()) runCatching { view.requestFocusFromTouch() }
            val granted = focusReturnId
                ?.let { id -> runCatching { deleteFocus[id]?.requestFocus() }.getOrNull() == true }
                ?: false
            if (!granted) runCatching { formFocus.requestFocus() }
            if (attempt < 9) delay(80)
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 22.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1.15f)
                .fillMaxHeight()
                .verticalScroll(rememberScreenScrollState("import.form"))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "IMPORT TERMINAL",
                    color = Cinema.Gold,
                    style = CinemaType.Kicker
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Feed your Cinema Hall",
                color = Cinema.TextPrimary,
                style = CinemaType.PageTitle
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "TMDB discover feeds and public MDBList collections are parsed on the proxy, " +
                    "then stored with artwork, ratings and episodic structures.",
                color = Cinema.TextSecondary,
                style = CinemaType.Body
            )

            Spacer(Modifier.height(20.dp))
            FieldLabel("Source library")
            Box {
                TvButton(
                    label = if (presetPanelOpen) "HIDE PRESETS" else "CHOOSE IMPORT SOURCE",
                    glyph = if (presetPanelOpen) "▲" else "▼",
                    style = TvButtonStyle.SECONDARY,
                    onClick = { presetPanelOpen = !presetPanelOpen }
                )
            }
            if (presetPanelOpen) {
                Spacer(Modifier.height(10.dp))
                PresetPanel(
                    selected = pickedPreset,
                    onPick = { preset ->
                        pickedPreset = preset
                        url = preset.url
                        presetPanelOpen = false
                    }
                )
            }

            Spacer(Modifier.height(18.dp))
            FieldLabel("Import URL")
            TvTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "https://… , themoviedb.org/list/… or /api/tmdb-presets?category=Popular%20Movies",
                focusRequester = formFocus
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(
                    label = if (busy) "IMPORTING" else "IMPORT CATALOG",
                    glyph = "↧",
                    style = TvButtonStyle.PRIMARY,
                    enabled = !busy && url.isNotBlank(),
                    onClick = { vm.importUrl(url) }
                )
                TvButton(
                    label = "SYNC ALL SOURCES",
                    glyph = "⟳",
                    style = TvButtonStyle.SECONDARY,
                    enabled = !busy && sources.isNotEmpty(),
                    onClick = { vm.syncAllSources() }
                )
            }
            // Kept on its own row: the three buttons do not fit the column width,
            // and a clipped PLAYROOM button is unreachable with a remote.
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(
                    label = "PLAYROOM",
                    glyph = "⌕",
                    style = TvButtonStyle.GHOST,
                    onClick = onOpenPlayroom
                )
            }

            Spacer(Modifier.height(18.dp))
            ImportStatusCard(
                phase = importState.phase,
                message = importState.message,
                pending = importState.pendingEnrichment
            )

            if (!settings.hasTmdbAccess) {
                Spacer(Modifier.height(14.dp))
                ErrorBanner(
                    "TMDB is offline — presets will be parsed but enrichment will wait " +
                        "until a key or proxy is configured in Settings."
                )
            }

            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("Pasted JSON manifest")
                Spacer(Modifier.weight(1f))
                Text(
                    "items[] · results[] · movies[] · shows[]",
                    color = Cinema.TextMuted,
                    fontSize = 10.sp
                )
            }
            TvTextField(
                value = jsonText,
                onValueChange = { jsonText = it },
                placeholder = "Paste a JSON array of catalog items…",
                singleLine = false,
                minLines = 5,
                maxLines = 8
            )
            Spacer(Modifier.height(10.dp))
            TvButton(
                label = "IMPORT JSON",
                glyph = "{ }",
                style = TvButtonStyle.SECONDARY,
                enabled = !busy && jsonText.isNotBlank(),
                onClick = { vm.importJson(jsonText) }
            )
            Spacer(Modifier.height(30.dp))
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("Imported sources")
                Spacer(Modifier.weight(1f))
                InfoPill("${sources.size} feeds · ${library.size} titles")
            }
            removeNotice?.let { notice ->
                Text(
                    notice,
                    color = Cinema.Online,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            if (sources.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(
                            shape = RoundedCornerShape(10.dp),
                            base = Glass.Sheet,
                            sheen = 0.6f
                        )
                        .padding(18.dp)
                ) {
                    Column {
                        Text(
                            "No sources registered yet",
                            color = Cinema.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Choose a TMDB or MDBList preset to build your first import, " +
                                "then re-sync it from here any time.",
                            color = Cinema.TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScreenScrollState("import.sources")),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sources.forEach { source ->
                        SourceRow(
                            source = source,
                            focusRequester = deleteFocus.getOrPut(source.id) { FocusRequester() },
                            onRemove = {
                                focusReturnId = source.id
                                pendingRemove = source
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Divider()
            Spacer(Modifier.height(14.dp))
            Text(
                "Queue status",
                color = Cinema.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (importState.pendingEnrichment > 0) {
                    "${importState.pendingEnrichment} titles are waiting for background enrichment. " +
                        "Artwork and episodes arrive one by one without freezing the interface."
                } else {
                    "Every imported title is fully enriched. New imports will queue automatically."
                },
                color = Cinema.TextMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }

    pendingRemove?.let { source ->
        val neighbour = sources.firstOrNull { it.id != source.id }?.id
        RemoveSourceDialog(
            source = source,
            onDismiss = {
                pendingRemove = null
                focusTick += 1
            },
            onRemoveListOnly = {
                vm.removeSource(source)
                removeNotice = "Removed ${source.name} · its titles stay in the library"
                pendingRemove = null
                focusReturnId = neighbour
                focusTick += 1
            },
            onRemoveListAndTitles = {
                val removed = vm.removeSource(source, deleteItems = true)
                removeNotice = "Removed ${source.name} with $removed titles"
                pendingRemove = null
                focusReturnId = neighbour
                focusTick += 1
            }
        )
    }
}

@Composable
private fun RemoveSourceDialog(
    source: ImportSource,
    onDismiss: () -> Unit,
    onRemoveListOnly: () -> Unit,
    onRemoveListAndTitles: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .glassSurface(
                    shape = RoundedCornerShape(CinemaDimens.CardRadius),
                    base = Glass.Sheet
                )
                .padding(24.dp)
        ) {
            Text(
                "Delete “${source.name}”?",
                color = Cinema.TextPrimary,
                style = CinemaType.SectionTitle
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This removes the saved import feed. The ${source.itemCount} titles it brought in " +
                    "can stay in your library or be deleted with it.",
                color = Cinema.TextSecondary,
                style = CinemaType.Body
            )
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(
                    label = "DELETE LIST + TITLES",
                    style = TvButtonStyle.DANGER,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRemoveListAndTitles
                )
                TvButton(
                    label = "LIST ONLY",
                    style = TvButtonStyle.SECONDARY,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRemoveListOnly
                )
                TvButton(
                    label = "CANCEL",
                    style = TvButtonStyle.GHOST,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun PresetPanel(selected: ImportPreset?, onPick: (ImportPreset) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(10.dp),
                base = Glass.Sheet,
                sheen = 0.6f
            )
            .padding(vertical = 8.dp)
    ) {
        listOf(
            ImportPresets.TMDB_GROUP to ImportPresets.tmdbPresets,
            ImportPresets.MDB_GROUP to ImportPresets.mdblistPresets
        ).forEach { (group, presets) ->
            Text(
                group,
                color = Cinema.Gold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
            presets.forEach { preset ->
                PresetRow(preset = preset, selected = preset == selected, onClick = { onPick(preset) })
            }
        }
    }
}

@Composable
private fun PresetRow(preset: ImportPreset, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = shape,
                focused = focused,
                base = when {
                    selected -> Cinema.Gold.copy(alpha = 0.10f)
                    focused -> Glass.Control
                    else -> Color.Transparent
                },
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
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            preset.name,
            color = if (selected) Cinema.Gold else if (focused) Cinema.TextPrimary else Cinema.TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (preset.kind.name == "TMDB_DISCOVER") "TMDB" else "MDBLIST",
            color = Cinema.TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun ImportStatusCard(phase: ImportPhase, message: String, pending: Int) {
    val accent = when (phase) {
        ImportPhase.IDLE -> Cinema.TextMuted
        ImportPhase.RUNNING -> Cinema.Pinging
        ImportPhase.SUCCESS -> Cinema.Online
        ImportPhase.ERROR -> Cinema.Offline
    }
    val title = when (phase) {
        ImportPhase.IDLE -> "Queue idle"
        ImportPhase.RUNNING -> "Importing catalog"
        ImportPhase.SUCCESS -> "Import complete"
        ImportPhase.ERROR -> "Import failed"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(10.dp),
                base = Glass.Sheet,
                tint = accent.copy(alpha = 0.10f),
                sheen = 0.6f
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(34.dp).background(accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(
                message.ifBlank {
                    "Select a source or paste a URL to pull a catalog into the Cinema Hall."
                },
                color = Cinema.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
        if (pending > 0) {
            Spacer(Modifier.width(12.dp))
            InfoPill("$pending enriching", Cinema.Gold, Cinema.Gold.copy(alpha = 0.12f))
        }
    }
}

@Composable
private fun SourceRow(
    source: ImportSource,
    focusRequester: FocusRequester? = null,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(8.dp),
                base = Glass.Control,
                sheen = 0.5f
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                source.name,
                color = Cinema.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${source.kind} · ${source.itemCount} titles · " +
                    DateUtils.getRelativeTimeSpanString(
                        source.lastSync,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    ),
                color = Cinema.TextMuted,
                fontSize = 10.sp,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        TvButton(
            label = "DELETE",
            style = TvButtonStyle.DANGER,
            compact = true,
            focusRequester = focusRequester,
            onClick = onRemove
        )
    }
}
