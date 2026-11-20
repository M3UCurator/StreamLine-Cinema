package com.streamline.cinema.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The only focus language in the app. Every interactive surface — tab pill,
 * poster, row, transport button, dialog action — renders its highlight through
 * [glassSurface] or [glassFrame], and both draw from these tokens.
 *
 * Growth is proportional to the element so small targets move visibly while a
 * full-width row never shoves its neighbours:
 *  - 14% icon-only controls (transport, star/remove toggles)
 *  - 6% pills and tabs
 *  - 5% media cards
 *  - 4% standard buttons and compact fields
 *  - 1% full-width rows
 */
enum class FocusScale(val factor: Float) {
    NONE(1f),
    ROW(1.01f),
    CONTROL(1.04f),
    CARD(1.05f),
    PILL(1.06f),
    ICON(1.14f)
}

object FocusTokens {
    /** Fast enough to feel instant, slow enough to read as movement. */
    const val DurationMs = 120

    /** Monotonic: it approaches the target and settles, never overshoots. */
    val Easing = FastOutSlowInEasing

    /** Warm ring; 15.4:1 against Cinema.Ink, 13.1:1 against Cinema.Panel. */
    val Ring = Color(0xFFFFDEA0)

    val RingWidth = 2.dp

    /** White lift applied to a control's fill while focused. */
    const val FillLift = 0.07f

    /** Smaller lift over poster/still artwork so the image stays true. */
    const val MediaLift = 0.04f

    fun lifted(base: Color, amount: Float = FillLift): Color = lerp(base, Color.White, amount)
}

@Composable
internal fun rememberFocusAmount(focused: Boolean, enabled: Boolean = true): State<Float> =
    animateFloatAsState(
        targetValue = if (focused && enabled) 1f else 0f,
        animationSpec = tween(FocusTokens.DurationMs, easing = FocusTokens.Easing),
        label = "focusAmount"
    )

/**
 * Transform-only growth. The layout node keeps its measured size, so neighbours
 * never move and the user's spatial memory stays valid while focus trails.
 */
fun Modifier.focusScale(
    focused: Boolean,
    scale: FocusScale,
    enabled: Boolean = true
): Modifier = composed {
    if (scale == FocusScale.NONE) return@composed this
    val amount = rememberFocusAmount(focused, enabled)
    graphicsLayer {
        val growth = 1f + (scale.factor - 1f) * amount.value
        scaleX = growth
        scaleY = growth
    }
}

internal fun DrawScope.shapeCorner(shape: RoundedCornerShape): CornerRadius {
    val max = size.minDimension / 2f
    return CornerRadius(
        shape.topStart.toPx(size, this).coerceAtMost(max),
        shape.topEnd.toPx(size, this).coerceAtMost(max)
    )
}

/**
 * 2 dp warm ring plus a 1 dp dark seam just inside it. The seam keeps the ring
 * legible on gold fills and bright artwork without adding a second focus style.
 */
internal fun DrawScope.drawFocusRing(shape: RoundedCornerShape, amount: Float) {
    if (amount <= 0.01f) return
    val stroke = FocusTokens.RingWidth.toPx()
    if (size.minDimension <= stroke * 3f) return
    val corner = shapeCorner(shape)
    val inset = stroke / 2f
    val seamInset = stroke * 1.9f
    if (size.minDimension > seamInset * 2f) {
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.38f * amount),
            topLeft = Offset(seamInset / 2f, seamInset / 2f),
            size = Size(size.width - seamInset, size.height - seamInset),
            cornerRadius = CornerRadius(
                (corner.x - seamInset / 2f).coerceAtLeast(0f),
                (corner.y - seamInset / 2f).coerceAtLeast(0f)
            ),
            style = Stroke(width = 1f)
        )
    }
    drawRoundRect(
        color = FocusTokens.Ring.copy(alpha = amount),
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(
            (corner.x - inset).coerceAtLeast(0f),
            (corner.y - inset).coerceAtLeast(0f)
        ),
        style = Stroke(width = stroke)
    )
}

/**
 * Composable form of the primitive: focus tracking, proportional growth, glass
 * fill, warm ring, click/long-click and the UI sound layer in one place.
 * Content is laid out by the caller; nothing draws its own highlight.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(CinemaDimens.ControlRadius),
    scale: FocusScale = FocusScale.CONTROL,
    base: Color = Color.Transparent,
    tint: Color = Color.Transparent,
    enabled: Boolean = true,
    role: Role = Role.Button,
    contentDescription: String? = null,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    // Surfaces that raise a minimum height must centre their content, or a
    // short label pins to the top of a tall button and reads as misaligned.
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val sounds = LocalUiSounds.current
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onLongClick = {
                if (enabled) {
                    sounds.select()
                    onLongClick()
                }
            },
            onClick = {
                if (enabled) {
                    sounds.select()
                    onClick()
                }
            }
        )
    } else {
        Modifier.clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {
                if (enabled) {
                    sounds.select()
                    onClick()
                }
            }
        )
    }
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .alpha(if (enabled) 1f else 0.4f)
            .glassSurface(
                shape = shape,
                focused = focused,
                base = base,
                tint = tint,
                scale = scale,
                indicator = enabled
            )
            .onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
                if (it.isFocused) sounds.focus()
            }
            .semantics {
                this.role = role
                if (!contentDescription.isNullOrBlank()) {
                    this.contentDescription = contentDescription
                }
            }
            .then(clickModifier),
        contentAlignment = contentAlignment,
        content = content
    )
}
