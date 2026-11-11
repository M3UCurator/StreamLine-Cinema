package com.streamline.cinema.ui

import android.app.ActivityManager
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.platform.LocalContext
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
 * Static ambient light behind the app. Heavy blur is applied only where the
 * platform supports it; older boxes still get the soft radial wash.
 */
@Composable
fun AmbientGlassBackdrop(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    val blurRadius: Dp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !lowRam) 96.dp else 0.dp
    BoxWithConstraints(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Cinema.Ink))
        AmbientBlob(
            color = Cinema.Gold.copy(alpha = 0.13f),
            diameter = 780.dp,
            blurRadius = blurRadius,
            modifier = Modifier.align(Alignment.TopStart).offset(x = (-260).dp, y = (-300).dp)
        )
        AmbientBlob(
            color = Cinema.Crimson.copy(alpha = 0.09f),
            diameter = 860.dp,
            blurRadius = blurRadius,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 220.dp, y = 260.dp)
        )
        AmbientBlob(
            color = Color(0xFF3E6BD8).copy(alpha = 0.10f),
            diameter = 640.dp,
            blurRadius = blurRadius,
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = 340.dp, y = (-60).dp)
        )
    }
}

@Composable
private fun AmbientBlob(
    color: Color,
    diameter: Dp,
    blurRadius: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .size(diameter)
            .then(
                if (blurRadius > 0.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.blur(blurRadius)
                } else {
                    Modifier
                }
            )
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    0f to color,
                    0.55f to color.copy(alpha = color.alpha * 0.45f),
                    1f to Color.Transparent
                )
            )
    )
}
