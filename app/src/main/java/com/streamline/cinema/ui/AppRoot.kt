package com.streamline.cinema.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.streamline.cinema.CinemaViewModel
import com.streamline.cinema.PlayerActivity
import com.streamline.cinema.data.CatalogItem
import com.streamline.cinema.data.CinemaProfile
import com.streamline.cinema.data.LiveChannel
import com.streamline.cinema.data.MediaType
import com.streamline.cinema.data.PlaybackOption
import com.streamline.cinema.data.UpdateRelease
import com.streamline.cinema.data.WatchProgress
import com.streamline.cinema.ui.screens.DetailScreen
import com.streamline.cinema.ui.screens.FavoritesScreen
import com.streamline.cinema.ui.screens.HomeScreen
import com.streamline.cinema.ui.screens.ImportScreen
import com.streamline.cinema.ui.screens.KidsCurationScreen
import com.streamline.cinema.ui.screens.LibraryScreen
import com.streamline.cinema.ui.screens.LiveScreen
import com.streamline.cinema.ui.screens.PinDialog
import com.streamline.cinema.ui.screens.PlayroomScreen
import com.streamline.cinema.ui.screens.ProfileGateScreen
import com.streamline.cinema.ui.screens.ProfilePinDialog
import com.streamline.cinema.ui.screens.SettingsScreen

