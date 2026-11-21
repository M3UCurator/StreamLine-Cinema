package com.streamline.cinema.ui.player

import android.app.Activity
import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.streamline.cinema.StreamLineApp
import com.streamline.cinema.data.AddonSubtitle
import com.streamline.cinema.data.BandwidthCap
import com.streamline.cinema.data.BrowserMask
import com.streamline.cinema.data.BufferTier
import com.streamline.cinema.data.CatalogItem
import com.streamline.cinema.data.CinemaProfile
import com.streamline.cinema.data.DecoderType
import com.streamline.cinema.data.DeviceProfile
import com.streamline.cinema.data.DrmConfig
import com.streamline.cinema.data.EpgGuide
import com.streamline.cinema.data.ExternalIntents
import com.streamline.cinema.data.ExternalPlayerInfo
import com.streamline.cinema.data.Http
import com.streamline.cinema.data.LiveChannel
import com.streamline.cinema.data.NativeStreamProxy
import com.streamline.cinema.data.PlaybackEngine
import com.streamline.cinema.data.PlaybackLocks
import com.streamline.cinema.data.PlaybackOption
import com.streamline.cinema.data.PlaybackPlan
import com.streamline.cinema.data.PlayerUaPreset
import com.streamline.cinema.data.SkipKind
import com.streamline.cinema.data.SkipSegments
import com.streamline.cinema.data.SourceResolution
import com.streamline.cinema.data.StreamBadgeCatalog
import com.streamline.cinema.data.StreamKind
import com.streamline.cinema.data.StreamMeta
import com.streamline.cinema.data.StreamSearchEvent
import com.streamline.cinema.data.StreamSearchPhase
import com.streamline.cinema.data.StreamSearchStep
import com.streamline.cinema.data.StreamUrls
import com.streamline.cinema.data.StremioAddons
import com.streamline.cinema.data.TransportVerdict
import com.streamline.cinema.data.WatchProgress
import com.streamline.cinema.data.await
import com.streamline.cinema.data.rankSources
import com.streamline.cinema.ui.Cinema
import com.streamline.cinema.ui.CinemaDimens
import com.streamline.cinema.ui.CinemaType
import com.streamline.cinema.ui.Chip
import com.streamline.cinema.ui.FocusScale
import com.streamline.cinema.ui.Glass
import com.streamline.cinema.ui.PosterImage
import com.streamline.cinema.ui.TvButton
import com.streamline.cinema.ui.TvButtonStyle
import com.streamline.cinema.ui.glassSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private enum class AspectMode(
    val label: String,
    val resizeMode: Int,
    val ratio: Float? = null
) {
    ORIGINAL("Original", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    R16_9("16:9", AspectRatioFrameLayout.RESIZE_MODE_FILL, 16f / 9f),
    R18_9("18:9", AspectRatioFrameLayout.RESIZE_MODE_FILL, 18f / 9f),
    STRETCH("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    CROP("Crop", AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
}

private enum class OsdPanel { AUDIO, SUBTITLES, OVERFLOW, TOOLS, TOGGLES, QUALITY, TRANSPORT, INFO, STATS }

private enum class OsdFocus { PLAY, TIMELINE, SKIP, BACK }

// Live edge policy for HLS. Media3's default target offset is three target
// durations clamped to the window, which on a six-second window (2 s segments)
// pins the playhead to the oldest edge with no slack. Three seconds puts it in
// the middle of such a window, and the speed range lets the player walk back to
// the target after a hitch instead of falling out of the window.
private val LIVE_ITEM_CONFIGURATION = MediaItem.LiveConfiguration.Builder()
    .setTargetOffsetMs(3_000)
    .setMinOffsetMs(2_000)
    .setMaxOffsetMs(6_000)
    .setMinPlaybackSpeed(0.97f)
    .setMaxPlaybackSpeed(1.03f)
    .build()
// Dropped-frame fallback: when the decoder cannot keep up with the selected
// rendition, cap the video height instead of letting the picture stutter.
private const val DROPPED_FRAME_WINDOW_MS = 15_000L
private const val DROPPED_FRAME_MIN_PLAY_MS = 10_000L
private const val DROPPED_FRAME_PERCENT = 12f
private const val DROPPED_FRAME_FALLBACK_HEIGHT = 720
private const val FOCUS_ATTEMPTS = 4
private const val FOCUS_RETRY_MS = 90L

private const val RECOVERY_COOLDOWN_MS = 20_000L
// Ignore a resume that lands inside the launch's own settle window, when the
// activity is bouncing through PAUSE/START around the target app.
private const val HANDOFF_SETTLE_MS = 700L
// A hand-off that never covers this screen is treated as failed after this long.
private const val HANDOFF_TIMEOUT_MS = 6_000L
// A credits-time next-up countdown aims at the real end, stopping this many
// seconds short so the hand-off lands before the final frame.
private const val CREDITS_END_MARGIN_S = 2
// While the finger is on the seek bar the picture is re-seeked at this pace so
// the user can see the frame they are choosing without flooding the loader.
private const val SCRUB_PREVIEW_INTERVAL_MS = 250L
// A direct route that has played this long is considered proof the origin
// serves the player as-is; AUTO remembers it and skips the proxy on the next
// tune for that origin.
private const val TRANSPORT_LEARN_MS = 60_000L
// A live stream that has played this long clears a dead-channel mark.
private const val CHANNEL_HEALTH_SUCCESS_MS = 20_000L
// A D-pad sweep through the buffer rows saves several values back-to-back;
// rebuilding Media3 per step would re-initialise the codec each time.
private const val BUFFER_SETTLE_MS = 400L

// The three settings the load control is built from. They reach the player
// only once the selection has settled, so one pick costs one rebuild.
private data class BufferSelection(
    val tier: BufferTier,
    val lowLatency: Boolean,
    val highCapacity: Boolean
)

private data class TrackChoice(
    val group: TrackGroup,
    val index: Int,
    val label: String,
    val selected: Boolean,
    val language: String = ""
)

/**
 * A viewer's explicit track pick, kept independent of the player instance.
 * [TrackChoice] holds a [TrackGroup] from one player's snapshot; a rebuild
 * parses fresh groups, so the pick is re-resolved by its label instead.
 */
private data class SavedTrackPick(
    val label: String,
    val language: String
)

private val hardwareFirstSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
    val infos = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
    // Enforce hardware acceleration for H.264/HEVC/AV1 whenever the device has
    // a hardware decoder; software-only codecs are kept only for formats with
    // no hardware implementation at all (AV1 on older boxes).
    infos.filter { it.hardwareAccelerated }.ifEmpty { infos }
}

@Composable
fun PlayerScreen(
    item: CatalogItem?,
    season: Int?,
    episode: Int?,
    episodeTitle: String?,
    directUrl: String?,
    directHeaders: Map<String, String> = emptyMap(),
    directDrm: DrmConfig? = null,
    directMeta: StreamMeta = StreamMeta(),
    startPositionOverrideMs: Long? = null,
    fallbackTitle: String,
    channels: List<LiveChannel> = emptyList(),
    channelIndex: Int = -1,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val app = context.applicationContext as? StreamLineApp
    val repository = app?.container?.repository
    val settingsStore = app?.container?.settingsStore
    val settings by (settingsStore?.settings
        ?: error("StreamLine container unavailable")).collectAsState()
    val badgeCatalog by (app?.container?.badges?.catalog ?: emptyFlow())
        .collectAsState(StreamBadgeCatalog.EMPTY)
    // The player only ever runs inside the active profile's session, so the
    // language defaults are read once rather than observed.
    val activeProfile = app?.container?.profileStore?.active?.value ?: CinemaProfile.default()

    // A series launched without episode context starts at its first real slot.
    // Blindly falling back to S1E1 is what made the player search (and play) an
    // episode the viewer was not on; with no slot at all, 0/0 means "not chosen".
    val firstEpisodeSlot = remember(item?.key) {
        item?.takeIf { it.isSeries }?.let(::buildEpisodeSlots)?.firstOrNull()
    }
    val firstSeasonNumber = remember(item?.key) {
        item?.takeIf { it.isSeries }?.seasons
            ?.sortedBy { it.number }
            ?.firstOrNull { it.number > 0 }
            ?.number
    }
    var activeSeason by remember(item?.key) {
        mutableIntStateOf(
            season ?: firstEpisodeSlot?.season ?: firstSeasonNumber ?: 0
        )
    }
    var activeEpisode by remember(item?.key) {
        mutableIntStateOf(episode ?: firstEpisodeSlot?.number ?: 0)
    }
    var activeEpisodeTitle by remember(item?.key) {
        // Name the episode we actually start on, never a different slot's title.
        mutableStateOf(
            episodeTitle ?: item?.takeIf { it.isSeries }?.seasons
                ?.firstOrNull { it.number == activeSeason }
                ?.episodes
                ?.firstOrNull { it.number == activeEpisode }
                ?.title
        )
    }

    val plan: PlaybackPlan? = remember(item?.key, activeSeason, activeEpisode, activeEpisodeTitle, directUrl) {
        if (directUrl != null || item == null || repository == null) null
        else repository.playbackPlan(item, activeSeason, activeEpisode, activeEpisodeTitle)
    }
    val isLive = channels.isNotEmpty()
    // Only searched VOD streams can be picked: a live tune and a direct URL
    // already carry their single source.
    val manualSourcePick =
        !settings.autoPlaySearchedStream && !isLive && item != null && directUrl == null
    var liveIndex by remember(channels) {
        mutableIntStateOf(channelIndex.takeIf { it in channels.indices } ?: 0)
    }
    // The Live screen already tuned the first channel before opening the player.
    var channelOption by remember(channels, directDrm) {
        mutableStateOf(
            if (isLive && directUrl != null) {
                PlaybackOption(
                    name = channels.getOrNull(liveIndex)?.name ?: fallbackTitle,
                    url = directUrl,
                    headers = directHeaders,
                    drm = directDrm,
                    meta = directMeta
                )
            } else {
                null
            }
        )
    }
    var tunedIndex by remember(channels) {
        mutableIntStateOf(if (isLive && directUrl != null) liveIndex else -1)
    }
    var channelResolving by remember { mutableStateOf(false) }
    val baseOptions: List<PlaybackOption> = remember(
        plan,
        directUrl,
        directHeaders,
        directDrm,
        directMeta,
        channelOption,
        isLive
    ) {
        when {
            isLive -> listOfNotNull(channelOption)
            directUrl != null ->
                listOf(
                    PlaybackOption(
                        name = "Direct stream",
                        url = directUrl,
                        headers = directHeaders,
                        drm = directDrm,
                        meta = directMeta
                    )
                )
            else -> plan?.options.orEmpty()
        }
    }
    var addonOptions by remember(item?.key, activeSeason, activeEpisode, directUrl) {
        mutableStateOf<List<PlaybackOption>>(emptyList())
    }
    var searchSteps by remember(item?.key, activeSeason, activeEpisode, directUrl) {
        mutableStateOf<List<StreamSearchStep>>(emptyList())
    }
    var searchInProgress by remember(item?.key, activeSeason, activeEpisode, directUrl) {
        mutableStateOf(false)
    }
    var searchCompleted by remember(item?.key, activeSeason, activeEpisode, directUrl) {
        mutableStateOf(false)
    }
    val addonsEnabled = settings.addons.any { it.enabled }
    LaunchedEffect(item?.key, activeSeason, activeEpisode, directUrl, addonsEnabled) {
        val target = item
        val repo = repository
        if (directUrl != null || target == null || repo == null) {
            addonOptions = emptyList()
            searchSteps = emptyList()
            searchInProgress = false
            searchCompleted = true
            return@LaunchedEffect
        }
        // A full search: the overlay must not declare "No playable streams"
        // until the catalog and every enabled addon have answered.
        addonOptions = emptyList()
        searchSteps = emptyList()
        searchInProgress = true
        searchCompleted = false
        val ordered = LinkedHashMap<String, StreamSearchStep>()
        try {
            repo.searchPlayableStreams(target, activeSeason, activeEpisode).collect { event ->
                when (event) {
                    is StreamSearchEvent.Step -> {
                        ordered[event.step.key] = event.step
                        searchSteps = ordered.values.toList()
                    }
                    is StreamSearchEvent.Finished -> {
                        event.steps.forEach { ordered[it.key] = it }
                        searchSteps = ordered.values.toList()
                        addonOptions = event.options
                        searchInProgress = false
                        searchCompleted = true
                    }
                }
            }
        } finally {
            searchInProgress = false
        }
    }
    // Addon streams are appended so the catalog source keeps index 0 and the
    // selected option does not jump when the addon replies arrive late. Smart
    // ordering only shuffles addon sources, never the catalog stream.
    val preferredSourceLanguage =
        if (settings.preferLanguageSources) settings.contentLanguage else ""
    val offeredOptions: List<PlaybackOption> = remember(
        addonOptions, baseOptions, preferredSourceLanguage
    ) {
        baseOptions + rankSources(addonOptions, preferredSourceLanguage)
    }
    val options: List<PlaybackOption> = remember(
        offeredOptions, settings.qualityLimit, settings.sourceSizeLimit
    ) {
        excludeOversizedSources(
            excludeHighQualitySources(offeredOptions, settings.qualityLimit),
            settings.sourceSizeLimit
        )
    }
    val sourcesHiddenByLimits = offeredOptions.size - options.size

    var optionIndex by remember { mutableIntStateOf(0) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }
    // In manual mode the player holds no source until the search ends and the
    // viewer picks one, so nothing starts underneath the picker.
    var awaitingPick by remember(item?.key, directUrl, activeSeason, activeEpisode) {
        mutableStateOf(manualSourcePick)
    }
    LaunchedEffect(manualSourcePick, awaitingPick, searchInProgress, searchCompleted, options) {
        if (!manualSourcePick || !awaitingPick) return@LaunchedEffect
        if (searchInProgress || !searchCompleted || options.isEmpty()) return@LaunchedEffect
        showPicker = true
    }
    var externalMessage by remember { mutableStateOf<String?>(null) }
    var autoHandoff by remember { mutableStateOf(false) }
    // Hand-off window. The local player is paused, never torn down, while this
    // is open, and a launched app never closes it either: returning from that
    // app resumes the same instance. The window only tracks whether a target
    // has been picked so the OSD can say where the stream went.
    var openWithOpen by remember { mutableStateOf(false) }
    var openWithPlayers by remember { mutableStateOf<List<ExternalPlayerInfo>>(emptyList()) }
    var openWithError by remember { mutableStateOf<String?>(null) }
    var openWithTarget by remember { mutableStateOf<PlaybackOption?>(null) }
    var handoffLabel by remember { mutableStateOf<String?>(null) }
    var handoffAtMs by remember { mutableLongStateOf(0L) }
    var resumeAfterOpenWith by remember { mutableStateOf(false) }

    var osdVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<OsdPanel?>(null) }
    var osdFocusTarget by remember { mutableStateOf(OsdFocus.PLAY) }
    var timelineFocused by remember { mutableStateOf(false) }
    var skipFocused by remember { mutableStateOf(false) }
    var backFocused by remember { mutableStateOf(false) }
    var dockFocused by remember { mutableStateOf(false) }

    fun hideOsd() {
        osdVisible = false
        dockFocused = false
        timelineFocused = false
        skipFocused = false
        backFocused = false
    }

    var decoderMode by remember { mutableStateOf(settings.decoderType) }
    var aspectMode by remember { mutableStateOf(AspectMode.FIT) }
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    // Media3 reports position 0 once a fatal error drops the player to IDLE, so
    // the playhead is mirrored while it runs; failover, reloads and player
    // rebuilds resume from here instead of restarting the title.
    var lastKnownPositionMs by remember(item?.key, directUrl, activeSeason, activeEpisode) {
        mutableLongStateOf(0L)
    }
    // Resume / start-over choice made in the app shell. It wins over the plan's
    // own resume point and is consumed once so reloads do not jump again.
    var startOverrideMs by remember(item?.key, directUrl) { mutableStateOf(startPositionOverrideMs) }
    var startOverrideApplied by remember(item?.key, directUrl) { mutableStateOf(false) }

    var brightness by remember { mutableFloatStateOf(1f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var volumePercent by remember { mutableIntStateOf(100) }
    var nightMode by remember { mutableStateOf(false) }
    var eqPreset by remember { mutableIntStateOf(0) }
    // Ten fixed bands in the 0–63 preset scale. Loading a preset rewrites the
    // list; nudging a band marks the curve custom.
    var eqLevels by remember { mutableStateOf(presetLevels(WinampEqPreset.AllPresets.first())) }
    var eqCustom by remember { mutableStateOf(false) }
    // The audio sink reads these snapshots on every buffer, so tone and boost
    // edits never re-create an effect, a player, or an audio session.
    val eqState = remember { EqState() }
    val gainState = remember { GainState() }
    // Captions start off for every title: Media3 otherwise auto-selects the
    // first embedded text track and burns it over the opening frame. The
    // profile's subtitle language only biases which track a later caption
    // switch picks (see preferredSubtitleLanguage).
    var dropSubtitles by remember { mutableStateOf(true) }
    var gesturesEnabled by remember { mutableStateOf(true) }
    var subtitleScale by remember { mutableFloatStateOf(settings.subtitleScale) }
    var subtitleBackground by remember { mutableIntStateOf(settings.subtitleBackdrop) }

    fun setSubtitleScale(value: Float) {
        subtitleScale = value
        settingsStore?.update { it.copy(subtitleScale = value) }
    }

    fun setSubtitleBackdrop(value: Int) {
        subtitleBackground = value
        settingsStore?.update { it.copy(subtitleBackdrop = value) }
    }

    var externalSubtitleUri by remember { mutableStateOf<Uri?>(null) }
    var externalSubtitleName by remember { mutableStateOf<String?>(null) }
    var externalSubtitleMime by remember { mutableStateOf(MimeTypes.APPLICATION_SUBRIP) }

    var addonSubtitles by remember(item?.key, activeSeason, activeEpisode) {
        mutableStateOf<List<AddonSubtitle>>(emptyList())
    }

    LaunchedEffect(item?.key, activeSeason, activeEpisode) {
        val target = item
        val repo = repository
        // The built-in subtitle addon serves a fresh install too, so this no
        // longer depends on the viewer having added one.
        if (directUrl != null || target == null || repo == null) {
            addonSubtitles = emptyList()
            return@LaunchedEffect
        }
        addonSubtitles = withTimeoutOrNull(6_000L) {
            runCatching { repo.addonSubtitles(target, activeSeason, activeEpisode) }.getOrDefault(emptyList())
        }.orEmpty()
    }

    // Holding the source back keeps the engine from starting the first result
    // while the manual picker is open; a dismiss lifts the hold below.
    val current = if (awaitingPick) null else options.getOrNull(optionIndex)
    var tracks by remember { mutableStateOf<Tracks?>(null) }
    val currentQuality = remember(current) { current?.let(::qualityOf) }
    // Only source sets with two or more distinct resolutions get a quality switcher.
    val qualityChoices: List<Pair<String, Int>> = remember(options) {
        val firstIndex = LinkedHashMap<String, Int>()
        options.forEachIndexed { index, option ->
            val quality = qualityOf(option) ?: return@forEachIndexed
            if (quality !in firstIndex) firstIndex[quality] = index
        }
        if (firstIndex.size < 2) {
            emptyList()
        } else {
            firstIndex.entries
                .sortedBy { entry ->
                    QUALITY_ORDER.indexOf(entry.key).let { rank ->
                        if (rank < 0) Int.MAX_VALUE else rank
                    }
                }
                .map { it.key to it.value }
        }
    }
    // Live HLS/DASH renditions arrive as video tracks; cap ABR at a chosen height.
    val liveQualityChoices = remember(tracks, isLive, settings.qualityLimit) {
        if (isLive) {
            videoQualityChoices(tracks).filterNot { settings.qualityLimit.excludes(it.second) }
        } else {
            emptyList()
        }
    }
    var qualityCap by remember(current?.url) { mutableIntStateOf(0) }
    val qualityCapLabel = liveQualityChoices.firstOrNull { it.second == qualityCap }?.first
    val qualityLabel = if (liveQualityChoices.isNotEmpty()) qualityCapLabel ?: "AUTO" else currentQuality
    val qualityVisible = qualityChoices.size >= 2 || liveQualityChoices.isNotEmpty()
    var onlineSubtitlesOn by remember(current?.url, item?.key) { mutableStateOf(false) }
    // Which addon track the reload should mark as default; null means the
    // first English entry of the fetched list.
    var preferredAddonSubtitle by remember(current?.url, item?.key) { mutableStateOf<String?>(null) }

    val title = when {
        isLive -> channels.getOrNull(liveIndex)?.name ?: fallbackTitle
        else -> plan?.title ?: item?.title ?: fallbackTitle
    }

    var failedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var failoverNote by remember { mutableStateOf<String?>(null) }

    val playbackUrl = current?.url
    val fileName = remember(playbackUrl, title) { mediaFileName(playbackUrl, title) }

    val episodeSlots = remember(item) { item?.let(::buildEpisodeSlots).orEmpty() }
    val slotIndex = episodeSlots.indexOfFirst { it.season == activeSeason && it.number == activeEpisode }
    val previousSlot = episodeSlots.getOrNull(slotIndex - 1).takeIf { slotIndex > 0 }
    val nextSlot = episodeSlots.getOrNull(slotIndex + 1).takeIf { slotIndex >= 0 }
    val nextSlotLatest = rememberUpdatedState(nextSlot)

    // End-of-episode hand-off: a short, cancellable countdown instead of an
    // abrupt cut, so nobody gets yanked into the next episode unprepared.
    var nextUpSlot by remember { mutableStateOf<EpisodeSlot?>(null) }
    var nextUpSeconds by remember { mutableIntStateOf(NEXT_UP_SECONDS) }
    var nextUpTotal by remember { mutableIntStateOf(NEXT_UP_SECONDS) }
    // Playhead that should hand off to [nextUpSlot]; null means the eight-second
    // end-of-file countdown. A credits offer sets it minutes ahead, so pausing
    // or buffering during the credits holds the hand-off instead of racing a
    // wall clock.
    var nextUpDueAtMs by remember { mutableStateOf<Long?>(null) }
    // Cancelling the credits-time offer silences it for this episode. The
    // end-of-file offer still appears then: at that point it is the only way
    // forward without a dead frame.
    var nextUpCancelled by remember(item?.key, activeSeason, activeEpisode) {
        mutableStateOf(false)
    }

    // TheIntroDB gives real recap/intro/credits boundaries per episode (anime
    // included, through its TMDB id); the lookup is cached, so a rewatch or the
    // prefetched successor resolves without another request.
    val introDb = app?.container?.introDb
    var skipSegments by remember(item?.key, activeSeason, activeEpisode) {
        mutableStateOf<SkipSegments?>(null)
    }
    var segmentsResolved by remember(item?.key, activeSeason, activeEpisode) {
        mutableStateOf(false)
    }
    LaunchedEffect(item?.key, activeSeason, activeEpisode, isLive, introDb) {
        val target = item
        val lookup = introDb
        if (isLive || target == null || !target.isSeries || lookup == null ||
            target.tmdbId <= 0 || activeSeason <= 0 || activeEpisode <= 0
        ) {
            segmentsResolved = true
            return@LaunchedEffect
        }
        skipSegments = withTimeoutOrNull(4_000L) {
            lookup.segments(target.tmdbId, activeSeason, activeEpisode)
        }
        segmentsResolved = true
        // Warm the successor so its boundaries are on hand when the credits of
        // this episode begin.
        val following = nextSlotLatest.value
        if (following != null) {
            withTimeoutOrNull(4_000L) {
                lookup.segments(target.tmdbId, following.season, following.number)
            }
        }
    }
    // Explicit audio/caption picks outlive a player rebuild (buffer settings,
    // decoder cycle, native fallback) but not a change of source, whose track
    // list is unrelated.
    var audioPick by remember(playbackUrl, item?.key) { mutableStateOf<SavedTrackPick?>(null) }
    var textPick by remember(playbackUrl, item?.key) { mutableStateOf<SavedTrackPick?>(null) }

    fun dismissNextUp() {
        nextUpCancelled = true
        nextUpSlot = null
        nextUpDueAtMs = null
    }

    fun wake(target: OsdFocus = OsdFocus.PLAY) {
        osdFocusTarget = target
        osdVisible = true
        interaction++
    }

    BackHandler {
        when {
            locked -> osdVisible = true
            panel != null -> {
                panel = null
                if (!osdVisible) wake(OsdFocus.PLAY)
            }
            showPicker -> {
                showPicker = false
                // Dismissing the manual picker starts the best source instead
                // of leaving the screen without a player.
                awaitingPick = false
            }
            osdVisible -> hideOsd()
            // The pill is hidden while the OSD is up, so BACK only reaches it
            // from the clean video surface.
            nextUpSlot != null -> dismissNextUp()
            else -> onExit()
        }
    }

    fun cycleAspect() {
        aspectMode = AspectMode.entries[(aspectMode.ordinal + 1) % AspectMode.entries.size]
        interaction++
    }

    LaunchedEffect(options.size) {
        if (optionIndex > options.lastIndex) optionIndex = 0
    }

    LaunchedEffect(current?.url) {
        val target = current ?: return@LaunchedEffect
        val repo = repository ?: return@LaunchedEffect
        // A named VOD player is the more explicit choice, so the chooser waits;
        // live and direct-URL previews keep the plain EXTERNAL behavior.
        val vodDefault = item != null && directUrl == null && !isLive &&
            settings.externalVodPlayer.isNotBlank()
        if (!autoHandoff && settings.playbackEngine == PlaybackEngine.EXTERNAL && !vodDefault) {
            autoHandoff = true
            // Closing waits for the chosen app to actually cover this screen;
            // a launch that never lands leaves the built-in player intact.
            if (repo.openExternally(
                    url = target.url,
                    headers = target.headers,
                    kind = target.streamKind ?: StreamUrls.kindOf(target.url)
                )
            ) {
                handoffLabel = "the system chooser"
                handoffAtMs = SystemClock.elapsedRealtime()
            } else {
                externalMessage = "External hand-off unavailable · using built-in player"
            }
        }
    }

    val nativeRequired = current != null
    val playbackHeaders = remember(current) { current?.headers.orEmpty() }
    val playbackDrm = remember(current) { current?.drm }
    // Short links and token endpoints have no extension; the repository fills
    // this in when it can, and the load path discovers it otherwise.
    val playbackKind = remember(current) {
        current?.streamKind ?: StreamUrls.kindOf(current?.url)
    }
    // HLS can play either from the origin or through the loopback proxy, which
    // re-sends playlists, keys and segments with the configured browser profile.
    // AUTO starts direct and falls back to the proxy when the origin refuses the
    // request or the tune does not start; the answer is remembered per origin,
    // so a header-gated provider is only learned once. The manual pin is kept
    // for the current player session.
    var transportMode by remember { mutableStateOf(TransportMode.AUTO) }
    // Per source: the route this prepare actually took, and whether the direct
    // attempt already failed, so AUTO falls back exactly once.
    var directRoute by remember(playbackUrl) { mutableStateOf(false) }
    var directFailed by remember(playbackUrl) { mutableStateOf(false) }
    // A denial only proves the origin refused the direct route. The proxy
    // verdict is written once that route actually plays, so a session-limit
    // ban that denies both routes never pins the origin to the proxy for the
    // whole verdict lifetime.
    var pendingProxyVerdict by remember(playbackUrl) { mutableStateOf(false) }
    // A proxy upstream trip may still leave the origin reachable directly, even
    // after AUTO once chose the proxy; force that single attempt.
    var proxyFallback by remember(playbackUrl) { mutableStateOf(false) }
    // A native engine that decodes audio but never creates a video output
    // leaves a black screen; the source is then rebuilt on the built-in player.
    // Session-scoped: the fallback says the engine cannot run on this device, so
    // the next zapped channel must not retry it again.
    var nativeFallback by remember { mutableStateOf(false) }
    // The load path types an extensionless link by walking it once. Keeping the
    // answer lets the adaptive window apply from the first prepare and stops a
    // rebuild from resolving the same link a second time.
    var discoveredSource by remember(playbackUrl) { mutableStateOf<SourceResolution?>(null) }
    // Only HLS has a proxy alternative; a progressive MP4/Matroska link always
    // plays from the origin, so the route control is hidden for it.
    val proxyCapable = isHlsUrl(playbackUrl, playbackKind ?: discoveredSource?.kind)
    // The verdict is per origin, not per link: every channel on a gated host
    // benefits from the one denial that taught it.
    val transportOrigin = remember(playbackUrl, discoveredSource) {
        StreamUrls.originOf(discoveredSource?.url ?: playbackUrl)
    }
    val originVerdict = app?.container?.transportMemory?.verdict(transportOrigin)
    val lowRamDevice = remember { DeviceProfile.isLowRam(context) }
    // Buffer settings are rebuild-class: the load control and the native
    // engine caches are fixed at construction. They enter the build only after
    // the selection has settled, so sweeping the tier row rebuilds once.
    val bufferSelection = BufferSelection(
        tier = settings.bufferTier,
        lowLatency = settings.lowLatency,
        highCapacity = settings.highCapacityBuffer
    )
    var settledBuffer by remember { mutableStateOf(bufferSelection) }
    LaunchedEffect(bufferSelection) {
        if (bufferSelection == settledBuffer) return@LaunchedEffect
        delay(BUFFER_SETTLE_MS)
        settledBuffer = bufferSelection
    }
    val profileAdaptive = remember(playbackUrl, playbackKind, discoveredSource) {
        isAdaptiveUrl(playbackUrl, playbackKind ?: discoveredSource?.kind)
    }
    // One resolution for the build, the live re-apply paths and the panels.
    // Buffer fields come from the settled selection, so the player key only
    // moves once a sweep has finished; everything else is already live.
    // Remembered because this composable also hosts the one-second playhead
    // state: without the key it reallocated the profile and its rebuild-key
    // list on every tick for no change.
    val profile = remember(
        settings,
        decoderMode,
        profileAdaptive,
        isLive,
        lowRamDevice,
        settledBuffer,
        playbackHeaders,
        nativeFallback
    ) {
        playbackProfile(
            settings = settings,
            decoder = decoderMode,
            adaptive = profileAdaptive,
            lowRam = lowRamDevice,
            tier = settledBuffer.tier,
            lowLatency = settledBuffer.lowLatency,
            highCapacityVod = settledBuffer.highCapacity,
            headers = playbackHeaders,
            forceBuiltIn = nativeFallback,
            live = isLive
        )
    }
    // Reloads (retry timer, proxy bypass, RELOAD button) re-prepare the same
    // player instance instead of building a new one: tearing down Media3 or a
    // native engine costs a codec re-init and drops every buffered segment,
    // which turns a transient network blip into a multi-second black screen.
    // Live zapping is the same trade at channel granularity: the URL is not a
    // build key there, so one engine lasts the session and each zap swaps the
    // MediaItem. Only a changed engine profile (settings, native headers,
    // adaptive class) or a DRM stream rebuilds it.
    val playerInstanceKey = if (isLive) null else playbackUrl
    val builtPlayer: BuiltPlayer? = remember(
        nativeRequired,
        playerInstanceKey,
        isLive,
        profile.rebuildKey,
        playbackDrm,
        nativeFallback
    ) {
        if (nativeRequired) {
            buildPlayer(
                context = context,
                profile = profile,
                isLive = isLive,
                drm = playbackDrm,
                subtitlesDisabled = dropSubtitles,
                subtitleLanguage = preferredSubtitleLanguage(activeProfile),
                eqState = eqState,
                gainState = gainState
            )
        } else {
            null
        }
    }
    val playerOrNull: Player? = builtPlayer?.player
    // VLC and Media3 open HTTPS themselves; MPV routes everything through the
    // loopback proxy, so the DIRECT pin cannot change its route.
    val engineCanDirect = playerOrNull?.canBypassProxy() ?: true
    // VOD resumes where the replaced player stopped; live re-tunes to the edge.
    var carryOverPositionMs by remember(playbackUrl) { mutableLongStateOf(0L) }
    DisposableEffect(playerOrNull, isLive) {
        onDispose {
            val leaving = playerOrNull
            if (leaving != null && !isLive) {
                carryOverPositionMs = leaving.currentPosition.coerceAtLeast(0L)
            }
            leaving?.release()
        }
    }
    // A requested native engine can come back as the built-in player: its
    // library is missing, the API floor is not met, or construction threw.
    // Record the fallback so later rebuilds do not retry a dead engine on every
    // settings change. DRM streams skip the engine by design and are excluded.
    LaunchedEffect(playerOrNull, profile.engine, nativeFallback) {
        if (nativeFallback || playbackDrm != null) return@LaunchedEffect
        val requested = settings.playbackEngine
        if (requested != PlaybackEngine.VLC && requested != PlaybackEngine.MPV) {
            return@LaunchedEffect
        }
        if (playerOrNull != null && playerOrNull !is NativeEnginePlayer) {
            Log.w(
                "StreamLine:player",
                "${requested.name} unavailable · staying on the built-in player"
            )
            nativeFallback = true
        }
    }
    // UA and headers are live-class for Media3: the factory hands every new
    // data source the current properties, so no codec re-init is needed.
    LaunchedEffect(builtPlayer, profile.userAgent, profile.headers) {
        builtPlayer?.liveHeaders?.update(profile.userAgent, profile.headers)
    }
    // The preferred display mode is owned by the player window; hand the
    // launcher back its original rate instead of leaving it at 24 Hz.
    DisposableEffect(Unit) {
        onDispose { FrameRateMatching.restore(activity) }
    }

    // Media3 keeps the counters (dropped frames, bandwidth, underruns) that the
    // old buffer-size bar hinted at. Attach once per player so a stats panel can
    // read them on demand instead of paying attention to them continuously. The
    // reads below use the per-session stats: a retained live engine opens a new
    // session per channel, so the panel and the dropped-frame cap describe the
    // channel on screen instead of the whole zapping session.
    val playbackStatsListener = remember { PlaybackStatsListener(false, null) }
    // One probe per source: a retained live player keeps its instance across
    // channel zaps, so the previous channel's decoder names must not survive.
    val decoderProbe = remember(playbackUrl) { DecoderProbe() }
    DisposableEffect(playerOrNull, decoderProbe) {
        // Analytics counters only exist on the built-in engine; native engines
        // report what they can through the same Player state instead.
        val target = playerOrNull as? ExoPlayer ?: return@DisposableEffect onDispose { }
        target.addAnalyticsListener(playbackStatsListener)
        target.addAnalyticsListener(decoderProbe)
        onDispose {
            target.removeAnalyticsListener(playbackStatsListener)
            target.removeAnalyticsListener(decoderProbe)
        }
    }
    // Polled once a second while the panel is open, and refreshed with it so
    // the estimate shown is the one ABR is using now, not the session mean.
    val osdStats = rememberOsdStats(panel, playerOrNull, playbackStatsListener, decoderProbe)

    // Set when the decoder cannot keep up with the selected rendition; the
    // video ceiling then drops to 720p for the rest of this source.
    var droppedFrameCapHeight by remember(playbackUrl) { mutableIntStateOf(0) }
    LaunchedEffect(playerOrNull, playbackUrl) {
        if (playerOrNull !is ExoPlayer) return@LaunchedEffect
        while (isActive) {
            delay(DROPPED_FRAME_WINDOW_MS)
            if (droppedFrameCapHeight > 0) return@LaunchedEffect
            val stats = runCatching { playbackStatsListener.playbackStats }.getOrNull()
                ?: continue
            if (stats.totalPlayTimeMs < DROPPED_FRAME_MIN_PLAY_MS) continue
            val rate = stats.droppedFramesRate
            if (rate >= DROPPED_FRAME_PERCENT) {
                Log.i(
                    "StreamLine:player",
                    "Dropped ${stats.totalDroppedFrames} frames (${rate}%) · capping video at " +
                        "${DROPPED_FRAME_FALLBACK_HEIGHT}p"
                )
                droppedFrameCapHeight = DROPPED_FRAME_FALLBACK_HEIGHT
                return@LaunchedEffect
            }
        }
    }

    /**
     * Where a replacement source should pick up. Prefers the live playhead but
     * falls back to the mirrored one, because a fatal error resets the player
     * to IDLE and [ExoPlayer.getCurrentPosition] then reports 0.
     */
    fun resumePosition(): Long {
        val active = playerOrNull
        if (active != null && active.playbackState != Player.STATE_IDLE) {
            val live = active.currentPosition
            if (live > 0) return live
        }
        return lastKnownPositionMs
    }

    fun notePlayhead(positionMs: Long) {
        lastKnownPositionMs = positionMs.coerceAtLeast(0L)
    }

    fun attemptFailover(): Boolean {
        val pool = options.indices.filter {
            it != optionIndex && it !in failedIndices && options[it].url.isNotBlank()
        }
        val next = pool.minOrNull() ?: return false
        // Live failover tunes to the new edge; a stale playhead would rewind
        // into the provider's window (or outside it) instead of continuing.
        val resumeMs = if (!isLive) resumePosition().takeIf { it > 5_000 } else null
        failedIndices = failedIndices + optionIndex
        if (resumeMs != null) pendingSeekMs = resumeMs
        Log.i("StreamLine:player", "Failover → ${options[next].name} · resume ${resumeMs ?: 0} ms")
        failoverNote = "Switching to ${options[next].name}"
        optionIndex = next
        reloadToken++
        interaction++
        return true
    }

    var nativeError by remember { mutableStateOf<PlaybackFailure?>(null) }
    // Scoped to the source: a fresh stream must not inherit the previous one's
    // spent retry budget, which otherwise opens the panel after the first error.
    var reconnectAttempt by remember(playbackUrl) { mutableIntStateOf(0) }
    // Slips past the live edge are re-prepared straight to the edge; they are
    // counted apart from reconnectAttempt so a hiccup never spends the budget a
    // real reconnect needs.
    var liveEdgeRecoveries by remember(playbackUrl) { mutableIntStateOf(0) }
    var reconnectNotice by remember { mutableStateOf<String?>(null) }
    // Per source: a pending backoff for the old stream must not fire against
    // the one the viewer just tuned to.
    var retryPlanMs by remember(playbackUrl) { mutableStateOf<Long?>(null) }
    // Repeated transport failures at the proxy mean the upstream is gone; the
    // player is pushed out of its retry loop instead of waiting it out.
    val proxyTrips = remember(playbackUrl) { MutableSharedFlow<String>(extraBufferCapacity = 4) }
    var proxyStreamId by remember(playbackUrl) { mutableStateOf<String?>(null) }
    var lastRecoveryAt by remember(playbackUrl) { mutableLongStateOf(0L) }

    // A frozen playhead otherwise keeps the loader retrying for over a minute.
    // Surface it like a failed tune; RELOAD keeps the VOD playhead.
    fun recordLiveFailure() {
        if (!isLive) return
        channels.getOrNull(liveIndex)?.let { app?.container?.channelHealth?.recordFailure(it.key) }
    }

    /**
     * Next index in [delta]'s direction, hopping over channels already marked
     * dead. Returns [from] when every channel that way is dead so zapping can
     * never land on a known-broken entry at the end of the list.
     */
    fun liveIndexAfter(from: Int, delta: Int): Int {
        val step = if (delta >= 0) 1 else -1
        val healthStore = app?.container?.channelHealth
            ?: return (from + step).coerceIn(0, channels.lastIndex)
        var index = from + step
        while (index in channels.indices) {
            if (!healthStore.isDead(channels[index].key)) return index
            index += step
        }
        return from
    }

    fun recoverStalledPlayback(title: String, detail: String) {
        // Most stalls are the provider recycling a session, so re-prepare the
        // same source before the panel (and its failover) takes over. The
        // budget is shared with error reconnects: a source that keeps freezing
        // escalates instead of retrying forever.
        val target = playerOrNull
        if (target != null && reconnectAttempt < stallRetryLimit(isLive)) {
            reconnectAttempt += 1
            pendingSeekMs = if (isLive) null else resumePosition().takeIf { it > 5_000 }
            runCatching { target.stop() }
            nativeError = null
            failoverNote = null
            val waitMs = stallRetryDelayMs(reconnectAttempt)
            Log.i(
                "StreamLine:player",
                "stall · re-preparing the same source in $waitMs ms " +
                    "(attempt $reconnectAttempt/${stallRetryLimit(isLive)})"
            )
            reconnectNotice = "Playback stalled · reconnecting in ${waitMs / 1000}s"
            retryPlanMs = waitMs
            return
        }
        if (reconnectAttempt > 0) {
            Log.w(
                "StreamLine:player",
                "stall · retries exhausted after $reconnectAttempt attempt(s); opening the panel"
            )
        }
        // AUTO's last move before the panel: a frozen direct connection may be
        // an origin that only answers the proxy's browser profile.
        if (target != null && proxyCapable && directRoute && !directFailed &&
            transportMode == TransportMode.AUTO
        ) {
            directFailed = true
            pendingSeekMs = if (isLive) null else resumePosition().takeIf { it > 5_000 }
            runCatching { target.stop() }
            nativeError = null
            failoverNote = null
            reconnectNotice = "Stalled on the direct route · switching to the proxy"
            retryPlanMs = null
            reloadToken++
            return
        }
        nativeError = PlaybackFailure(
            kind = FailureKind.NETWORK,
            title = title,
            detail = detail,
            action = "Reload to resume, or choose another source."
        )
        failoverNote = null
        reconnectNotice = null
        retryPlanMs = null
        recordLiveFailure()
    }

    fun recoverFromProxyFailure() {
        if (nativeError != null || directRoute || transportMode == TransportMode.PROXY) return
        // AUTO already tried the origin on this tune; a second direct attempt
        // would only re-run a route that just failed. A pinned PROXY never
        // leaves the proxy.
        if (directFailed) return
        val target = playerOrNull ?: return
        if (!target.canBypassProxy()) return
        // A provider that refused the configured agent refuses the direct
        // request too, so the proxy is the only path that can play this one.
        if (proxyStreamId?.let { NativeStreamProxy.requiresBrowserMask(it) } == true) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecoveryAt < RECOVERY_COOLDOWN_MS) return
        lastRecoveryAt = now
        pendingSeekMs = if (!isLive) resumePosition().takeIf { it > 5_000 } else null
        runCatching { target.stop() }
        reconnectNotice = "Provider unreachable · reconnecting directly"
        proxyFallback = true
        reloadToken++
    }

    /**
     * Applies a manual route choice. Pinned modes suppress the automatic
     * fallbacks, so DIRECT really stays direct until the viewer changes it.
     * Switching only re-prepares when the resulting route differs; flipping
     * AUTO↔DIRECT while both already play direct must not restart the stream.
     */
    fun setTransportMode(mode: TransportMode) {
        val currentVerdict = app?.container?.transportMemory?.verdict(transportOrigin)
        val target = playerOrNull
        val nextDirect = transportUsesDirect(
            mode = mode,
            verdict = if (mode == TransportMode.AUTO) currentVerdict else null,
            canDirect = target?.canBypassProxy() ?: true,
            autoDirectFailed = false
        )
        val routeChanges = proxyCapable && nextDirect != directRoute
        transportMode = mode
        if (routeChanges) {
            directFailed = false
            proxyFallback = false
            pendingProxyVerdict = false
            pendingSeekMs = if (isLive) null else resumePosition().takeIf { it > 1_000 }
            runCatching { target?.stop() }
            nativeError = null
            failoverNote = null
            reconnectAttempt = 0
            reconnectNotice = null
            retryPlanMs = null
            reloadToken++
            interaction++
        }
    }

    // Channel switching resolves each tune through the addon that published the channel.
    LaunchedEffect(liveIndex, isLive) {
        val repo = repository ?: return@LaunchedEffect
        if (!isLive || liveIndex == tunedIndex) return@LaunchedEffect
        val channel = channels.getOrNull(liveIndex) ?: return@LaunchedEffect
        channelResolving = true
        val resolved = withTimeoutOrNull(20_000L) {
            runCatching { repo.liveStream(channel) }.getOrNull()
        }
        channelResolving = false
        val option = resolved?.option
        if (option != null) {
            channelOption = option
            tunedIndex = liveIndex
            nativeError = null
            failoverNote = null
        } else {
            app?.container?.channelHealth?.recordFailure(channel.key)
            failoverNote = when {
                resolved?.externalOnly == true ->
                    "${channel.name} only plays in an external player (UDP/RTP/RTSP) · " +
                        "open it from the Live tab."
                resolved?.error != null -> "Could not tune ${channel.name}: ${resolved.error}"
                resolved?.externalUrl != null ->
                    "${channel.name} has no direct stream · the addon only links a watch page."
                else -> "Could not tune ${channel.name}"
            }
            // The previous channel keeps playing; leaving liveIndex on the failed
            // one would relabel that stream with the wrong channel and need an
            // extra press before a retry can even start.
            if (tunedIndex in channels.indices) liveIndex = tunedIndex
        }
    }

    // Channel surfing is the dominant Live interaction. While the OSD is open
    // (i.e. the user is likely to zap next), resolve the neighbours' manifests
    // ahead of time so a press is served from cache instead of paying the full
    // addon round trip.
    LaunchedEffect(osdVisible, isLive, liveIndex) {
        if (!osdVisible || !isLive) return@LaunchedEffect
        val repo = repository ?: return@LaunchedEffect
        delay(400)
        // The neighbour resolve pulls manifests from the same CDN the channel
        // on screen is still filling from. While the player is buffering (or
        // has failed) that read is the one the viewer is waiting on, so leave
        // the prefetch for the next OSD open instead of stealing its bytes.
        val target = playerOrNull
        if (target != null && target.playbackState != Player.STATE_READY) return@LaunchedEffect
        listOf(liveIndex - 1, liveIndex + 1).forEach { index ->
            channels.getOrNull(index)?.let { channel ->
                runCatching { repo.prefetchLive(channel) }
            }
        }
    }

    fun cycleDecoder() {
        pendingSeekMs = if (isLive) null else resumePosition()
        decoderMode = when (decoderMode) {
            DecoderType.AUTO, DecoderType.SW -> DecoderType.HW
            DecoderType.HW -> DecoderType.HW_PLUS
            DecoderType.HW_PLUS -> DecoderType.SW
        }
        interaction++
    }

    fun toggleStereoDownmix() {
        val store = settingsStore ?: return
        pendingSeekMs = if (isLive) null else resumePosition()
        runCatching { store.update { it.copy(forceStereoDownmix = !it.forceStereoDownmix) } }
        interaction++
    }

    fun toggleVolumeNormalization() {
        val store = settingsStore ?: return
        pendingSeekMs = if (isLive) null else resumePosition()
        runCatching { store.update { it.copy(normalizeVolume = !it.normalizeVolume) } }
        interaction++
    }

    fun chooseOption(index: Int, resume: Boolean) {
        optionIndex = index
        failedIndices = emptySet()
        failoverNote = null
        nativeError = null
        pendingSeekMs = if (resume) {
            resumePosition().takeIf { it > 5_000 }
        } else {
            null
        }
        if (!resume) lastKnownPositionMs = 0L
        reloadToken++
        interaction++
    }

    fun playSlot(slot: EpisodeSlot) {
        if (slot.season == activeSeason && slot.number == activeEpisode) return
        nextUpSlot = null
        nextUpDueAtMs = null
        activeSeason = slot.season
        activeEpisode = slot.number
        activeEpisodeTitle = slot.title
        optionIndex = 0
        failedIndices = emptySet()
        failoverNote = null
        nativeError = null
        // Null, not 0: a zero pending seek made the prepare gate issue a
        // pointless seekTo(0) on the fresh episode and delay its first frame.
        pendingSeekMs = null
        lastKnownPositionMs = 0L
        reloadToken++
        osdFocusTarget = OsdFocus.PLAY
        interaction++
    }

    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var bufferedMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    // Credits start minutes before the file ends, so that marker — not EOF —
    // is when the next episode becomes relevant. A database entry without a
    // credits marker falls back to the same last-minute window a viewer
    // expects from a standard episode, so the offer still appears on time.
    val creditsStartMs = remember(skipSegments, segmentsResolved, durationMs, isLive) {
        when {
            isLive -> null
            else -> skipSegments?.creditsStart(durationMs)
                ?: if (segmentsResolved) fallbackCreditsStartMs(durationMs) else null
        }
    }
    LaunchedEffect(
        creditsStartMs, positionMs, durationMs, nextSlot, nextUpSlot, nextUpCancelled, isLive
    ) {
        val start = creditsStartMs ?: return@LaunchedEffect
        val slot = nextSlot ?: return@LaunchedEffect
        if (isLive || nextUpCancelled || nextUpSlot != null || positionMs < start) {
            return@LaunchedEffect
        }
        val dueAt = durationMs - CREDITS_END_MARGIN_S * 1_000L
        // Seeking into the final seconds should not switch episodes on its own;
        // the offer only arms while there is still something to hand off to.
        if (positionMs >= dueAt) return@LaunchedEffect
        val seconds = ((dueAt - positionMs) / 1_000L).toInt().coerceAtLeast(1)
        nextUpTotal = seconds.coerceAtLeast(NEXT_UP_SECONDS)
        nextUpSeconds = seconds
        nextUpDueAtMs = dueAt
        nextUpSlot = slot
    }

    LaunchedEffect(nextUpSlot, nextUpDueAtMs) {
        val slot = nextUpSlot ?: return@LaunchedEffect
        val dueAt = nextUpDueAtMs
        if (dueAt == null) {
            while (nextUpSeconds > 1) {
                delay(1_000L)
                nextUpSeconds -= 1
            }
        } else {
            // The countdown mirrors the playhead, so pausing keeps the offer
            // pending instead of switching episodes mid-pause.
            while (positionMs < dueAt) {
                delay(500L)
                nextUpSeconds = ((dueAt - positionMs) / 1_000L).toInt().coerceAtLeast(1)
            }
        }
        nextUpSlot = null
        nextUpDueAtMs = null
        playSlot(slot)
    }

    var liveGuide by remember { mutableStateOf(EpgGuide.EMPTY) }
    var liveNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var audioAudit by remember { mutableStateOf<AudioAudit?>(null) }
    var videoPixelSize by remember { mutableStateOf(IntSize.Zero) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var wasPlayingBeforeScrub by remember { mutableStateOf(false) }
    // Throttles the live preview seeks issued while the finger is on the bar.
    var lastScrubSeekAt by remember { mutableLongStateOf(0L) }

    // Hold a partial wake lock and a high-performance Wi-Fi lock while media is
    // running or refilling; the OS otherwise lets the radio doze mid-stream.
    // Releasing them the moment playback dips into buffering is exactly when a
    // slow segment needs the lock most.
    val holdPlaybackLocks = isPlaying || buffering
    DisposableEffect(playerOrNull, holdPlaybackLocks) {
        if (playerOrNull == null || !holdPlaybackLocks) return@DisposableEffect onDispose { }
        val locks = PlaybackLocks.acquire(context)
        onDispose { locks.release() }
    }

    DisposableEffect(playerOrNull) {
        val target = playerOrNull ?: return@DisposableEffect onDispose { }
        // Native engines discover the video track after demux and can refine the
        // frame rate once the first frame is decoded, so AFR re-checks on every
        // signal that carries track information, not just the first Tracks push.
        fun syncFrameRate(snapshot: Tracks? = tracks) {
            if (!settings.frameRateMatching) return
            val rate = (target as? ExoPlayer)?.videoFormat?.frameRate
                ?: snapshot?.let { firstFormat(it, C.TRACK_TYPE_VIDEO) }?.frameRate
                ?: 0f
            FrameRateMatching.apply(activity, rate)
        }
        // A retained live engine carries the previous channel's overrides, so
        // the preferred language and the saved picks are scoped to the item,
        // not to the instance: every rebuild and every zap parses fresh groups.
        var trackItem: MediaItem? = null
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) syncFrameRate()
                // Pausing is the moment a viewer actually leaves; persist the
                // playhead here instead of rewriting the whole library state
                // file every few seconds during playback.
                if (!playing && target.playbackState == Player.STATE_READY) {
                    plan?.let { persistProgress(repository, it, activeEpisodeTitle, target) }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    nativeError = null
                    failoverNote = null
                    reconnectNotice = null
                    retryPlanMs = null
                }
                if (playbackState == Player.STATE_ENDED) {
                    // Roll into the next episode after a short, cancellable
                    // countdown so a binge never stalls.
                    val slot = nextSlotLatest.value
                    if (!isLive && slot != null) {
                        nextUpTotal = NEXT_UP_SECONDS
                        nextUpSeconds = NEXT_UP_SECONDS
                        nextUpDueAtMs = null
                        nextUpSlot = slot
                    } else if (isLive) {
                        // A provider that ends its live playlist (placeholder
                        // loops, VOD-style manifests) leaves the player parked on
                        // the last frame without raising an error, so the channel
                        // would sit frozen with no way back but exiting. Surface
                        // the usual panel; reloading starts a fresh tune instead
                        // of auto-retrying, which a zero-length playlist would
                        // turn into a reload storm.
                        recordLiveFailure()
                        nativeError = PlaybackFailure(
                            kind = FailureKind.SOURCE,
                            title = "Channel ended",
                            detail = "The provider stopped sending this stream.",
                            action = "Reload to tune in again."
                        )
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Snapshot the playhead before the failure tears the player down;
                // the failover source resumes from it.
                notePlayhead(maxOf(lastKnownPositionMs, target.currentPosition))
                // A playhead that slid out of the live window is not a fault,
                // and Media3's documented remedy is a re-prepare: it rejoins at
                // the edge without spending a provider session, a source slot,
                // or the channel's health.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
                    liveEdgeRecoveries < LIVE_EDGE_RECOVERY_LIMIT
                ) {
                    liveEdgeRecoveries += 1
                    Log.i(
                        "StreamLine:player",
                        "live edge · re-preparing at the edge " +
                            "(attempt $liveEdgeRecoveries/$LIVE_EDGE_RECOVERY_LIMIT)"
                    )
                    nativeError = null
                    failoverNote = null
                    reconnectNotice = "Catching up to live"
                    retryPlanMs = null
                    runCatching { target.prepare() }
                    return
                }
                // AUTO opens direct. A 401/403/407/451 there is the origin
                // refusing the request itself, not the account being throttled,
                // so hand the URL to the proxy once: it re-sends every playlist
                // and segment with the browser profile this CDN accepts. The
                // proxy verdict is remembered per origin once that route plays,
                // so the next tune there starts on the proxy.
                if (proxyCapable && directRoute && !directFailed &&
                    transportMode == TransportMode.AUTO && error.isOriginDenial()
                ) {
                    directFailed = true
                    pendingProxyVerdict = true
                    if (!isLive) pendingSeekMs = resumePosition().takeIf { it > 5_000 }
                    runCatching { target.stop() }
                    nativeError = null
                    failoverNote = null
                    reconnectNotice = "Origin refused the direct request · retrying through the proxy"
                    retryPlanMs = null
                    reloadToken++
                    return
                }
                // Flussonic-style token protection answers 401/403/429 when the
                // account's session limit is hit. Reconnecting straight away (or
                // bypassing the proxy) only adds sessions and refreshes the
                // provider's negative cache, so wait out the ban instead — the
                // provider's own Retry-After wins over our backoff ladder.
                if (error.isHttpAuthorizationFailure() &&
                    reconnectAttempt < providerRetryLimit(isLive)
                ) {
                    reconnectAttempt += 1
                    val waitMs = error.providerRetryAfterMs() ?: providerRetryDelayMs(reconnectAttempt)
                    if (!isLive) pendingSeekMs = resumePosition().takeIf { it > 5_000 }
                    nativeError = null
                    failoverNote = null
                    reconnectNotice =
                        "Provider is limiting this stream · retrying in ${waitMs / 1000}s"
                    retryPlanMs = waitMs
                    return
                }
                // A live edge that drops the socket (reset, DNS, timeout) is
                // usually the provider recycling the session, not a dead
                // channel, and a film's socket drop is just as often a CDN
                // hiccup. Retry the same source with a short backoff before
                // spending a source slot on failover; the budget is shared
                // with stall recovery, so a source that keeps failing still
                // reaches the panel and the automatic source switch.
                if (reconnectAttempt < errorRetryLimit(isLive) &&
                    error.isRetryableInPlace()
                ) {
                    reconnectAttempt += 1
                    val waitMs = transientReconnectDelayMs(reconnectAttempt, isLive)
                    if (!isLive) pendingSeekMs = resumePosition().takeIf { it > 5_000 }
                    nativeError = null
                    failoverNote = null
                    Log.i(
                        "StreamLine:player",
                        if (error.isLicenceAcquisitionFailure()) {
                            "licence · re-acquiring in $waitMs ms · DRM retained " +
                                "(attempt $reconnectAttempt/${errorRetryLimit(isLive)})"
                        } else {
                            "transient · ${error.errorCodeName} · re-preparing the same " +
                                "source in $waitMs ms " +
                                "(attempt $reconnectAttempt/${errorRetryLimit(isLive)})"
                        }
                    )
                    reconnectNotice = if (error.isLicenceAcquisitionFailure()) {
                        "Licence server busy · retrying in ${waitMs / 1000}s"
                    } else if (error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) {
                        "Stream interrupted · reconnecting in ${waitMs / 1000}s"
                    } else {
                        "Connection lost · reconnecting in ${waitMs / 1000}s"
                    }
                    retryPlanMs = waitMs
                    return
                }
                // The retries above all ran on the direct route. A proxy retry
                // is the last transport-level move before the panel: the proxy
                // fetches through a different stack (its own resolver, cookie-
                // less requests, browser masking), which rescues origins that
                // drop or reset direct sockets. No verdict is recorded — a CDN
                // hiccup is not gating, and the next tune still starts direct.
                if (proxyCapable && directRoute && !directFailed &&
                    transportMode == TransportMode.AUTO &&
                    error.isRetryableInPlace() &&
                    !error.isLicenceAcquisitionFailure()
                ) {
                    directFailed = true
                    if (!isLive) pendingSeekMs = resumePosition().takeIf { it > 5_000 }
                    runCatching { target.stop() }
                    nativeError = null
                    failoverNote = null
                    reconnectNotice = "Direct connection failed · retrying through the proxy"
                    retryPlanMs = null
                    reloadToken++
                    return
                }
                val failure = classifyPlaybackError(error)
                // Only a failure that survives the retries and says something
                // about the channel may mark it dead; a reset, a timeout or a
                // live-edge slip would otherwise make zapping skip a stream
                // that works on the next tune.
                if (isLive && !error.isTransientForChannel()) recordLiveFailure()
                Log.w(
                    "StreamLine:player",
                    "Playback failed (${error.errorCodeName}/${error.errorCode}) · ${failure.kind}"
                )
                nativeError = failure
                reconnectNotice = null
                retryPlanMs = null
            }

            override fun onTracksChanged(tracksSnapshot: Tracks) {
                tracks = tracksSnapshot
                // A new item (a rebuild or a live zap on the retained engine)
                // starts from a clean slate: the previous channel's overrides
                // no longer match these groups and would only mislead the
                // selector, so they are dropped before the picks re-apply.
                val itemNow = target.currentMediaItem
                val newItem = itemNow != null && itemNow !== trackItem &&
                    tracksSnapshot.groups.isNotEmpty()
                if (newItem) trackItem = itemNow
                val explicit = target.trackSelectionParameters.buildUpon()
                var hasExplicit = newItem
                if (newItem) {
                    explicit.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    explicit.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    // The preferred language is applied once per item: a zap
                    // parses fresh groups, so keying this to the instance
                    // played the new channel's default track.
                    trackOverride(
                        tracksSnapshot, C.TRACK_TYPE_AUDIO, activeProfile.audioLanguage
                    )?.let { explicit.setOverrideForType(it) }
                }
                // Then the viewer's explicit picks, which the rebuilt item
                // would otherwise silently drop in favour of the defaults.
                audioPick?.let { pick ->
                    savedTrackOverride(tracksSnapshot, C.TRACK_TYPE_AUDIO, pick)?.let {
                        explicit.setOverrideForType(it)
                        hasExplicit = true
                    }
                }
                // A saved caption pick must not outlive the viewer switching
                // captions off: re-applying it here would re-enable the text
                // renderer behind an "Off" label.
                if (!dropSubtitles) {
                    textPick?.let { pick ->
                        savedTrackOverride(tracksSnapshot, C.TRACK_TYPE_TEXT, pick)?.let {
                            explicit.setOverrideForType(it)
                            hasExplicit = true
                        }
                    }
                }
                if (hasExplicit) {
                    val updated = explicit.build()
                    // Native engines publish Tracks on every ES change; setting
                    // identical parameters again re-selects tracks for nothing.
                    if (updated != target.trackSelectionParameters) {
                        target.trackSelectionParameters = updated
                    }
                }
                val audit = AudioAuditProbe.inspect(
                    context,
                    firstFormat(tracksSnapshot, C.TRACK_TYPE_AUDIO),
                    settings.forceStereoDownmix,
                    settings.normalizeVolume
                )
                if (audit != audioAudit) {
                    audioAudit = audit
                    AudioAuditProbe.log(audit)
                }
                if (settings.frameRateMatching) {
                    // ExoPlayer exposes the selected rendition; VLC and MPV
                    // publish their video track through the same Tracks list,
                    // so both engines drive display matching here.
                    syncFrameRate(tracksSnapshot)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoPixelSize = IntSize(videoSize.width, videoSize.height)
                // VLC only creates its video output once a frame is decoded,
                // which is often after the ES-selected Tracks update.
                syncFrameRate()
            }
        }
        target.addListener(listener)
        onDispose { target.removeListener(listener) }
    }

    // A dead mark belongs to a channel that could not play; once a stream has
    // actually been running for a while, forget the mark so a provider that
    // fixed its entry is picked up without waiting for the weekly decay.
    LaunchedEffect(playerOrNull, isLive, tunedIndex, liveIndex, isPlaying) {
        if (!isLive || !isPlaying || tunedIndex != liveIndex) return@LaunchedEffect
        delay(CHANNEL_HEALTH_SUCCESS_MS)
        val target = playerOrNull ?: return@LaunchedEffect
        if (!target.isPlaying || target.playbackState != Player.STATE_READY) return@LaunchedEffect
        channels.getOrNull(liveIndex)?.let { app?.container?.channelHealth?.recordSuccess(it.key) }
    }

    // Lifecycle guard: stop releasing playback when the activity leaves the screen
    // (PiP keeps ON_PAUSE only, so playback survives picture-in-picture). Live
    // streams get stop() instead of pause() — that drops the entire buffered
    // back-buffer, and the next prepare starts from the live edge again. A
    // hand-off pauses the same instance and never closes it: the external player
    // owns the foreground, and BACK from it lands on the paused frame instead of
    // a destroyed activity.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeOnStart by remember { mutableStateOf(false) }
    val handoffInFlight by rememberUpdatedState(handoffLabel)
    val handoffStartedAt by rememberUpdatedState(handoffAtMs)
    val handoffResume by rememberUpdatedState(resumeAfterOpenWith)
    DisposableEffect(lifecycleOwner, playerOrNull) {
        val observer = LifecycleEventObserver { _, event ->
            val target = playerOrNull ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resumeOnStart = target.playWhenReady
                    if (isLive || target.isCurrentMediaItemLive) {
                        runCatching { target.stop() }
                    } else {
                        target.pause()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (resumeOnStart) {
                        if (target.playbackState == Player.STATE_IDLE) {
                            runCatching { target.prepare() }
                        }
                        target.playWhenReady = true
                    }
                    // Back in the app with the hand-off still in flight: the
                    // external player is gone, so this is the switch-back — the
                    // same instance picks up where it was paused.
                    val label = handoffInFlight
                    if (label != null &&
                        SystemClock.elapsedRealtime() - handoffStartedAt > HANDOFF_SETTLE_MS
                    ) {
                        handoffLabel = null
                        externalMessage = "Back in the app player"
                        if (handoffResume) {
                            // Live streams were stopped on the way out, and a
                            // stopped player needs a fresh prepare before play.
                            if (target.playbackState == Player.STATE_IDLE) {
                                runCatching { target.prepare() }
                            }
                            target.play()
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A target that opens a floating window instead of covering this activity
    // never raises ON_STOP; the local player must not stay paused forever. Only
    // act while this activity is in front — in the background it is the
    // external player that owns playback.
    LaunchedEffect(handoffLabel) {
        val label = handoffLabel ?: return@LaunchedEffect
        delay(HANDOFF_TIMEOUT_MS)
        if (handoffLabel == label &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            handoffLabel = null
            externalMessage = "Hand-off to $label did not start · still in the app player"
            val target = playerOrNull
            if (resumeAfterOpenWith && target != null) {
                if (target.playbackState == Player.STATE_IDLE) {
                    runCatching { target.prepare() }
                }
                target.play()
            }
        }
    }

    // Live back-buffer flush: a live stream left paused keeps growing its buffer.
    // After a minute of pause the buffer is dropped and the player holds at the
    // live edge instead of accumulating media in memory.
    LaunchedEffect(playerOrNull, isPlaying) {
        val target = playerOrNull ?: return@LaunchedEffect
        val liveChannelStream = isLive || target.isCurrentMediaItemLive
        if (isPlaying || !liveChannelStream) return@LaunchedEffect
        delay(60_000)
        if (!target.isPlaying && liveChannelStream) {
            runCatching {
                target.stop()
                target.prepare()
                target.playWhenReady = false
                target.seekToDefaultPosition()
            }
        }
    }

    LaunchedEffect(playerOrNull, playbackUrl, reloadToken) {
        val target = playerOrNull ?: return@LaunchedEffect
        val media = current ?: return@LaunchedEffect
        val directUrl = playbackUrl ?: media.url
        // A short link or token endpoint has no extension and the addon reply
        // reported no type. Walk it once: Media3 resolves HLS/DASH relative
        // URIs against the URL it is handed, and the final response names the
        // extractor, which otherwise defaults to progressive and throws
        // UnrecognizedInputFormatException on a playlist.
        val cached = discoveredSource
        val discovered = cached ?: if (
            playbackKind == null && StreamUrls.needsKindDiscovery(directUrl)
        ) {
            StremioAddons.resolveSource(directUrl, playbackHeaders, settings.userAgentString)
        } else {
            null
        }
        if (cached == null && discovered != null) {
            discoveredSource = discovered
            // An adaptive kind moves the window, the byte budget and the native
            // caches, all fixed at construction. Let the rebuild this state
            // change triggers own the tune instead of preparing a progressive
            // profile that is about to be discarded.
            if (discovered.kind != null) return@LaunchedEffect
        }
        val resolvedUrl = discovered?.url ?: directUrl
        val resolvedKind = discovered?.kind ?: playbackKind
        // The route is a per-attempt decision: it must not restart this effect
        // on its own (it is always paired with a reload token bump), otherwise
        // a fallback would interrupt healthy playback. AUTO opens direct,
        // skips a route the origin already refused (learned verdict), and stays
        // on the proxy once this tune's direct attempt failed. DIRECT and PROXY
        // are pins: they ignore both the verdict and the fallbacks.
        val canDirect = target.canBypassProxy()
        val direct = proxyFallback || transportUsesDirect(
            mode = transportMode,
            verdict = if (transportMode == TransportMode.AUTO) {
                app?.container?.transportMemory?.verdict(transportOrigin)
            } else {
                null
            },
            canDirect = canDirect,
            autoDirectFailed = directFailed
        )
        val proxied = if (!direct &&
            (resolvedKind == StreamKind.HLS || isHlsUrl(resolvedUrl))
        ) {
            NativeStreamProxy.proxyHls(
                url = resolvedUrl,
                userAgent = settings.userAgentString,
                headers = playbackHeaders
            )
        } else {
            null
        }
        directRoute = proxied == null
        // The forced direct attempt is spent; a later reload follows the
        // normal mode/verdict/directFailed policy again.
        proxyFallback = false
        proxyStreamId = proxied?.streamId
        val sourceUrl = proxied?.url ?: resolvedUrl
        // A slow kind discovery or proxy rewrite must not overtake a newer zap.
        if (!isActive) return@LaunchedEffect
        // The live engine is retained across zaps: release the previous
        // channel's loader and buffered segments before preparing the new one,
        // so two sources never load side by side.
        if (isLive) {
            val previousUri = target.currentMediaItem
                ?.localConfiguration?.uri?.toString()
            if (previousUri != null && previousUri != sourceUrl) {
                Log.i(
                    "StreamLine:player",
                    "Live zap · same ${target.javaClass.simpleName}, new item"
                )
                runCatching { target.stop() }
            }
        }
        // A zap may carry another addon's headers, and the retained instance
        // keeps its data source factories: refresh them before the first
        // playlist read instead of waiting for the live-header effect.
        builtPlayer?.liveHeaders?.update(profile.userAgent, profile.headers)
        runCatching {
            val builder = MediaItem.Builder().setUri(sourceUrl)
            val mime = resolvedKind?.media3Mime() ?: adaptiveMimeType(sourceUrl)
            mime?.let { builder.setMimeType(it) }
            // Live HLS keeps an explicit target offset: Media3's default of
            // three target durations lands on the oldest edge of a short-window
            // channel, where the first hitch drops the playhead out of it.
            if (isLive && mime == MimeTypes.APPLICATION_M3U8) {
                builder.setLiveConfiguration(LIVE_ITEM_CONFIGURATION)
            }
            val subtitleConfigs = buildList {
                val seen = mutableSetOf<String>()
                media.subtitles.forEach { subtitle ->
                    if (seen.add(subtitle.url)) add(subtitleConfiguration(subtitle))
                }
                if (onlineSubtitlesOn) {
                    val english = addonSubtitles
                        .filter { it.lang.startsWith("en", ignoreCase = true) }
                        .ifEmpty { addonSubtitles }
                    val chosen = english.firstOrNull { it.url == preferredAddonSubtitle }
                        ?: english.firstOrNull()
                    addonSubtitles.forEach { subtitle ->
                        if (seen.add(subtitle.url)) {
                            add(subtitleConfiguration(subtitle, default = subtitle.url == chosen?.url))
                        }
                    }
                }
                externalSubtitleUri?.let { uri ->
                    add(
                        MediaItem.SubtitleConfiguration.Builder(uri)
                            .setMimeType(externalSubtitleMime)
                            .setLanguage("und")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                }
            }
            if (subtitleConfigs.isNotEmpty()) {
                builder.setSubtitleConfigurations(subtitleConfigs)
            }
            target.setMediaItem(builder.build())
            target.prepare()
            val override = startOverrideMs
            if (override != null) startOverrideApplied = true
            startOverrideMs = null
            // A rebuild-class setting replaced the player; resume VOD where the
            // old instance stopped instead of restarting the film at zero.
            val carried = if (!isLive) carryOverPositionMs else 0L
            carryOverPositionMs = 0L
            val planSeek =
                if (!startOverrideApplied && optionIndex == 0) plan?.startPositionMs ?: 0L else 0L
            val pending = pendingSeekMs
            val seek = pending ?: override ?: carried.takeIf { it > 0 } ?: planSeek
            pendingSeekMs = null
            // An explicit resume point (retry, failover, native fallback,
            // manual reload) is honoured even a few seconds in; only a plan
            // start or a carried playhead skips the sub-5 s floor.
            if (pending != null || override != null || seek > 5_000) target.seekTo(seek)
            target.playWhenReady = true
            // The player is built with text tracks disabled. Every reload has to
            // re-assert the current choice, otherwise a side-loaded subtitle is
            // attached to the media item but the text renderer never starts, so
            // the cue file is never fetched.
            val textParams = target.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, dropSubtitles)
            if (!dropSubtitles) {
                textPick?.let { pick ->
                    tracks?.let { savedTrackOverride(it, C.TRACK_TYPE_TEXT, pick) }
                        ?.let { textParams.setOverrideForType(it) }
                }
            }
            target.trackSelectionParameters = textParams.build()
        }.onFailure {
            nativeError = PlaybackFailure(
                kind = FailureKind.UNKNOWN,
                title = "Player error",
                detail = it.message ?: "The stream could not be opened.",
                action = "Reload, or choose another source."
            )
        }
    }

    // A native engine can decode audio while never creating a video output,
    // which leaves sound over a black screen. Rebuild the same source on the
    // built-in player, which owns its decoder surface, and keep the playhead.
    LaunchedEffect(playerOrNull, playbackUrl) {
        val target = playerOrNull as? NativeEnginePlayer ?: return@LaunchedEffect
        while (isActive) {
            delay(1_000)
            if (!target.audioOnlyStalled()) continue
            Log.w(
                "StreamLine:player",
                "${target.javaClass.simpleName} played audio without video · " +
                    "switching to the built-in player"
            )
            pendingSeekMs = target.currentPosition.takeIf { it > 2_000 }
            nativeFallback = true
            return@LaunchedEffect
        }
    }

    // The retry policy can keep a tune buffering for minutes when the
    // provider accepts the request and then sends nothing. Give every tune a
    // hard deadline: on the AUTO direct route, try the proxy once, then surface
    // the usual "Stream interrupted" panel instead of an endless spinner. Live
    // gets the tighter window; a film may legitimately wait on a long manifest
    // chain, so it gets a few more seconds.
    LaunchedEffect(isLive, playerOrNull, playbackUrl, reloadToken) {
        val target = playerOrNull ?: return@LaunchedEffect
        // Native engines probe every rendition through the loopback proxy
        // before the first frame, so they get a wider tune window than Media3.
        val tuneDeadlineMs = when {
            target is NativeEnginePlayer -> NATIVE_TUNE_WATCHDOG_MS
            isLive -> LIVE_TUNE_WATCHDOG_MS
            else -> VOD_TUNE_WATCHDOG_MS
        }
        val watchdog = TuneWatchdog(SystemClock.elapsedRealtime(), tuneDeadlineMs)
        val watched = target.asWatchdogPlayer()
        while (isActive) {
            val decision = watchdog.tick(
                watched,
                SystemClock.elapsedRealtime(),
                nativeError != null,
                canTryProxy = proxyCapable && directRoute && !directFailed &&
                    transportMode == TransportMode.AUTO,
                isHls = proxyCapable
            )
            when (decision) {
                TuneDecision.Ready -> {
                    // The proxy route reached READY after a direct denial: that
                    // is the evidence the proxy actually rescues this origin.
                    if (!directRoute && pendingProxyVerdict) {
                        app?.container?.transportMemory?.record(
                            transportOrigin, TransportVerdict.PROXY
                        )
                        pendingProxyVerdict = false
                    }
                    return@LaunchedEffect
                }
                TuneDecision.Aborted -> return@LaunchedEffect
                TuneDecision.Continue -> delay(500)
                TuneDecision.TryProxy, TuneDecision.Failed -> {
                    runCatching { target.stop() }
                    if (decision == TuneDecision.TryProxy) {
                        directFailed = true
                        reconnectNotice = "Direct tune timed out · retrying through the proxy"
                        reloadToken++
                    } else {
                        nativeError = PlaybackFailure(
                            kind = FailureKind.NETWORK,
                            title = if (isLive) "Channel stalled" else "Stream stalled",
                            detail = "No video arrived from the provider within " +
                                "${tuneDeadlineMs / 1000} seconds.",
                            action = "Reload, or choose another source."
                        )
                        failoverNote = null
                        reconnectNotice = null
                        retryPlanMs = null
                    }
                    return@LaunchedEffect
                }
            }
        }
    }

    // Mid-stream watchdog: once the stream has actually played, watch the
    // playhead instead of the buffering state. A dead connection or frozen
    // loader keeps Media3 retrying for over a minute; this catches it in 15 s
    // while still ignoring user pauses, scrubs and audio-focus suppression.
    // Reloads re-key the effect so a fresh prepare gets its own window.
    LaunchedEffect(playerOrNull, playbackUrl, reloadToken) {
        val target = playerOrNull ?: return@LaunchedEffect
        val watchdog = StallWatchdog()
        val watched = target.asWatchdogPlayer()
        while (isActive) {
            if (target.playerError != null || nativeError != null) return@LaunchedEffect
            // A proxied stream that needed the browser-mask retry proves the
            // origin is UA-gated, even though direct playback never failed here
            // (the verdict was learned earlier, or the mask only fired on a
            // later segment). Teaching it keeps the next tune off the direct
            // route. The store dedupes, so this is a no-op after the first hit.
            val activeProxy = proxyStreamId
            if (activeProxy != null && transportMode == TransportMode.AUTO &&
                NativeStreamProxy.requiresBrowserMask(activeProxy)
            ) {
                app?.container?.transportMemory?.record(transportOrigin, TransportVerdict.PROXY)
            }
            when (watchdog.tick(watched, SystemClock.elapsedRealtime(), isScrubbing)) {
                StallDecision.Ended -> return@LaunchedEffect
                StallDecision.Stalled -> {
                    recoverStalledPlayback(
                        title = if (isLive) "Channel stalled" else "Playback stalled",
                        detail = "Nothing played for " +
                            "${PLAYBACK_STALL_WATCHDOG_MS / 1000} seconds."
                    )
                    return@LaunchedEffect
                }
                StallDecision.Idle, StallDecision.Progress -> delay(PLAYBACK_STALL_POLL_MS)
            }
        }
    }

    // A direct route that plays cleanly for a sustained stretch is evidence the
    // origin serves this player as-is, so AUTO remembers it and stops paying
    // for the proxy path's extra hop. Pinned modes teach nothing: a forced
    // DIRECT run says the viewer chose it, not that AUTO should keep doing it.
    // The running stream is never interrupted.
    LaunchedEffect(directRoute, isPlaying, playbackUrl, transportMode, transportOrigin) {
        if (!directRoute || !isPlaying) return@LaunchedEffect
        if (transportMode != TransportMode.AUTO || transportOrigin == null) return@LaunchedEffect
        if (originVerdict == TransportVerdict.DIRECT) return@LaunchedEffect
        delay(TRANSPORT_LEARN_MS)
        if (directRoute && isPlaying) {
            app?.container?.transportMemory?.record(transportOrigin, TransportVerdict.DIRECT)
        }
    }

    DisposableEffect(playerOrNull, playbackUrl) {
        val listener = NativeStreamProxy.UpstreamFailureListener { id -> proxyTrips.tryEmit(id) }
        NativeStreamProxy.addUpstreamFailureListener(listener)
        onDispose { NativeStreamProxy.removeUpstreamFailureListener(listener) }
    }

    LaunchedEffect(playerOrNull, playbackUrl) {
        proxyTrips.collect { id ->
            if (id == proxyStreamId) recoverFromProxyFailure()
        }
    }

    LaunchedEffect(playerOrNull, profile.abr) {
        val target = playerOrNull ?: return@LaunchedEffect
        target.trackSelectionParameters = target.trackSelectionParameters.buildUpon()
            .setForceHighestSupportedBitrate(!profile.abr)
            .build()
    }

    // Video ceiling for every source, live and VOD alike: the quality setting's
    // tier and everything below it, with ABR free to step further down. The
    // exclusion setting is a hard ceiling: it hides everything above its tier.
    // A decoder that drops frames pulls this source down to 720p for the rest
    // of playback. Rebuilt on the running player, never by a teardown.
    LaunchedEffect(
        playerOrNull,
        qualityCap,
        profile.maxResolution,
        profile.bandwidthCap,
        droppedFrameCapHeight
    ) {
        val target = playerOrNull ?: return@LaunchedEffect
        val cap = profile.maxResolution
        val maxHeight = listOfNotNull(
            cap.maxHeight.takeIf { it > 0 },
            qualityCap.takeIf { it > 0 },
            droppedFrameCapHeight.takeIf { it > 0 }
        ).minOrNull() ?: Int.MAX_VALUE
        val builder = target.trackSelectionParameters.buildUpon()
            .applyBandwidthCap(profile.bandwidthCap)
        builder.setMaxVideoSize(cap.maxWidth, maxHeight)
        target.trackSelectionParameters = builder.build()
    }

    LaunchedEffect(playerOrNull, current?.url) {
        while (isActive) {
            val target = playerOrNull ?: break
            if (!isScrubbing) {
                val live = target.currentPosition.coerceAtLeast(0)
                positionMs = live
                if (live > 0) lastKnownPositionMs = live
                bufferedMs = target.bufferedPosition.coerceAtLeast(0)
            }
            val duration = target.duration
            if (duration > 0) durationMs = duration
            if (isLive) liveNowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    // The Live tab resolves the same guides in this process, so the client
    // cache makes this a no-op fetch when the user tunes in from there.
    LaunchedEffect(repository, isLive) {
        val repo = repository ?: return@LaunchedEffect
        if (!isLive) return@LaunchedEffect
        liveGuide = runCatching { repo.iptvEpg(force = false).guide }.getOrDefault(EpgGuide.EMPTY)
    }

    val liveChannel = channels.getOrNull(liveIndex)
    val liveProgram: LiveProgramInfo? = if (isLive && liveChannel != null) {
        liveGuide.nowNext(liveChannel.epgId, liveChannel.name, liveNowMs)?.let { nowNext ->
            val now = nowNext.now
            LiveProgramInfo(
                title = now?.title.orEmpty(),
                startMs = now?.startMs ?: 0L,
                stopMs = now?.stopMs ?: 0L,
                nextTitle = nowNext.next?.title,
                nextStartMs = nowNext.next?.startMs
            )
        }
    } else {
        null
    }

    // A progress write serializes the whole library state, so a 4 s cadence
    // rewrote it hundreds of times per film. Half a minute keeps resume
    // accurate; pause and dispose cover the moments a viewer actually leaves.
    LaunchedEffect(playerOrNull, plan?.progressKey) {
        val target = playerOrNull ?: return@LaunchedEffect
        val activePlan = plan ?: return@LaunchedEffect
        try {
            while (true) {
                delay(30_000)
                persistProgress(repository, activePlan, activeEpisodeTitle, target)
            }
        } finally {
            runCatching { persistProgress(repository, activePlan, activeEpisodeTitle, target) }
        }
    }

    fun nativeMediaKey(keyCode: Int): Boolean {
        val active = playerOrNull ?: return false
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (active.isPlaying) active.pause() else active.play()
                interaction++
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                active.play()
                interaction++
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                active.pause()
                interaction++
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val limit = if (active.duration > 0) active.duration else Long.MAX_VALUE
                active.seekTo((active.currentPosition + 10_000).coerceAtMost(limit))
                interaction++
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                active.seekTo((active.currentPosition - 10_000).coerceAtLeast(0))
                interaction++
                true
            }
            else -> false
        }
    }

    LaunchedEffect(optionIndex, reloadToken) {
        nativeError = null
    }

    // Keyed on the backoff value, not on reloadToken: an unrelated reload
    // (proxy bypass, an OSD action) must not restart the wait, and the plan is
    // scoped to the source so a tuned channel never fires a stale retry.
    LaunchedEffect(retryPlanMs, playbackUrl) {
        val waitMs = retryPlanMs ?: return@LaunchedEffect
        delay(waitMs)
        retryPlanMs = null
        reconnectNotice = null
        nativeError = null
        reloadToken++
    }

    // Only a sustained session clears the backoff; a stream that plays for a
    // few seconds and drops again must keep escalating instead of resetting.
    // Keyed on the stream and the playing state, not on reloadToken: a reload
    // that keeps playing is not a new session, and re-keying it there would
    // restart this clock on every rebuffer.
    // The live-edge budget is reset on the same clock: a channel that holds
    // the edge for 90 s has proven it can stay there.
    LaunchedEffect(isPlaying, optionIndex, playbackUrl) {
        if (!isPlaying || (reconnectAttempt == 0 && liveEdgeRecoveries == 0)) {
            return@LaunchedEffect
        }
        delay(90_000L)
        reconnectAttempt = 0
        liveEdgeRecoveries = 0
    }

    LaunchedEffect(nativeError, options.size, searchInProgress) {
        if (nativeError == null) return@LaunchedEffect
        if (searchInProgress) {
            // Still probing sources: do not declare every source dead yet.
            failoverNote = "Searching more sources…"
            return@LaunchedEffect
        }
        val failedAt = optionIndex
        delay(400)
        if (optionIndex != failedAt) return@LaunchedEffect
        if (options.size <= 1) {
            failoverNote = null
            return@LaunchedEffect
        }
        if (attemptFailover()) {
            nativeError = null
        } else {
            failoverNote = "All ${options.size} sources failed"
        }
    }

    val playFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val skipFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val unlockFocus = remember { FocusRequester() }

    val seekEnabled = durationMs > 0 && !locked && gesturesEnabled

    LaunchedEffect(osdVisible, osdFocusTarget, locked, current != null) {
        // Locked is checked before OSD visibility: the unlock pill is composed even
        // when the OSD is hidden, so the root must never take focus while locked or
        // the remote dead-ends. The pill is composed on demand, so retry a few times
        // before giving up on slow boxes.
        if (locked) {
            repeat(FOCUS_ATTEMPTS) {
                delay(FOCUS_RETRY_MS)
                runCatching { unlockFocus.requestFocus() }
            }
            return@LaunchedEffect
        }
        if (!osdVisible) {
            runCatching { rootFocus.requestFocus() }
            return@LaunchedEffect
        }
        val requester = when {
            osdFocusTarget == OsdFocus.TIMELINE && seekEnabled -> seekFocus
            osdFocusTarget == OsdFocus.SKIP -> skipFocus
            osdFocusTarget == OsdFocus.BACK -> backFocus
            else -> playFocus
        }
        // The OSD is composed on demand and animated in; on a slow TV box the
        // first requests can land before the button is focusable, so keep asking
        // until an OSD control reports focus (or the user moves focus elsewhere).
        repeat(FOCUS_ATTEMPTS) {
            runCatching { requester.requestFocus() }
            delay(FOCUS_RETRY_MS)
            if (dockFocused || timelineFocused || skipFocused || backFocused) return@LaunchedEffect
        }
    }

    fun seekBy(deltaMs: Long) {
        val active = playerOrNull ?: return
        val limit = active.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (active.currentPosition + deltaMs).coerceIn(0L, limit)
        // Seeking never pauses or rebuilds the player: the picture keeps
        // rendering from the new position so the jump can be judged live.
        active.seekTo(target)
        positionMs = target
        notePlayhead(target)
        interaction++
    }

    var trickplayText by remember { mutableStateOf<String?>(null) }
    var trickplayTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(trickplayTick) {
        if (trickplayText == null) return@LaunchedEffect
        delay(900)
        trickplayText = null
    }
    var skipNotice by remember { mutableStateOf<String?>(null) }
    var skipNoticeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(skipNoticeTick) {
        if (skipNotice == null) return@LaunchedEffect
        delay(3_500)
        skipNotice = null
    }
    fun showTrickplay(deltaMs: Long) {
        val active = playerOrNull ?: return
        val prefix = if (deltaMs >= 0) "+" else "−"
        trickplayText = "$prefix${kotlin.math.abs(deltaMs) / 1000} s  ·  ${formatTime(active.currentPosition)}"
        trickplayTick++
    }

    fun tuneChannel(delta: Int) {
        if (!isLive || delta == 0) return
        val target = liveIndexAfter(liveIndex, delta)
        if (target == liveIndex) {
            skipNotice = "Dead channels skipped · no live channel " +
                if (delta > 0) "after this one" else "before this one"
            skipNoticeTick++
            interaction++
            return
        }
        skipNotice = null
        liveIndex = target
        optionIndex = 0
        failedIndices = emptySet()
        failoverNote = null
        nativeError = null
        pendingSeekMs = null
        lastKnownPositionMs = 0L
        interaction++
        channels.getOrNull(target)?.let { channel ->
            trickplayText = "CH ${target + 1}/${channels.size}  ·  ${channel.name}"
            trickplayTick++
        }
    }

    // Precise boundaries come from TheIntroDB. The duration heuristic is only
    // a stand-in for episodes the database has nothing on (offline, unknown
    // title); a titled episode with no intro segment is a cold open, so the
    // fallback stays off once an answer arrives.
    val skipOffer = remember(skipSegments, positionMs, durationMs, isLive) {
        if (isLive) null else skipSegments?.offerAt(positionMs, durationMs)
    }
    val fallbackIntroEndMs = remember(item?.key, segmentsResolved, durationMs, isLive) {
        when {
            isLive || item == null || !segmentsResolved || durationMs <= 0 -> 0L
            durationMs >= 12 * 60_000L -> 90_000L
            durationMs >= 6 * 60_000L -> 75_000L
            else -> 0L
        }
    }
    val fallbackIntroActive = skipSegments == null && fallbackIntroEndMs > 0 &&
        positionMs >= 12_000L && positionMs < fallbackIntroEndMs
    // While the next-up card is on screen the credits are already accounted for.
    val skipLabel = when {
        nextUpSlot != null -> null
        skipOffer != null -> skipOffer.kind.short
        fallbackIntroActive -> SkipKind.INTRO.short
        else -> null
    }
    val skipHint = when {
        nextUpSlot != null -> ""
        skipOffer != null -> skipOffer.kind.label
        fallbackIntroActive -> SkipKind.INTRO.label
        else -> ""
    }
    val skipTargetMs = skipOffer?.targetMs ?: fallbackIntroEndMs
    val nextHighlight = !isLive && nextSlot != null && durationMs > 0 &&
        (creditsStartMs?.let { positionMs >= it } == true ||
            (durationMs - positionMs) <= 90_000L)

    fun applySkip() {
        val target = skipTargetMs
        if (target > 0) {
            playerOrNull?.seekTo(target)
            positionMs = target
            notePlayhead(target)
        }
        // The offer disappears once the playhead passes it, so hand focus back
        // to the transport before that happens.
        skipFocused = false
        dockFocused = true
        osdFocusTarget = OsdFocus.PLAY
        runCatching { playFocus.requestFocus() }
        interaction++
    }

    // The offer expires on its own once the playhead leaves the segment; drop
    // focus back to the bar instead of leaving the remote on a hidden node.
    LaunchedEffect(skipLabel, skipFocused) {
        if (skipLabel != null || !skipFocused) return@LaunchedEffect
        skipFocused = false
        timelineFocused = true
        osdFocusTarget = OsdFocus.TIMELINE
        runCatching { seekFocus.requestFocus() }
    }

    val displayPosition = if (isScrubbing && durationMs > 0) {
        (scrubFraction * durationMs).toLong()
    } else {
        positionMs
    }

    val anyDialog = panel != null || showPicker || openWithOpen
    LaunchedEffect(osdVisible, interaction, locked, anyDialog, isScrubbing) {
        if (!osdVisible || locked || anyDialog || isScrubbing) return@LaunchedEffect
        delay(4_000)
        hideOsd()
    }

    val clockFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var clockText by remember { mutableStateOf(clockFormatter.format(Date())) }
    LaunchedEffect(Unit) {
        while (isActive) {
            clockText = clockFormatter.format(Date())
            delay(10_000)
        }
    }

    val headerTitleText = remember(
        item, activeSeason, activeEpisode, activeEpisodeTitle, fallbackTitle, directUrl, liveIndex, channels
    ) {
        when {
            isLive -> channels.getOrNull(liveIndex)?.name ?: fallbackTitle
            item == null -> fallbackTitle
            item.isSeries -> buildString {
                append(item.title)
                // Nothing was selected, so no slot may be named in the title.
                if (activeEpisode >= 1) {
                    append(" — S").append(activeSeason).append(" E").append(activeEpisode)
                    activeEpisodeTitle?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                }
            }
            else -> item.title
        }
    }
    val episodeRuntimeMinutes = remember(item, activeSeason, activeEpisode) {
        item?.seasons
            ?.firstOrNull { it.number == activeSeason }
            ?.episodes
            ?.firstOrNull { it.number == activeEpisode }
            ?.runtimeMinutes
    }
    val lengthLabel = when {
        durationMs > 0 -> formatDurationLabel(durationMs)
        item == null -> "LIVE"
        episodeRuntimeMinutes != null -> formatDurationLabel(episodeRuntimeMinutes * 60_000L)
        item.runtimeMinutes != null -> formatDurationLabel(item.runtimeMinutes * 60_000L)
        else -> null
    }
    val headerMeta = listOfNotNull(
        lengthLabel,
        current?.name?.takeIf { it.isNotBlank() }
    ).joinToString("  •  ")

    val searchedCount = searchSteps.count { it.phase != StreamSearchPhase.SEARCHING }
    val searchStatus = if (searchInProgress) {
        "Searching playable sources · $searchedCount/${searchSteps.size}"
    } else null

    val audioChoices = remember(tracks) {
        buildTrackChoices(tracks, C.TRACK_TYPE_AUDIO, ::audioTrackLabel)
    }
    val textChoices = remember(tracks) {
        buildTrackChoices(tracks, C.TRACK_TYPE_TEXT, ::textTrackLabel)
    }

    var infoRows by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(panel, playbackUrl, decoderMode, durationMs, tracks) {
        if (panel != OsdPanel.INFO) return@LaunchedEffect
        infoRows = null
        infoRows = loadStreamInfo(
            item = item,
            season = activeSeason,
            episode = activeEpisode,
            episodeTitle = activeEpisodeTitle,
            source = current,
            fileName = fileName,
            durationMs = durationMs,
            tracks = tracks,
            audioAudit = audioAudit,
            decoder = decoderMode,
            abr = profile.abr
        )
    }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // SAF grant: keep the picked subtitle readable for the whole session
            // (and across a player rebuild after a decoder cycle) without any
            // storage permission.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val name = queryDisplayName(context, uri) ?: "Subtitles"
            externalSubtitleUri = uri
            externalSubtitleName = name
            externalSubtitleMime = when {
                name.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                name.endsWith(".ssa", ignoreCase = true) || name.endsWith(".ass", ignoreCase = true) ->
                    "text/x-ssa"
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            dropSubtitles = false
            panel = null
            reloadToken++
            interaction++
        }
    }

    fun selectAudio(choice: TrackChoice) {
        val target = playerOrNull ?: return
        audioPick = SavedTrackPick(choice.label, choice.language)
        target.trackSelectionParameters = target.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(choice.group, choice.index))
            .build()
        panel = null
        interaction++
    }

    fun autoAudio() {
        val target = playerOrNull ?: return
        audioPick = null
        target.trackSelectionParameters = target.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        panel = null
        interaction++
    }

    fun selectText(choice: TrackChoice?) {
        val target = playerOrNull ?: return
        val builder = target.trackSelectionParameters.buildUpon()
        if (choice == null) {
            textPick = null
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            dropSubtitles = true
        } else {
            textPick = SavedTrackPick(choice.label, choice.language)
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(choice.group, choice.index))
            dropSubtitles = false
        }
        target.trackSelectionParameters = builder.build()
        panel = null
        interaction++
    }

    fun loadOnlineSubtitles(pick: AddonSubtitle?) {
        onlineSubtitlesOn = true
        preferredAddonSubtitle = pick?.url
        dropSubtitles = false
        pendingSeekMs = if (isLive) null else resumePosition().takeIf { it > 1_000 }
        reloadToken++
        panel = null
        interaction++
    }

    /**
     * The captions switch turns itself on in this order: an embedded (or VTT)
     * track the viewer can actually show, then the English addon list, then the
     * file they loaded from disk. Only a stream with none of those stays off.
     */
    fun enableCaptions() {
        val embedded = textChoices
        val preferred = embedded.firstOrNull {
            preferredSubtitleLanguage(activeProfile)
                ?.let { tag -> it.language.startsWith(tag, ignoreCase = true) } == true
        } ?: embedded.firstOrNull { it.language.startsWith("en", ignoreCase = true) }
            ?: embedded.firstOrNull()
        if (preferred != null) {
            selectText(preferred)
            return
        }
        if (addonSubtitles.isNotEmpty()) {
            loadOnlineSubtitles(pick = null)
            return
        }
        if (externalSubtitleUri != null) {
            dropSubtitles = false
            pendingSeekMs = if (isLive) null else resumePosition().takeIf { it > 1_000 }
            reloadToken++
            panel = null
            interaction++
        }
    }

    // The window pauses the one player instance instead of closing it, so the
    // frame and position it holds survive whatever the viewer picks there.
    fun openOpenWith() {
        val target = current ?: return
        openWithPlayers = repository?.installedExternalPlayers().orEmpty()
        openWithTarget = target
        openWithError = null
        resumeAfterOpenWith = playerOrNull?.playWhenReady == true
        playerOrNull?.pause()
        hideOsd()
        openWithOpen = true
        interaction++
    }

    fun closeOpenWith(resume: Boolean) {
        openWithOpen = false
        openWithError = null
        if (resume && handoffLabel == null && resumeAfterOpenWith) playerOrNull?.play()
        // The window hid the OSD on the way in; bring it back so the resumed (or
        // still paused) frame has its controls and status line.
        wake(OsdFocus.PLAY)
    }

    // Streams that carry pipe headers, ride on a spoofed UA, or already needed
    // the on-device proxy cannot travel as a bare URL: VLC and mpv ignore the
    // `headers` extra, so the origin would deny them. Those hand-offs get the
    // loopback relay, which re-sends every request with the same headers while
    // this process stays alive. DASH/SMOOTH manifests are left direct — their
    // child URLs point back at the origin, where a relay cannot follow them.
    fun relayedHandoff(target: PlaybackOption): Boolean {
        val kind = target.streamKind ?: discoveredSource?.kind ?: StreamUrls.kindOf(target.url)
        if (kind == StreamKind.DASH || kind == StreamKind.SMOOTH) return false
        return target.headers.isNotEmpty() ||
            settings.playerUserAgent != PlayerUaPreset.DEFAULT ||
            proxyStreamId != null
    }

    /**
     * Hands [target] to one app (or the chooser). The local player stays paused
     * but alive for the whole hand-off, so BACK from the external player lands
     * on the same frame instead of a destroyed activity. Returns false when no
     * installed activity accepted the stream.
     */
    fun handOffTo(target: PlaybackOption, packageName: String?): Boolean {
        val label = if (packageName == null) {
            "the system chooser"
        } else {
            openWithPlayers.firstOrNull { it.packageName == packageName }
                ?.label?.takeIf { it.isNotBlank() } ?: packageName
        }
        // Hand over the URL this player actually reads, not the short link.
        val sourceUrl = discoveredSource?.url ?: target.url
        val kind = target.streamKind ?: discoveredSource?.kind ?: StreamUrls.kindOf(sourceUrl)
        val relayUrl = if (relayedHandoff(target)) {
            NativeStreamProxy.proxyStream(
                url = sourceUrl,
                userAgent = settings.userAgentString,
                headers = target.headers
            )?.url
        } else {
            null
        }
        val ok = ExternalIntents.launchIn(
            context = context,
            packageName = packageName,
            url = relayUrl ?: sourceUrl,
            headers = target.headers,
            userAgent = settings.userAgentString,
            kind = kind
        )
        if (!ok) {
            openWithError =
                "Nothing on this device accepted the stream. Install a video player or copy the URL."
            externalMessage = "External hand-off unavailable · using built-in player"
            return false
        }
        openWithOpen = false
        openWithError = null
        handoffLabel = label
        handoffAtMs = SystemClock.elapsedRealtime()
        playerOrNull?.pause()
        externalMessage = if (relayUrl != null) {
            "Handing off to $label · relayed with the stream headers…"
        } else {
            "Handing off to $label…"
        }
        interaction++
        return true
    }

    // VOD default: with an external package chosen in Settings, a movie or
    // episode hands off as soon as its source is current. Live tuning and the
    // direct-URL preview stay built-in; the per-URL flag hands each picked
    // source off once, including a new episode or a pick from the list.
    var vodHandoffDone by remember(current?.url) { mutableStateOf(false) }
    LaunchedEffect(current?.url, settings.externalVodPlayer, isLive, directUrl) {
        val target = current ?: return@LaunchedEffect
        val packageName = settings.externalVodPlayer
        if (packageName.isBlank() || isLive || item == null || directUrl != null ||
            vodHandoffDone
        ) {
            return@LaunchedEffect
        }
        vodHandoffDone = true
        // The picker normally fills this; the auto path needs the label too.
        if (openWithPlayers.none { it.packageName == packageName }) {
            openWithPlayers = repository?.installedExternalPlayers().orEmpty()
        }
        handOffTo(target, packageName)
    }

    fun enterPip() {
        val host = activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            host.enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }

    LaunchedEffect(brightness) {
        activity?.window?.let { window ->
            val params = window.attributes
            params.screenBrightness = if (brightness >= 0.99f) -1f else brightness
            window.attributes = params
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { window ->
                val params = window.attributes
                params.screenBrightness = -1f
                window.attributes = params
            }
        }
    }

    LaunchedEffect(eqState, eqLevels, eqPreset) {
        val preset = WinampEqPreset.AllPresets.getOrElse(eqPreset) { WinampEqPreset.Cinema }
        eqState.update(
            FloatArray(eqLevels.size) { index ->
                val shifted = (eqLevels[index] + preset.preamp - EQ_NEUTRAL_LEVEL)
                    .coerceIn(0, EQ_MAX_LEVEL)
                winampLevelToDb(shifted)
            }
        )
    }
    // 0–100% attenuates the player output; 100–200% adds makeup gain for quiet
    // film mixes. Both run inside the sink's processor chain. Applying both
    // keeps the slider usable on TVs whose system volume step is too coarse.
    LaunchedEffect(playerOrNull, volumePercent) {
        playerOrNull?.volume = (volumePercent.coerceIn(0, 200) / 100f).coerceAtMost(1f)
    }
    LaunchedEffect(gainState, volumePercent, nightMode) {
        val gainDb = ((volumePercent - 100).coerceAtLeast(0)) / 10f
        gainState.update(if (nightMode) gainDb / 2f else gainDb)
    }

    val tintAlpha = (((1f - contrast) * 0.35f) + ((1f - saturation) * 0.25f)).coerceIn(0f, 0.55f)
    val captionStyle = captionStyle(subtitleBackground)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val keyCode = event.nativeKeyEvent.keyCode
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    // BACK while locked must not reach BackHandler: dispatching it
                    // drops key focus to the root Box and leaves the pill unfocused
                    // until another key. Consume it and keep the pill focused.
                    if (!locked) return@onPreviewKeyEvent false
                    interaction++
                    runCatching { unlockFocus.requestFocus() }
                    return@onPreviewKeyEvent true
                }
                if (nativeMediaKey(keyCode)) return@onPreviewKeyEvent true
                if (locked) {
                    interaction++
                    // If focus ever lands on the root while locked, any key must
                    // hand it back to the unlock pill instead of doing nothing.
                    runCatching { unlockFocus.requestFocus() }
                    return@onPreviewKeyEvent false
                }
                if (!osdVisible) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            // LEFT/RIGHT also retire the next-up pill; the seek
                            // itself still runs so the remote stays predictable.
                            if (nextUpSlot != null) dismissNextUp()
                            if (isLive) {
                                tuneChannel(-1)
                            } else {
                                seekBy(-10_000L)
                                showTrickplay(-10_000L)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (nextUpSlot != null) dismissNextUp()
                            if (isLive) {
                                tuneChannel(1)
                            } else {
                                seekBy(10_000L)
                                showTrickplay(10_000L)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            // OK on the clean surface is the pill's play-now
                            // action; without a pill it wakes the OSD as before.
                            // A failure overlay keeps OK for its own buttons.
                            val offer = nextUpSlot
                            if (offer != null && nativeError == null) {
                                playSlot(offer)
                            } else {
                                wake(OsdFocus.PLAY)
                            }
                            true
                        }
                        else -> {
                            wake(OsdFocus.PLAY)
                            true
                        }
                    }
                } else {
                    // Explicit zones: header (top), timeline, dock (bottom). Whatever the
                    // focused control is, UP and DOWN always move toward the neighbouring
                    // zone, so a remote never gets trapped inside the dock.
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            when {
                                dockFocused -> hideOsd()
                                // The skip pill floats over the timeline, so DOWN
                                // belongs back on the bar rather than the transport row.
                                skipFocused -> {
                                    skipFocused = false
                                    timelineFocused = true
                                    osdFocusTarget = OsdFocus.TIMELINE
                                    runCatching { seekFocus.requestFocus() }
                                }
                                else -> {
                                    dockFocused = true
                                    timelineFocused = false
                                    osdFocusTarget = OsdFocus.PLAY
                                    runCatching { playFocus.requestFocus() }
                                }
                            }
                            interaction++
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            when {
                                dockFocused -> {
                                    dockFocused = false
                                    if (seekEnabled) {
                                        osdFocusTarget = OsdFocus.TIMELINE
                                        runCatching { seekFocus.requestFocus() }
                                    } else {
                                        osdFocusTarget = OsdFocus.BACK
                                        runCatching { backFocus.requestFocus() }
                                    }
                                }
                                timelineFocused && skipLabel != null -> {
                                    skipFocused = false
                                    timelineFocused = false
                                    osdFocusTarget = OsdFocus.SKIP
                                    runCatching { skipFocus.requestFocus() }
                                }
                                skipFocused -> Unit
                                !backFocused -> {
                                    osdFocusTarget = OsdFocus.BACK
                                    runCatching { backFocus.requestFocus() }
                                }
                            }
                            interaction++
                            true
                        }
                        else -> {
                            if (skipFocused &&
                                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                    keyCode == KeyEvent.KEYCODE_ENTER)
                            ) {
                                applySkip()
                                interaction++
                                true
                            } else if (!dockFocused && !timelineFocused && !skipFocused && !backFocused) {
                                // Touch wakes the OSD without handing focus to it (touch
                                // mode refuses programmatic focus), so the first remote
                                // key after a tap lands on the transport row.
                                osdFocusTarget = OsdFocus.PLAY
                                runCatching { playFocus.requestFocus() }
                                interaction++
                                true
                            } else {
                                interaction++
                                false
                            }
                        }
                    }
                }
            }
    ) {
        when {
            current == null -> {
                StreamSearchOverlay(
                    steps = searchSteps,
                    inProgress = searchInProgress || !searchCompleted,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                val density = LocalDensity.current
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val nativeEngine = playerOrNull as? NativeEnginePlayer
                    val videoRatio = if (videoPixelSize.width > 0 && videoPixelSize.height > 0) {
                        videoPixelSize.width.toFloat() / videoPixelSize.height.toFloat()
                    } else {
                        null
                    }
                    val forcedRatio = aspectMode.ratio
                    // libVLC's opaque output stretches the picture to the
                    // surface bounds, so on the native engines "fit" has to be
                    // expressed as the surface size rather than a resize mode.
                    val surfaceRatio = forcedRatio ?: videoRatio?.takeIf {
                        nativeEngine != null &&
                            (aspectMode == AspectMode.FIT || aspectMode == AspectMode.CROP)
                    }
                    val cropToFill = nativeEngine != null && aspectMode == AspectMode.CROP
                    val frame: DpSize = when {
                        aspectMode == AspectMode.ORIGINAL && videoRatio != null -> {
                            val nativeW: Dp = with(density) { videoPixelSize.width.toDp() }
                            val nativeH: Dp = with(density) { videoPixelSize.height.toDp() }
                            val scale = minOf(
                                1f,
                                maxWidth.value / nativeW.value.coerceAtLeast(1f),
                                maxHeight.value / nativeH.value.coerceAtLeast(1f)
                            )
                            DpSize(nativeW * scale, nativeH * scale)
                        }
                        surfaceRatio != null -> {
                            val widthBound = if (cropToFill) {
                                maxWidth.value / maxHeight.value.coerceAtLeast(1f) > surfaceRatio
                            } else {
                                maxWidth.value / maxHeight.value.coerceAtLeast(1f) <= surfaceRatio
                            }
                            if (widthBound) {
                                DpSize(maxWidth, maxWidth / surfaceRatio)
                            } else {
                                DpSize(maxHeight * surfaceRatio, maxHeight)
                            }
                        }
                        else -> DpSize(maxWidth, maxHeight)
                    }
                    if (nativeEngine != null) {
                        // libVLC / libmpv paint into their own surfaces; PlayerView
                        // would only cover them with a shutter.
                        key(nativeEngine) {
                            AndroidView(
                                factory = { nativeEngine.renderView },
                                modifier = Modifier
                                    .size(frame.width, frame.height)
                            )
                        }
                    } else {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    // A seek or a source switch must not blank the
                                    // picture: hold the last frame until the new one
                                    // is decoded.
                                    setKeepContentOnPlayerReset(true)
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            update = { view ->
                                view.player = playerOrNull
                                view.resizeMode = aspectMode.resizeMode
                                view.subtitleView?.apply {
                                    setStyle(captionStyle)
                                    setFractionalTextSize(0.0533f * subtitleScale)
                                }
                            },
                            onRelease = { view ->
                                view.player = null
                            },
                            modifier = Modifier.size(frame.width, frame.height)
                        )
                    }
                }
            }
        }

        if (tintAlpha > 0.01f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = tintAlpha)))
        }

        if ((buffering || channelResolving) && current != null && !isScrubbing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cinema.Gold, strokeWidth = 3.dp)
            }
        }

        if (current != null && searchInProgress && !osdVisible) {
            SearchProgressPill(
                done = searchedCount,
                total = searchSteps.size,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 22.dp)
            )
        }

        if (!osdVisible && !locked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { wake() }
                    }
                    .pointerInput(isLive, liveIndex, channels.size) {
                        if (!isLive) return@pointerInput
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onHorizontalDrag = { _, amount -> dragged += amount },
                            onDragEnd = {
                                // Mouse / touch channel surfing: a firm swipe flips channels.
                                val threshold = 110.dp.toPx()
                                when {
                                    dragged <= -threshold -> tuneChannel(1)
                                    dragged >= threshold -> tuneChannel(-1)
                                }
                            }
                        )
                    }
            )
        }

        // The pill owns the clean video surface; with the OSD up, its transport
        // and NEXT button already cover the same hand-off.
        if (!osdVisible && !locked) {
            nextUpSlot?.let { slot ->
                NextUpCard(
                    slot = slot,
                    seconds = nextUpSeconds,
                    total = nextUpTotal,
                    onPlayNow = { playSlot(slot) },
                    onDismiss = { dismissNextUp() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = 108.dp)
                )
            }
        }

        val failure = nativeError
        if (failure != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.78f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.widthIn(max = 640.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        failure.title,
                        color = Cinema.Offline,
                        style = CinemaType.SectionTitle
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        failure.detail,
                        color = Cinema.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        failure.action,
                        color = Cinema.Gold,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (failoverNote != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            failoverNote!!,
                            color = Cinema.Gold,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvButton(
                            label = "RELOAD",
                            glyph = "⟳",
                            style = TvButtonStyle.PRIMARY,
                            onClick = {
                                failedIndices = emptySet()
                                failoverNote = null
                                nativeError = null
                                reconnectAttempt = 0
                                reconnectNotice = null
                                retryPlanMs = null
                                if (!isLive) {
                                    // RELOAD after a failure continues the title
                                    // instead of restarting it from zero.
                                    pendingSeekMs = resumePosition().takeIf { it > 5_000 }
                                }
                                reloadToken++
                            }
                        )
                        if (isLive && channels.size > 1) {
                            TvButton(
                                label = "SKIP TO NEXT",
                                glyph = "⏭",
                                style = TvButtonStyle.SECONDARY,
                                onClick = {
                                    failedIndices = emptySet()
                                    failoverNote = null
                                    nativeError = null
                                    reconnectAttempt = 0
                                    reconnectNotice = null
                                    retryPlanMs = null
                                    tuneChannel(1)
                                }
                            )
                        }
                        if (current != null) {
                            TvButton(
                                label = "OPEN WITH",
                                glyph = "⤴",
                                style = TvButtonStyle.SECONDARY,
                                onClick = { openOpenWith() }
                            )
                        }
                    }
                }
            }
        }

        val reconnectLabel = reconnectNotice
        if (failure == null && reconnectLabel != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 96.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    reconnectLabel,
                    color = Cinema.Gold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(CinemaDimens.PillRadius))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        AnimatedVisibility(
            // With no source loaded the search/empty overlay owns the screen;
            // a disabled transport dock only covers its explanation.
            visible = osdVisible && !locked && current != null,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(160)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { hideOsd() }
                        }
                )

                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                            )
                        )
                )

                PlayerTopHeader(
                    title = headerTitleText,
                    meta = headerMeta,
                    status = externalMessage ?: failoverNote ?: skipNotice
                        ?: searchStatus.takeIf { current != null },
                    clock = clockText,
                    backFocus = backFocus,
                    onBack = onExit,
                    onBackFocusChange = { focused ->
                        backFocused = focused
                        if (focused) {
                            dockFocused = false
                            timelineFocused = false
                        }
                        interaction++
                    },
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                PlayerDock(
                    positionMs = displayPosition,
                    bufferedMs = bufferedMs,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    controlsEnabled = playerOrNull != null,
                    seekEnabled = seekEnabled,
                    previousEnabled = if (isLive) liveIndex > 0 else previousSlot != null,
                    nextEnabled = if (isLive) liveIndex < channels.lastIndex else nextSlot != null,
                    aspectLabel = aspectMode.label,
                    aspectActive = aspectMode != AspectMode.FIT,
                    qualityLabel = qualityLabel,
                    qualityVisible = qualityVisible,
                    subtitlesOn = !dropSubtitles,
                    decoderLabel = decoderMode.label,
                    skipLabel = skipLabel,
                    skipHint = skipHint,
                    nextHighlight = nextHighlight,
                    previousLabel = if (isLive) "Previous channel" else "Previous episode",
                    nextLabel = if (isLive) "Next channel" else "Next episode",
                    playFocus = playFocus,
                    seekFocus = seekFocus,
                    skipFocus = skipFocus,
                    onSeekFocusChange = { focused ->
                        timelineFocused = focused
                        if (focused) {
                            dockFocused = false
                            skipFocused = false
                            backFocused = false
                        }
                    },
                    onSkipFocusChange = { focused ->
                        skipFocused = focused
                        if (focused) {
                            dockFocused = false
                            timelineFocused = false
                            backFocused = false
                        }
                    },
                    onDockFocusChange = {
                        dockFocused = true
                        backFocused = false
                        skipFocused = false
                        timelineFocused = false
                        interaction++
                    },
                    onScrubStart = {
                        wasPlayingBeforeScrub = playerOrNull?.isPlaying == true
                        isScrubbing = true
                        lastScrubSeekAt = 0L
                        // Only the playhead is held; the surface stays attached
                        // so the frames at the touched position keep rendering.
                        playerOrNull?.pause()
                        interaction++
                    },
                    onScrub = { fraction ->
                        scrubFraction = fraction
                        val active = playerOrNull
                        val duration = durationMs
                        if (active != null && duration > 0) {
                            val target = (fraction * duration).toLong()
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastScrubSeekAt >= SCRUB_PREVIEW_INTERVAL_MS) {
                                lastScrubSeekAt = now
                                active.seekTo(target)
                                positionMs = target
                                notePlayhead(target)
                            }
                        }
                    },
                    onScrubEnd = { fraction ->
                        val duration = durationMs
                        val active = playerOrNull
                        if (active != null && duration > 0) {
                            val target = (fraction * duration).toLong()
                            active.seekTo(target)
                            positionMs = target
                            notePlayhead(target)
                        }
                        isScrubbing = false
                        if (wasPlayingBeforeScrub) active?.play()
                        interaction++
                    },
                    onSeekRelative = { delta -> seekBy(delta) },
                    onPlayPause = {
                        playerOrNull?.let { p ->
                            if (p.isPlaying) p.pause() else p.play()
                        }
                        interaction++
                    },
                    onPrevious = {
                        if (isLive) tuneChannel(-1) else previousSlot?.let(::playSlot)
                    },
                    onNext = {
                        if (isLive) tuneChannel(1) else nextSlot?.let(::playSlot)
                    },
                    onRewind = { seekBy(-10_000L) },
                    onForward = { seekBy(10_000L) },
                    onSkipSegment = { applySkip() },
                    onCycleAspect = { cycleAspect() },
                    onSources = {
                        showPicker = true
                        interaction++
                    },
                    onQuality = {
                        panel = OsdPanel.QUALITY
                        interaction++
                    },
                    onSubtitles = { panel = OsdPanel.SUBTITLES },
                    onCycleDecoder = { cycleDecoder() },
                    onExternal = { openOpenWith() },
                    onOverflow = { panel = OsdPanel.OVERFLOW },
                    isLive = isLive,
                    liveProgram = liveProgram,
                    liveChannelName = liveChannel?.name.orEmpty(),
                    nowMs = liveNowMs,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        trickplayText?.takeIf { !osdVisible }?.let { hint ->
            Text(
                text = hint,
                color = Cinema.Gold,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 56.dp)
                    .glassSurface(
                        shape = RoundedCornerShape(CinemaDimens.PillRadius),
                        base = Glass.Sheet,
                        sheen = 0.5f
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }

        if (locked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .pointerInput(Unit) {
                        detectTapGestures { osdVisible = true }
                    }
            )
            UnlockButton(
                focusRequester = unlockFocus,
                onUnlock = {
                    locked = false
                    osdVisible = true
                    interaction++
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(28.dp)
            )
        }
    }

    val activePanel = panel
    val overflowFirstFocus = remember { FocusRequester() }
    val infoFirstFocus = remember { FocusRequester() }
    // The OSD used to stay up behind the dialog. The right-hand sheet covers
    // the player clock, which then showed through underneath its CLOSE button,
    // so only the sheet steps the controls out; closing anything hidden brings
    // them straight back.
    LaunchedEffect(activePanel) {
        if (activePanel == OsdPanel.OVERFLOW) hideOsd()
    }
    if (activePanel != null) {
        OsdPanelDialog(
            title = when (activePanel) {
                OsdPanel.AUDIO -> "Audio tracks"
                OsdPanel.SUBTITLES -> "Subtitles & captions"
                OsdPanel.OVERFLOW -> "Playback options"
                OsdPanel.TOOLS -> "Visuals & audio"
                OsdPanel.TOGGLES -> "Disable / override"
                OsdPanel.QUALITY -> "Stream quality"
                OsdPanel.TRANSPORT -> "Stream route"
                OsdPanel.INFO -> "Stream info"
                OsdPanel.STATS -> "Playback stats"
            },
            onDismiss = {
                panel = null
                if (!osdVisible) wake(OsdFocus.PLAY)
            },
            sideSheet = activePanel == OsdPanel.OVERFLOW,
            initialFocus = when (activePanel) {
                OsdPanel.OVERFLOW -> overflowFirstFocus
                OsdPanel.INFO -> infoFirstFocus
                else -> null
            }
        ) {
            when (activePanel) {
                OsdPanel.AUDIO -> {
                    Chip(
                        label = "Auto · ABR preferred",
                        selected = audioChoices.none { it.selected },
                        onClick = { autoAudio() }
                    )
                    audioChoices.forEach { choice ->
                        Chip(
                            label = choice.label + if (choice.selected) "  ·  ON" else "",
                            selected = choice.selected,
                            onClick = { selectAudio(choice) }
                        )
                    }
                    if (audioChoices.isEmpty()) {
                        Text("No switchable audio tracks in this stream.", color = Cinema.TextMuted, fontSize = 12.sp)
                    }
                }
                OsdPanel.SUBTITLES -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(
                            label = "Off",
                            selected = dropSubtitles,
                            onClick = { selectText(null) }
                        )
                        Chip(
                            label = "ON · auto",
                            selected = !dropSubtitles && textChoices.none { it.selected },
                            onClick = { enableCaptions() }
                        )
                    }
                    textChoices.forEach { choice ->
                        Chip(
                            label = choice.label + if (choice.selected) "  ·  ON" else "",
                            selected = choice.selected && !dropSubtitles,
                            onClick = { selectText(choice) }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    TvButton(
                        label = if (externalSubtitleName != null) {
                            "REPLACE · ${externalSubtitleName!!.take(26)}"
                        } else {
                            "LOAD .SRT / .VTT FILE"
                        },
                        glyph = "＋",
                        style = TvButtonStyle.SECONDARY,
                        compact = true,
                        onClick = {
                            subtitlePicker.launch(
                                arrayOf(
                                    MimeTypes.APPLICATION_SUBRIP,
                                    MimeTypes.TEXT_VTT,
                                    "text/x-ssa",
                                    "text/plain",
                                    "*/*"
                                )
                            )
                        }
                    )
                    val englishAddons = remember(addonSubtitles) {
                        addonSubtitles
                            .filter { it.lang.startsWith("en", ignoreCase = true) }
                            .ifEmpty { addonSubtitles }
                    }
                    if (englishAddons.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        TvButton(
                            label = if (onlineSubtitlesOn) {
                                "ENGLISH SUBTITLES · ON (${englishAddons.size})"
                            } else {
                                "LOAD ${englishAddons.size} ENGLISH SUBTITLES"
                            },
                            glyph = "☁",
                            style = if (onlineSubtitlesOn) TvButtonStyle.PRIMARY else TvButtonStyle.SECONDARY,
                            compact = true,
                            onClick = { loadOnlineSubtitles(pick = null) }
                        )
                        // Every English entry from the addons is a one-press
                        // choice once loaded; picking one re-prepares the same
                        // source with that track as the default.
                        englishAddons.take(6).forEach { subtitle ->
                            Chip(
                                label = subtitle.label?.take(34)
                                    ?: "English · ${subtitle.url.substringAfterLast('/').take(24)}",
                                selected = onlineSubtitlesOn && preferredAddonSubtitle == subtitle.url,
                                onClick = { loadOnlineSubtitles(pick = subtitle) }
                            )
                        }
                    }
                    if (textChoices.isEmpty() && externalSubtitleName == null && addonSubtitles.isEmpty()) {
                        Text(
                            "This stream carries no embedded subtitle tracks. Load a local file to overlay subtitles.",
                            color = Cinema.TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    SectionRow("Caption size")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(
                            label = "A",
                            selected = subtitleScale == 0.85f,
                            onClick = { setSubtitleScale(0.85f) }
                        )
                        Chip(
                            label = "A·",
                            selected = subtitleScale == 1f,
                            onClick = { setSubtitleScale(1f) }
                        )
                        Chip(
                            label = "A+",
                            selected = subtitleScale == 1.2f,
                            onClick = { setSubtitleScale(1.2f) }
                        )
                    }
                    SectionRow("Caption backdrop")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(
                            label = "No BG",
                            selected = subtitleBackground == 0,
                            onClick = { setSubtitleBackdrop(0) }
                        )
                        Chip(
                            label = "Dim BG",
                            selected = subtitleBackground == 1,
                            onClick = { setSubtitleBackdrop(1) }
                        )
                        Chip(
                            label = "Black BG",
                            selected = subtitleBackground == 2,
                            onClick = { setSubtitleBackdrop(2) }
                        )
                    }
                }
                OsdPanel.OVERFLOW -> {
                    // One rail, ordered by what people change mid-film: tracks
                    // and loudness first, then the stream route, then the
                    // diagnostics and session controls. Stereo downmix moved
                    // here when the dock took over the external hand-off.
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        OsdMenuRow(
                            glyph = PlayerGlyph.SOURCES,
                            label = "Playback source",
                            value = current?.name ?: "Auto",
                            focusRequester = overflowFirstFocus,
                            compact = true,
                            onClick = {
                                panel = null
                                showPicker = true
                            }
                        )
                        OsdMenuRow(
                            glyph = PlayerGlyph.AUDIO,
                            label = "Audio track",
                            value = audioChoices.firstOrNull { it.selected }?.label ?: "Auto",
                            compact = true,
                            onClick = { panel = OsdPanel.AUDIO }
                        )
                        OsdMenuRow(
                            glyph = PlayerGlyph.BOOST,
                            label = "Volume / equalizer",
                            value = "${volumePercent}% · " + if (eqCustom) {
                                "Custom"
                            } else {
                                WinampEqPreset.AllPresets
                                    .getOrElse(eqPreset) { WinampEqPreset.Cinema }
                                    .name
                            },
                            compact = true,
                            onClick = { panel = OsdPanel.TOOLS }
                        )
                        OsdMenuRow(
                            glyph = PlayerGlyph.STEREO,
                            label = "Stereo downmix",
                            value = if (settings.forceStereoDownmix) "On" else "Off",
                            compact = true,
                            onClick = { toggleStereoDownmix() }
                        )
                        if (qualityVisible) {
                            OsdMenuRow(
                                glyph = PlayerGlyph.DECODER,
                                label = "Stream quality",
                                value = qualityLabel ?: "Auto",
                                compact = true,
                                onClick = { panel = OsdPanel.QUALITY }
                            )
                        }
                        if (proxyCapable) {
                            OsdMenuRow(
                                glyph = PlayerGlyph.SOURCES,
                                label = "Stream route",
                                value = when {
                                    transportMode == TransportMode.DIRECT -> "Direct"
                                    transportMode == TransportMode.PROXY -> "Proxy"
                                    proxyStreamId != null -> "Auto · Proxy"
                                    else -> "Auto · Direct"
                                },
                                compact = true,
                                onClick = { panel = OsdPanel.TRANSPORT }
                            )
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            OsdMenuRow(
                                glyph = PlayerGlyph.PIP,
                                label = "Picture in picture",
                                compact = true,
                                onClick = {
                                    panel = null
                                    enterPip()
                                }
                            )
                        }
                        OsdMenuRow(
                            glyph = PlayerGlyph.INFO,
                            label = "Info",
                            value = "Codec & bitrate",
                            compact = true,
                            onClick = { panel = OsdPanel.INFO }
                        )
                        OsdMenuRow(
                            glyph = PlayerGlyph.DECODER,
                            label = "Stats for nerds",
                            value = "Live counters",
                            compact = true,
                            onClick = { panel = OsdPanel.STATS }
                        )
                        OsdMenuRow(
                            glyph = PlayerGlyph.LOCK,
                            label = "Lock controls",
                            compact = true,
                            onClick = {
                                locked = true
                                panel = null
                                hideOsd()
                            }
                        )
                        OsdMenuRow(
                            glyph = PlayerGlyph.RELOAD,
                            label = "Reload stream",
                            compact = true,
                            onClick = {
                                pendingSeekMs = if (isLive) null else resumePosition().takeIf { it > 1_000 }
                                reconnectAttempt = 0
                                reconnectNotice = null
                                retryPlanMs = null
                                reloadToken++
                                panel = null
                                interaction++
                            }
                        )
                    }
                }
                OsdPanel.TOOLS -> {
                    StepperRow(
                        label = "Brightness",
                        value = "${(brightness * 100).toInt()}%",
                        onDecrease = { brightness = (brightness - 0.1f).coerceIn(0.3f, 1f) },
                        onIncrease = { brightness = (brightness + 0.1f).coerceIn(0.3f, 1f) }
                    )
                    StepperRow(
                        label = "Contrast",
                        value = "${(contrast * 100).toInt()}%",
                        onDecrease = { contrast = (contrast - 0.1f).coerceIn(0.6f, 1.4f) },
                        onIncrease = { contrast = (contrast + 0.1f).coerceIn(0.6f, 1.4f) }
                    )
                    StepperRow(
                        label = "Saturation",
                        value = "${(saturation * 100).toInt()}%",
                        onDecrease = { saturation = (saturation - 0.1f).coerceIn(0.5f, 1.5f) },
                        onIncrease = { saturation = (saturation + 0.1f).coerceIn(0.5f, 1.5f) }
                    )
                    SectionRow("Equalizer")
                    WinampEqPreset.AllPresets.chunked(3).forEach { presetRow ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            presetRow.forEach { preset ->
                                val index = WinampEqPreset.AllPresets.indexOf(preset)
                                Chip(
                                    label = preset.name,
                                    selected = !eqCustom && eqPreset == index,
                                    onClick = {
                                        eqPreset = index
                                        eqCustom = false
                                        eqLevels = presetLevels(preset)
                                    }
                                )
                            }
                        }
                    }
                    SectionRow(if (eqCustom) "Bands · custom" else "Bands")
                    EQ_BAND_LABELS.forEachIndexed { index, bandLabel ->
                        EqBandRow(
                            label = bandLabel,
                            level = eqLevels.getOrElse(index) { EQ_NEUTRAL_LEVEL },
                            onAdjust = { delta ->
                                eqCustom = true
                                eqLevels = EQ_BAND_LABELS.indices.map { band ->
                                    val current = eqLevels.getOrElse(band) { EQ_NEUTRAL_LEVEL }
                                    val next = if (band == index) current + delta else current
                                    next.coerceIn(0, EQ_MAX_LEVEL)
                                }
                            }
                        )
                    }
                    if (playerOrNull != null && playerOrNull !is ExoPlayer) {
                        Text(
                            "Bands and boost shape the built-in player's audio; " +
                                "VLC and MPV play the track untouched.",
                            color = Cinema.TextMuted,
                            fontSize = 10.sp
                        )
                    }
                    StepperRow(
                        label = "Volume / boost",
                        value = "${volumePercent}%",
                        onDecrease = { volumePercent = (volumePercent - 25).coerceIn(0, 200) },
                        onIncrease = { volumePercent = (volumePercent + 25).coerceIn(0, 200) }
                    )
                    Text(
                        "100% is unity gain. Below that the player output is attenuated; " +
                            "above it makeup gain lifts quiet film dialogue.",
                        color = Cinema.TextMuted,
                        fontSize = 10.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(
                            label = "Night mode",
                            selected = nightMode,
                            onClick = { nightMode = !nightMode }
                        )
                        Chip(
                            label = "Normalize volume",
                            selected = settings.normalizeVolume,
                            onClick = { toggleVolumeNormalization() }
                        )
                        Chip(
                            label = "Reset picture & audio",
                            selected = false,
                            onClick = {
                                brightness = 1f
                                contrast = 1f
                                saturation = 1f
                                volumePercent = 100
                                nightMode = false
                                eqPreset = 0
                                eqCustom = false
                                eqLevels = presetLevels(WinampEqPreset.Cinema)
                            }
                        )
                    }
                }
                OsdPanel.TOGGLES -> {
                    Chip(
                        label = if (dropSubtitles) "Subtitles dropped" else "Drop subtitles",
                        selected = dropSubtitles,
                        onClick = { selectText(null) }
                    )
                    Chip(
                        label = "Lock touch / controls",
                        selected = locked,
                        onClick = {
                            locked = true
                            panel = null
                            hideOsd()
                        }
                    )
                    Chip(
                        label = if (gesturesEnabled) "Gestures enabled" else "Gestures overridden",
                        selected = !gesturesEnabled,
                        onClick = {
                            gesturesEnabled = !gesturesEnabled
                            interaction++
                        }
                    )
                    Text(
                        "Gestures drive tap-to-show and drag-to-seek. Locking hides every control until you hold the unlock key.",
                        color = Cinema.TextMuted,
                        fontSize = 12.sp
                    )
                }
                OsdPanel.QUALITY -> {
                    if (liveQualityChoices.isNotEmpty()) {
                        SectionRow("Live quality limit")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(
                                label = "AUTO" + if (qualityCap == 0) "  ·  ON" else "",
                                selected = qualityCap == 0,
                                onClick = {
                                    qualityCap = 0
                                    interaction++
                                }
                            )
                            liveQualityChoices.forEach { (label, height) ->
                                Chip(
                                    label = label + if (qualityCap == height) "  ·  ON" else "",
                                    selected = qualityCap == height,
                                    onClick = {
                                        qualityCap = height
                                        interaction++
                                    }
                                )
                            }
                        }
                        Text(
                            "Caps the live stream at this rendition. ABR can still step below " +
                                "it when bandwidth drops; Auto lets it use the full ladder.",
                            color = Cinema.TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    if (qualityChoices.isNotEmpty()) {
                        SectionRow(if (liveQualityChoices.isNotEmpty()) "Source quality" else "Stream quality")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            qualityChoices.forEach { (label, index) ->
                                Chip(
                                    label = label + if (label == currentQuality) "  ·  ON" else "",
                                    selected = label == currentQuality,
                                    onClick = {
                                        panel = null
                                        if (index != optionIndex) chooseOption(index, resume = true)
                                    }
                                )
                            }
                        }
                        Text(
                            "Switches to the best playable source carrying that resolution. " +
                                "Playback resumes at the current position.",
                            color = Cinema.TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }
                OsdPanel.TRANSPORT -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TransportMode.entries.forEach { mode ->
                            Chip(
                                label = mode.label,
                                selected = transportMode == mode,
                                onClick = { setTransportMode(mode) }
                            )
                        }
                    }
                    Text(
                        when (transportMode) {
                            TransportMode.AUTO ->
                                "Opens a direct connection first. Falls back to the on-device " +
                                    "proxy when the origin refuses the request or the stream will " +
                                    "not start, and remembers the answer for this host."
                            TransportMode.DIRECT ->
                                "Always asks the origin directly. The player still sends the " +
                                    "configured User-Agent and the addon's headers."
                            TransportMode.PROXY ->
                                "Always fetches through the on-device proxy, which re-sends every " +
                                    "playlist, key and segment with the configured browser profile."
                        },
                        color = Cinema.TextMuted,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Playing ${if (proxyStreamId != null) "through the proxy" else "direct"}",
                        color = Cinema.TextSecondary,
                        fontSize = 12.sp
                    )
                    if (!engineCanDirect) {
                        Text(
                            "This engine cannot fetch HTTPS streams itself, so playback stays " +
                                "on the proxy in every mode.",
                            color = Cinema.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    if (transportOrigin != null) {
                        Text(
                            "Remembered for ${transportOrigin.substringAfter("://")}: " +
                                when (originVerdict) {
                                    TransportVerdict.DIRECT -> "direct works"
                                    TransportVerdict.PROXY -> "needs the proxy"
                                    null -> "nothing yet"
                                },
                            color = Cinema.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
                OsdPanel.STATS -> {
                    val snapshot = osdStats.stats
                    val videoFormat = snapshot.videoFormatHistory.lastOrNull()?.format
                    val audioFormat = snapshot.audioFormatHistory.lastOrNull()?.format
                    val width = videoFormat?.width?.takeIf { it > 0 } ?: videoPixelSize.width
                    val height = videoFormat?.height?.takeIf { it > 0 } ?: videoPixelSize.height
                    val rows: List<Pair<String, String>> = buildList {
                        add(
                            "Resolution" to if (width > 0 && height > 0) "$width × $height" else "—"
                        )
                        add(
                            "Frame rate" to (videoFormat?.frameRate?.takeIf { it > 0f }
                                ?.let { "${formatFrameRate(it)} fps" } ?: "—")
                        )
                        add(
                            "Video codec" to (listOfNotNull(
                                videoFormat?.codecs?.takeIf { it.isNotBlank() },
                                videoFormat?.sampleMimeType?.substringAfter('/')?.uppercase()
                            ).firstOrNull() ?: "—")
                        )
                        add(
                            // The rendition ABR settled on, not the mean: this
                            // and the estimate below explain every switch.
                            "Video bitrate" to (
                                videoFormat?.bitrate?.takeIf { it > 0 }
                                    ?.let { formatBitrate(it.toLong()) }
                                    ?: snapshot.meanVideoFormatBitrate.takeIf { it > 0 }
                                        ?.let { formatBitrate(it.toLong()) }
                                    ?: "—"
                                )
                        )
                        add(
                            "Bandwidth estimate" to (
                                osdStats.bandwidthBps.takeIf { it > 0 }
                                    ?.let { formatBitrate(it) }
                                    ?: snapshot.meanBandwidth.takeIf { it > 0 }
                                        ?.let { formatBitrate(it.toLong()) }
                                    ?: "—"
                                )
                        )
                        add(
                            "Dropped frames" to buildString {
                                append(snapshot.totalDroppedFrames)
                                val rate = snapshot.droppedFramesRate
                                if (rate > 0f) append(" · ${"%.1f".format(rate)}%")
                            }
                        )
                        add(
                            "Audio underruns" to buildString {
                                append(snapshot.totalAudioUnderruns)
                                val rate = snapshot.audioUnderrunRate
                                if (rate > 0f) append(" · ${"%.2f".format(rate)}/min")
                            }
                        )
                        add(
                            "Rebuffers" to buildString {
                                append(snapshot.totalRebufferCount)
                                val longest = snapshot.maxRebufferTimeMs
                                if (longest > 0) append(" · longest ${longest / 1000}s")
                            }
                        )
                        add(
                            "Buffered" to (playerOrNull
                                ?.let { formatBufferedMs(it.totalBufferedDuration) } ?: "—")
                        )
                        add("Audio" to describeAudio(audioFormat))
                        add("Decoder" to profile.decoder.name.replace('_', ' '))
                        // The mode above is a request; these are the codecs
                        // MediaCodec actually opened for this source.
                        add("Video decoder" to (osdStats.videoDecoder ?: "—"))
                        add("Audio decoder" to (osdStats.audioDecoder ?: "—"))
                        add(
                            "Engine" to describeRunningEngine(playerOrNull, nativeFallback)
                        )
                        add("Buffer" to describeBuffer(profile))
                        add("Memory cap" to describeMemoryBudget(profile))
                        add(
                            "Delivery" to when {
                                proxyStreamId != null -> buildString {
                                    append("Native proxy")
                                    when {
                                        transportMode == TransportMode.PROXY -> {}
                                        directFailed -> append(" · direct failed")
                                        originVerdict == TransportVerdict.PROXY ->
                                            append(" · remembered")
                                    }
                                }
                                transportMode == TransportMode.AUTO -> "Direct · auto"
                                else -> "Direct"
                            }
                        )
                        add("Playback" to if (isLive) "Live" else "On demand")
                        add("Source" to (current?.name ?: "—"))
                    }
                    rows.forEachIndexed { index, (label, value) ->
                        InfoRow(
                            label = label,
                            value = value,
                            focusRequester = if (index == 0) infoFirstFocus else null
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Counters come from Media3 and refresh every second while this panel is open.",
                        color = Cinema.TextMuted,
                        fontSize = 11.sp
                    )
                }
                OsdPanel.INFO -> {
                    val rows = infoRows
                    if (rows == null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Cinema.Gold,
                                strokeWidth = 2.dp
                            )
                            Text(
                                "Reading stream metadata…",
                                color = Cinema.TextMuted,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        rows.forEachIndexed { index, (label, value) ->
                            InfoRow(
                                label = label,
                                value = value,
                                focusRequester = if (index == 0) infoFirstFocus else null
                            )
                        }
                        LaunchedEffect(rows) { runCatching { infoFirstFocus.requestFocus() } }
                    }
                }
            }
        }
    }

    if (showPicker) {
        SourcePicker(
            options = options,
            steps = searchSteps,
            searchInProgress = searchInProgress,
            hiddenByLimits = sourcesHiddenByLimits,
            // Nothing is loaded while the manual pick is pending, so no card
            // may claim to be the source in play.
            selectedIndex = if (awaitingPick) -1 else optionIndex,
            badges = badgeCatalog,
            onDismiss = {
                showPicker = false
                awaitingPick = false
                if (!osdVisible) wake(OsdFocus.PLAY)
            },
            onSelect = { index ->
                // Re-picking the source already playing must not restart it;
                // the first pick after the hold always has to start playback.
                val wasAwaiting = awaitingPick
                showPicker = false
                awaitingPick = false
                if (!osdVisible) wake(OsdFocus.PLAY)
                if (wasAwaiting || index != optionIndex) chooseOption(index, resume = false)
            }
        )
    }

    if (openWithOpen) {
        OpenWithDialog(
            title = headerTitleText,
            streamUrl = openWithTarget?.url ?: current?.url.orEmpty(),
            players = openWithPlayers,
            error = openWithError,
            relayed = openWithTarget?.let { relayedHandoff(it) } == true,
            onDismiss = { closeOpenWith(resume = true) },
            onLaunch = { packageName ->
                openWithTarget?.let { handOffTo(it, packageName) }
            }
        )
    }
}

private val QUALITY_ORDER = listOf(
    "8K", "4K", "2160p", "1440p", "1080p", "720p", "576p", "480p", "360p", "240p"
)

/** Distinct supported video heights in a live adaptive stream, best first. */
private fun videoQualityChoices(tracks: Tracks?): List<Pair<String, Int>> {
    val heights = sortedSetOf<Int>()
    tracks?.groups.orEmpty()
        .filter { it.type == C.TRACK_TYPE_VIDEO }
        .forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val height = group.getTrackFormat(index).height
                if (height > 0) heights += height
            }
        }
    if (heights.size < 2) return emptyList()
    return heights.sortedDescending()
        .map { height -> qualityHeightLabel(height) to height }
        .distinctBy { it.first }
}

private fun qualityHeightLabel(height: Int): String = when {
    height >= 4320 -> "8K"
    height >= 2160 -> "4K"
    else -> "${height}p"
}

@Composable
private fun SectionRow(label: String) {
    Text(
        text = label.uppercase(),
        color = Cinema.TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, color = Cinema.TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TvButton("–", onDecrease, style = TvButtonStyle.SECONDARY, compact = true)
        Text(value, color = Cinema.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        TvButton("+", onIncrease, style = TvButtonStyle.SECONDARY, compact = true)
    }
}

/**
 * One equalizer band: a focusable bipolar track. Left/Right on the D-pad nudges
 * the band, so the row needs no separate buttons.
 */
@Composable
private fun EqBandRow(label: String, level: Int, onAdjust: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val db = winampLevelToDb(level)
    val normalized = when {
        level >= EQ_NEUTRAL_LEVEL ->
            (level - EQ_NEUTRAL_LEVEL).toFloat() / (EQ_MAX_LEVEL - EQ_NEUTRAL_LEVEL)
        else -> (level - EQ_NEUTRAL_LEVEL).toFloat() / EQ_NEUTRAL_LEVEL
    }.coerceIn(-1f, 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(CinemaDimens.ControlRadius),
                focused = focused,
                base = Glass.Control,
                scale = FocusScale.ROW,
                sheen = 0.45f
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onAdjust(-1)
                        true
                    }
                    Key.DirectionRight -> {
                        onAdjust(1)
                        true
                    }
                    else -> false
                }
            }
            .semantics { contentDescription = "$label equalizer band" }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            color = Cinema.TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier.width(72.dp)
        )
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Cinema.PanelRaised)
        ) {
            val half = maxWidth / 2
            Box(
                Modifier
                    .offset(x = if (normalized >= 0f) half else half + half * normalized)
                    .width(half * abs(normalized).coerceAtLeast(0.02f))
                    .fillMaxHeight()
                    .background(if (focused) Cinema.Gold else Cinema.GoldDim)
            )
            Box(
                Modifier
                    .offset(x = half)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Cinema.TextMuted)
            )
        }
        Text(
            text = String.format(Locale.US, "%+.1f dB", db),
            color = if (focused) Cinema.Gold else Cinema.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp)
        )
    }
}

