package com.streamline.cinema.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.streamline.cinema.data.MediaItem
import com.streamline.cinema.data.WatchProgress
import com.streamline.cinema.data.tmdbGenresMap

/**
 * Grid card that fills its cell, so the hall grid stays responsive at any column count.
 */
@Composable
fun MediaCard(
    media: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: WatchProgress? = null,
    focusRequester: FocusRequester? = null,
    backdropUrl: String? = null,
    focusSurface: String? = null
) {
    var focused by remember { mutableStateOf(false) }
    val focusMemory = LocalFocusMemory.current
    val sounds = LocalUiSounds.current
    val cardFocus = remember { FocusRequester() }
    val selfFocus = focusRequester ?: cardFocus
    val style = LocalPosterStyle.current
    // Reserve two title lines so the genre row stays level between cards.
    val titleBlock = with(LocalDensity.current) { (style.titleLineHeight * 2).toDp() }

    LaunchedEffect(focusSurface, focusMemory.restoreTick(focusSurface)) {
        if (focusSurface != null && focusMemory.recall(focusSurface) == media.id.toString()) {
            kotlinx.coroutines.delay(90)
            runCatching { selfFocus.requestFocus() }
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.045f else 1f,
        animationSpec = tween(130),
        label = "mediaCardScale"
    )
    val genreLabel = tmdbGenresMap[media.genreIds.firstOrNull()]

    Column(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (focused) 2f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(selfFocus)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
                if (it.isFocused && focusSurface != null) {
                    focusMemory.record(focusSurface, media.id.toString())
                }
            }
            .clickable {
                sounds.select()
                onClick()
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(style.aspectRatio)
                .glassFrame(
                    shape = RoundedCornerShape(CinemaDimens.CardRadius),
                    focused = focused
                )
        ) {
            PosterImage(
                url = if (style.isLandscape) backdropUrl ?: media.posterUrl else media.posterUrl,
                title = media.title,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.6f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.7f)
                        )
                    )
            )
            if (progress != null && progress.isResumable) {
                ResumeBadge(
                    label = progress.resumeLabel,
                    compact = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
            if (media.voteAverage > 0.0) {
                Text(
                    text = "★ %.1f".format(media.voteAverage),
                    color = Cinema.Gold,
                    fontSize = style.metaSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .glassSurface(
                            shape = RoundedCornerShape(CinemaDimens.PillRadius),
                            base = Color.Black.copy(alpha = 0.42f),
                            sheen = 0.5f
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
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
                        Modifier
                            .fillMaxWidth(progress.fraction)
                            .height(4.dp)
                            .background(Cinema.Gold)
                    )
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = media.title,
            color = if (focused) Cinema.Gold else Cinema.TextPrimary,
            fontSize = style.titleSize,
            fontWeight = FontWeight.SemiBold,
            lineHeight = style.titleLineHeight,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(titleBlock)
        )
        if (genreLabel != null) {
            Text(
                text = genreLabel,
                color = Cinema.TextMuted,
                fontSize = style.metaSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}
