package com.streamline.cinema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.data.AudioLanguage
import com.streamline.cinema.data.CinemaProfile
import com.streamline.cinema.data.MaturityLevel
import com.streamline.cinema.data.SubtitleLanguageOption
import com.streamline.cinema.ui.BrandMark
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.Chip
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.LocalUiSounds
import com.streamline.cinema.ui.PinDisplay
import com.streamline.cinema.ui.PinPad
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.TvTextField
import com.streamline.cinema.ui.glassSurface
import com.streamline.cinema.ui.profileColor
import kotlinx.coroutines.delay

@Composable
fun ProfileGateScreen(
    vm: CinemaViewModel,
    canDismiss: Boolean,
    onClose: () -> Unit,
    onPicked: (CinemaProfile) -> Unit
) {
    val profiles by vm.profiles.collectAsState()
    val active by vm.activeProfile.collectAsState()
    var managing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CinemaProfile?>(null) }
    val firstFocus = remember { FocusRequester() }
    // Editing or deleting the active Kids profile would bypass Kids Mode
    // without the PIN, so profile management stays closed while it is active.
    LaunchedEffect(active.isKids) {
        if (active.isKids) managing = false
    }
    LaunchedEffect(profiles.size, managing) {
        delay(180)
        runCatching { firstFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Cinema.InkSoft, Cinema.Ink, Color(0xFF120C10))
                )
            )
            // The gate is modal: swallow taps so they never reach the Home grid
            // rendered behind the overlay.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 72.dp, vertical = 40.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            BrandMark(
                subtitle = "WHO'S WATCHING?",
                tileSize = 38.dp,
                wordmarkStyle = CinemaType.Wordmark.copy(fontSize = 17.sp, letterSpacing = 3.sp),
                subtitleStyle = CinemaType.WordmarkSub.copy(fontSize = 10.sp, letterSpacing = 3.sp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Every profile keeps its own Continue Watching, Favorites, ratings and languages.",
                color = Cinema.TextMuted,
                fontSize = 12.sp
            )

            // Tiles take the space between header and footer, so a larger font
            // scale can never overlap the subtitle or push the actions off-screen.
            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                contentAlignment = Alignment.CenterStart
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    items(profiles, key = { it.id }) { profile ->
                        ProfileTile(
                            profile = profile,
                            active = profile.id == active.id,
                            managing = managing,
                            focusRequester = if (profile.id == profiles.first().id) firstFocus else null,
                            onClick = {
                                if (managing) editing = profile else onPicked(profile)
                            }
                        )
                    }
                    item(key = "add") {
                        AddProfileTile(onClick = { editing = CinemaProfile(id = "", name = "") })
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvButton(
                    label = when {
                        active.isKids -> "PROFILES LOCKED"
                        managing -> "DONE MANAGING"
                        else -> "MANAGE PROFILES"
                    },
                    glyph = if (managing) "✓" else "⚙",
                    style = if (managing) TvButtonStyle.PRIMARY else TvButtonStyle.SECONDARY,
                    compact = true,
                    enabled = !active.isKids,
                    onClick = { managing = !managing }
                )
                if (canDismiss) {
                    TvButton(
                        label = "CANCEL",
                        style = TvButtonStyle.GHOST,
                        compact = true,
                        onClick = onClose
                    )
                }
                if (active.isKids) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "KIDS PROFILE · rating lock uses your parental PIN",
                        color = Cinema.Gold,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }

    editing?.let { draft ->
        ProfileEditorDialog(
            seed = draft,
            isNew = draft.id.isBlank(),
            canDelete = profiles.size > 1 && draft.id.isNotBlank(),
            onDismiss = { editing = null },
            onSave = { saved ->
                if (saved.id.isBlank()) {
                    vm.addProfile(saved.name, saved.maturity)
                } else {
                    vm.saveProfile(saved)
                }
                editing = null
            },
            onDelete = {
                if (vm.deleteProfile(draft.id)) editing = null
            }
        )
    }
}