@Composable
private fun OsdPanelDialog(
    title: String,
    onDismiss: () -> Unit,
    initialFocus: FocusRequester? = null,
    sideSheet: Boolean = false,
    content: @Composable () -> Unit
) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(initialFocus) {
        delay(140)
        runCatching { (initialFocus ?: closeFocus).requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Compose sizes the dialog window to its content height, so the scrim
        // behind the panel only covered the panel and left a dark rectangle
        // over the film. Force the window to the whole screen, drop the
        // platform dim (the scrim is ours) and make the decor transparent.
        val dialogWindow = runCatching {
            (LocalView.current.parent as? DialogWindowProvider)?.window
        }.getOrNull()
        DisposableEffect(dialogWindow) {
            dialogWindow?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
            }
            onDispose { }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (sideSheet) 0.55f else 0.82f))
                .pointerInput(Unit) {
                    detectTapGestures { onDismiss() }
                },
            contentAlignment = if (sideSheet) Alignment.CenterEnd else Alignment.Center
        ) {
            Column(
                Modifier
                    .then(
                        if (sideSheet) {
                            // Full-height right rail holding one column of compact
                            // rows, so every overflow item stays on screen without
                            // scrolling. The fraction has to be applied outside
                            // widthIn — the other order takes the fraction of the
                            // cap and the labels collapse to an ellipsis.
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.44f)
                                .widthIn(max = 420.dp)
                        } else {
                            Modifier
                                .widthIn(max = 780.dp)
                                .fillMaxWidth(0.8f)
                                .heightIn(max = minOf(640.dp, (LocalConfiguration.current.screenHeightDp * 0.8f).dp))
                        }
                    )
                    .glassSurface(
                        shape = if (sideSheet) {
                            RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
                        } else {
                            RoundedCornerShape(CinemaDimens.CardRadius)
                        },
                        base = Glass.Sheet
                    )
                    .padding(if (sideSheet) 12.dp else 22.dp)
                    .verticalScroll(rememberScrollState())
                    .pointerInput(Unit) {
                        detectTapGestures { }
                    },
                verticalArrangement = Arrangement.spacedBy(if (sideSheet) 6.dp else 10.dp)
            ) {
                if (sideSheet) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            color = Cinema.TextPrimary,
                            style = CinemaType.SectionTitle,
                            modifier = Modifier.weight(1f)
                        )
                        TvButton(
                            label = "CLOSE",
                            style = TvButtonStyle.GHOST,
                            compact = true,
                            onClick = onDismiss,
                            focusRequester = closeFocus
                        )
                    }
                } else {
                    Text(title, color = Cinema.TextPrimary, style = CinemaType.SectionTitle)
                }
                content()
                if (!sideSheet) {
                    Spacer(Modifier.height(4.dp))
                    TvButton(
                        label = "CLOSE",
                        style = TvButtonStyle.GHOST,
                        onClick = onDismiss,
                        focusRequester = closeFocus
                    )
                }
            }
        }
    }
}

