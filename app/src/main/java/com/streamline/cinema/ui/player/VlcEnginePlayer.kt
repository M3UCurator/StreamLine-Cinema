package com.streamline.cinema.ui.player

import android.content.Context
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import kotlin.math.roundToInt

private const val AUDIO_ID_PREFIX = "vlc-audio-"
private const val TEXT_ID_PREFIX = "vlc-text-"

// Opening the input before the SurfaceView owns a surface makes libVLC reject
// the opaque output and fall back to GLES, which paints black frames: audio
// plays while the picture never shows.
private const val SURFACE_WAIT_MS = 4_000L

// Give the demuxer enough time to publish the video ES and start audio before
// declaring that no video output will ever arrive.
private const val AUDIO_ONLY_GRACE_MS = 6_000L

/**
 * libVLC engine profile. The option set trades a little latency for resilience:
 * every network tier keeps a cache large enough to ride out provider packet loss
 * without tearing down the tune (the behaviour the "packet-loss recovery"
 * profile is chosen for).
 */
@OptIn(UnstableApi::class)
internal class VlcEnginePlayer(
    context: Context,
    private val userAgent: String,
    private val headers: Map<String, String>,
    private val abrEnabled: Boolean,
    isLive: Boolean,
    private val cachingMs: Int,
    private val amlogicFix: Boolean
) : NativeEnginePlayer(context.applicationContext, isLive) {

    private val appContext = context.applicationContext
    private val libVlc = LibVLC(appContext, buildOptions())
    private val player = try {
        MediaPlayer(libVlc)
    } catch (t: Throwable) {
        // A failed construction still holds the native instance; release it
        // rather than leaving it to the finalizer assertion.
        libVlc.release()
        throw t
    }
    private var media: Media? = null
    private var attached = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var surfaceReady = false
    private var startPending = false
    // Set from libVLC's event thread, read from the player thread.
    @Volatile private var videoOutSeen = false
    private var startedAtMs = 0L

    /**
     * libVLC's opaque Android output refuses to start without a subtitles
     * surface and falls back to gles2, which then paints black. The extra
     * (translucent, media-overlay) surface is what the official
     * VLCVideoLayout build supplies; without it the picture never shows.
     */
    private val subtitlesView = SurfaceView(videoView.context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
    }

    private val layoutListener = IVLCVout.OnNewVideoLayoutListener { _, _, _, _, _, _, _ ->
        // The window keeps the SurfaceView's size; only the picture inside it
        // follows the video's aspect ratio.
        if (videoView.width > 0 && videoView.height > 0) {
            player.vlcVout.setWindowSize(videoView.width, videoView.height)
        }
    }

    init {
        // The provider binds its tokens to this UA; libVLC defaults to VLC's
        // own agent, which some CDNs answer with 403.
        libVlc.setUserAgent("StreamLineCinema", userAgent)
        Log.i(
            "StreamLine:engine",
            "vlc caching=${cachingMs}ms amlogic=$amlogicFix hw=" +
                (if (amlogicFix) "mediacodec+mediacodec-dr" else "any")
        )
        renderView.addView(subtitlesView)
        player.vlcVout.addCallback(object : IVLCVout.Callback {
            override fun onSurfacesCreated(vout: IVLCVout) {
                attached = true
            }

            override fun onSurfacesDestroyed(vout: IVLCVout) {
                attached = false
            }
        })
        player.setEventListener { event -> onMediaEvent(event) }
        bindVout()
    }

    /**
     * Attaching before the SurfaceView has a surface is the supported order:
     * libVLC registers for the holder's callbacks and picks the surface up when
     * it arrives. Re-binding only when detached keeps a re-created window from
     * being torn down behind an attached view.
     */
    private fun bindVout() {
        val vout = player.vlcVout
        if (vout.areViewsAttached()) return
        runCatching {
            vout.setVideoView(videoView)
            vout.setSubtitlesView(subtitlesView)
            vout.attachViews(layoutListener)
        }
    }

    // LibVLC appends its own defaults to this list, so it must stay mutable.
    // Late frames are dropped and the decoder is allowed to skip: refusing both
    // Makes a slow frame queue grow until the picture lags the audio instead of
    // simply losing the frame. Network and live pre-roll follow Settings →
    // Video buffer size; BALANCED maps to the 1.5 s both used before the tier.
    // The Amlogic fix pins the MediaCodec module and its direct output, which is
    // the vendor's frame path instead of avcodec-hw's generic "any".
    private fun buildOptions(): MutableList<String> = mutableListOf(
        if (amlogicFix) "--avcodec-hw=mediacodec" else "--avcodec-hw=any",
        "--network-caching=$cachingMs",
        "--live-caching=$cachingMs",
        "--file-caching=3000",
        // No --http-reconnect: VLC then reopens the source at EOF, so a finite
        // HLS master playlist is re-downloaded forever instead of parsed.
        "--adaptive-logic=" + if (abrEnabled) "predictive" else "highest",
        "--no-video-title-show"
    ).apply {
        if (amlogicFix) add("--mediacodec-dr")
    }

    private fun onMediaEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                engineReady()
                refreshTracks()
                updateVideoSize()
            }
            MediaPlayer.Event.Buffering -> engineBuffering(event.buffering < 100f)
            MediaPlayer.Event.Paused -> engineReady()
            MediaPlayer.Event.EndReached -> engineEnded()
            MediaPlayer.Event.Stopped -> engineStopped()
            MediaPlayer.Event.EncounteredError ->
                engineFailed("VLC could not play this stream.")
            MediaPlayer.Event.LengthChanged -> engineDuration(event.lengthChanged)
            MediaPlayer.Event.Vout -> {
                if (!videoOutSeen) {
                    videoOutSeen = true
                    Log.d("StreamLine:engine", "VLC video output created")
                    // The decoder can fill in the frame rate after ESSelected;
                    // republish so the player sees it for AFR.
                    refreshTracks()
                }
                updateVideoSize()
            }
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected -> refreshTracks()
        }
    }

    private fun updateVideoSize() {
        val track = player.currentVideoTrack ?: return
        engineVideoSize(track.width, track.height)
    }

    private fun refreshTracks() {
        val audioCurrent = player.audioTrack
        val textCurrent = player.spuTrack
        val groups = mutableListOf<Tracks.Group>()
        runCatching { player.currentVideoTrack }.getOrNull()?.let { video ->
            val fps = if (video.frameRateNum > 0 && video.frameRateDen > 0) {
                video.frameRateNum.toFloat() / video.frameRateDen
            } else {
                0f
            }
            groups += videoTrackGroup(video.width, video.height, fps)
        }
        player.audioTracks?.forEach { track ->
            groups += trackGroup(
                id = "$AUDIO_ID_PREFIX${track.id}",
                label = track.name,
                language = null,
                mimeType = "audio/mpeg",
                selected = track.id == audioCurrent
            )
        }
        player.spuTracks?.forEach { track ->
            groups += trackGroup(
                id = "$TEXT_ID_PREFIX${track.id}",
                label = track.name,
                language = null,
                mimeType = "text/vtt",
                selected = track.id == textCurrent
            )
        }
        engineTracks(Tracks(groups))
    }

    override fun startEngine(item: MediaItem, play: Boolean) {
        val url = item.localConfiguration?.uri?.toString() ?: return
        val next = Media(libVlc, Uri.parse(url)).apply {
            addOption(":network-caching=$cachingMs")
            addOption(":live-caching=$cachingMs")
            addOption(":http-user-agent=$userAgent")
            headers.entries
                .firstOrNull { it.key.equals("Referer", true) }
                ?.value
                ?.let { addOption(":http-referrer=$it") }
            item.localConfiguration?.subtitleConfigurations?.forEachIndexed { index, subtitle ->
                runCatching {
                    addSlave(
                        IMedia.Slave(IMedia.Slave.Type.Subtitle, 4 + index, subtitle.uri.toString())
                    )
                }
            }
            if (!play) addOption(":start-paused")
        }
        media?.release()
        media = next
        player.setMedia(next)
        videoOutSeen = false
        startedAtMs = SystemClock.elapsedRealtime()
        if (surfaceReady) {
            player.play()
        } else {
            startPending = true
            mainHandler.postDelayed(::flushStart, SURFACE_WAIT_MS)
        }
    }

    private fun flushStart() {
        if (!startPending) return
        startPending = false
        runCatching { player.play() }
    }

    /**
     * See [audioOnlyStalled]. libVLC reports the creation of its video output
     * through [MediaPlayer.Event.Vout]; if that never fires while audio runs
     * and the media has a video track, the engine has no usable output.
     */
    override fun audioOnlyStalled(): Boolean {
        if (videoOutSeen || startedAtMs == 0L) return false
        if (SystemClock.elapsedRealtime() - startedAtMs < AUDIO_ONLY_GRACE_MS) return false
        val videoTracks = runCatching { player.videoTracksCount }.getOrDefault(0)
        if (videoTracks <= 0) return false
        return runCatching { player.isPlaying && player.time > 2_000 }.getOrDefault(false)
    }

    override fun setEnginePlayWhenReady(play: Boolean) {
        if (startPending) {
            // The input has not been opened yet; a pending play will open it.
            if (!play) startPending = false
            return
        }
        if (play) player.play() else runCatching { player.pause() }
    }

    override fun seekEngineTo(positionMs: Long) {
        player.setTime(positionMs)
    }

    override fun setEngineSpeed(speed: Float) {
        player.rate = speed
    }

    override fun setEngineVolume(volume: Float) {
        player.setVolume((volume * 100f).roundToInt().coerceIn(0, 100))
    }

    override fun stopEngine() {
        runCatching { player.stop() }
    }

    override fun releaseEngine() {
        startPending = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { player.setEventListener(null) }
        runCatching { if (player.vlcVout.areViewsAttached()) player.vlcVout.detachViews() }
        runCatching { player.release() }
        runCatching { media?.release() }
        media = null
        runCatching { libVlc.release() }
    }

    override fun enginePositionMs(): Long = player.time

    override fun engineDurationMs(): Long = player.length

    override fun onSurfaceAvailable(surface: Surface) {
        surfaceReady = true
        bindVout()
        if (videoView.width > 0 && videoView.height > 0) {
            player.vlcVout.setWindowSize(videoView.width, videoView.height)
        }
        flushStart()
    }

    override fun onSurfaceResized(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        player.vlcVout.setWindowSize(width, height)
    }

    override fun onSurfaceLost() {
        surfaceReady = false
        if (attached) runCatching { player.vlcVout.detachViews() }
    }

    override fun applyTrackSelection(parameters: TrackSelectionParameters) {
        val audio = nativeTrackId(parameters, C.TRACK_TYPE_AUDIO, AUDIO_ID_PREFIX)
        if (audio != null) runCatching { player.setAudioTrack(audio) }
        val text = nativeTrackId(parameters, C.TRACK_TYPE_TEXT, TEXT_ID_PREFIX)
        if (text != null) {
            runCatching { player.setSpuTrack(text) }
        } else if (parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            runCatching { player.setSpuTrack(-1) }
        }
    }

    private fun nativeTrackId(
        parameters: TrackSelectionParameters,
        trackType: Int,
        prefix: String
    ): Int? {
        val override = parameters.overrides.values
            .firstOrNull { it.mediaTrackGroup.type == trackType }
            ?: return null
        val index = override.trackIndices.firstOrNull() ?: return null
        return override.mediaTrackGroup.getFormat(index).id
            ?.removePrefix(prefix)
            ?.toIntOrNull()
    }
}
