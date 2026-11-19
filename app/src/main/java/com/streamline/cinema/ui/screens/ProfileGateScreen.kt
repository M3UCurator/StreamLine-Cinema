package com.streamline.cinema.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.data.AudioLanguage
import com.streamline.cinema.data.CinemaProfile
import com.streamline.cinema.data.MaturityLevel
import com.streamline.cinema.data.SubtitleLanguageOption
import com.streamline.cinema.ui.AvatarPicker
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.Chip
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.LocalUiSounds
import com.streamline.cinema.ui.PinDisplay
import com.streamline.cinema.ui.PinPad
import com.streamline.cinema.ui.ProfileAvatar
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.TvTextField
import com.streamline.cinema.ui.glassSurface
import com.streamline.cinema.ui.profileAvatarOptions
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
    var gateFocused by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    // Editing or deleting the active Kids profile would bypass Kids Mode
    // without the PIN, so profile management stays closed while it is active.
    LaunchedEffect(active.isKids) {
        if (active.isKids) managing = false
    }
    // Opening the gate disposes whatever held focus behind it, and that detach
    // can land after the first request, so keep asking briefly until a gate
    // control owns focus instead of letting the D-pad go dead.
    LaunchedEffect(profiles.size, managing, canDismiss) {
        repeat(12) {
            delay(if (it == 0) 180 else 100)
            if (gateFocused) return@LaunchedEffect
            runCatching { firstFocus.requestFocus() }
        }
    }

    BoxWithConstraints(
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
    ) {
        // Edge gutters are a share of the screen, not fixed dp, so a panel
        // that overscans by 5% cannot clip the row. The whole stack then
        // centres inside that safe zone.
        val gutterX = (maxWidth * 0.07f).coerceAtLeast(48.dp)
        val gutterY = (maxHeight * 0.06f).coerceAtLeast(28.dp)
        val gap = 24.dp
        val slots = profiles.size + 1
        val wide = LocalDensity.current.fontScale > 1.15f
        // Size the tiles from the space actually available so five slots land
        // in one centred row instead of scrolling on a 1080p panel.
        val fitted = (maxWidth - gutterX * 2 - gap * (slots - 1)) / slots
        val tileWidth = fitted.coerceIn(
            if (wide) 156.dp else 132.dp,
            if (wide) 196.dp else 176.dp
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = gutterX, vertical = gutterY)
                .onFocusChanged { gateFocused = it.hasFocus },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "WHO'S WATCHING?",
                color = Cinema.TextPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.2.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(40.dp))

            // Avatars are 256 px images pre-scaled for the tile; they load
            // straight from resources without a network or disk round trip.
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileTile(
                        profile = profile,
                        active = profile.id == active.id,
                        managing = managing,
                        width = tileWidth,
                        focusRequester = if (profile.id == profiles.first().id) firstFocus else null,
                        onClick = {
                            if (managing) editing = profile else onPicked(profile)
                        }
                    )
                }
                item(key = "add") {
                    AddProfileTile(
                        width = tileWidth,
                        onClick = {
                            editing = CinemaProfile(
                                id = "",
                                name = "",
                                avatarIndex = profiles.size % profileAvatarOptions.size
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            // Secondary actions close the stack, so Down from any tile always
            // lands on the same predictable row.
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
                    glyph = if (managing) "✓" else "＋",
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
            }
            if (active.isKids) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "KIDS PROFILE · rating lock uses your parental PIN",
                    color = Cinema.Gold,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center
                )
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
                    vm.addProfile(saved.name, saved.maturity, saved.avatarIndex)
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
    width: Dp,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val tileShape = RoundedCornerShape(CinemaDimens.CardRadius)
    // Focus is a short spring scale on top of the glass underline and edge
    // light: it reads from three metres away and costs one animated layer.
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.07f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 650f),
        label = "profileTileScale"
    )
    val avatar = (width * 0.70f).coerceIn(84.dp, 116.dp)
    Column(
        modifier = Modifier
            .width(width)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
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
        ProfileAvatar(index = profile.avatarIndex, size = avatar)
        Spacer(Modifier.height(12.dp))
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
private fun AddProfileTile(width: Dp, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val tileShape = RoundedCornerShape(CinemaDimens.CardRadius)
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.07f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 650f),
        label = "addProfileTileScale"
    )
    val avatar = (width * 0.70f).coerceIn(84.dp, 116.dp)
    Column(
        modifier = Modifier
            .width(width)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
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
                .size(avatar)
                .glassSurface(
                    shape = CircleShape,
                    base = Glass.Control,
                    sheen = 0.6f
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("＋", color = Cinema.Gold, fontSize = (avatar.value * 0.36f).sp)
        }
        Spacer(Modifier.height(12.dp))
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
    var avatarIndex by remember(seed.id) { mutableStateOf(seed.avatarIndex) }
    var maturity by remember(seed.id) { mutableStateOf(seed.maturity) }
    var audio by remember(seed.id) { mutableStateOf(seed.audioLanguage) }
    var subtitles by remember(seed.id) { mutableStateOf(seed.subtitleLanguage) }
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp

    Dialog(
        onDismissRequest = onDismiss,
        // The platform default width caps the window well below the editor's
        // intended 640 dp, which squeezed the avatar grid to three columns.
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.72f)
                .widthIn(max = 640.dp)
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
            Text("AVATAR", color = Cinema.TextMuted, fontSize = 10.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(8.dp))
            AvatarPicker(selected = avatarIndex, onSelect = { avatarIndex = it })
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
                                avatarIndex = avatarIndex,
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