@Composable
private fun UnlockButton(
    focusRequester: FocusRequester,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    var holding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        delay(120)
        runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(holding) {
        if (!holding) {
            holdProgress = 0f
            return@LaunchedEffect
        }
        val startedAt = SystemClock.elapsedRealtime()
        while (holding) {
            val progress = ((SystemClock.elapsedRealtime() - startedAt) / 1_400f).coerceIn(0f, 1f)
            holdProgress = progress
            if (progress >= 1f) {
                holding = false
                onUnlock()
                break
            }
            delay(40)
        }
    }

    Column(
        modifier = modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionCenter || event.key == Key.Enter ||
                    event.key == Key.Spacebar
                ) {
                    if (event.type == KeyEventType.KeyDown) holding = true else holding = false
                    true
                } else {
                    false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        holding = true
                        tryAwaitRelease()
                        holding = false
                    }
                )
            }
            .glassSurface(
                shape = RoundedCornerShape(CinemaDimens.PillRadius),
                focused = holding,
                base = Color.Black.copy(alpha = 0.55f),
                tint = if (holding) Glass.AccentWash else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "🔒",
            color = Cinema.Gold,
            fontSize = 20.sp
        )
        Text(
            text = if (holding) "HOLD TO UNLOCK…" else "HOLD OK TO UNLOCK",
            color = Cinema.TextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(120.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.18f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(holdProgress)
                    .height(4.dp)
                    .background(Cinema.Gold)
            )
        }
    }
}

