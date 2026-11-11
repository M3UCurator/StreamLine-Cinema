package com.streamline.cinema.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

internal enum class PlayerGlyph {
    BACK, PLAY, PAUSE, PREVIOUS, NEXT, REWIND, FORWARD,
    DISPLAY, SUBTITLES, DECODER, MORE,
    INFO, EXTERNAL, AUDIO, BOOST, EQ, SPEED, SOURCES, PIP, LOCK, FLIP, RELOAD
}

/** Vector glyphs drawn in code so the player adds no icon dependency to the APK. */
@Composable
internal fun PlayerGlyphIcon(
    glyph: PlayerGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp
) {
    when (glyph) {
        PlayerGlyph.REWIND, PlayerGlyph.FORWARD -> {
            Box(modifier.size(size), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(size)) { drawSkipArc(glyph == PlayerGlyph.REWIND, tint) }
                Text(
                    text = "10",
                    color = tint,
                    fontSize = (size.value * 0.32f).sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        else -> Canvas(modifier.size(size)) { drawGlyph(glyph, tint) }
    }
}

private fun DrawScope.drawGlyph(glyph: PlayerGlyph, tint: Color) {
    val w = size.width
    val h = size.height
    val minDim = w.coerceAtMost(h)
    val sw = (minDim * 0.095f).coerceAtLeast(2f)
    val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun at(x: Float, y: Float) = Offset(w * x, h * y)

    when (glyph) {
        PlayerGlyph.BACK -> {
            drawLine(tint, at(0.16f, 0.5f), at(0.84f, 0.5f), sw, StrokeCap.Round)
            drawLine(tint, at(0.16f, 0.5f), at(0.44f, 0.22f), sw, StrokeCap.Round)
            drawLine(tint, at(0.16f, 0.5f), at(0.44f, 0.78f), sw, StrokeCap.Round)
        }
        PlayerGlyph.PLAY -> {
            val path = Path().apply {
                moveTo(w * 0.3f, h * 0.16f)
                lineTo(w * 0.84f, h * 0.5f)
                lineTo(w * 0.3f, h * 0.84f)
                close()
            }
            drawPath(path, tint)
        }
        PlayerGlyph.PAUSE -> {
            drawRoundRect(tint, at(0.28f, 0.16f), Size(w * 0.16f, h * 0.68f), CornerRadius(sw * 0.6f))
            drawRoundRect(tint, at(0.56f, 0.16f), Size(w * 0.16f, h * 0.68f), CornerRadius(sw * 0.6f))
        }
        PlayerGlyph.PREVIOUS -> {
            drawLine(tint, at(0.2f, 0.18f), at(0.2f, 0.82f), sw, StrokeCap.Round)
            val path = Path().apply {
                moveTo(w * 0.82f, h * 0.18f)
                lineTo(w * 0.36f, h * 0.5f)
                lineTo(w * 0.82f, h * 0.82f)
                close()
            }
            drawPath(path, tint)
        }
        PlayerGlyph.NEXT -> {
            drawLine(tint, at(0.8f, 0.18f), at(0.8f, 0.82f), sw, StrokeCap.Round)
            val path = Path().apply {
                moveTo(w * 0.18f, h * 0.18f)
                lineTo(w * 0.64f, h * 0.5f)
                lineTo(w * 0.18f, h * 0.82f)
                close()
            }
            drawPath(path, tint)
        }
        PlayerGlyph.REWIND -> drawSkipArc(true, tint)
        PlayerGlyph.FORWARD -> drawSkipArc(false, tint)
        PlayerGlyph.DISPLAY -> {
            drawRoundRect(tint, at(0.1f, 0.22f), Size(w * 0.8f, h * 0.56f), CornerRadius(sw), style = stroke)
            drawLine(tint, at(0.3f, 0.5f), at(0.7f, 0.5f), sw, StrokeCap.Round)
            drawLine(tint, at(0.3f, 0.5f), at(0.42f, 0.38f), sw, StrokeCap.Round)
            drawLine(tint, at(0.3f, 0.5f), at(0.42f, 0.62f), sw, StrokeCap.Round)
            drawLine(tint, at(0.7f, 0.5f), at(0.58f, 0.38f), sw, StrokeCap.Round)
            drawLine(tint, at(0.7f, 0.5f), at(0.58f, 0.62f), sw, StrokeCap.Round)
        }
        PlayerGlyph.SUBTITLES -> {
            drawRoundRect(tint, at(0.1f, 0.24f), Size(w * 0.8f, h * 0.52f), CornerRadius(sw), style = stroke)
            drawLine(tint, at(0.26f, 0.58f), at(0.44f, 0.58f), sw * 0.85f, StrokeCap.Round)
            drawLine(tint, at(0.52f, 0.58f), at(0.7f, 0.58f), sw * 0.85f, StrokeCap.Round)
            drawLine(tint, at(0.26f, 0.7f), at(0.6f, 0.7f), sw * 0.85f, StrokeCap.Round)
        }
        PlayerGlyph.DECODER -> {
            drawRoundRect(tint, at(0.26f, 0.26f), Size(w * 0.48f, h * 0.48f), CornerRadius(sw * 0.8f), style = stroke)
            drawRoundRect(tint, at(0.38f, 0.38f), Size(w * 0.24f, h * 0.24f), CornerRadius(sw * 0.4f))
            listOf(0.36f, 0.5f, 0.64f).forEach { x ->
                drawLine(tint, at(x, 0.12f), at(x, 0.26f), sw * 0.75f, StrokeCap.Round)
                drawLine(tint, at(x, 0.74f), at(x, 0.88f), sw * 0.75f, StrokeCap.Round)
            }
            listOf(0.36f, 0.5f, 0.64f).forEach { y ->
                drawLine(tint, at(0.12f, y), at(0.26f, y), sw * 0.75f, StrokeCap.Round)
                drawLine(tint, at(0.74f, y), at(0.88f, y), sw * 0.75f, StrokeCap.Round)
            }
        }
        PlayerGlyph.MORE -> {
            drawCircle(tint, sw * 0.95f, at(0.5f, 0.24f))
            drawCircle(tint, sw * 0.95f, at(0.5f, 0.5f))
            drawCircle(tint, sw * 0.95f, at(0.5f, 0.76f))
        }
        PlayerGlyph.INFO -> {
            drawCircle(tint, minDim * 0.36f, at(0.5f, 0.5f), style = stroke)
            drawCircle(tint, sw * 0.7f, at(0.5f, 0.32f))
            drawLine(tint, at(0.5f, 0.45f), at(0.5f, 0.72f), sw, StrokeCap.Round)
        }
        PlayerGlyph.EXTERNAL -> {
            drawRoundRect(tint, at(0.12f, 0.24f), Size(w * 0.6f, h * 0.58f), CornerRadius(sw), style = stroke)
            drawLine(tint, at(0.52f, 0.48f), at(0.86f, 0.14f), sw, StrokeCap.Round)
            drawLine(tint, at(0.62f, 0.14f), at(0.86f, 0.14f), sw, StrokeCap.Round)
            drawLine(tint, at(0.86f, 0.38f), at(0.86f, 0.14f), sw, StrokeCap.Round)
        }
        PlayerGlyph.AUDIO -> {
            drawCircle(tint, sw * 1.4f, at(0.32f, 0.74f))
            drawLine(tint, at(0.46f, 0.74f), at(0.46f, 0.24f), sw, StrokeCap.Round)
            val flag = Path().apply {
                moveTo(w * 0.46f, h * 0.24f)
                quadraticTo(w * 0.64f, h * 0.26f, w * 0.7f, h * 0.45f)
            }
            drawPath(flag, tint, style = stroke)
        }
        PlayerGlyph.BOOST -> {
            val speaker = Path().apply {
                moveTo(w * 0.12f, h * 0.4f)
                lineTo(w * 0.28f, h * 0.4f)
                lineTo(w * 0.44f, h * 0.22f)
                lineTo(w * 0.44f, h * 0.78f)
                lineTo(w * 0.28f, h * 0.6f)
                lineTo(w * 0.12f, h * 0.6f)
                close()
            }
            drawPath(speaker, tint)
            drawArc(
                tint, -55f, 110f, false,
                topLeft = at(0.42f, 0.28f),
                size = Size(w * 0.36f, h * 0.44f),
                style = Stroke(sw * 0.8f, cap = StrokeCap.Round)
            )
        }
        PlayerGlyph.EQ -> {
            listOf(0.28f to 0.38f, 0.5f to 0.66f, 0.72f to 0.32f).forEach { (x, knob) ->
                drawLine(tint, at(x, 0.18f), at(x, 0.82f), sw * 0.8f, StrokeCap.Round)
                drawCircle(tint, sw * 1.5f, at(x, knob))
            }
        }
        PlayerGlyph.SPEED -> {
            drawArc(
                tint, 145f, 250f, false,
                topLeft = at(0.14f, 0.18f),
                size = Size(w * 0.72f, h * 0.72f),
                style = Stroke(sw, cap = StrokeCap.Round)
            )
            drawLine(tint, at(0.5f, 0.56f), at(0.74f, 0.34f), sw, StrokeCap.Round)
            drawCircle(tint, sw * 1.1f, at(0.5f, 0.56f))
        }
        PlayerGlyph.SOURCES -> {
            drawRoundRect(tint, at(0.28f, 0.16f), Size(w * 0.56f, h * 0.4f), CornerRadius(sw), style = stroke)
            drawRoundRect(tint, at(0.14f, 0.4f), Size(w * 0.56f, h * 0.4f), CornerRadius(sw), style = stroke)
        }
        PlayerGlyph.PIP -> {
            drawRoundRect(tint, at(0.1f, 0.2f), Size(w * 0.8f, h * 0.6f), CornerRadius(sw), style = stroke)
            drawRoundRect(tint, at(0.52f, 0.48f), Size(w * 0.28f, h * 0.22f), CornerRadius(sw * 0.4f))
        }
        PlayerGlyph.LOCK -> {
            drawArc(
                tint, 180f, 180f, false,
                topLeft = at(0.3f, 0.1f),
                size = Size(w * 0.4f, h * 0.42f),
                style = Stroke(sw, cap = StrokeCap.Round)
            )
            drawRoundRect(tint, at(0.22f, 0.42f), Size(w * 0.56f, h * 0.44f), CornerRadius(sw), style = stroke)
            drawCircle(tint, sw * 0.7f, at(0.5f, 0.62f))
        }
        PlayerGlyph.FLIP -> {
            drawLine(tint, at(0.5f, 0.12f), at(0.5f, 0.88f), sw * 0.7f, StrokeCap.Round)
            val filled = Path().apply {
                moveTo(w * 0.4f, h * 0.28f)
                lineTo(w * 0.14f, h * 0.5f)
                lineTo(w * 0.4f, h * 0.72f)
                close()
            }
            drawPath(filled, tint)
            val outline = Path().apply {
                moveTo(w * 0.6f, h * 0.28f)
                lineTo(w * 0.86f, h * 0.5f)
                lineTo(w * 0.6f, h * 0.72f)
                close()
            }
            drawPath(outline, tint, style = stroke)
        }
        PlayerGlyph.RELOAD -> {
            val inset = minDim * 0.16f
            val diameter = minDim - inset * 2f
            drawArc(
                tint, -60f, 300f, false,
                topLeft = Offset(inset, inset),
                size = Size(diameter, diameter),
                style = Stroke(sw, cap = StrokeCap.Round)
            )
            arrowHead(
                tint = tint,
                cx = inset + diameter / 2f,
                cy = inset + diameter / 2f,
                radius = diameter / 2f,
                angleDeg = -60f,
                sweepDir = -1f,
                stroke = sw
            )
        }
    }
}

private fun DrawScope.drawSkipArc(rewind: Boolean, tint: Color) {
    val s = size.minDimension
    val sw = (s * 0.085f).coerceAtLeast(1.8f)
    val inset = s * 0.16f
    val diameter = s - inset * 2f
    val cx = inset + diameter / 2f
    val cy = inset + diameter / 2f
    val start = if (rewind) 180f else 0f
    val sweep = if (rewind) -270f else 270f
    drawArc(
        tint, start, sweep, false,
        topLeft = Offset(inset, inset),
        size = Size(diameter, diameter),
        style = Stroke(sw, cap = StrokeCap.Round)
    )
    arrowHead(
        tint = tint,
        cx = cx,
        cy = cy,
        radius = diameter / 2f,
        angleDeg = start + sweep,
        sweepDir = if (sweep > 0) 1f else -1f,
        stroke = sw
    )
}

private fun DrawScope.arrowHead(
    tint: Color,
    cx: Float,
    cy: Float,
    radius: Float,
    angleDeg: Float,
    sweepDir: Float,
    stroke: Float
) {
    val radians = Math.toRadians(angleDeg.toDouble())
    val cosA = cos(radians).toFloat()
    val sinA = sin(radians).toFloat()
    val tip = Offset(cx + radius * cosA, cy + radius * sinA)
    val tangentX = -sinA * sweepDir
    val tangentY = cosA * sweepDir
    val length = stroke * 2.5f
    val width = stroke * 1.7f
    drawLine(
        tint,
        Offset(tip.x - tangentX * length + cosA * width, tip.y - tangentY * length + sinA * width),
        tip,
        stroke,
        StrokeCap.Round
    )
    drawLine(
        tint,
        Offset(tip.x - tangentX * length - cosA * width, tip.y - tangentY * length - sinA * width),
        tip,
        stroke,
        StrokeCap.Round
    )
}
