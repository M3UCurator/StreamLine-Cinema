package com.streamline.cinema.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.streamline.cinema.data.CatalogItem
import com.streamline.cinema.data.WatchProgress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

enum class AppTab(val label: String) {
    HOME("Home"),
    MOVIES("Movies"),
    SHOWS("TV Shows"),
    LIVE("Live"),
    FAVORITES("Favorites"),
    SETTINGS("Settings"),

    // Reachable from the top-bar SEARCH button (and Import from the library
    // toolbars); never rendered in the tab bar.
    PLAYROOM("Playroom"),
    IMPORT("Import")
}

val primaryTabs: List<AppTab> = listOf(
    AppTab.HOME,
    AppTab.MOVIES,
    AppTab.SHOWS,
    AppTab.LIVE,
    AppTab.FAVORITES,
    AppTab.SETTINGS
)

val hiddenTabs: Set<AppTab> = setOf(AppTab.PLAYROOM, AppTab.IMPORT)

enum class TvButtonStyle { PRIMARY, SECONDARY, GHOST, DANGER }

@Composable
fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TvButtonStyle = TvButtonStyle.SECONDARY,
    enabled: Boolean = true,
    glyph: String? = null,
    compact: Boolean = false,
    dense: Boolean = false,
    focusRequester: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val scale by animateFloatAsState(
        targetValue = if (focused && enabled) 1.04f else 1f,
        animationSpec = tween(120),
        label = "buttonScale"
    )
    val shape = RoundedCornerShape(CinemaDimens.ControlRadius)
    val base = when (style) {
        TvButtonStyle.PRIMARY -> Cinema.Gold
        TvButtonStyle.SECONDARY -> Glass.Control
        TvButtonStyle.GHOST -> if (focused) Glass.Sheet else Color.Transparent
        TvButtonStyle.DANGER -> if (focused) Glass.Sheet else Color.Transparent
    }
    val tint = when {
        style == TvButtonStyle.PRIMARY -> Color.Transparent
        style == TvButtonStyle.DANGER -> Glass.DangerWash
        focused -> Glass.AccentWash
        else -> Color.Transparent
    }
    val contentColor = when (style) {
        TvButtonStyle.PRIMARY -> Cinema.Ink
        TvButtonStyle.SECONDARY -> if (focused) Cinema.Gold else Cinema.TextPrimary
        TvButtonStyle.GHOST -> if (focused) Cinema.TextPrimary else Cinema.TextSecondary
        TvButtonStyle.DANGER -> Cinema.Offline
    }
    val showGlass = base.alpha > 0f || tint.alpha > 0f

    val clickableModifier = Modifier
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (enabled) 1f else 0.4f
        }
        .then(
            if (showGlass) {
                Modifier.glassSurface(shape = shape, focused = focused, base = base, tint = tint)
            } else {
                Modifier.clip(shape)
            }
        )
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) sounds.focus()
        }
        .semantics {
            role = Role.Button
            contentDescription = label
        }
        .clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {
                sounds.select()
                onClick()
            }
        )

    Box(
        modifier = modifier
            .then(clickableModifier)
            // Grows with font scale instead of clipping the label at 1.3×/1.5×.
            .defaultMinSize(minHeight = if (compact) 34.dp else 46.dp)
            .padding(horizontal = when { compact -> 12.dp; dense -> 14.dp; else -> 22.dp }),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (glyph != null) {
                Text(
                    text = glyph,
                    color = contentColor,
                    fontSize = if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = label,
                color = contentColor,
                style = if (compact) CinemaType.ButtonCompact else CinemaType.Button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    leadingGlyph: String? = null,
    focusRequester: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    // The IME is opt-in: D-pad focus alone must never throw a phone keyboard over
    // the screen. Only a direct tap or an OK press switches the field to editing.
    var editing by remember { mutableStateOf(false) }
    val fieldFocus = remember { focusRequester ?: FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(CinemaDimens.CardRadius)

    LaunchedEffect(editing) {
        if (editing) {
            runCatching { fieldFocus.requestFocus() }
            delay(80)
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }

    Box(modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = !editing,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                .glassSurface(
                    shape = shape,
                    focused = focused,
                    base = Glass.Control,
                    tint = if (focused) Glass.AccentWash.copy(alpha = 0.06f) else Color.Transparent,
                    indicator = false
                )
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) editing = false
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            editing = true
                            true
                        }
                        // A read-only field must not swallow the D-pad: arrows leave it.
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                            if (editing) {
                                false
                            } else {
                                focusManager.moveFocus(
                                    when (event.key) {
                                        Key.DirectionUp -> FocusDirection.Up
                                        Key.DirectionDown -> FocusDirection.Down
                                        Key.DirectionLeft -> FocusDirection.Left
                                        else -> FocusDirection.Right
                                    }
                                )
                                true
                            }
                        }
                        else -> false
                    }
                }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            textStyle = CinemaType.Button.copy(
                color = Cinema.TextPrimary,
                lineHeight = 20.sp
            ),
            cursorBrush = SolidColor(Cinema.Gold),
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(
                onDone = {
                    editing = false
                    // Keep a focused node after submit, otherwise the D-pad is dead:
                    // hand focus to whatever sits below the field, else to the right.
                    if (!focusManager.moveFocus(FocusDirection.Down)) {
                        focusManager.moveFocus(FocusDirection.Right)
                    }
                }
            ),
            decorationBox = { innerTextField ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (leadingGlyph != null) {
                        Text(
                            text = leadingGlyph,
                            color = if (focused) Cinema.Gold else Cinema.TextMuted,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = Cinema.TextMuted,
                                fontSize = 14.sp,
                                maxLines = maxLines,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            }
        )
        // While read-only, the field's own text-input node swallows the touch, so a
        // tap would only focus it. A transparent overlay owns the tap instead and
        // starts editing; it disappears once editing so the caret and selection work.
        if (!editing) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            editing = true
                            runCatching { fieldFocus.requestFocus() }
                        })
                    }
            )
        }
    }
}

