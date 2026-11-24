package com.streamline.cinema.ui.player

import android.view.KeyEvent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.FocusScale
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.glassSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DockScrim = Color(0xFF0E1629)

@Composable
internal fun PlayerTopHeader(
    title: String,
    meta: String,
    status: String?,
    clock: String,
    backFocus: FocusRequester,
    downTarget: FocusRequester,
    onBack: () -> Unit,
    onBackFocusChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 30.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BackButton(
            focusRequester = backFocus,
            downTarget = downTarget,
            onFocusChange = onBackFocusChange,
            onClick = onBack
        )
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = Cinema.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = meta,
                    color = Cinema.TextSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!status.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = status,
                    color = Cinema.Gold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        Text(
            text = clock,
            color = Cinema.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun BackButton(
    focusRequester: FocusRequester,
    downTarget: FocusRequester,
    onFocusChange: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(46.dp)
            .focusRequester(focusRequester)
            .focusProperties {
                // The header is the top of the OSD: only DOWN has somewhere
                // to go. Cancel keeps the other directions from hunting for a
                // neighbouring control on the far side of the screen.
                up = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
                down = downTarget
            }
            .glassSurface(
                shape = CircleShape,
                focused = focused,
                base = Color.White.copy(alpha = 0.08f),
                scale = FocusScale.ICON,
                lift = 0.14f
            )
            .onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        PlayerGlyphIcon(
            glyph = PlayerGlyph.BACK,
            tint = Cinema.TextPrimary,
            size = 22.dp
        )
    }
}

/** Current EPG window for the tuned channel, handed to the live OSD. */
internal data class LiveProgramInfo(
    val title: String,
    val startMs: Long,
    val stopMs: Long,
    val nextTitle: String? = null,
    val nextStartMs: Long? = null
) {
    fun progressAt(nowMs: Long): Float =
        if (stopMs <= startMs) 0f
        else ((nowMs - startMs).toFloat() / (stopMs - startMs)).coerceIn(0f, 1f)
}

@Composable
internal fun PlayerDock(
    telemetry: PlaybackTelemetry,
    durationMs: Long,
    isPlaying: Boolean,
    controlsEnabled: Boolean,
    seekEnabled: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    aspectLabel: String,
    aspectActive: Boolean,
    qualityLabel: String?,
    qualityVisible: Boolean,
    subtitlesOn: Boolean,
    decoderLabel: String,
    skipLabel: String? = null,
    skipHint: String = "Skip intro",
    nextHighlight: Boolean = false,
    previousLabel: String = "Previous episode",
    nextLabel: String = "Next episode",
    playFocus: FocusRequester,
    seekFocus: FocusRequester,
    skipFocus: FocusRequester,
    backFocus: FocusRequester,
    onSeekFocusChange: (Boolean) -> Unit,
    onSkipFocusChange: (Boolean) -> Unit = {},
    onDockFocusChange: (Boolean) -> Unit = {},
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onSeekRelative: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSkipSegment: () -> Unit = {},
    onCycleAspect: () -> Unit,
    onSources: () -> Unit,
    onQuality: () -> Unit,
    onSubtitles: () -> Unit,
    onCycleDecoder: () -> Unit,
    onExternal: () -> Unit,
    onOverflow: () -> Unit,
    isLive: Boolean = false,
    liveProgram: LiveProgramInfo? = null,
    liveChannelName: String = "",
    scrubFraction: Float? = null,
    modifier: Modifier = Modifier
) {
    // The dock is the only reader of the 1 Hz counters, which keeps the
    // position tick from recomposing the screen that mounts the video surface.
    // A scrubbed target is previewed until the ticker catches up.
    val positionMs = scrubFraction?.let { (it * durationMs).toLong() } ?: telemetry.positionMs
    val bufferedMs = telemetry.bufferedMs
    val previousFocus = remember { FocusRequester() }
    val rewindFocus = remember { FocusRequester() }
    val forwardFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    val aspectFocus = remember { FocusRequester() }
    val sourcesFocus = remember { FocusRequester() }
    val qualityFocus = remember { FocusRequester() }
    val subtitlesFocus = remember { FocusRequester() }
    val decoderFocus = remember { FocusRequester() }
    val externalFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }

    // The live OSD replaces the seek bar, so dock "up" stays on the transport row.
    val upFocus = if (isLive) playFocus else seekFocus

    var hint by remember { mutableStateOf<String?>(null) }
    fun hintFor(label: String, focused: Boolean) {
        if (focused) {
            hint = label
            onDockFocusChange(true)
        } else if (hint == label) {
            hint = null
        }
    }
    // The pill is its own zone above the timeline, so its focus must not read
    // as "inside the dock" or UP/DOWN would fight the caller's zone routing.
    fun pillHint(label: String, focused: Boolean) {
        if (focused) hint = label else if (hint == label) hint = null
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.35f to DockScrim.copy(alpha = 0.55f),
                    0.65f to DockScrim.copy(alpha = 0.88f),
                    1f to DockScrim.copy(alpha = 0.97f)
                )
            )
            .padding(start = 40.dp, end = 40.dp, top = 72.dp, bottom = 26.dp)
    ) {
        if (isLive) {
            LiveProgramRow(
                program = liveProgram,
                channelName = liveChannelName,
                nowMs = telemetry.liveNowMs
            )
        } else {
            Box(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTime(positionMs),
                        color = Cinema.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(16.dp))
                    PlayerSeekBar(
                        positionMs = positionMs,
                        bufferedMs = bufferedMs,
                        durationMs = durationMs,
                        enabled = seekEnabled,
                        focusRequester = seekFocus,
                        upTarget = if (skipLabel != null) skipFocus else backFocus,
                        downTarget = playFocus,
                        onFocusChange = { focused ->
                            onSeekFocusChange(focused)
                            if (focused) {
                                hint = "Seek · ←/→ skip 10 s"
                            } else if (hint == "Seek · ←/→ skip 10 s") {
                                hint = null
                            }
                        },
                        onScrubStart = onScrubStart,
                        onScrub = onScrub,
                        onScrubEnd = onScrubEnd,
                        onSeekRelative = onSeekRelative,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        // A VOD row with nothing loaded yet is not live; claiming so
                        // reads as a channel when the search simply found no source.
                        text = if (durationMs > 0) formatTime(durationMs) else "",
                        color = Cinema.TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                // Skip offer floats clear of the transport row, right-aligned
                // over the end of the bar it relates to.
                skipLabel?.let { label ->
                    SkipPill(
                        text = label,
                        hint = skipHint,
                        focusRequester = skipFocus,
                        downTarget = seekFocus,
                        onFocusHint = ::pillHint,
                        onFocusChange = onSkipFocusChange,
                        onClick = onSkipSegment,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = (-50).dp)
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp),
            contentAlignment = Alignment.Center
        ) {
            hint?.let { text ->
                Text(
                    text = text,
                    color = Cinema.Gold,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            DockButton(
                glyph = PlayerGlyph.PREVIOUS,
                label = previousLabel,
                enabled = previousEnabled && controlsEnabled,
                focusRequester = previousFocus,
                upTarget = upFocus,
                leftTarget = previousFocus,
                rightTarget = rewindFocus,
                onFocusHint = ::hintFor,
                onClick = onPrevious
            )
            DockButton(
                glyph = PlayerGlyph.REWIND,
                label = "Back 10 s",
                enabled = controlsEnabled,
                focusRequester = rewindFocus,
                upTarget = upFocus,
                leftTarget = if (previousEnabled) previousFocus else rewindFocus,
                rightTarget = playFocus,
                onFocusHint = ::hintFor,
                onClick = onRewind
            )
            DockButton(
                glyph = if (isPlaying) PlayerGlyph.PAUSE else PlayerGlyph.PLAY,
                label = if (isPlaying) "Pause" else "Play",
                enabled = controlsEnabled,
                primary = true,
                focusRequester = playFocus,
                upTarget = upFocus,
                leftTarget = rewindFocus,
                rightTarget = forwardFocus,
                onFocusHint = ::hintFor,
                onClick = onPlayPause
            )
            DockButton(
                glyph = PlayerGlyph.FORWARD,
                label = "Forward 10 s",
                enabled = controlsEnabled,
                focusRequester = forwardFocus,
                upTarget = upFocus,
                leftTarget = playFocus,
                rightTarget = when {
                    nextEnabled -> nextFocus
                    else -> aspectFocus
                },
                onFocusHint = ::hintFor,
                onClick = onForward
            )
            DockButton(
                glyph = PlayerGlyph.NEXT,
                label = nextLabel,
                enabled = nextEnabled && controlsEnabled,
                active = nextHighlight,
                focusRequester = nextFocus,
                upTarget = upFocus,
                leftTarget = forwardFocus,
                rightTarget = aspectFocus,
                onFocusHint = ::hintFor,
                onClick = onNext
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            DockButton(
                glyph = PlayerGlyph.DISPLAY,
                label = "Display · $aspectLabel",
                active = aspectActive,
                focusRequester = aspectFocus,
                upTarget = upFocus,
                leftTarget = when {
                    nextEnabled -> nextFocus
                    else -> forwardFocus
                },
                rightTarget = sourcesFocus,
                onFocusHint = ::hintFor,
                onClick = onCycleAspect
            )
            DockButton(
                glyph = PlayerGlyph.SOURCES,
                label = "Playback sources",
                focusRequester = sourcesFocus,
                upTarget = upFocus,
                leftTarget = aspectFocus,
                rightTarget = if (qualityVisible) qualityFocus else subtitlesFocus,
                onFocusHint = ::hintFor,
                onClick = onSources
            )
            if (qualityVisible) {
                DockButton(
                    text = qualityLabel ?: "AUTO",
                    label = "Quality · ${qualityLabel ?: "Auto"}",
                    focusRequester = qualityFocus,
                    upTarget = upFocus,
                    leftTarget = sourcesFocus,
                    rightTarget = subtitlesFocus,
                    onFocusHint = ::hintFor,
                    onClick = onQuality
                )
            }
            DockButton(
                glyph = PlayerGlyph.SUBTITLES,
                label = if (subtitlesOn) "Subtitles · On" else "Subtitles · Off",
                active = subtitlesOn,
                focusRequester = subtitlesFocus,
                upTarget = upFocus,
                leftTarget = if (qualityVisible) qualityFocus else sourcesFocus,
                rightTarget = decoderFocus,
                onFocusHint = ::hintFor,
                onClick = onSubtitles
            )
            DockButton(
                text = decoderLabel,
                label = "Decoder · $decoderLabel",
                active = decoderLabel != "AUTO",
                focusRequester = decoderFocus,
                upTarget = upFocus,
                leftTarget = subtitlesFocus,
                rightTarget = externalFocus,
                onFocusHint = ::hintFor,
                onClick = onCycleDecoder
            )
            DockButton(
                glyph = PlayerGlyph.EXTERNAL,
                label = "Play in external player",
                focusRequester = externalFocus,
                upTarget = upFocus,
                leftTarget = decoderFocus,
                rightTarget = moreFocus,
                onFocusHint = ::hintFor,
                onClick = onExternal
            )
            DockButton(
                glyph = PlayerGlyph.MORE,
                label = "More",
                focusRequester = moreFocus,
                upTarget = upFocus,
                leftTarget = externalFocus,
                rightTarget = moreFocus,
                onFocusHint = ::hintFor,
                onClick = onOverflow
            )
        }
    }
}

@Composable
private fun DockButton(
    glyph: PlayerGlyph? = null,
    text: String? = null,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    leftTarget: FocusRequester? = null,
    rightTarget: FocusRequester? = null,
    onFocusHint: (String, Boolean) -> Unit = { _, _ -> },
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    // Play/pause swaps its label while focused; without this the hint strip
    // keeps naming the old action until focus moves away.
    LaunchedEffect(label, focused) {
        if (focused) onFocusHint(label, true)
    }
    val diameter = if (primary) 66.dp else 52.dp
    val tint = if (active) Cinema.Gold else Cinema.TextPrimary
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(diameter)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                // The transport row is the bottom of the OSD. DOWN is owned by
                // the screen's zone routing (it hides the controls); Cancel
                // keeps stray traversal from leaving the row sideways.
                down = FocusRequester.Cancel
                upTarget?.let { up = it }
                leftTarget?.let { left = it }
                rightTarget?.let { right = it }
            }
            .graphicsLayer { alpha = if (enabled) 1f else 0.32f }
            .glassSurface(
                shape = CircleShape,
                focused = focused,
                base = if (active) Glass.AccentWash else Color.Transparent,
                scale = FocusScale.ICON,
                lift = 0.12f
            )
            .onFocusChanged {
                focused = it.isFocused
                onFocusHint(label, it.isFocused)
            }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (text != null) {
            Text(
                text = text,
                color = tint,
                fontSize = if (text.length >= 5) 10.sp else 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
        } else if (glyph != null) {
            PlayerGlyphIcon(
                glyph = glyph,
                tint = tint,
                size = if (primary) 30.dp else 24.dp
            )
        }
    }
}

/**
 * Contextual skip offer. It floats above the end of the seek bar so it reads as
 * an action on the timeline rather than another transport control; up from the
 * seek bar is the only way in, which keeps the transport row's left/right walk
 * uninterrupted while a segment is offered.
 */
@Composable
private fun SkipPill(
    text: String,
    hint: String,
    focusRequester: FocusRequester,
    downTarget: FocusRequester,
    onFocusHint: (String, Boolean) -> Unit = { _, _ -> },
    onFocusChange: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(CinemaDimens.PillRadius)
    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .focusProperties {
                up = FocusRequester.Cancel
                down = downTarget
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .glassSurface(
                shape = shape,
                focused = focused,
                base = Glass.AccentWash,
                scale = FocusScale.PILL,
                lift = 0.16f
            )
            .onFocusChanged {
                focused = it.isFocused
                onFocusHint(hint, it.isFocused)
                onFocusChange(it.isFocused)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Cinema.Gold,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
internal fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onSeekRelative: (Long) -> Unit,
    upTarget: FocusRequester? = null,
    downTarget: FocusRequester? = null,
    bufferedMs: Long = 0L,
    modifier: Modifier = Modifier
) {
    val progressFraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    // The buffer can never lag the playhead: engines that only publish a
    // playhead (VLC/MPV report buffered = position) then draw no band.
    val bufferedFraction = if (durationMs > 0) {
        (bufferedMs.toFloat() / durationMs).coerceIn(progressFraction, 1f)
    } else {
        0f
    }
    var focused by remember { mutableStateOf(false) }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .focusRequester(focusRequester)
            .focusProperties {
                // UP leaves for the skip pill (when offered) or the header,
                // DOWN returns to the transport row. LEFT/RIGHT are the bar's
                // own seek gestures, so they never traverse away.
                up = upTarget ?: FocusRequester.Cancel
                down = downTarget ?: FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
            }
            .focusable(enabled = enabled)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onSeekRelative(-10_000L)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onSeekRelative(10_000L)
                        true
                    }
                    else -> false
                }
            }
            // One gesture handler for tap and drag: two parallel detectors
            // cancel each other's tap when the finger lifts without slop.
            .pointerInput(enabled, durationMs) {
                if (!enabled || durationMs <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    if (width <= 0f) return@awaitEachGesture
                    var fraction = (down.position.x / width).coerceIn(0f, 1f)
                    onScrubStart()
                    onScrub(fraction)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) break
                        fraction = (change.position.x / width).coerceIn(0f, 1f)
                        onScrub(fraction)
                        change.consume()
                    }
                    onScrubEnd(fraction)
                }
            }
    ) {
        val centerY = size.height / 2f
        val capsuleHeight = 30.dp.toPx()
        if (focused) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.1f),
                topLeft = Offset(0f, centerY - capsuleHeight / 2f),
                size = Size(size.width, capsuleHeight),
                cornerRadius = CornerRadius(capsuleHeight / 2f)
            )
        }
        val barHeight = if (focused) 8.dp.toPx() else 6.dp.toPx()
        val radius = CornerRadius(barHeight / 2f)
        drawRoundRect(
            color = if (enabled) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f),
            topLeft = Offset(0f, centerY - barHeight / 2f),
            size = Size(size.width, barHeight),
            cornerRadius = radius
        )
        if (durationMs > 0) {
            // Loaded-but-unwatched span: a solid grey band that starts where the
            // gold playhead ends, so buffer headroom is readable at a glance.
            if (bufferedFraction > progressFraction) {
                drawRoundRect(
                    color = Color.White.copy(alpha = if (enabled) 0.55f else 0.14f),
                    topLeft = Offset(0f, centerY - barHeight / 2f),
                    size = Size(size.width * bufferedFraction, barHeight),
                    cornerRadius = radius
                )
            }
            drawRoundRect(
                color = Cinema.Gold,
                topLeft = Offset(0f, centerY - barHeight / 2f),
                size = Size(size.width * progressFraction, barHeight),
                cornerRadius = radius
            )
            val handleRadius = if (focused) 9.dp.toPx() else 7.dp.toPx()
            val center = Offset(size.width * progressFraction, centerY)
            drawCircle(Color.White, handleRadius, center)
            if (focused) {
                drawCircle(
                    color = Cinema.Gold,
                    radius = handleRadius + 2.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

/**
 * Live OSD timeline: the current programme, a start/end-flanked progress bar
 * built from the guide window, and the next programme. It never shows the
 * player buffer, which is meaningless for a live edge.
 */
@Composable
private fun LiveProgramRow(
    program: LiveProgramInfo?,
    channelName: String,
    nowMs: Long
) {
    val hasWindow = program != null && program.stopMs > program.startMs
    // The guide publishes absolute timestamps, so these are wall-clock labels
    // rather than the elapsed-time format used for the VOD seek bar.
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = program?.title?.takeIf { it.isNotBlank() }
                ?: channelName.takeIf { it.isNotBlank() }
                ?: "Live",
            color = Cinema.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (hasWindow) clock.format(Date(program!!.startMs)) else "",
                color = Cinema.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(14.dp))
            LiveProgressBar(
                fraction = program?.progressAt(nowMs) ?: 0f,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = if (hasWindow) clock.format(Date(program!!.stopMs)) else "LIVE",
                color = if (hasWindow) Cinema.TextSecondary else Cinema.Gold,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                program?.nextTitle.isNullOrBlank() -> "NEXT · Guide data unavailable"
                program?.nextStartMs == null -> "NEXT · ${program.nextTitle}"
                else -> "NEXT · ${program.nextTitle} · ${clock.format(Date(program.nextStartMs))}"
            },
            color = Cinema.TextMuted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LiveProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(20.dp)
    ) {
        val centerY = size.height / 2f
        val barHeight = 6.dp.toPx()
        val radius = CornerRadius(barHeight / 2f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.16f),
            topLeft = Offset(0f, centerY - barHeight / 2f),
            size = Size(size.width, barHeight),
            cornerRadius = radius
        )
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped > 0f) {
            drawRoundRect(
                color = Cinema.Gold,
                topLeft = Offset(0f, centerY - barHeight / 2f),
                size = Size(size.width * clamped, barHeight),
                cornerRadius = radius
            )
        }
    }
}

