package com.streamline.cinema.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamline.cinema.data.PosterOrientation
import com.streamline.cinema.data.PosterSize

/** Card metrics derived from Settings so every poster surface rescales together. */
data class PosterStyle(
    val orientation: PosterOrientation = PosterOrientation.PORTRAIT,
    val size: PosterSize = PosterSize.MEDIUM
) {
    val isLandscape: Boolean get() = orientation == PosterOrientation.LANDSCAPE

    val aspectRatio: Float get() = if (isLandscape) 16f / 9f else 2f / 3f

    val cardWidth: Dp
        get() = if (isLandscape) {
            when (size) {
                PosterSize.SMALL -> 216.dp
                PosterSize.MEDIUM -> 272.dp
                PosterSize.LARGE -> 344.dp
            }
        } else {
            when (size) {
                PosterSize.SMALL -> 112.dp
                PosterSize.MEDIUM -> 142.dp
                PosterSize.LARGE -> 190.dp
            }
        }

    /** Minimum width for a responsive grid cell holding one card. */
    val gridCellWidth: Dp
        get() = if (isLandscape) {
            when (size) {
                PosterSize.SMALL -> 232.dp
                PosterSize.MEDIUM -> 288.dp
                PosterSize.LARGE -> 360.dp
            }
        } else {
            when (size) {
                PosterSize.SMALL -> 124.dp
                PosterSize.MEDIUM -> 160.dp
                PosterSize.LARGE -> 208.dp
            }
        }

    val continueWidth: Dp
        get() = when (size) {
            PosterSize.SMALL -> 210.dp
            PosterSize.MEDIUM -> 250.dp
            PosterSize.LARGE -> 310.dp
        }

    val titleSize: TextUnit
        get() = when (size) {
            PosterSize.SMALL -> 12.sp
            PosterSize.MEDIUM -> 14.sp
            PosterSize.LARGE -> 16.sp
        }

    val metaSize: TextUnit
        get() = when (size) {
            PosterSize.SMALL -> 10.sp
            PosterSize.MEDIUM -> 11.sp
            PosterSize.LARGE -> 12.sp
        }

    /** Keeps one- and two-line titles from jumping between rails and grids. */
    val titleLineHeight: TextUnit get() = titleSize * 1.25f

    /** Feature poster on detail/preview surfaces, scaled off the rail card. */
    val featureWidth: Dp
        get() = if (isLandscape) cardWidth else cardWidth * 1.25f

    /** 16:9 live-channel tile width, so Live and Playroom match. */
    val channelWidth: Dp
        get() = when (size) {
            PosterSize.SMALL -> 180.dp
            PosterSize.MEDIUM -> 220.dp
            PosterSize.LARGE -> 270.dp
        }
}

val LocalPosterStyle = compositionLocalOf { PosterStyle() }
