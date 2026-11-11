package com.streamline.cinema.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Cinema {
    val Ink = Color(0xFF07080D)
    val InkSoft = Color(0xFF0C0E15)
    val Surface = Color(0xFF11141C)
    val Panel = Color(0xFF181C27)
    val PanelRaised = Color(0xFF222736)
    val Gold = Color(0xFFF4B942)
    val GoldDim = Color(0xFFB98B2C)
    val Crimson = Color(0xFFE94560)
    val Tmdb = Color(0xFF01B4E4)
    val TextPrimary = Color(0xFFF5F6FA)
    val TextSecondary = Color(0xFF9AA3B7)
    val TextMuted = Color(0xFF6C7487)
    val Border = Color(0xFF2A3040)
    val Online = Color(0xFF54C98A)
    val Offline = Color(0xFFE5484D)
    val Pinging = Color(0xFFF5C451)
    val Unchecked = Color(0xFF6E7689)

    val LabelStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp
    )
}

/**
 * Shared type scale. Every screen pulls its hierarchy from here so the wordmark,
 * hero and rails keep the same voice instead of drifting per component.
 */
object CinemaType {
    val Wordmark = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 2.4.sp
    )
    val WordmarkSub = TextStyle(
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 4.sp
    )
    val Kicker = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.2.sp
    )
    val HeroTitle = TextStyle(
        fontSize = 46.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.6).sp,
        lineHeight = 48.sp
    )
    val HeroSynopsis = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp
    )
    val SectionTitle = TextStyle(
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.3.sp
    )
    val SectionSubtitle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp
    )
    val CardTitle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp
    )
    val Meta = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp
    )
    val Caption = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp
    )

    /** One page heading for every tab, so no screen drifts a few points off. */
    val PageTitle = TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.2.sp,
        lineHeight = 31.sp
    )

    /** Detail and preview hero title. Two lines max, ellipsised. */
    val DetailTitle = TextStyle(
        fontSize = 34.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.3).sp,
        lineHeight = 38.sp
    )

    val Body = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp
    )

    val BodySmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp
    )

    /** Uppercase field/section label, shared by Settings, forms and rails. */
    val Overline = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp
    )

    /** Every compact pill (info, rating, status) uses this voice. */
    val Pill = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp
    )

    val Button = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp
    )

    val ButtonCompact = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp
    )
}

object CinemaDimens {
    /** Height of the floating top bar; screen content starts below it. */
    val TopBarHeight = 58.dp
    /** Scrim fade under the top bar, so scrolled content dissolves into the bar. */
    val TopBarScrim = 34.dp
    /** Corner language: one radius for media, one for controls, one for pills. */
    val CardRadius = 14.dp
    val ControlRadius = 12.dp
    val PillRadius = 999.dp
    val ScreenGutter = 48.dp
}


@Composable
fun CinemaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cinema.Gold,
            onPrimary = Cinema.Ink,
            secondary = Cinema.Crimson,
            onSecondary = Color.White,
            background = Cinema.Ink,
            onBackground = Cinema.TextPrimary,
            surface = Cinema.Surface,
            onSurface = Cinema.TextPrimary,
            surfaceVariant = Cinema.Panel,
            onSurfaceVariant = Cinema.TextSecondary,
            outline = Cinema.Border
        ),
        content = content
    )
}