@Composable
private fun ProfileTile(
    profile: CinemaProfile,
    active: Boolean,
    managing: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val tileShape = RoundedCornerShape(12.dp)
    // Bigger labels need a wider tile and a smaller avatar, otherwise names wrap
    // and the tile grows past the row box at 1.5x.
    val wide = LocalDensity.current.fontScale > 1.15f
    Column(
        modifier = Modifier
            .width(if (wide) 176.dp else 138.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .glassSurface(
                shape = tileShape,
                focused = focused,
                base = when {
                    focused -> Glass.Control
                    active -> Cinema.Gold.copy(alpha = 0.10f)
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
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(if (wide) 72.dp else 96.dp)
                .clip(CircleShape)
                .background(profileColor(profile.colorIndex)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = profile.initial,
                color = Cinema.Ink,
                fontSize = if (wide) 28.sp else 38.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Spacer(Modifier.height(if (wide) 8.dp else 10.dp))
        Text(
            text = profile.name,
            color = if (focused) Cinema.Gold else Cinema.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (managing) "EDIT" else profile.maturity.label.uppercase(),
            color = if (managing) Cinema.Offline else Cinema.TextMuted,
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun AddProfileTile(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val tileShape = RoundedCornerShape(12.dp)
    val wide = LocalDensity.current.fontScale > 1.15f
    Column(
        modifier = Modifier
            .width(if (wide) 176.dp else 138.dp)
            .glassSurface(
                shape = tileShape,
                focused = focused,
                base = if (focused) Glass.Control else Color.White.copy(alpha = 0.03f),
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
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(if (wide) 72.dp else 96.dp)
                .glassSurface(
                    shape = CircleShape,
                    base = Glass.Control,
                    sheen = 0.6f
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("＋", color = Cinema.Gold, fontSize = if (wide) 26.sp else 34.sp)
        }
        Spacer(Modifier.height(if (wide) 8.dp else 10.dp))
        Text(
            "Add profile",
            color = Cinema.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "KIDS OR ADULT",
            color = Cinema.TextMuted,
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun ProfileEditorDialog(
    seed: CinemaProfile,
    isNew: Boolean,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (CinemaProfile) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember(seed.id) { mutableStateOf(seed.name) }
    var maturity by remember(seed.id) { mutableStateOf(seed.maturity) }
    var audio by remember(seed.id) { mutableStateOf(seed.audioLanguage) }
    var subtitles by remember(seed.id) { mutableStateOf(seed.subtitleLanguage) }
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(640.dp)
                .heightIn(max = maxDialogHeight)
                .glassSurface(
                    shape = RoundedCornerShape(CinemaDimens.CardRadius),
                    base = Glass.Sheet
                )
                .padding(26.dp)
        ) {
            // Fields scroll; the action row stays pinned so SAVE/CANCEL are
            // always on screen on short TV viewports.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                text = if (isNew) "New profile" else "Edit ${seed.name}",
                color = Cinema.TextPrimary,
                style = CinemaType.SectionTitle
            )
            Spacer(Modifier.height(16.dp))
            TvTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                placeholder = "Profile name"
            )
            Spacer(Modifier.height(18.dp))
            Text("MATURITY", color = Cinema.TextMuted, fontSize = 10.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MaturityLevel.entries.forEach { level ->
                    Chip(
                        label = level.label,
                        selected = maturity == level,
                        onClick = { maturity = level }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(maturity.detail, color = Cinema.TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(18.dp))
            Text("AUDIO LANGUAGE", color = Cinema.TextMuted, fontSize = 10.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AudioLanguage.entries) { option ->
                    Chip(
                        label = option.label,
                        selected = audio == option.code,
                        compact = true,
                        onClick = { audio = option.code }
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("SUBTITLES", color = Cinema.TextMuted, fontSize = 10.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SubtitleLanguageOption.entries) { option ->
                    Chip(
                        label = option.label,
                        selected = subtitles == option.code,
                        compact = true,
                        onClick = { subtitles = option.code }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(
                    label = if (isNew) "CREATE PROFILE" else "SAVE",
                    glyph = "✓",
                    style = TvButtonStyle.PRIMARY,
                    compact = true,
                    enabled = name.isNotBlank(),
                    onClick = {
                        onSave(
                            seed.copy(
                                name = name,
                                maturity = maturity,
                                audioLanguage = audio,
                                subtitleLanguage = subtitles
                            )
                        )
                    }
                )
                TvButton("CANCEL", onDismiss, compact = true, style = TvButtonStyle.GHOST)
                if (canDelete) {
                    Spacer(Modifier.weight(1f))
                    TvButton(
                        label = "DELETE",
                        glyph = "✕",
                        style = TvButtonStyle.DANGER,
                        compact = true,
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

/** Parental PIN gate used when a Kids profile hands the remote to an adult. */
@Composable
fun ProfilePinDialog(
    title: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Boolean
) {
    PinDialog(
        title = title,
        message = "Enter the parental PIN to leave the Kids profile.",
        onDismiss = onDismiss,
        onSubmit = onSubmit
    )
}
