package com.streamline.cinema.ui.splash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.streamline.cinema.ui.Cinema
import kotlin.math.min

/**
 * Start screen drawn entirely in Compose: the brand tile wipes in, the play
 * glyph is revealed left to right, a glint crosses the tile, then the wordmark
 * rises under it. Timing lives in [SplashTimeline]; this file only renders a
 * sampled [SplashTimeline.Frame].
 */
@Composable
fun SplashScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val finish by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        val startedAt = withFrameNanos { it }
        var elapsed = 0L
        while (elapsed < SplashTimeline.TOTAL_MS) {
            withFrameNanos { now ->
                elapsed = (now - startedAt) / 1_000_000L
                elapsedMs = elapsed
            }
        }
        finish()
    }

    val frame = SplashTimeline.frame(elapsedMs)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Cinema.Ink),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val haloCenter = Offset(size.width / 2f, size.height * 0.44f)
            val haloRadius = min(size.width, size.height) * 0.62f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Cinema.Gold.copy(alpha = 0.13f * frame.backdrop),
                        Color.Transparent
                    ),
                    center = haloCenter,
                    radius = haloRadius
                ),
                radius = haloRadius,
                center = haloCenter
            )
            val seam = size.width * 0.32f * frame.backdrop
            val seamAlpha = Color.White.copy(alpha = 0.05f * frame.backdrop)
            drawLine(
                color = seamAlpha,
                start = Offset((size.width - seam) / 2f, haloCenter.y - haloRadius * 0.52f),
                end = Offset((size.width + seam) / 2f, haloCenter.y - haloRadius * 0.52f),
                strokeWidth = 1f
            )
            drawLine(
                color = seamAlpha,
                start = Offset((size.width - seam) / 2f, haloCenter.y + haloRadius * 0.52f),
                end = Offset((size.width + seam) / 2f, haloCenter.y + haloRadius * 0.52f),
                strokeWidth = 1f
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(y = (-14).dp)
        ) {
            SplashTile(frame)
            Spacer(Modifier.height(26.dp))
            Text(
                text = "STREAMLINE",
                color = Cinema.TextPrimary.copy(alpha = frame.wordmarkAlpha),
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 8.sp,
                modifier = Modifier.offset(y = (frame.wordmarkRise * 12f).dp)
            )
            Text(
                text = "CINEMA",
                color = Cinema.Gold.copy(alpha = frame.subtitleAlpha),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 13.sp,
                modifier = Modifier.offset(y = (frame.subtitleRise * 10f).dp)
            )
            Spacer(Modifier.height(22.dp))
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .height(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(220.dp * frame.rule)
                        .fillMaxHeight()
                        .background(Cinema.Gold.copy(alpha = 0.9f))
                )
            }
        }
    }
}

@Composable
private fun SplashTile(frame: SplashTimeline.Frame) {
    Canvas(
        Modifier
            .size(104.dp)
            .graphicsLayer {
                scaleX = frame.tileScale
                scaleY = frame.tileScale
                alpha = frame.tileAlpha
            }
    ) {
        val w = size.width
        val h = size.height
        val radius = CornerRadius(w * 0.2f)
        drawRoundRect(color = Cinema.Gold, cornerRadius = radius)
        clipPath(Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, w, h), radius)) }) {
            if (frame.glyph > 0f) {
                val glyphPath = Path().apply {
                    moveTo(w * 0.36f, h * 0.26f)
                    lineTo(w * 0.72f, h * 0.5f)
                    lineTo(w * 0.36f, h * 0.74f)
                    close()
                }
                // The play mark is wiped in from its left edge instead of fading.
                clipRect(right = w * lerp(0.36f, 0.72f, frame.glyph)) {
                    drawPath(glyphPath, Cinema.Ink)
                }
            }
            if (frame.sweep >= 0f) {
                val band = w * 0.7f
                val left = -band + (w + band * 2f) * frame.sweep
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.5f),
                            Color.Transparent
                        ),
                        startX = left,
                        endX = left + band
                    ),
                    topLeft = Offset.Zero,
                    size = Size(w, h)
                )
            }
        }
    }
}