@Composable
fun AppRoot(vm: CinemaViewModel) {
    val settings by vm.settings.collectAsState()
    val updateState by vm.update.collectAsState()
    val profile by vm.activeProfile.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    // Playroom and Import are hidden routes; back returns to the page that opened them.
    var hiddenReturnTab by rememberSaveable { mutableStateOf(AppTab.MOVIES) }
    var detail by remember { mutableStateOf<CatalogItem?>(null) }
    var lastBackAt by remember { mutableStateOf(0L) }
    var profileGateOpen by rememberSaveable { mutableStateOf(true) }
    var profileChosen by rememberSaveable { mutableStateOf(false) }
    var pendingProfile by remember { mutableStateOf<CinemaProfile?>(null) }
    // A restricted tab waiting on the parental PIN while a Kids profile is active.
    var pendingLockedTab by remember { mutableStateOf<AppTab?>(null) }
    var kidsCurationOpen by remember { mutableStateOf(false) }
    var settingsFocusTick by remember { mutableIntStateOf(0) }
    var pendingPlay by remember { mutableStateOf<PlayRequest?>(null) }
    var dismissedUpdateTag by rememberSaveable { mutableStateOf<String?>(null) }
    val focusMemory = remember { FocusMemory() }
    val screenState = remember { ScreenStateStore() }
    // Cancelling a PIN dialog must hand focus back to the bar control that opened it.
    var barRestoreTarget by remember { mutableStateOf<String?>(null) }
    var barRestoreTick by remember { mutableIntStateOf(0) }
    // Home owns the focus entry point; the top bar and a closing profile gate
    // both need to reach it.
    val homeEntryFocus = remember { FocusRequester() }
    var homeFocusTick by remember { mutableIntStateOf(0) }
    // The exiting detail page keeps its content during the close animation.
    val displayedDetail = remember { mutableStateOf<CatalogItem?>(null) }

    LaunchedEffect(detail) {
        if (detail != null) displayedDetail.value = detail
    }

    fun closeDetail() {
        detail = null
        focusMemory.requestRestore()
    }

    // The gate holds focus while it is open; removing it leaves nothing focused,
    // so Home has to claim focus again or the D-pad goes dead.
    fun closeProfileGate() {
        profileGateOpen = false
        profileChosen = true
        homeFocusTick++
    }

    fun launchPlayer(item: CatalogItem, season: Int?, episode: Int?, title: String?, startMs: Long?) {
        PlayerActivity.launch(
            context = context,
            item = item,
            season = season,
            episode = episode,
            episodeTitle = title,
            startPositionMs = startMs
        )
    }

    fun applyProfile(target: CinemaProfile) {
        vm.switchProfile(target.id)
        closeProfileGate()
        tab = AppTab.HOME
        detail = null
    }

    fun selectProfile(target: CinemaProfile) {
        val leavingKids = profile.isKids && target.id != profile.id
        if (leavingKids) {
            pendingProfile = target
            return
        }
        applyProfile(target)
    }

    // Search, imports and settings sit behind the parental PIN while a Kids
    // profile is active; every route funnels through here so none can slip by.
    fun openTab(target: AppTab) {
        val locked = vm.activeProfile.value.isKids &&
            (target == AppTab.SETTINGS || target == AppTab.PLAYROOM || target == AppTab.IMPORT)
        if (locked) pendingLockedTab = target else tab = target
    }

    fun openSearch() {
        if (tab != AppTab.PLAYROOM) hiddenReturnTab = tab
        openTab(AppTab.PLAYROOM)
    }

    fun resolveResume(request: PlayRequest, resume: Boolean) {
        launchPlayer(
            request.item,
            request.season,
            request.episode,
            request.title,
            if (resume) request.progress.positionMs else 0L
        )
        pendingPlay = null
    }

    BackHandler(enabled = profileGateOpen) {
        if (profileChosen) closeProfileGate()
    }
    BackHandler(enabled = detail != null) { closeDetail() }
    BackHandler(enabled = detail == null && tab in hiddenTabs) { tab = hiddenReturnTab }
    BackHandler(enabled = detail == null && tab !in hiddenTabs && tab != AppTab.HOME) {
        tab = AppTab.HOME
    }
    BackHandler(enabled = detail == null && tab == AppTab.HOME) {
        val now = System.currentTimeMillis()
        if (now - lastBackAt <= 2_000L) {
            (context as? Activity)?.finish()
        } else {
            lastBackAt = now
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    val openPlayer: (CatalogItem, Int?, Int?, String?) -> Unit = { item, season, episode, title ->
        val entry = vm.resumeEntryFor(item, season, episode)
        if (entry != null && entry.isResumable && entry.positionMs > 5_000) {
            pendingPlay = PlayRequest(item, season, episode, title, entry)
        } else {
            launchPlayer(item, season, episode, title, null)
        }
    }

    val launchLive: (PlaybackOption, String, List<LiveChannel>, Int) -> Unit =
        { option, title, channels, index ->
            PlayerActivity.launchUrl(
                context = context,
                url = option.url,
                title = title,
                headers = option.headers,
                channels = channels,
                channelIndex = index,
                drm = option.drm,
                meta = option.meta
            )
        }

    val uiSounds = remember(settings.uiSounds) { UiSounds(view, settings.uiSounds) }

    CompositionLocalProvider(
        LocalPosterStyle provides PosterStyle(
            orientation = settings.posterOrientation,
            size = settings.posterSize
        ),
        LocalFocusMemory provides focusMemory,
        LocalScreenState provides screenState,
        LocalUiSounds provides uiSounds
    ) {
        Box(Modifier.fillMaxSize().background(Cinema.Ink)) {
            AmbientGlassBackdrop()
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(tween(240)) + slideInHorizontally(tween(240)) { it / 28 }) togetherWith
                        fadeOut(tween(140))
                },
                label = "tabContent",
                modifier = Modifier.fillMaxSize()
            ) { activeTab ->
                val body: @Composable () -> Unit = {
                    when (activeTab) {
                        AppTab.HOME -> HomeScreen(
                            vm = vm,
                            onOpenDetail = { detail = it },
                            onPlay = openPlayer,
                            onNavigate = { openTab(it) },
                            onOpenProfiles = { profileGateOpen = true },
                            entryFocus = homeEntryFocus,
                            focusSignal = homeFocusTick,
                            focusReady = !profileGateOpen
                        )
                        AppTab.MOVIES -> LibraryScreen(
                            vm = vm,
                            type = MediaType.MOVIE,
                            onOpenDetail = { detail = it },
                            onImport = {
                                hiddenReturnTab = AppTab.MOVIES
                                openTab(AppTab.IMPORT)
                            }
                        )
                        AppTab.SHOWS -> LibraryScreen(
                            vm = vm,
                            type = MediaType.TV,
                            onOpenDetail = { detail = it },
                            onImport = {
                                hiddenReturnTab = AppTab.SHOWS
                                openTab(AppTab.IMPORT)
                            }
                        )
                        AppTab.LIVE -> LiveScreen(
                            vm = vm,
                            onPlay = launchLive,
                            onOpenSettings = { openTab(AppTab.SETTINGS) }
                        )
                        AppTab.FAVORITES -> FavoritesScreen(
                            vm = vm,
                            onOpenDetail = { detail = it }
                        )
                        AppTab.PLAYROOM -> PlayroomScreen(
                            vm = vm,
                            onOpenDetail = { detail = it },
                            onPlay = openPlayer,
                            onOpenSettings = { openTab(AppTab.SETTINGS) },
                            onPlayLive = launchLive
                        )
                        AppTab.IMPORT -> ImportScreen(
                            vm = vm,
                            onOpenPlayroom = {
                                // Back from the Playroom must return to Import, not to
                                // whatever hiddenReturnTab happened to remember.
                                hiddenReturnTab = AppTab.IMPORT
                                openTab(AppTab.PLAYROOM)
                            }
                        )
                        AppTab.SETTINGS -> SettingsScreen(
                            vm = vm,
                            onPreviewStream = { url, title ->
                                PlayerActivity.launchUrl(context, url, title)
                            },
                            onCurateKids = { kidsCurationOpen = true },
                            onKidsModeEnabled = {
                                homeFocusTick++
                                tab = AppTab.HOME
                            },
                            focusSignal = settingsFocusTick
                        )
                    }
                }
                // Home owns the full canvas so its hero runs edge to edge behind
                // the bar. The other tabs keep their content clear of the bar.
                if (activeTab == AppTab.HOME) {
                    body()
                } else {
                    Box(Modifier.fillMaxSize().padding(top = CinemaDimens.TopBarHeight)) {
                        body()
                    }
                }
            }

            TopTabBar(
                selected = tab,
                onSelect = { openTab(it) },
                profileName = profile.name,
                profileBadge = profile.initial,
                profileColor = profileColor(profile.colorIndex),
                onProfileClick = { profileGateOpen = true },
                onSearchClick = if (profile.isKids) null else ({ openSearch() }),
                downFocusRequester = if (tab == AppTab.HOME) homeEntryFocus else null,
                restoreTarget = barRestoreTarget,
                restoreTick = barRestoreTick,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            AnimatedVisibility(
                visible = detail != null,
                enter = fadeIn(tween(220)) + scaleIn(tween(240), initialScale = 0.985f),
                exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.99f)
            ) {
                displayedDetail.value?.let { item ->
                    Box(Modifier.fillMaxSize().background(Cinema.Ink)) {
                        DetailScreen(
                            vm = vm,
                            seed = item,
                            onBack = { closeDetail() },
                            onPlay = openPlayer,
                            onOpenRelated = { related ->
                                displayedDetail.value = related
                                detail = related
                            }
                        )
                    }
                }
            }

            if (profileGateOpen) {
                ProfileGateScreen(
                    vm = vm,
                    canDismiss = profileChosen,
                    onClose = { closeProfileGate() },
                    onPicked = { picked ->
                        if (picked.id == profile.id) {
                            closeProfileGate()
                        } else {
                            selectProfile(picked)
                        }
                    }
                )
            }

            if (kidsCurationOpen) {
                KidsCurationScreen(
                    vm = vm,
                    onClose = {
                        kidsCurationOpen = false
                        settingsFocusTick++
                    }
                )
            }
        }
    }

    pendingProfile?.let { target ->
        ProfilePinDialog(
            title = "Leave Kids profile",
            onDismiss = {
                pendingProfile = null
                barRestoreTarget = "profile"
                barRestoreTick++
            },
            onSubmit = { pin ->
                val ok = vm.kidsMode.verifyPin(pin)
                if (ok) {
                    pendingProfile = null
                    applyProfile(target)
                }
                ok
            }
        )
    }

    pendingLockedTab?.let { target ->
        val title = when (target) {
            AppTab.PLAYROOM -> "Search is locked"
            AppTab.IMPORT -> "Imports are locked"
            AppTab.SETTINGS -> "Settings are locked"
            else -> "Parental approval required"
        }
        PinDialog(
            title = title,
            message = "Kids Mode is on. Enter the parental PIN to continue.",
            onDismiss = {
                pendingLockedTab = null
                barRestoreTarget = if (target in primaryTabs) target.name else tab.name
                barRestoreTick++
            },
            onSubmit = { pin ->
                val ok = vm.kidsMode.verifyPin(pin)
                if (ok) {
                    pendingLockedTab = null
                    tab = target
                }
                ok
            }
        )
    }

    pendingPlay?.let { request ->
        ResumePromptDialog(
            request = request,
            onResume = { resolveResume(request, resume = true) },
            onStartOver = { resolveResume(request, resume = false) },
            onDismiss = {
                pendingPlay = null
                focusMemory.requestRestore()
            }
        )
    }

    val pendingUpdate = updateState.release
    LaunchedEffect(updateState.installStarted) {
        if (updateState.installStarted) dismissedUpdateTag = pendingUpdate?.tag
    }
    // Never cover the profile gate, a resume prompt or the kids curation flow;
    // the offer waits until the main UI is interactive again.
    if (pendingUpdate != null &&
        pendingUpdate.tag != dismissedUpdateTag &&
        !profileGateOpen &&
        pendingPlay == null &&
        !kidsCurationOpen
    ) {
        UpdatePromptDialog(
            release = pendingUpdate,
            downloading = updateState.downloading,
            progress = updateState.progress,
            error = updateState.error,
            onInstall = { vm.downloadAndInstallUpdate() },
            onLater = { dismissedUpdateTag = pendingUpdate.tag }
        )
    }
}