private fun subtitleConfiguration(subtitle: AddonSubtitle, default: Boolean = false): MediaItem.SubtitleConfiguration {
    val builder = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
        .setMimeType(subtitleMimeType(subtitle.url))
        .setLanguage(subtitle.lang.ifBlank { "und" })
    if (default) builder.setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
    subtitle.label?.takeIf { it.isNotBlank() }?.let { builder.setLabel(it.take(48)) }
    return builder.build()
}

private fun subtitleMimeType(url: String): String {
    val path = url.substringBefore('?').lowercase()
    return when {
        path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
        path.endsWith(".ass") || path.endsWith(".ssa") -> "text/x-ssa"
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

private fun persistProgress(
    repository: com.streamline.cinema.data.CinemaRepository?,
    plan: PlaybackPlan,
    episodeTitle: String?,
    player: Player
) {
    val repo = repository ?: return
    val position = player.currentPosition
    val duration = player.duration
    if (position <= 0 || duration <= 0) return
    runCatching {
        repo.recordProgress(
            WatchProgress(
                playbackKey = plan.progressKey,
                itemKey = plan.itemKey,
                tmdbId = plan.tmdbId,
                type = plan.type,
                title = plan.title,
                posterUrl = plan.posterUrl,
                season = plan.season,
                episode = plan.episode,
                episodeTitle = episodeTitle,
                positionMs = position,
                durationMs = duration
            )
        )
    }
}

/**
 * A built player plus the handles needed to restyle it while it plays. Only
 * the built-in engine exposes live request headers: its data source factories
 * outlive construction, so a UA/header change reaches the next segment without
 * a codec re-init.
 */
private class BuiltPlayer(
    val player: Player,
    val liveHeaders: LiveRequestHeaders? = null
)

/** Late-bound UA/header sink for the built-in player's data sources. */
private class LiveRequestHeaders {
    @Volatile private var apply: ((String, Map<String, String>) -> Unit)? = null

    fun install(apply: (String, Map<String, String>) -> Unit) {
        this.apply = apply
    }

    fun update(userAgent: String, headers: Map<String, String>) {
        apply?.invoke(userAgent, headers)
    }
}

/**
 * Picks the engine pinned in Settings. AUTO, BUILT-IN and EXTERNAL keep the
 * built-in Media3 player (EXTERNAL hands off before this point); VLC and MPV
 * fall back to it when their native library is missing, below the API floor,
 * or when the stream needs a DRM session the native engines cannot join.
 */
private fun buildPlayer(
    context: Context,
    profile: PlaybackProfile,
    isLive: Boolean,
    drm: DrmConfig? = null,
    subtitlesDisabled: Boolean = false,
    subtitleLanguage: String? = null,
    eqState: EqState,
    gainState: GainState
): BuiltPlayer {
    // Native engines take their caches and request headers as constructor
    // arguments, which is why both sit in the profile's rebuild key for VLC
    // and MPV but not for Media3.
    val native = when (profile.engine) {
        PlaybackEngine.VLC -> nativeEngine("VLC", drm) {
            VlcEnginePlayer(
                context,
                profile.userAgent,
                profile.headers,
                profile.abr,
                isLive,
                profile.native.vlcCachingMs,
                profile.amlogicFix,
                profile.maxResolution.maxWidth,
                profile.maxResolution.maxHeight
            )
        }
        PlaybackEngine.MPV -> nativeEngine("MPV", drm) {
            if (MpvEnginePlayer.isSupported) {
                MpvEnginePlayer(
                    context,
                    profile.userAgent,
                    profile.headers,
                    isLive,
                    profile.native.mpvCacheSeconds,
                    profile.byteBudget,
                    profile.amlogicFix
                )
            } else {
                null
            }
        }
        PlaybackEngine.AUTO, PlaybackEngine.BUILT_IN, PlaybackEngine.EXTERNAL -> null
    }
    return native?.let { BuiltPlayer(it) } ?: buildExoPlayer(
        context = context,
        profile = profile,
        drm = drm,
        subtitlesDisabled = subtitlesDisabled,
        subtitleLanguage = subtitleLanguage,
        eqState = eqState,
        gainState = gainState
    )
}

private inline fun nativeEngine(
    name: String,
    drm: DrmConfig?,
    build: () -> NativeEnginePlayer?
): NativeEnginePlayer? {
    if (drm != null) {
        Log.w("StreamLine:player", "$name has no DRM session · using built-in player")
        return null
    }
    return runCatching { build() }
        .onFailure {
            Log.w("StreamLine:player", "$name unavailable · using built-in player", it)
        }
        .getOrNull()
}

/**
 * Applies the user's bandwidth tier to the track selector. The cap is a hard
 * ceiling ABR may step under but never over; AUTO removes both bounds.
 */
private fun TrackSelectionParameters.Builder.applyBandwidthCap(
    cap: BandwidthCap
): TrackSelectionParameters.Builder =
    setMaxVideoBitrate(cap.bitsPerSecond.takeIf { it > 0 } ?: Integer.MAX_VALUE)

/**
 * One meter for the whole process, so the estimate survives channel changes:
 * a zap starts on the rendition the previous segment justified instead of
 * re-learning the line. The fixed 500 kbps seed replaces Media3's network-class
 * guess: on a volatile line a Wi-Fi/Ethernet default opens on a rendition the
 * first seconds cannot drain, and ABR climbs from the seed once samples land.
 * The stats panel reads this instance, which is why it is owned here rather
 * than left to the library singleton.
 */
private object PlaybackBandwidth {
    @Volatile private var meter: DefaultBandwidthMeter? = null

    fun meter(context: Context): DefaultBandwidthMeter =
        meter ?: synchronized(this) {
            meter ?: DefaultBandwidthMeter.Builder(context.applicationContext)
                .setInitialBitrateEstimate(INITIAL_BITRATE_ESTIMATE_BPS)
                .build()
                .also { meter = it }
        }

    /** Current estimate in bits/s, or 0 before the meter has one. */
    fun estimateBps(): Long = meter?.bitrateEstimate ?: 0L
}

// ABR tuning for the adaptive track selection. Ascending takes longer than
// descending so a short burst cannot ratchet quality up into a stall, and only
// 70% of the measured rate is treated as available, leaving headroom for
// bursts the meter cannot see. The retained tail means a recovering line does
// not re-download segments it just discarded.
private const val INITIAL_BITRATE_ESTIMATE_BPS = 500_000L
private const val QUALITY_INCREASE_SETTLE_MS = 15_000
private const val QUALITY_DECREASE_SETTLE_MS = 10_000
private const val RETAIN_AFTER_DISCARD_MS = 25_000
private const val ADAPTIVE_BANDWIDTH_FRACTION = 0.7f

/**
 * Merges the configured UA under the addon's own headers over the browser
 * defaults. Both the initial build and every live update run through this,
 * so a restyle and a fresh build send identical headers.
 */
private fun requestProperties(
    userAgent: String,
    headers: Map<String, String>
): Map<String, String> = BrowserMask.withDefaults(
    StreamUrls.mergeHeaders(mapOf("User-Agent" to userAgent), headers)
)

private fun buildExoPlayer(
    context: Context,
    profile: PlaybackProfile,
    drm: DrmConfig? = null,
    subtitlesDisabled: Boolean = false,
    subtitleLanguage: String? = null,
    eqState: EqState,
    gainState: GainState
): BuiltPlayer {
    CinemaRenderersFactory.logFfmpegAvailability()
    Log.i(
        "StreamLine:audio",
        "Sink plan · ${AudioAuditProbe.describeSink(context, profile.downmixStereo, profile.normalizeVolume)}"
    )

    fun dataSource(client: okhttp3.OkHttpClient): OkHttpDataSource.Factory {
        // The UA must travel as a default request property only: Media3 appends
        // the factory-level UA with addHeader() after the request properties, so
        // setUserAgent() would send two UA values and win over an addon's pipe
        // UA. Addon streams also often need the emitting page's Referer; the
        // CDN answers 403 otherwise. Merging case-insensitively keeps one UA
        // header when the addon spells it `user-agent`.
        val factory = OkHttpDataSource.Factory(client)
        // Browser defaults sit under the configured UA and the addon's own
        // headers, so a provider that binds to its app's agent keeps it while
        // a CDN that sniffs for a browser (Trace) sees the full profile.
        factory.setDefaultRequestProperties(requestProperties(profile.userAgent, profile.headers))
        return factory
    }

    // Progressive reads must stay unbounded (one response body carries the
    // whole film), while HLS/DASH segments use a bounded read timeout so a
    // stalled chunk is abandoned and retried instead of hanging the loader.
    val progressiveSource = dataSource(Http.streamingClient)
    val adaptiveSource = dataSource(Http.adaptiveClient)
    val dataSourceFactory = DefaultDataSource.Factory(context, progressiveSource)
    val adaptiveDataSourceFactory = DefaultDataSource.Factory(context, adaptiveSource)
    // Held past construction so a UA/header change applies to the next data
    // source instead of forcing a rebuild.
    val liveHeaders = LiveRequestHeaders().apply {
        install { userAgent, headers ->
            val properties = requestProperties(userAgent, headers)
            progressiveSource.setDefaultRequestProperties(properties)
            adaptiveSource.setDefaultRequestProperties(properties)
        }
    }

    // The tier picks the forward window and the rewind retention; BALANCED is
    // the pre-tier profile, so existing installs keep their tuning. The
    // high-capacity switch requests the MP4/MKV window (15 s / 50 s) and the
    // planner shortens it to what the byte budget holds.
    // The back buffer is trimmed to what the byte budget can actually retain:
    // Media3 counts both buffers against the same allocation, so a rewind pin
    // larger than the budget would starve the forward fill.
    val plan = planBuffer(profile.window, profile.byteBudget, profile.bandwidthCap.bitsPerSecond)
    val window = plan.window
    Log.i(
        "StreamLine:player",
        "Load control · ${if (profile.adaptive) "adaptive" else "progressive"} · " +
            "${window.minBufferMs}/${window.maxBufferMs} ms · " +
            "start ${window.bufferForPlaybackMs} ms · " +
            "re-buffer ${window.bufferForPlaybackAfterRebufferMs} ms · " +
            "back ${window.backBufferMs} ms"
    )
    plan.budget?.let { budget ->
        if (budget.clamped) {
            Log.i("StreamLine:player", "Byte cap · ${describeByteBudget(budget)}")
        }
        when {
            plan.windowExceedsBudget -> Log.i(
                "StreamLine:player",
                "Buffer plan · window ${seconds(profile.window.maxBufferMs)} s exceeds " +
                    "${describeByteBudget(budget)} · shortened to " +
                    "${seconds(window.maxBufferMs)} s · back buffer dropped"
            )
            plan.trimmedBackBufferMs > 0 -> Log.i(
                "StreamLine:player",
                "Buffer plan · back ${seconds(profile.window.backBufferMs)} s trimmed to " +
                    "${seconds(window.backBufferMs)} s (${describeByteBudget(budget)})"
            )
        }
    }

    val loadControlBuilder = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            window.minBufferMs,
            window.maxBufferMs,
            window.bufferForPlaybackMs,
            window.bufferForPlaybackAfterRebufferMs
        )
    profile.byteBudget?.let { budget ->
        // Seconds are not a memory bound: the same window holds four times
        // the bytes at 4K. A byte budget makes the loader stop at the RAM
        // the device actually has, and disabling time priority is what
        // makes Media3 honour it. The tier only moves the time window.
        loadControlBuilder.setTargetBufferBytes(
            budget.effectiveBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
    }

    val loadControl = loadControlBuilder
        // Evict media behind the playhead once it is older than the back buffer.
        // Old segments are released from memory continuously, which stops long
        // live sessions from exhausting RAM on 32-bit boxes.
        .setBackBuffer(window.backBufferMs, false)
        // Time priority lets the loader keep filling when the device is fast
        // enough; low-RAM devices switch it off so their byte budget is
        // enforced.
        .setPrioritizeTimeOverSizeThresholds(!profile.memoryBounded)
        .build()

    val renderersFactory = CinemaRenderersFactory(
        context,
        downmixStereo = profile.downmixStereo,
        normalizeVolume = profile.normalizeVolume,
        eq = eqState,
        gain = gainState
    )
        // HW is the only strict mode: no software fallback when the pinned
        // hardware decoder refuses the stream. HW+ keeps the fallback.
        .setEnableDecoderFallback(profile.decoder != DecoderType.HW)
        .setEnableAudioTrackPlaybackParams(true)
    if (profile.amlogicFix) {
        // Amlogic-class SoCs hand frames to their video pipeline through the
        // asynchronous MediaCodec API; driving the synchronous API can lose
        // frames or drop the stream outright on non-compliant samples.
        renderersFactory.forceEnableMediaCodecAsynchronousQueueing()
        Log.i("StreamLine:player", "Amlogic fix · async MediaCodec queueing + tunneling")
    }
    when (profile.decoder) {
        DecoderType.SW -> renderersFactory.setMediaCodecSelector(MediaCodecSelector.PREFER_SOFTWARE)
        // AUTO keeps Media3's own decoder ranking; HW/HW+ pin a hardware
        // decoder whenever one exists, and differ only in whether the software
        // fallback (set above) is enabled — so the two switches no longer
        // resolve to the same selector.
        DecoderType.HW, DecoderType.HW_PLUS ->
            renderersFactory.setMediaCodecSelector(hardwareFirstSelector)
        DecoderType.AUTO -> Unit
    }

    // Timeout-aware retries shared by progressive, HLS and DASH. The adaptive
    // data source's 30 s read timeout turns a stalled chunk into a retryable
    // SocketTimeoutException rather than an endless buffering spinner.
    val loadPolicy = StreamLoadErrorPolicy(profile.segmentRetries)
    val mediaSourceFactory = adaptiveSourceFactories(
        progressiveDataSource = dataSourceFactory,
        adaptiveDataSource = adaptiveDataSourceFactory,
        policy = loadPolicy
    )
    // Encrypted feeds only decode once a DRM session manager is attached; the
    // license callback (local ClearKey JSON or a Widevine/PlayReady server) is
    // built from what the addon's watch page advertised.
    if (drm != null) {
        drmSessionManager(drm, adaptiveDataSourceFactory)?.let { manager ->
            mediaSourceFactory.setDrmSessionManagerProvider { manager }
        }
    }

    // Explicit DefaultTrackSelector so ABR stays under our control: mixed
    // HLS/DASH renditions adapt across mime types and non-seamless switches,
    // and disabling ABR pins the highest supported rendition. The selection
    // factory tunes how ABR walks the ladder; the size cap goes in at
    // construction too, so the first rendition is already bounded.
    Log.i(
        "StreamLine:player",
        "Resolution cap · ${profile.maxResolution.maxWidth}×${profile.maxResolution.maxHeight}"
    )
    val trackSelector = DefaultTrackSelector(
        context,
        AdaptiveTrackSelection.Factory(
            QUALITY_INCREASE_SETTLE_MS,
            QUALITY_DECREASE_SETTLE_MS,
            RETAIN_AFTER_DISCARD_MS,
            ADAPTIVE_BANDWIDTH_FRACTION
        )
    ).apply {
        setParameters(
            buildUponParameters()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .setForceHighestSupportedBitrate(!profile.abr)
                // Only takes effect on devices whose codecs advertise
                // tunneling; elsewhere Media3 keeps the non-tunneled path.
                .setTunnelingEnabled(profile.amlogicFix)
                .applyBandwidthCap(profile.bandwidthCap)
                .setMaxVideoSize(
                    profile.maxResolution.maxWidth,
                    profile.maxResolution.maxHeight
                )
                .build()
        )
    }

    // Movie content type + audio focus: without this the player never requests
    // focus, so system alerts and other apps can duck or mute a film.
    val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    val player = ExoPlayer.Builder(context, renderersFactory)
        .setMediaSourceFactory(mediaSourceFactory)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        // Owned instance rather than the library singleton so the stats panel
        // can read the estimate that ABR is actually using.
        .setBandwidthMeter(PlaybackBandwidth.meter(context))
        .setAudioAttributes(audioAttributes, true)
        .build()
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setForceHighestSupportedBitrate(!profile.abr)
        // Media3 selects the first text track by default; captions stay off
        // until the viewer asks, and the profile's language only biases which
        // track that switch lands on.
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitlesDisabled)
        .setPreferredTextLanguage(subtitleLanguage)
        .setSelectUndeterminedTextLanguage(false)
        .build()
    return BuiltPlayer(player, liveHeaders)
}

private fun audioTrackLabel(format: androidx.media3.common.Format, index: Int): String {
    val language = format.language?.let { tag ->
        runCatching { Locale.forLanguageTag(tag).displayLanguage }.getOrNull()
    }
    return listOfNotNull(
        language?.takeIf { it.isNotBlank() },
        format.label,
        if (format.channelCount > 0) "${format.channelCount} ch" else null
    ).ifEmpty { listOf("Track ${index + 1}") }.joinToString(" · ")
}

private fun textTrackLabel(format: androidx.media3.common.Format, index: Int): String {
    val language = format.language?.let { tag ->
        runCatching { Locale.forLanguageTag(tag).displayLanguage }.getOrNull()
    }
    return listOfNotNull(
        language?.takeIf { it.isNotBlank() },
        format.label,
        if (format.sampleMimeType == MimeTypes.TEXT_VTT) "WebVTT" else null
    ).ifEmpty { listOf("Subtitles ${index + 1}") }.joinToString(" · ")
}

/**
 * Re-resolves a saved pick against the track list a player instance reports.
 * Matching on the picker's own label keeps menu and reload consistent, and a
 * track that vanished in the new instance simply leaves the default in place.
 */
private fun savedTrackOverride(
    tracks: Tracks,
    trackType: Int,
    pick: SavedTrackPick
): TrackSelectionOverride? {
    val labeler = if (trackType == C.TRACK_TYPE_AUDIO) ::audioTrackLabel else ::textTrackLabel
    val match = buildTrackChoices(tracks, trackType, labeler)
        .firstOrNull { it.label == pick.label }
        ?: return null
    return TrackSelectionOverride(match.group, match.index)
}

private fun buildTrackChoices(
    tracks: Tracks?,
    trackType: Int,
    labeler: (androidx.media3.common.Format, Int) -> String
): List<TrackChoice> {
    val groups = tracks?.groups ?: return emptyList()
    return groups.filter { it.type == trackType }.flatMap { group ->
        (0 until group.length).mapNotNull { index ->
            if (!group.isTrackSupported(index)) return@mapNotNull null
            val format = group.getTrackFormat(index)
            TrackChoice(
                group = group.mediaTrackGroup,
                index = index,
                label = labeler(format, index),
                selected = group.isTrackSelected(index),
                language = format.language.orEmpty()
            )
        }
    }
}

/** Preset level for every band of the fixed ten-band curve. */
private fun presetLevels(preset: WinampEqPreset): List<Int> = EQ_BAND_HZ.map(preset::levelAt)

private fun captionStyle(index: Int): CaptionStyleCompat = when (index) {
    1 -> CaptionStyleCompat(
        android.graphics.Color.WHITE,
        0x99000000.toInt(),
        0,
        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
        android.graphics.Color.BLACK,
        null
    )
    2 -> CaptionStyleCompat(
        android.graphics.Color.WHITE,
        android.graphics.Color.BLACK,
        0,
        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
        android.graphics.Color.BLACK,
        null
    )
    else -> CaptionStyleCompat.DEFAULT
}

private fun isAdaptiveUrl(url: String?, kind: StreamKind? = null): Boolean {
    if (kind != null) return true
    if (url.isNullOrBlank()) return false
    return StreamUrls.kindOf(url) != null
}

private fun isHlsUrl(url: String?, kind: StreamKind? = null): Boolean {
    if (kind != null) return kind == StreamKind.HLS
    if (url.isNullOrBlank()) return false
    return StreamUrls.kindOf(url) == StreamKind.HLS
}

private fun mediaFileName(url: String?, fallback: String): String {
    val segment = url?.let { runCatching { Uri.parse(it).lastPathSegment }.getOrNull() }
    val decoded = segment?.let { runCatching { Uri.decode(it) }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
    return decoded ?: fallback
}

/** What the stats panel shows: the counters plus the codecs actually opened. */
private data class OsdStats(
    val stats: PlaybackStats = PlaybackStats.EMPTY,
    val bandwidthBps: Long = 0L,
    val videoDecoder: String? = null,
    val audioDecoder: String? = null
)

/**
 * Polls only while the stats panel is on screen. Kept out of the player
 * composable because the K2 data-flow pass scales badly with the number of
 * mutable locals it has to track through that many nested lambdas.
 */
@Composable
private fun rememberOsdStats(
    panel: OsdPanel?,
    player: Player?,
    statsListener: PlaybackStatsListener,
    probe: DecoderProbe
): OsdStats {
    var value by remember { mutableStateOf(OsdStats()) }
    LaunchedEffect(panel, player, probe) {
        if (panel != OsdPanel.STATS) return@LaunchedEffect
        while (true) {
            value = OsdStats(
                stats = runCatching { statsListener.playbackStats }
                    .getOrNull() ?: PlaybackStats.EMPTY,
                bandwidthBps = PlaybackBandwidth.estimateBps(),
                videoDecoder = probe.videoDecoder,
                audioDecoder = probe.audioDecoder
            )
            delay(1_000L)
        }
    }
    return value
}


private fun queryDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

@Composable
private fun StreamSearchOverlay(
    steps: List<StreamSearchStep>,
    inProgress: Boolean,
    modifier: Modifier = Modifier
) {
    val playableCount = steps.sumOf { it.found }
    Box(
        modifier.background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(0.8f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inProgress) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        color = Cinema.Gold,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    when {
                        inProgress -> "Searching playable sources"
                        playableCount > 0 -> "Playable sources found"
                        else -> "No playable streams"
                    },
                    color = if (!inProgress && playableCount == 0) Cinema.Offline
                    else Cinema.TextPrimary,
                    style = CinemaType.SectionTitle
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    inProgress ->
                        "Checking the catalog and every enabled addon before giving up on this title."
                    steps.isEmpty() -> "No source was provided for this title."
                    else -> "Searched ${steps.size} " +
                        "${if (steps.size == 1) "source" else "sources"} · $playableCount playable"
                },
                color = Cinema.TextSecondary,
                style = CinemaType.BodySmall
            )
            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    steps.forEach { SourceSearchRow(it) }
                }
            }
            if (!inProgress && playableCount == 0) {
                Spacer(Modifier.height(18.dp))
                // With no episode chosen the sources are not missing — the
                // search simply had no slot to look for.
                val needsEpisode = steps.any { it.detail?.contains("episode not selected") == true }
                Text(
                    if (needsEpisode) "Pick a season and episode for this series, then try again."
                    else "Import a catalog with direct links or register a Stremio addon in Settings.",
                    color = Cinema.TextMuted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SourceSearchRow(step: StreamSearchStep, dense: Boolean = false) {
    val phaseColor = when (step.phase) {
        StreamSearchPhase.FOUND -> Cinema.Online
        StreamSearchPhase.SEARCHING -> Cinema.Gold
        StreamSearchPhase.EMPTY -> Cinema.TextMuted
        StreamSearchPhase.FAILED -> Cinema.Offline
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (step.phase == StreamSearchPhase.SEARCHING) {
                CircularProgressIndicator(
                    Modifier.size(if (dense) 11.dp else 13.dp),
                    color = Cinema.Gold,
                    strokeWidth = 1.6.dp
                )
            } else {
                Text(
                    when (step.phase) {
                        StreamSearchPhase.FOUND -> "✓"
                        StreamSearchPhase.EMPTY -> "–"
                        else -> "✕"
                    },
                    color = phaseColor,
                    fontSize = if (dense) 11.sp else 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            step.label,
            color = Cinema.TextPrimary,
            fontSize = if (dense) 11.sp else 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Unweighted, a long addon name pushed the detail off the row.
            modifier = Modifier.weight(1.6f, fill = false)
        )
        Spacer(Modifier.width(12.dp))
        val detail = step.detail ?: when (step.phase) {
            StreamSearchPhase.SEARCHING -> "searching…"
            StreamSearchPhase.FOUND -> "${step.found} playable"
            StreamSearchPhase.EMPTY -> "no playable streams"
            StreamSearchPhase.FAILED -> "failed"
        }
        Text(
            detail,
            color = if (step.phase == StreamSearchPhase.FOUND) Cinema.TextSecondary
            else Cinema.TextMuted,
            fontSize = if (dense) 10.sp else 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SearchProgressPill(done: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .glassSurface(
                shape = RoundedCornerShape(CinemaDimens.PillRadius),
                base = Glass.Sheet,
                sheen = 0.5f
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            Modifier.size(12.dp),
            color = Cinema.Gold,
            strokeWidth = 1.5.dp
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "SEARCHING SOURCES · $done/$total",
            color = Cinema.TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private data class EpisodeSlot(
    val season: Int,
    val number: Int,
    val title: String?,
    val stillUrl: String? = null
)

private const val NEXT_UP_SECONDS = 8

@Composable
private fun NextUpCard(
    slot: EpisodeSlot,
    seconds: Int,
    total: Int,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fraction = (seconds.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(
        modifier
            .width(240.dp)
            .glassSurface(
                shape = RoundedCornerShape(CinemaDimens.ControlRadius),
                base = Glass.Sheet,
                sheen = 0.6f
            )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2.2f)
        ) {
            PosterImage(
                url = slot.stillUrl,
                title = slot.title.orEmpty(),
                // The card already names the episode, so the still stays silent.
                description = null,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.10f),
                            1f to Color.Black.copy(alpha = 0.66f)
                        )
                    )
            )
            Text(
                "UP NEXT",
                color = Cinema.Gold,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(CinemaDimens.PillRadius))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .semantics { contentDescription = "Dismiss next episode" }
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "✕",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                "S${slot.season} E${slot.number}",
                color = Cinema.Gold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                slot.title?.takeIf { it.isNotBlank() } ?: "Next episode",
                color = Cinema.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Playing next in ${seconds}s",
                color = Cinema.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.12f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(Cinema.Gold)
                )
            }
            Spacer(Modifier.height(10.dp))
            TvButton(
                label = "PLAY NOW",
                glyph = "▶",
                style = TvButtonStyle.PRIMARY,
                compact = true,
                onClick = onPlayNow
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "◀ ▶ or BACK to dismiss",
                color = Cinema.TextMuted,
                fontSize = 9.sp
            )
        }
    }
}

/**
 * Credits window for episodes the database has no marker for. A standard
 * 20–40 minute episode cuts to credits inside its last minute to ninety
 * seconds, so the offer starts there; shorter serials get a shorter window
 * and anything near a feature length keeps the widest one.
 */
private fun fallbackCreditsStartMs(durationMs: Long): Long? = when {
    durationMs >= 40 * 60_000L -> durationMs - 90_000L
    durationMs >= 20 * 60_000L -> durationMs - 75_000L
    durationMs >= 10 * 60_000L -> durationMs - 60_000L
    else -> null
}

/**
 * Caption language the viewer prefers for a title. Captions themselves still
 * start off; this only decides which track the first manual "on" lands on.
 */
private fun preferredSubtitleLanguage(profile: CinemaProfile): String? =
    when (val code = profile.subtitleLanguage) {
        "" -> null
        "auto" -> profile.audioLanguage.ifBlank { null }
        else -> code
    }

private fun trackOverride(tracks: Tracks, type: Int, languageCode: String): TrackSelectionOverride? {
    if (languageCode.isBlank()) return null
    val wanted = languageCode.lowercase()
    tracks.groups.filter { it.type == type }.forEach { group ->
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val language = group.getTrackFormat(index).language?.lowercase() ?: continue
            if (language == wanted || language.substringBefore('-') == wanted) {
                return TrackSelectionOverride(group.mediaTrackGroup, index)
            }
        }
    }
    return null
}

private fun buildEpisodeSlots(item: CatalogItem): List<EpisodeSlot> =
    item.seasons
        .sortedBy { it.number }
        .flatMap { season ->
            season.episodes.sortedBy { it.number }.map { episode ->
                EpisodeSlot(
                    season = season.number,
                    number = episode.number,
                    title = episode.title.takeIf { it.isNotBlank() },
                    stillUrl = episode.stillUrl
                )
            }
        }

private suspend fun loadStreamInfo(
    item: CatalogItem?,
    season: Int,
    episode: Int,
    episodeTitle: String?,
    source: PlaybackOption?,
    fileName: String,
    durationMs: Long,
    tracks: Tracks?,
    audioAudit: AudioAudit?,
    decoder: DecoderType,
    abr: Boolean
): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    rows += "Title" to (item?.title ?: fileName)
    if (item != null && item.isSeries) {
        val label = buildString {
            append("S").append(season).append(" E").append(episode)
            episodeTitle?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        rows += "Episode" to label
    }
    val video = firstFormat(tracks, C.TRACK_TYPE_VIDEO)
    val audio = firstFormat(tracks, C.TRACK_TYPE_AUDIO)
    rows += "Video" to describeVideo(video, durationMs)
    rows += "Audio" to describeAudio(audio)
    audioAudit?.let { audit ->
        rows += "Audio path" to audit.summary
        audit.warning?.let { warning -> rows += "Audio warning" to warning }
    }
    rows += "Duration" to if (durationMs > 0) formatTime(durationMs) else "Live / unknown"
    rows += "Decoder" to decoder.label
    rows += "Adaptive" to if (abr) "ABR enabled" else "Fixed quality"
    rows += "Source" to (source?.name ?: "Direct link")
    rows += "File" to fileName

    val url = source?.url
    if (!url.isNullOrBlank() && url.startsWith("http")) {
        rows += "URL" to (if (url.length > 76) url.take(76) + "…" else url)
        val contentLength = withTimeoutOrNull(2_500L) {
            runCatching {
                Http.client.await(Request.Builder().url(url).head().build()).use { response ->
                    response.header("Content-Length")?.toLongOrNull()
                }
            }.getOrNull()
        }
        contentLength?.takeIf { it > 0 }?.let { rows += "Size" to formatBytes(it) }
    }
    return rows
}

private fun firstFormat(tracks: Tracks?, type: Int): Format? {
    val group = tracks?.groups?.firstOrNull { group ->
        group.type == type && (0 until group.length).any { group.isTrackSupported(it) }
    } ?: return null
    val index = (0 until group.length).firstOrNull { group.isTrackSupported(it) } ?: return null
    return group.getTrackFormat(index)
}

private fun describeVideo(format: Format?, durationMs: Long): String {
    if (format == null) return "Not reported yet"
    val parts = mutableListOf<String>()
    if (format.width > 0 && format.height > 0) {
        parts += "${format.width}×${format.height}"
    }
    format.frameRate?.takeIf { it > 0f }?.let { parts += "${it.toInt()} fps" }
    format.sampleMimeType?.substringAfter('/')?.let { parts += it.uppercase() }
    format.codecs?.takeIf { it.isNotBlank() }?.let { parts += it }
    if (format.bitrate > 0) {
        parts += "${format.bitrate / 1_000} kbps"
    } else if (durationMs > 0 && format.bitrate <= 0) {
        parts += "bitrate n/a"
    }
    format.colorInfo?.let { color ->
        color.colorSpace?.let { parts += colorSpaceName(it) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "Not reported yet"
}

private fun colorSpaceName(space: Int): String = when (space) {
    C.COLOR_SPACE_BT2020 -> "BT.2020"
    C.COLOR_SPACE_BT709 -> "BT.709"
    C.COLOR_SPACE_BT601 -> "BT.601"
    else -> "Rec. $space"
}

private fun describeAudio(format: Format?): String {
    if (format == null) return "Not reported yet"
    val parts = mutableListOf<String>()
    format.language?.takeIf { it.isNotBlank() }?.let { tag ->
        parts += runCatching { Locale.forLanguageTag(tag).displayLanguage }.getOrNull()?.takeIf { it.isNotBlank() } ?: tag
    }
    format.label?.takeIf { it.isNotBlank() }?.let { parts += it }
    if (format.channelCount > 0) parts += "${format.channelCount} ch"
    format.sampleMimeType?.substringAfter('/')?.let { parts += it.uppercase() }
    format.codecs?.takeIf { it.isNotBlank() }?.let { parts += it }
    if (format.bitrate > 0) parts += "${format.bitrate / 1_000} kbps"
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "Not reported yet"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val kb = bytes / 1_024.0
    if (kb < 1_024) return "%.1f KB".format(kb)
    val mb = kb / 1_024.0
    if (mb < 1_024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1_024.0)
}

private fun formatBitrate(bitsPerSecond: Long): String {
    if (bitsPerSecond <= 0) return "—"
    if (bitsPerSecond >= 1_000_000) {
        return "%.2f Mbps".format(bitsPerSecond / 1_000_000.0)
    }
    return "${bitsPerSecond / 1_000} kbps"
}

private fun formatFrameRate(fps: Float): String =
    if (fps >= 10f) "%.0f".format(fps) else "%.2f".format(fps)

private fun formatBufferedMs(ms: Long): String {
    if (ms <= 0) return "—"
    return if (ms >= 1_000) "%.1f s".format(ms / 1_000.0) else "$ms ms"
}

/** The engine actually running, which differs from the pick after a fallback. */
private fun describeRunningEngine(player: Player?, fellBack: Boolean): String = when {
    player is VlcEnginePlayer -> "VLC"
    player is MpvEnginePlayer -> "MPV"
    fellBack -> "Built-in · fallback"
    else -> "Built-in"
}

private fun Player.asWatchdogPlayer(): WatchdogPlayer = object : WatchdogPlayer {
    override val playbackState: Int get() = this@asWatchdogPlayer.playbackState
    override val hasError: Boolean get() = playerError != null
    override val playWhenReady: Boolean get() = this@asWatchdogPlayer.playWhenReady
    override val suppressionReason: Int get() = playbackSuppressionReason
    override val currentPosition: Long get() = this@asWatchdogPlayer.currentPosition
    override val bufferedPosition: Long get() = this@asWatchdogPlayer.bufferedPosition
}