@Composable
internal fun OsdMenuRow(
    glyph: PlayerGlyph,
    label: String,
    value: String? = null,
    focusRequester: FocusRequester? = null,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 30.dp else 48.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .glassSurface(
                shape = RoundedCornerShape(9.dp),
                focused = focused,
                scale = FocusScale.ROW,
                lift = 0.12f
            )
            .onFocusChanged { focused = it.isFocused }
            .semantics { role = Role.Button }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 4.dp else 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerGlyphIcon(
            glyph = glyph,
            tint = Cinema.TextSecondary,
            size = if (compact) 16.dp else 20.dp
        )
        Spacer(Modifier.width(if (compact) 10.dp else 16.dp))
        Text(
            text = label,
            color = Cinema.TextPrimary,
            fontSize = if (compact) 13.sp else 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(if (compact) 8.dp else 16.dp))
            // The label wins the row: a long value used to take its intrinsic
            // width and squeeze names into "I…" in the two-column sheet.
            Text(
                text = value,
                color = Cinema.TextMuted,
                fontSize = if (compact) 11.sp else 13.sp,
                modifier = Modifier.widthIn(max = if (compact) 132.dp else 180.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String, focusRequester: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .glassSurface(
                shape = RoundedCornerShape(6.dp),
                focused = focused,
                scale = FocusScale.ROW,
                lift = 0.06f
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label.uppercase(),
            color = Cinema.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(132.dp)
        )
        Text(
            text = value,
            color = Cinema.TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

internal fun formatDurationLabel(ms: Long): String {
    val minutes = ms / 60_000
    if (minutes <= 0) return "${(ms / 1000).coerceAtLeast(0)}s"
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}