private data class PlayRequest(
    val item: CatalogItem,
    val season: Int?,
    val episode: Int?,
    val title: String?,
    val progress: WatchProgress
)

@Composable
private fun ResumePromptDialog(
    request: PlayRequest,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onDismiss: () -> Unit
) {
    val progress = request.progress
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        runCatching { primaryFocus.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .glassSurface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(CinemaDimens.CardRadius),
                    base = Glass.Sheet
                )
                .padding(26.dp)
        ) {
            Text(
                text = request.item.title,
                color = Cinema.TextPrimary,
                style = CinemaType.SectionTitle
            )
            Text(
                text = buildString {
                    append(progress.resumeLabel)
                    progress.episodeTitle?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                },
                color = Cinema.TextMuted,
                style = CinemaType.BodySmall
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .height(3.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.10f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.fraction)
                        .background(Cinema.Gold)
                        .height(3.dp)
                )
            }
            Text(
                text = "${formatPromptTime(progress.positionMs)} of ${formatPromptTime(progress.durationMs)} watched",
                color = Cinema.TextSecondary,
                style = CinemaType.BodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 20.dp)
            ) {
                TvButton(
                    label = "RESUME",
                    glyph = "▶",
                    style = TvButtonStyle.PRIMARY,
                    onClick = onResume,
                    focusRequester = primaryFocus
                )
                TvButton("START OVER", onStartOver, style = TvButtonStyle.SECONDARY)
                TvButton("CANCEL", onDismiss, style = TvButtonStyle.GHOST)
            }
        }
    }
}

