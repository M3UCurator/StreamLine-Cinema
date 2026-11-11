package com.streamline.cinema.ui

import android.view.SoundEffectConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * UI sound layer. Uses the platform key effects so it follows the TV's own
 * sound-effects volume and the user's "touch sounds" preference instead of
 * overriding either.
 */
class UiSounds(private val view: View?, private val enabled: Boolean) {
    private fun play(effect: Int) {
        if (enabled) view?.playSoundEffect(effect)
    }

    fun focus() = play(SoundEffectConstants.NAVIGATION_DOWN)
    fun select() = play(SoundEffectConstants.CLICK)
    fun back() = play(SoundEffectConstants.NAVIGATION_UP)
}

val LocalUiSounds = staticCompositionLocalOf { UiSounds(null, false) }

/** Wordmark used by the top bar and the profile gate so the brand never drifts. */
@Composable
fun BrandMark(
    subtitle: String = "CINEMA",
    modifier: Modifier = Modifier,
    tileSize: Dp = 34.dp,
    wordmarkStyle: TextStyle = CinemaType.Wordmark,
    subtitleStyle: TextStyle = CinemaType.WordmarkSub
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(tileSize)
                .clip(RoundedCornerShape(tileSize * 0.2f))
                .background(Cinema.Gold),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "▶",
                color = Cinema.Ink,
                fontSize = (tileSize.value * 0.44f).sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.Center) {
            Text("STREAMLINE", color = Cinema.TextPrimary, style = wordmarkStyle)
            Text(subtitle, color = Cinema.Gold, style = subtitleStyle)
        }
    }
}
