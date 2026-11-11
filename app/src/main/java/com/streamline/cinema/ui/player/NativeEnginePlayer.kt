package com.streamline.cinema.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

private const val ENGINE_POLL_MS = 500L
private const val SEEKABLE_STEP_MS = 10_000L

/** False when retrying the origin directly cannot work for this engine. */
internal fun Player.canBypassProxy(): Boolean =
    (this as? NativeEnginePlayer)?.canPlayDirectHttps ?: true

/**
 * Media3 [Player] facade over a native engine (libVLC / libmpv).
 *
 * The whole OSD, watchdogs, failover and progress tracking are written against
 * Media3, so the engines implement the same transport surface instead of a
 * second player UI. Frames are painted by the engine into [videoView]; the
 * wrapper only forwards commands and state.
 */
@OptIn(UnstableApi::class)
internal abstract class NativeEnginePlayer(
    context: Context,
    protected val isLive: Boolean
) : SimpleBasePlayer(Looper.getMainLooper()) {

    /**
     * Whether the engine's own HTTP stack can reach an https origin. When false
     * the player must keep using the loopback proxy: bypassing it would hand the
     * engine a URL it can never open.
     */
    open val canPlayDirectHttps: Boolean get() = true

    /**
     * True when the engine is decoding a video stream and audio is advancing
     * but no video output was ever created, which leaves the viewer with sound
     * over a black screen. The caller swaps engines instead of staying blank.
     */
    open fun audioOnlyStalled(): Boolean = false

    val videoView: SurfaceView = SurfaceView(context).apply {        setBackgroundColor(android.graphics.Color.BLACK)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = onSurfaceAvailable(holder.surface)

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                onSurfaceResized(width, height)

            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceLost()
        })
    }

    /**
     * View host handed to Compose. Engines that need a second surface (libVLC's
     * opaque output refuses to start without a subtitles surface) add it here
     * instead of drawing over the video surface itself.
     */
    val renderView: FrameLayout = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        addView(videoView)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaUid = Any()
    private val poller = object : Runnable {
        override fun run() {
            if (released) return
            syncFromEngine()
            mainHandler.postDelayed(this, ENGINE_POLL_MS)
        }
    }

    private var mediaItem: MediaItem? = null
    private var prepared = false
    private var released = false
    private var playbackState = Player.STATE_IDLE
    private var playWhenReady = false
    private var positionMs = 0L
    private var durationMs = C.TIME_UNSET
    private var buffering = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var volume = 1f
    private var speed = 1f
    private var playerError: PlaybackException? = null
    private var tracks: Tracks = Tracks.EMPTY
    private var trackParameters = TrackSelectionParameters.DEFAULT

    init {
        mainHandler.postDelayed(poller, ENGINE_POLL_MS)
    }

    // --- engine contract ---------------------------------------------------

    protected abstract fun startEngine(item: MediaItem, play: Boolean)

    protected abstract fun setEnginePlayWhenReady(play: Boolean)

    protected abstract fun seekEngineTo(positionMs: Long)

    protected abstract fun setEngineSpeed(speed: Float)

    protected abstract fun setEngineVolume(volume: Float)

    protected abstract fun stopEngine()

    protected abstract fun releaseEngine()

    protected abstract fun enginePositionMs(): Long

    protected abstract fun engineDurationMs(): Long

    protected abstract fun onSurfaceAvailable(surface: Surface)

    protected abstract fun onSurfaceResized(width: Int, height: Int)

    protected abstract fun onSurfaceLost()

    protected abstract fun applyTrackSelection(parameters: TrackSelectionParameters)

    // --- engines report state here -----------------------------------------

    protected fun postEngine(block: () -> Unit) {
        mainHandler.post {
            if (released) return@post
            block()
            invalidateState()
        }
    }

    protected fun engineReady() {
        postEngine {
            buffering = false
            playerError = null
            playbackState = Player.STATE_READY
        }
    }

    protected fun engineBuffering(loading: Boolean) {
        postEngine {
            buffering = loading
            playbackState = when {
                loading && playbackState != Player.STATE_IDLE -> Player.STATE_BUFFERING
                !loading && playbackState != Player.STATE_ENDED -> Player.STATE_READY
                else -> playbackState
            }
            if (!loading) playerError = null
        }
    }

    protected fun engineEnded() {
        postEngine {
            buffering = false
            playbackState = Player.STATE_ENDED
        }
    }

    protected fun engineStopped() {
        postEngine {
            buffering = false
            playbackState = Player.STATE_IDLE
        }
    }

    protected fun engineFailed(detail: String) {
        postEngine {
            buffering = false
            playbackState = Player.STATE_IDLE
            playerError = PlaybackException(
                detail,
                null,
                PlaybackException.ERROR_CODE_UNSPECIFIED
            )
        }
    }

    protected fun engineVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        postEngine {
            videoWidth = width
            videoHeight = height
        }
    }

    protected fun engineTracks(value: Tracks) {
        postEngine { tracks = value }
    }

    protected fun engineDuration(duration: Long) {
        if (duration <= 0 || duration == durationMs) return
        postEngine { durationMs = duration }
    }

    protected fun trackGroup(
        id: String,
        label: String?,
        language: String?,
        mimeType: String,
        selected: Boolean
    ): Tracks.Group = Tracks.Group(
        TrackGroup(
            Format.Builder()
                .setId(id)
                .setLabel(label)
                .setLanguage(language)
                .setSampleMimeType(mimeType)
                .build()
        ),
        false,
        intArrayOf(C.FORMAT_HANDLED),
        booleanArrayOf(selected)
    )

    /**
     * The facade has no video track of its own, but exposing one lets the
     * player read the engine's frame rate and drive display matching the same
     * way it does for Media3's `videoFormat`.
     */
    protected fun videoTrackGroup(width: Int, height: Int, frameRate: Float): Tracks.Group =
        Tracks.Group(
            TrackGroup(
                Format.Builder()
                    .setId("engine-video")
                    .setSampleMimeType("video/*")
                    .setWidth(width)
                    .setHeight(height)
                    .apply { if (frameRate > 0f) setFrameRate(frameRate) }
                    .build()
            ),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(true)
        )

    private fun syncFromEngine() {
        if (!prepared || released) return
        var dirty = false
        val position = runCatching { enginePositionMs() }.getOrDefault(-1L)
        if (position >= 0 && position != positionMs) {
            positionMs = position
            dirty = true
        }
        val duration = runCatching { engineDurationMs() }.getOrDefault(-1L)
        if (duration > 0 && duration != durationMs) {
            durationMs = duration
            dirty = true
        }
        // A stream that stops sending without an end event must not leave the
        // state machine parked on READY forever; the watchdog reads this.
        if (dirty) invalidateState()
    }

    // --- Media3 hooks ------------------------------------------------------

    override fun getState(): State {
        val item = mediaItem
        val data = item?.let {
            MediaItemData.Builder(mediaUid)
                .setMediaItem(it)
                .setMediaMetadata(it.mediaMetadata)
                .setDurationUs(if (durationMs > 0) durationMs * 1_000 else C.TIME_UNSET)
                .setIsSeekable(durationMs > 0)
                .setIsDynamic(isLive)
                .setTracks(tracks)
                .build()
        }
        val position = if (playbackState == Player.STATE_READY) {
            PositionSupplier.getExtrapolating(positionMs, speed)
        } else {
            PositionSupplier.getConstant(positionMs)
        }
        val builder = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(if (data != null) listOf(data) else emptyList())
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            // Media3 rejects isLoading on IDLE/ENDED; a stale buffer flag after
            // a stop must not crash the state build.
            .setIsLoading(
                buffering &&
                    playbackState != Player.STATE_IDLE &&
                    playbackState != Player.STATE_ENDED
            )
            .setPlaybackParameters(PlaybackParameters(speed))
            .setVolume(volume)
            .setTrackSelectionParameters(trackParameters)
            .setVideoSize(VideoSize(videoWidth, videoHeight))
            .setSeekBackIncrementMs(SEEKABLE_STEP_MS)
            .setSeekForwardIncrementMs(SEEKABLE_STEP_MS)
            .setContentPositionMs(position)
            .setContentBufferedPositionMs(PositionSupplier.getConstant(positionMs))
        playerError?.let { builder.setPlayerError(it) }
        return builder.build()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        android.util.Log.d("StreamLine:engine", "setMedia ${mediaItems.size} idx=$startIndex")
        // setMediaItem(MediaItem) reaches this hook with C.INDEX_UNSET (-1), so a
        // negative index means "the default item", not "no item".
        mediaItem = mediaItems.getOrNull(if (startIndex in mediaItems.indices) startIndex else 0)
        prepared = false
        playbackState = Player.STATE_IDLE
        positionMs = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        durationMs = C.TIME_UNSET
        buffering = false
        playerError = null
        tracks = Tracks.EMPTY
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        android.util.Log.d(
            "StreamLine:engine",
            "prepare item=${mediaItem?.localConfiguration?.uri} prepared=$prepared playWhenReady=$playWhenReady"
        )
        val item = mediaItem ?: return Futures.immediateVoidFuture()
        if (prepared) return Futures.immediateVoidFuture()
        prepared = true
        playbackState = Player.STATE_BUFFERING
        buffering = true
        playerError = null
        invalidateState()
        runCatching { startEngine(item, playWhenReady) }
            .onFailure { engineFailed(it.message ?: "The engine could not open this stream.") }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        prepared = false
        playbackState = Player.STATE_IDLE
        buffering = false
        positionMs = 0L
        durationMs = C.TIME_UNSET
        runCatching { stopEngine() }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        mainHandler.removeCallbacks(poller)
        runCatching { releaseEngine() }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (prepared) runCatching { setEnginePlayWhenReady(playWhenReady) }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        this.positionMs = positionMs.coerceAtLeast(0L)
        if (prepared) runCatching { seekEngineTo(this.positionMs) }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(
        playbackParameters: PlaybackParameters
    ): ListenableFuture<*> {
        speed = playbackParameters.speed.coerceIn(0.25f, 4f)
        if (prepared) runCatching { setEngineSpeed(speed) }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> =
        applyVolume(volume)

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> =
        applyVolume(volume)

    private fun applyVolume(value: Float): ListenableFuture<*> {
        volume = value.coerceIn(0f, 1f)
        if (prepared) runCatching { setEngineVolume(volume) }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(
        parameters: TrackSelectionParameters
    ): ListenableFuture<*> {
        trackParameters = parameters
        if (prepared) runCatching { applyTrackSelection(parameters) }
        invalidateState()
        return Futures.immediateVoidFuture()
    }
}