@Composable
fun PinDisplay(
    label: String,
    value: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(CinemaDimens.ControlRadius)
    Column(modifier) {
        FieldLabel(label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(
                    shape = shape,
                    focused = active,
                    base = Glass.Control,
                    tint = if (active) Glass.AccentWash else Color.Transparent
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(4) { index ->
                Box(
                    Modifier
                        .width(10.dp)
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(if (index < value.length) Cinema.Gold else Cinema.Border)
                )
            }
        }
    }
}

/** On-screen keypad: TV boxes often have no usable soft keyboard. */
@Composable
fun PinPad(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "DEL", "0", "CLEAR")
            .chunked(3)
            .forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { key ->
                        TvButton(
                            label = key,
                            style = if (key == "DEL" || key == "CLEAR") {
                                TvButtonStyle.GHOST
                            } else {
                                TvButtonStyle.SECONDARY
                            },
                            compact = true,
                            modifier = Modifier.weight(1f),
                            focusRequester = if (key == "1") firstFocus else null,
                            onClick = {
                                when (key) {
                                    "CLEAR" -> onValueChange("")
                                    "DEL" -> onValueChange(value.dropLast(1))
                                    else -> if (value.length < 4) onValueChange(value + key)
                                }
                            }
                        )
                    }
                }
            }
    }
}

/**
 * Captures physical-keyboard PIN digits without an IME: typing fills the dots
 * directly and backspace deletes, while the on-screen keypad keeps working for
 * pointer-only boxes. Key events are consumed so a focused keypad button does
 * not also receive the digit.
 */
fun Modifier.pinKeyInput(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val digit = when (event.key) {
        Key.Zero, Key.NumPad0 -> '0'
        Key.One, Key.NumPad1 -> '1'
        Key.Two, Key.NumPad2 -> '2'
        Key.Three, Key.NumPad3 -> '3'
        Key.Four, Key.NumPad4 -> '4'
        Key.Five, Key.NumPad5 -> '5'
        Key.Six, Key.NumPad6 -> '6'
        Key.Seven, Key.NumPad7 -> '7'
        Key.Eight, Key.NumPad8 -> '8'
        Key.Nine, Key.NumPad9 -> '9'
        else -> null
    }
    when {
        digit != null -> {
            onDigit(digit)
            true
        }
        event.key == Key.Backspace || event.key == Key.Delete -> {
            onBackspace()
            true
        }
        else -> false
    }
}

