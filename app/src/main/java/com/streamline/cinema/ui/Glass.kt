package com.streamline.cinema.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glass material tokens. Surfaces stay inside a tight 10–40% opacity band so a
 * panel still reads as a solid object over artwork, and focus is expressed with
 * a short gold underline instead of an outer glow.
 */
object Glass {
    /** Base tint of a floating sheet: dark enough for text, translucent enough to feel lit. */
    val Sheet = Color(0xFF0B0F16).copy(alpha = 0.62f)
    /** Controls sit one step lighter than sheets so they read as interactive. */
    val Control = Color(0xFF141924).copy(alpha = 0.72f)
    /** Barely-there fill used behind poster artwork (the image is the fill). */
    val Frame = Color(0xFF0D1119).copy(alpha = 0.55f)
    val AccentWash = Color(0xFFF4B942).copy(alpha = 0.12f)
    val DangerWash = Color(0xFFE5484D).copy(alpha = 0.14f)
    val EdgeHigh = Color.White.copy(alpha = 0.30f)
    val EdgeLow = Color.White.copy(alpha = 0.055f)
    val Halo = Color(0xFFF4B942)
    /** Top sheen of a sheet, simulating light caught on one edge. */
    val Sheen = Color.White.copy(alpha = 0.12f)
}

private fun DrawScope.glassCorner(shape: RoundedCornerShape): CornerRadius {
    val max = size.minDimension / 2f
    return CornerRadius(
        shape.topStart.toPx(size, this).coerceAtMost(max),
        shape.topEnd.toPx(size, this).coerceAtMost(max)
    )
}

private fun DrawScope.drawFocusUnderline(focus: Float) {
    if (focus <= 0.01f) return
    val thickness = 2.dp.toPx()
    if (size.height < 16.dp.toPx() || size.width < 24.dp.toPx()) return
    val width = (size.width * 0.42f)
        .coerceIn(thickness * 10f, 64.dp.toPx())
        .coerceAtMost(size.width - thickness * 4f)
    val bottom = size.height - 6.dp.toPx()
    drawRoundRect(
        color = Glass.Halo.copy(alpha = 0.95f * focus),
        topLeft = Offset((size.width - width) / 2f, bottom - thickness),
        size = Size(width, thickness),
        cornerRadius = CornerRadius(thickness / 2f)
    )
}

/** One physical pixel of light along the top-left edge; the only "border" left. */
private fun DrawScope.drawEdge(corner: CornerRadius, focus: Float) {
    if (size.width <= 2f || size.height <= 2f) return
    val top = lerp(Glass.EdgeHigh, Glass.Halo, focus * 0.7f)
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(top, Glass.EdgeLow, Glass.EdgeLow),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        ),
        topLeft = Offset(0.5f, 0.5f),
        size = Size(size.width - 1f, size.height - 1f),
        cornerRadius = corner,
        style = Stroke(width = 1f)
    )
}

/**
 * Frosted sheet: base tint, vertical sheen, optional accent wash, 1 px edge light
 * and a focus underline. Use on panels, dialogs, rows and pills.
 */
fun Modifier.glassSurface(
    shape: RoundedCornerShape = RoundedCornerShape(CinemaDimens.CardRadius),
    focused: Boolean = false,
    base: Color = Glass.Sheet,
    tint: Color = Color.Transparent,
    indicator: Boolean = true,
    sheen: Float = 1f
): Modifier = composed {
    val focus by animateFloatAsState(
        targetValue = if (focused && indicator) 1f else 0f,
        animationSpec = tween(160),
        label = "glassFocus"
    )
    Modifier
        .clip(shape)
        .drawBehind {
            drawRect(base)
            drawRect(
                Brush.verticalGradient(
                    0f to Glass.Sheen.copy(alpha = Glass.Sheen.alpha * sheen),
                    0.45f to Color.White.copy(alpha = 0.02f * sheen),
                    1f to Color.Black.copy(alpha = 0.10f)
                )
            )
            if (tint.alpha > 0f) drawRect(tint)
        }
        .drawWithContent {
            drawContent()
            drawEdge(glassCorner(shape), focus)
            drawFocusUnderline(focus)
        }
}

/**
 * Edge treatment for surfaces whose fill is an image (posters, stills, video
 * frames): focus underline + the same 1 px edge light, no fill.
 */
fun Modifier.glassFrame(
    shape: RoundedCornerShape = RoundedCornerShape(CinemaDimens.CardRadius),
    focused: Boolean = false,
    indicator: Boolean = true
): Modifier = composed {
    val focus by animateFloatAsState(
        targetValue = if (focused && indicator) 1f else 0f,
        animationSpec = tween(160),
        label = "glassFrameFocus"
    )
    Modifier
        .clip(shape)
        .drawWithContent {
            drawContent()
            drawEdge(glassCorner(shape), focus)
            drawFocusUnderline(focus)
        }
}

fun Modifier.glassEdge(shape: RoundedCornerShape, focused: Boolean = false): Modifier =
    glassFrame(shape = shape, focused = focused, indicator = false)

/**
 * Static backdrop: flat ink with a faint lift at the top edge. Posters and
 * stills carry the colour, so no wash competes with them.
 */
@Composable
fun AmbientGlassBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Cinema.InkSoft, Cinema.Ink), endY = 720f))
    )
}
