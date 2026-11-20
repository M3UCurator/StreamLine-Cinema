package com.streamline.cinema.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Colour tokens. Contrast is measured against [Ink] (the app background) with
 * the WCAG 2.1 relative-luminance formula; every text token clears its floor.
 *
 *  TextPrimary   18.2:1  body copy
 *  TextSecondary 13.7:1  supporting copy
 *  TextMuted      7.3:1  metadata, captions
 *  TextFaint      3.7:1  secondary-only: timestamps, hints, disabled context
 *  Focus ring    15.4:1  warm ring drawn by FocusTokens
 *
 * TextFaint is the one token that must never carry the only copy of a label or
 * an action. It exists for context beside a brighter sibling.
 */
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
    val TextPrimary = Color(0xFFF2F4F8)
    val TextSecondary = Color(0xFFCFD6E4)
    val TextMuted = Color(0xFF939CAF)
    val TextFaint = Color(0xFF626A7B)
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
 * Shared type scale — nine steps, one voice. Every screen pulls its hierarchy
 * from here so the wordmark, hero, rails, settings rows and player OSD cannot
 * drift apart per component:
 *
 *  1 46 HeroTitle       display / full-bleed hero
 *  2 34 DetailTitle     detail hero
 *  3 26 PageTitle       page heading
 *  4 19 SectionTitle    rail and section heading
 *  5 14 CardTitle       card and control label
 *  6 13 Body            reading copy
 *  7 12 BodySmall       dense secondary copy
 *  8 11 Pill / Kicker   pills, ratings, uppercase kickers
 *  9 10 Overline        uppercase field/section labels
 *
 * The wordmark lockup is a logotype, not a tenth scale step.
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

    /**
     * Every compact pill (info, rating, status) uses this voice. The tight
     * line height is deliberate: an unspecified one inherits bodyLarge's 24 sp
     * box, which inflates a capsule until it reads as a circle.
     */
    val Pill = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        lineHeight = 14.sp
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

/** Spacing, radii, strokes and component sizes, in one place. */
object CinemaTokens {
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 24.dp
    val SpaceXxl = 32.dp
    val Gutter = 48.dp

    val RadiusCard = 14.dp
    val RadiusControl = 12.dp
    val RadiusRow = 10.dp
    val RadiusPill = 999.dp

    val StrokeHairline = 1.dp
    val StrokeRing = 2.dp

    val SizeButton = 46.dp
    val SizeButtonCompact = 36.dp
    val SizeIcon = 44.dp
    val SizeIconLarge = 52.dp
    val SizeTransport = 66.dp
    val SizeTab = 40.dp
    val SizeRow = 48.dp
}

object CinemaDimens {
    /** Height of the floating top bar; screen content starts below it. */
    val TopBarHeight = 58.dp
    /** Scrim fade under the top bar, so scrolled content dissolves into the bar. */
    val TopBarScrim = 34.dp
    /** Corner language: one radius for media, one for controls, one for rows, one for pills. */
    val CardRadius = CinemaTokens.RadiusCard
    val ControlRadius = CinemaTokens.RadiusControl
    val RowRadius = CinemaTokens.RadiusRow
    val PillRadius = CinemaTokens.RadiusPill
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