/**
 * Window-level PIN capture for surfaces that are not a dialog. The [pinKeyInput]
 * modifier only sees keys on the focus path, so a pane that lost focus to the top
 * bar would drop typed digits; the activity drains this sink instead. Compose
 * attaches it while a PIN surface is on screen and detaches it on dispose.
 */
object PinKeyCapture {
    private var digitSink: ((Char) -> Unit)? = null
    private var backspaceSink: (() -> Unit)? = null

    fun attach(onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
        digitSink = onDigit
        backspaceSink = onBackspace
    }

    fun detach() {
        digitSink = null
        backspaceSink = null
    }

    fun consumeDigit(digit: Char): Boolean {
        val sink = digitSink ?: return false
        sink(digit)
        return true
    }

    fun consumeBackspace(): Boolean {
        val sink = backspaceSink ?: return false
        sink()
        return true
    }
}

@Composable
fun PosterImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    title: String = "",
    // Cards that carry their own spoken description pass null so the artwork is
    // not announced twice through the merged semantics node.
    description: String? = title
) {
    Box(modifier = modifier.background(Cinema.Panel)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = description,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Cinema.Panel, Cinema.PanelRaised))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title.take(1).uppercase().ifBlank { "?" },
                    color = Cinema.TextMuted,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ResumeBadge(
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Text(
        text = label,
        color = Cinema.Ink,
        fontSize = if (compact) 10.sp else 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Cinema.Gold)
            .padding(
                horizontal = if (compact) 9.dp else 11.dp,
                vertical = if (compact) 3.dp else 4.dp
            )
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: WatchProgress? = null,
    focusRequester: FocusRequester? = null,
    favorite: Boolean = false,
    onRemove: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    leftFocus: FocusRequester? = null,
    starOnLeft: Boolean = true,
    statusText: String? = null,
    rank: Int? = null,
    focusSurface: String? = null
) {
    var focused by remember { mutableStateOf(false) }
    var starFocused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val starFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }
    val selfFocus = focusRequester ?: cardFocus
    val focusMemory = LocalFocusMemory.current
    val style = LocalPosterStyle.current
    val cardShape = RoundedCornerShape(CinemaDimens.CardRadius)
    // Two reserved title lines keep the meta row level across a grid, even when
    // one card wraps and its neighbour does not.
    val titleBlock = with(LocalDensity.current) { (style.titleLineHeight * 2).toDp() }

    LaunchedEffect(focusSurface, focusMemory.restoreTick(focusSurface)) {
        if (focusSurface != null && focusMemory.recall(focusSurface) == item.key) {
            delay(90)
            runCatching { selfFocus.requestFocus() }
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(130),
        label = "posterScale"
    )
    val spoken = buildString {
        append(item.title)
        if (rank != null) append(", number $rank")
        item.yearLabel.takeIf { it.isNotBlank() }?.let { append(", $it") }
        progress?.takeIf { it.isResumable }?.let { append(", resume ${it.resumeLabel}") }
        statusText?.let { append(", $it") }
        if (onRemove != null) append(", press OK to remove")
    }
    Column(
        modifier = modifier
            .width(style.cardWidth)
            .zIndex(if (focused) 2f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(selfFocus)
            .then(
                if (onToggleFavorite != null || leftFocus != null) {
                    // LEFT returns to the previous column whenever one exists; the star
                    // stays the LEFT target of the row-start poster only.
                    Modifier.focusProperties {
                        val target = leftFocus
                            ?: if (starOnLeft && onToggleFavorite != null) starFocus else null
                        if (target != null) left = target
                    }
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
                if (it.isFocused && focusSurface != null) {
                    focusMemory.record(focusSurface, item.key)
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = spoken
            }
            .combinedClickable(
                onClick = {
                    sounds.select()
                    if (onRemove != null) onRemove() else onClick()
                },
                onLongClick = onToggleFavorite?.let { toggle ->
                    {
                        sounds.select()
                        toggle()
                    }
                }
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(style.aspectRatio)
                .glassFrame(cardShape, focused = focused)
        ) {
            PosterImage(
                url = if (style.isLandscape) item.backdropUrl ?: item.posterUrl else item.posterUrl,
                title = item.title,
                description = null,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.65f)
                        )
                    )
            )
            if (onToggleFavorite != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(30.dp)
                        .glassSurface(
                            shape = CircleShape,
                            focused = starFocused,
                            base = if (favorite) Cinema.Gold else Color.Black.copy(alpha = 0.55f)
                        )
                        .focusProperties { right = selfFocus }
                        .focusRequester(starFocus)
                        .onFocusChanged { starFocused = it.isFocused }
                        .clickable(onClick = onToggleFavorite)
                        .semantics {
                            contentDescription =
                                if (favorite) "Remove ${item.title} from favorites"
                                else "Add ${item.title} to favorites"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (favorite) "★" else "☆",
                        color = if (favorite) Cinema.Ink else Cinema.Gold,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (favorite) {
                Text(
                    text = "★",
                    color = Cinema.Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Cinema.Gold)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            if (progress != null && progress.isResumable) {
                ResumeBadge(
                    label = progress.resumeLabel,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
            if (rank != null) {
                Text(
                    text = "$rank",
                    color = Cinema.Gold,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 2.dp)
                )
            }
            if (rank == null) item.rating?.let { rating ->
                InfoPill(
                    text = "★ %.1f".format(rating),
                    color = Cinema.Gold,
                    background = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                )
            }
            if (onRemove != null) {
                Text(
                    text = "✕",
                    color = Cinema.TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Cinema.Offline.copy(alpha = 0.9f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            if (progress != null && progress.fraction > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.fraction)
                            .height(4.dp)
                            .background(Cinema.Gold)
                    )
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = item.title,
            color = if (focused) Cinema.Gold else Cinema.TextPrimary,
            fontSize = style.titleSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = style.titleLineHeight,
            modifier = Modifier.height(titleBlock)
        )
        val meta = listOfNotNull(
            item.yearLabel.takeIf { it.isNotBlank() },
            item.genres.firstOrNull()
        ).joinToString("  ·  ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                color = Cinema.TextMuted,
                fontSize = style.metaSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        statusText?.let {
            Text(
                text = it,
                color = Cinema.Gold,
                fontSize = style.metaSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun ContinueCard(
    item: CatalogItem,
    progress: WatchProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    focusSurface: String? = null
) {
    var focused by remember { mutableStateOf(false) }
    var removeFocused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val focusMemory = LocalFocusMemory.current
    val cardFocus = remember { FocusRequester() }
    val style = LocalPosterStyle.current
    val cardShape = RoundedCornerShape(CinemaDimens.CardRadius)
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(130), label = "continueScale")
    val spoken = buildString {
        append(item.title)
        if (progress.season != null && progress.episode != null) {
            append(", season ${progress.season} episode ${progress.episode}")
        }
        progress.episodeTitle?.let { append(", $it") }
        append(", ${progress.resumeLabel}")
    }

    LaunchedEffect(focusSurface, focusMemory.restoreTick(focusSurface)) {
        if (focusSurface != null && focusMemory.recall(focusSurface) == item.key) {
            delay(90)
            runCatching { cardFocus.requestFocus() }
        }
    }

    Column(
        modifier = modifier
            .width(style.continueWidth)
            .zIndex(if (focused || removeFocused) 2f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(cardFocus)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
                if (it.isFocused && focusSurface != null) {
                    focusMemory.record(focusSurface, item.key)
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = spoken
            }
            .clickable {
                sounds.select()
                onClick()
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .glassFrame(cardShape, focused = focused || removeFocused)
        ) {
            PosterImage(
                url = item.backdropUrl ?: item.posterUrl,
                title = item.title,
                description = null,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.4f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.9f)
                        )
                    )
            )
            if (progress.isResumable) {
                ResumeBadge(
                    label = progress.resumeLabel,
                    compact = true,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }
            if (onRemove != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .glassSurface(
                            shape = CircleShape,
                            focused = removeFocused,
                            base = if (removeFocused) Cinema.Gold else Color.Black.copy(alpha = 0.55f)
                        )
                        .onFocusChanged {
                            removeFocused = it.isFocused
                            if (it.isFocused) sounds.focus()
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Remove ${item.title} from Continue Watching"
                        }
                        .clickable {
                            sounds.select()
                            onRemove()
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "✕",
                        color = if (removeFocused) Cinema.Ink else Cinema.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                text = "▶",
                color = Cinema.Gold,
                fontSize = 20.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 15.dp, vertical = 8.dp)
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = item.title,
                    color = Cinema.TextPrimary,
                    fontSize = style.titleSize,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = style.titleLineHeight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (progress.season != null && progress.episode != null) {
                    Text(
                        text = "S${progress.season} E${progress.episode}" +
                            (progress.episodeTitle?.let { " · $it" } ?: ""),
                        color = Cinema.TextSecondary,
                        fontSize = style.metaSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.20f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.fraction)
                            .height(4.dp)
                            .background(Cinema.Gold)
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(18.dp)
                .background(Cinema.Gold, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(10.dp))
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Cinema.TextPrimary,
                style = CinemaType.SectionTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics { heading() }
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = subtitle,
                    color = Cinema.TextMuted,
                    style = CinemaType.SectionSubtitle,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(12.dp))
            TvButton(actionLabel, onAction, style = TvButtonStyle.GHOST, compact = true)
        }
    }
}

@Composable
fun PosterRow(
    title: String,
    items: List<CatalogItem>,
    onItemClick: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progressFor: (CatalogItem) -> WatchProgress? = { null },
    focusFirst: Boolean = false,
    favoriteKeys: Set<String> = emptySet(),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onItemRemove: ((CatalogItem) -> Unit)? = null,
    onItemFavorite: ((CatalogItem) -> Unit)? = null,
    ranked: Boolean = false,
    focusSurface: String? = null
) {
    if (items.isEmpty()) return
    val requesters = remember(items) { items.map { FocusRequester() } }
    val rowState = rememberScreenListState("posterRow.${focusSurface ?: title}")
    val snap = rememberSnapFlingBehavior(lazyListState = rowState)
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 26.dp)) {
        SectionHeader(title = title, subtitle = subtitle, actionLabel = actionLabel, onAction = onAction)
        LazyRow(
            state = rowState,
            flingBehavior = snap,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            // A wider end padding leaves the next card peeking at the right edge.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 6.dp,
                end = 56.dp,
                top = 4.dp,
                bottom = 4.dp
            )
        ) {
            itemsIndexed(items, key = { _, item -> item.key + item.source }) { index, item ->
                val requester = requesters[index]
                if (focusFirst && index == 0) {
                    androidx.compose.runtime.LaunchedEffect(item.key) {
                        kotlinx.coroutines.delay(120)
                        runCatching { requester.requestFocus() }
                    }
                }
                PosterCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    progress = progressFor(item),
                    focusRequester = requester,
                    leftFocus = requesters.getOrNull(index - 1),
                    starOnLeft = index == 0,
                    favorite = item.key in favoriteKeys,
                    onRemove = onItemRemove?.let { remove -> { remove(item) } },
                    onToggleFavorite = onItemFavorite?.let { toggle -> { toggle(item) } },
                    rank = if (ranked) index + 1 else null,
                    focusSurface = focusSurface
                )
            }
        }
    }
}

@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    compact: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val shape = RoundedCornerShape(CinemaDimens.PillRadius)
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .glassSurface(
                shape = shape,
                focused = focused,
                base = if (selected) Cinema.Gold else Glass.Control,
                tint = if (focused && !selected) Glass.AccentWash else Color.Transparent,
                sheen = if (selected) 0.7f else 0.55f
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .clickable {
                sounds.select()
                onClick()
            }
            .padding(
                horizontal = if (compact) 12.dp else 18.dp,
                vertical = if (compact) 6.dp else 9.dp
            )
    ) {
        Text(
            text = label,
            color = when {
                selected -> Cinema.Ink
                focused -> Cinema.TextPrimary
                else -> Cinema.TextSecondary
            },
            style = CinemaType.Pill,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val shape = RoundedCornerShape(CinemaDimens.ControlRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(
                shape = shape,
                focused = focused,
                base = Glass.Control,
                tint = if (focused) Glass.AccentWash else Color.Transparent
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = {
                    sounds.select()
                    onChange(it)
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Cinema.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (description != null) {
                Text(
                    description,
                    color = Cinema.TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box(
            modifier = Modifier
                .width(44.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) Cinema.Gold else Cinema.Border)
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (checked) Cinema.Ink else Cinema.TextSecondary)
            )
        }
    }
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = Cinema.TextMuted,
        style = CinemaType.Overline,
        modifier = modifier.padding(bottom = 6.dp)
    )
}

@Composable
fun InfoPill(
    text: String,
    color: Color = Cinema.TextSecondary,
    background: Color = Cinema.PanelRaised,
    modifier: Modifier = Modifier
) {
    // Opaque palette colours are eased into the glass opacity band so pills read
    // as layered material instead of painted chips.
    val fill = if (background.alpha >= 1f) background.copy(alpha = 0.62f) else background
    Text(
        text = text,
        color = color,
        style = CinemaType.Pill,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .glassSurface(
                shape = RoundedCornerShape(CinemaDimens.PillRadius),
                base = fill,
                sheen = 0.5f
            )
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

/** Two-tone pill: an accent key plus a bright value, e.g. `TMDB 8.1`. */
@Composable
fun InfoBadge(
    label: String,
    value: String,
    accent: Color = Cinema.TextPrimary,
    background: Color = Cinema.PanelRaised,
    modifier: Modifier = Modifier
) {
    val fill = if (background.alpha >= 1f) background.copy(alpha = 0.62f) else background
    Row(
        modifier = modifier
            .glassSurface(
                shape = RoundedCornerShape(CinemaDimens.PillRadius),
                base = fill,
                sheen = 0.5f
            )
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = accent,
            style = CinemaType.Pill,
            maxLines = 1
        )
        Text(
            text = value,
            color = Cinema.TextPrimary,
            style = CinemaType.Pill,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LoadingBlock(message: String = "Loading…", modifier: Modifier = Modifier) {    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(color = Cinema.Gold, strokeWidth = 3.dp)
        Text(message, color = Cinema.TextMuted, fontSize = 13.sp)
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(CinemaDimens.ControlRadius),
                base = Glass.Sheet,
                tint = Glass.DangerWash,
                sheen = 0.5f
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⚠", color = Cinema.Offline, fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            message,
            color = Cinema.TextPrimary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onRetry != null) {
            Spacer(Modifier.width(12.dp))
            TvButton("Retry", onRetry, style = TvButtonStyle.GHOST, compact = true)
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(64.dp)
                .glassSurface(
                    shape = RoundedCornerShape(18.dp),
                    base = Glass.Frame,
                    sheen = 0.8f
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("▤", color = Cinema.GoldDim, fontSize = 28.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, color = Cinema.TextPrimary, style = CinemaType.SectionTitle)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = Cinema.TextMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.widthIn(max = 560.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            TvButton(actionLabel, onAction, style = TvButtonStyle.PRIMARY)
        }
    }
}

@Composable
fun TopTabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    profileName: String = "",
    profileBadge: String = "",
    profileColor: Color = Cinema.Gold,
    onProfileClick: (() -> Unit)? = null,
    // Playroom search lives in the bar; Kids profiles pass null so the control
    // never renders (the tab gate behind it stays armed regardless).
    onSearchClick: (() -> Unit)? = null,
    // Down out of the tab strip cannot be resolved spatially when the page paints
    // under the bar, so full-bleed screens supply their entry focus explicitly.
    downFocusRequester: FocusRequester? = null,
    // Restores the tab (or the profile chip) that opened a PIN dialog once the
    // dialog closes; the bar stays composed underneath the dialog window.
    restoreTarget: String? = null,
    restoreTick: Int = 0
) {
    // At large font scales the bar has to shed spacing so every tab, the
    // profile chip and the status dot stay visible and focusable.
    val dense = LocalDensity.current.fontScale > 1.15f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CinemaDimens.TopBarHeight + CinemaDimens.TopBarScrim)
            .background(
                Brush.verticalGradient(
                    0f to Cinema.Ink.copy(alpha = 0.96f),
                    0.55f to Cinema.Ink.copy(alpha = 0.82f),
                    1f to Color.Transparent
                )
            )
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(CinemaDimens.TopBarHeight)
                .padding(horizontal = if (dense) 16.dp else 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandMark()
            Spacer(Modifier.width(if (dense) 12.dp else 26.dp))
            val tabRequesters = remember { primaryTabs.associateWith { FocusRequester() } }
            val profileFocus = remember { FocusRequester() }
            val searchFocus = remember { FocusRequester() }
            val lastTabFocus = tabRequesters.getValue(primaryTabs.last())
            val view = LocalView.current
            LaunchedEffect(restoreTarget, restoreTick) {
                if (restoreTick == 0 || restoreTarget.isNullOrBlank()) return@LaunchedEffect
                delay(140)
                // A dialog closing may leave the window without view focus; claim
                // it back before asking Compose to focus the bar control.
                if (!view.hasFocus()) runCatching { view.requestFocusFromTouch() }
                val requester = if (restoreTarget == "profile") {
                    profileFocus
                } else {
                    primaryTabs.firstOrNull { it.name == restoreTarget }?.let { tabRequesters[it] }
                }
                requester?.let { runCatching { it.requestFocus() } }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .focusGroup()
                    .glassSurface(
                        shape = RoundedCornerShape(CinemaDimens.PillRadius),
                        base = Color.White.copy(alpha = 0.035f),
                        sheen = 0.45f
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                primaryTabs.forEachIndexed { index, tab ->
                    val left = primaryTabs.getOrNull(index - 1)
                    val right = primaryTabs.getOrNull(index + 1)
                    TabItem(
                        tab = tab,
                        selected = tab == selected,
                        modifier = Modifier
                            .focusRequester(tabRequesters.getValue(tab))
                            .focusProperties {
                                if (left != null) this.left = tabRequesters.getValue(left)
                                if (right != null) {
                                    this.right = tabRequesters.getValue(right)
                                } else if (onSearchClick != null) {
                                    this.right = searchFocus
                                }
                                if (downFocusRequester != null) down = downFocusRequester
                            }
                    ) { onSelect(tab) }
                }
            }
            Spacer(Modifier.weight(1f))
            if (onSearchClick != null) {
                TvButton(
                    label = "SEARCH",
                    onClick = onSearchClick,
                    style = TvButtonStyle.GHOST,
                    glyph = "⌕",
                    compact = true,
                    focusRequester = searchFocus,
                    modifier = Modifier.focusProperties {
                        left = lastTabFocus
                        if (onProfileClick != null) right = profileFocus
                        if (downFocusRequester != null) down = downFocusRequester
                    }
                )
                Spacer(Modifier.width(if (dense) 10.dp else 18.dp))
            }
            TopBarClock()
            Spacer(Modifier.width(if (dense) 10.dp else 16.dp))
            if (onProfileClick != null) {
                ProfileChip(
                    name = profileName,
                    badge = profileBadge,
                    color = profileColor,
                    focusRequester = profileFocus,
                    leftFocusRequester = if (onSearchClick != null) searchFocus else null,
                    onClick = onProfileClick
                )
                Spacer(Modifier.width(if (dense) 10.dp else 18.dp))
            }
        }
    }
}

@Composable
private fun TopBarClock() {
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var time by remember { mutableStateOf(clock.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            time = clock.format(Date())
            // Wake on the next minute boundary so the label never lags the system clock.
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    Text(
        text = time,
        color = Cinema.TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp
    )
}

@Composable
private fun ProfileChip(
    name: String,
    badge: String,
    color: Color,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val shape = RoundedCornerShape(CinemaDimens.PillRadius)
    Row(
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusProperties {
                if (leftFocusRequester != null) left = leftFocusRequester
            }
            .glassSurface(
                shape = shape,
                focused = focused,
                base = if (focused) Glass.Control else Color.White.copy(alpha = 0.045f),
                tint = if (focused) Glass.AccentWash else Color.Transparent,
                sheen = 0.5f
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .clickable {
                sounds.select()
                onClick()
            }
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = badge,
                color = Cinema.Ink,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Text(
            text = name.ifBlank { "Profile" },
            color = if (focused) Cinema.Gold else Cinema.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            letterSpacing = 0.3.sp
        )
    }
}

@Composable
private fun TabItem(
    tab: AppTab,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val shape = RoundedCornerShape(CinemaDimens.PillRadius)
    val dense = LocalDensity.current.fontScale > 1.15f
    Column(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .then(
                if (focused) {
                    Modifier.glassSurface(
                        shape = shape,
                        focused = true,
                        base = Glass.Control,
                        tint = Glass.AccentWash,
                        sheen = 0.45f
                    )
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .clickable {
                sounds.select()
                onClick()
            }
            .padding(horizontal = if (dense) 8.dp else 14.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = tab.label,
            color = when {
                selected -> Cinema.Gold
                focused -> Cinema.TextPrimary
                else -> Cinema.TextSecondary
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .width(18.dp)
                .height(2.dp)
                .background(if (selected) Cinema.Gold else Color.Transparent)
        )
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.11f),
                        Color.Transparent
                    )
                )
            )
    )
}

/** Moving highlight used instead of a spinner while a rail or card is loading. */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )
    val sweep = progress * 1_200f - 600f
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.045f),
                        Color.White.copy(alpha = 0.115f),
                        Color.White.copy(alpha = 0.045f)
                    ),
                    start = Offset(sweep, 0f),
                    end = Offset(sweep + 600f, 420f)
                )
            )
    )
}

@Composable
fun SkeletonPoster(
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp? = null
) {
    val style = LocalPosterStyle.current
    Column(modifier.width(width ?: style.cardWidth)) {
        ShimmerBox(
            Modifier
                .fillMaxWidth()
                .aspectRatio(style.aspectRatio),
            shape = RoundedCornerShape(CinemaDimens.CardRadius)
        )
        Spacer(Modifier.height(10.dp))
        ShimmerBox(
            Modifier.fillMaxWidth(0.85f).height(style.titleSize.value.dp),
            shape = RoundedCornerShape(3.dp)
        )
        Spacer(Modifier.height(6.dp))
        ShimmerBox(
            Modifier.fillMaxWidth(0.5f).height(style.metaSize.value.dp),
            shape = RoundedCornerShape(3.dp)
        )
    }
}

@Composable
fun SkeletonRail(title: String, modifier: Modifier = Modifier, cards: Int = 6) {
    Column(modifier.fillMaxWidth().padding(bottom = 26.dp)) {
        SectionHeader(title = title, subtitle = "Loading…")
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(cards) {
                SkeletonPoster()
            }
        }
    }
}

/** Avatar palette shared by the profile picker and the top-bar chip. */
val profileAvatarColors: List<Color> = listOf(
    Cinema.Gold,
    Cinema.Crimson,
    Cinema.Online,
    Color(0xFF5B8DEF),
    Color(0xFFB07CFF),
    Color(0xFFFF8A5C)
)

fun profileColor(index: Int): Color =
    profileAvatarColors[index.coerceIn(0, profileAvatarColors.lastIndex)]

fun formatRuntime(minutes: Int?): String? =    minutes?.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m".replace("0h ", "") }

fun countLabel(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count ${if (count == 1) singular else plural}"
