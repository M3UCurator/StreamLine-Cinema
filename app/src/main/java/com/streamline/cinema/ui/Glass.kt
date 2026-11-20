package com.streamline.cinema.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Glass material tokens. Surfaces stay inside a tight 10–40% opacity band so a
 * panel still reads as a solid object over artwork.
 *
 * Focus is not a material: it is the shared primitive in FocusSystem.kt — a
 * proportional grow, a lightened fill and a 2 dp warm ring. Nothing here draws
 * a separate underline or glow.
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
    /** Static 1 px separation edge; never used as a focus cue. */
    val Hairline = Color.White.copy(alpha = 0.08f)
    /** Top sheen of a sheet, simulating light caught on one edge. */
    val Sheen = Color.White.copy(alpha = 0.12f)
}

/**
 * Frosted sheet: base tint, vertical sheen, optional accent wash, then the
 * focus primitive (grow + filled lift + warm ring). Use on panels, dialogs,
 * rows and pills.
 */
fun Modifier.glassSurface(
    shape: RoundedCornerShape = RoundedCornerShape(CinemaDimens.CardRadius),
    focused: Boolean = false,
    base: Color = Glass.Sheet,
    tint: Color = Color.Transparent,
    indicator: Boolean = true,
    sheen: Float = 1f,
    scale: FocusScale = FocusScale.NONE,
    lift: Float = FocusTokens.FillLift
): Modifier = composed {
    val amount = rememberFocusAmount(focused = focused && indicator)
    Modifier
        // The scale layer wraps the clip: growth has to happen outside the
        // rounded bounds or the clip would eat every visible millimetre.
        .graphicsLayer {
            if (scale != FocusScale.NONE) {
                val growth = 1f + (scale.factor - 1f) * amount.value
                scaleX = growth
                scaleY = growth
            }
        }
        .clip(shape)
        .drawBehind {
            drawRect(base)
            drawRect(
                Brush.verticalGradient(
                    0f to Glass.Sheen.copy(alpha = Glass.Sheen.alpha * sheen),
                    0.45f to Color.White.copy(alpha = 0.02f * sheen),
                    1f to Color.Black.copy(alpha = 0.10f * sheen)
                )
            )
            if (tint.alpha > 0f) drawRect(tint)
            if (amount.value > 0.01f) {
                drawRect(Color.White.copy(alpha = lift * amount.value))
                drawRect(Glass.Hairline)
            }
        }
        .drawWithContent {
            drawContent()
            drawFocusRing(shape, amount.value)
        }
}

/**
 * Edge treatment for surfaces whose fill is an image (posters, stills, video
 * frames): the same focus primitive without the sheet fill. The image lifts by
 * a quiet 4% so the ring never floats on a surface that ignored focus.
 */
fun Modifier.glassFrame(
    shape: RoundedCornerShape = RoundedCornerShape(CinemaDimens.CardRadius),
    focused: Boolean = false,
    indicator: Boolean = true,
    scale: FocusScale = FocusScale.NONE
): Modifier = composed {
    val amount = rememberFocusAmount(focused = focused && indicator)
    Modifier
        .graphicsLayer {
            if (scale != FocusScale.NONE) {
                val growth = 1f + (scale.factor - 1f) * amount.value
                scaleX = growth
                scaleY = growth
            }
        }
        .clip(shape)
        .drawWithContent {
            drawContent()
            if (amount.value > 0.01f) {
                drawRect(Color.White.copy(alpha = FocusTokens.MediaLift * amount.value))
                drawFocusRing(shape, amount.value)
            }
        }
}

/** Static edge for imagery that must keep its artwork untouched. */
fun Modifier.glassEdge(shape: RoundedCornerShape, focused: Boolean = false): Modifier = composed {
    val amount = rememberFocusAmount(focused)
    Modifier
        .clip(shape)
        .drawWithContent {
            drawContent()
            drawRect(Glass.Hairline)
            drawFocusRing(shape, amount.value)
        }
}

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