private fun formatPromptTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun UpdatePromptDialog(
    release: UpdateRelease,
    downloading: Boolean,
    progress: Float,
    error: String?,
    onInstall: () -> Unit,
    onLater: () -> Unit
) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        runCatching { primaryFocus.requestFocus() }
    }
    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier
                .glassSurface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(CinemaDimens.CardRadius),
                    base = Glass.Sheet
                )
                .padding(26.dp)
        ) {
            Text(
                text = "Update available",
                color = Cinema.TextPrimary,
                style = CinemaType.SectionTitle
            )
            Text(
                text = "${release.name.ifBlank { release.tag }} · " +
                    formatUpdateSize(release.asset?.size ?: 0),
                color = Cinema.TextMuted,
                style = CinemaType.BodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "Syncs from the StreamLine-Cinema GitHub release. Settings, library, " +
                    "profiles and playlists are kept.",
                color = Cinema.TextSecondary,
                style = CinemaType.BodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
            if (downloading) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .height(3.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.10f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Cinema.Gold)
                    )
                }
                Text(
                    text = "${(progress * 100).toInt()}%",
                    color = Cinema.TextSecondary,
                    style = CinemaType.BodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            error?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    color = Cinema.Offline,
                    style = CinemaType.BodySmall,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 20.dp)
            ) {
                TvButton(
                    label = if (downloading) "DOWNLOADING…" else "INSTALL UPDATE",
                    glyph = "↓",
                    style = TvButtonStyle.PRIMARY,
                    enabled = !downloading,
                    onClick = onInstall,
                    focusRequester = primaryFocus
                )
                TvButton(
                    label = "LATER",
                    onClick = onLater,
                    style = TvButtonStyle.GHOST
                )
            }
        }
    }
}

private fun formatUpdateSize(bytes: Long): String {
    if (bytes <= 0) return "size unknown"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) "%.1f MB".format(mb) else "${bytes / 1024} KB"
}
