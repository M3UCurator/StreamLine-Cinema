package com.streamline.cinema.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamline.cinema.data.ExternalPlayerInfo
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.LocalUiSounds
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.glassSurface
import kotlinx.coroutines.delay

/** One selectable row of the hand-off list; a null package is the chooser. */
internal data class OpenWithTarget(
    val packageName: String?,
    val name: String,
    val detail: String,
    val badge: String,
    val monogram: String,
    val accent: Color
)

/** The chooser first, then every detected player in the repository's order. */
internal fun openWithTargets(players: List<ExternalPlayerInfo>): List<OpenWithTarget> {
    val chooser = OpenWithTarget(
        packageName = null,
        name = "Android system program chooser",
        detail = "Every installed app that can open this stream",
        badge = "ACTION_VIEW",
        monogram = "AND",
        accent = Cinema.Gold
    )
    return listOf(chooser) + players.map(::playerTarget)
}

private fun playerTarget(info: ExternalPlayerInfo): OpenWithTarget = when (info.packageName) {
    "com.brouken.player" -> OpenWithTarget(
        info.packageName, "JustPlayer", "Pure ExoPlayer v2 / Media3", info.packageName,
        "JUST", Cinema.Tmdb
    )
    "org.videolan.vlc" -> OpenWithTarget(
        info.packageName, "VLC", "LibVLC hardware decoder", info.packageName,
        "VLC", Cinema.Gold
    )
    "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro" -> OpenWithTarget(
        info.packageName, "MX Player", "HW+ codec engine", info.packageName,
        "MX", Cinema.Crimson
    )
    "is.xyz.mpv" -> OpenWithTarget(
        info.packageName, "mpv", "GPU-accelerated libmpv", info.packageName,
        "MPV", Cinema.Online
    )
    else -> OpenWithTarget(
        info.packageName,
        info.label.ifBlank { info.packageName },
        "Installed video player",
        info.packageName,
        info.label.filter { it.isLetterOrDigit() }.uppercase().take(4).ifEmpty { "APP" },
        Cinema.TextSecondary
    )
}

/**
 * Hand-off window. It sits over the paused in-app player: picking a row hands
 * the stream to that app while the local player keeps its position, and
 * **Done (Back to Player)** drops back into the app player untouched.
 */
@Composable
internal fun OpenWithDialog(
    title: String,
    streamUrl: String,
    players: List<ExternalPlayerInfo>,
    error: String?,
    onDismiss: () -> Unit,
    onLaunch: (String?) -> Unit
) {
    val context = LocalContext.current
    val targets = remember(players) { openWithTargets(players) }
    val firstFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    var copied by remember { mutableStateOf(false) }
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    LaunchedEffect(targets) {
        delay(140)
        runCatching { firstFocus.requestFocus() }
    }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_600)
        copied = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(0.86f)
                    .heightIn(max = maxHeight)
                    .glassSurface(
                        shape = RoundedCornerShape(CinemaDimens.CardRadius),
                        base = Glass.Sheet
                    )
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "OPEN WITH ANDROID PLAYER",
                            color = Cinema.Gold,
                            style = CinemaType.Kicker
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            buildAnnotatedString {
                                append("Launch ")
                                withStyle(
                                    SpanStyle(
                                        color = Cinema.TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                ) {
                                    append("\u201C")
                                    append(title)
                                    append("\u201D")
                                }
                                append(" directly into your system-installed hardware video player.")
                            },
                            color = Cinema.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    TvButton(
                        label = "\u2715",
                        style = TvButtonStyle.GHOST,
                        compact = true,
                        onClick = onDismiss
                    )
                }

                Spacer(Modifier.height(2.dp))
                // The row list scrolls on short screens; the URL field and the
                // switch-back action stay pinned so DONE is never off-screen.
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    targets.forEachIndexed { index, target ->
                        OpenWithRow(
                            target = target,
                            highlighted = index == 0,
                            focusRequester = if (index == 0) firstFocus else null,
                            onClick = { onLaunch(target.packageName) }
                        )
                    }

                    if (error != null) {
                        Text(error, color = Cinema.Offline, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "RAW STREAM URL (FOR CUSTOM PLAYERS)",
                    color = Cinema.TextMuted,
                    style = CinemaType.Overline
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glassSurface(
                            shape = RoundedCornerShape(CinemaDimens.ControlRadius),
                            base = Color.Black.copy(alpha = 0.42f)
                        )
                        .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        streamUrl,
                        color = Cinema.TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TvButton(
                        label = if (copied) "COPIED" else "COPY",
                        style = if (copied) TvButtonStyle.PRIMARY else TvButtonStyle.SECONDARY,
                        compact = true,
                        onClick = {
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("Stream URL", streamUrl)
                            )
                            copied = true
                        }
                    )
                }

                Spacer(Modifier.height(6.dp))
                TvButton(
                    label = "DONE (BACK TO PLAYER)",
                    style = TvButtonStyle.SECONDARY,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismiss,
                    focusRequester = doneFocus
                )
            }
        }
    }
}

@Composable
private fun OpenWithRow(
    target: OpenWithTarget,
    highlighted: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    val sounds = LocalUiSounds.current
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .glassSurface(
                shape = RoundedCornerShape(CinemaDimens.ControlRadius),
                focused = focused,
                base = if (highlighted) Glass.AccentWash else Glass.Control,
                tint = if (focused) Glass.AccentWash else Color.Transparent,
                sheen = 0.8f
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) sounds.focus()
            }
            .clickable(interactionSource = interaction, indication = null) {
                sounds.select()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(target.accent.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                target.monogram,
                color = target.accent,
                fontSize = if (target.monogram.length >= 4) 10.sp else 13.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.4.sp
            )
        }
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    target.name,
                    color = Cinema.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (highlighted) {
                    Text(
                        "DEFAULT",
                        color = Cinema.Gold,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
            Text(
                target.detail,
                color = Cinema.TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            target.badge,
            color = Cinema.TextMuted,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp)
        )
    }
}
