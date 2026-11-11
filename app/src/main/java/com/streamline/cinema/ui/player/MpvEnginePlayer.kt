package com.streamline.cinema.ui.player

import android.content.Context
import android.os.Build
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import dev.jdtech.mpv.MPVLib
import kotlin.math.roundToLong

private const val MPV_AUDIO_ID_PREFIX = "mpv-audio-"
private const val MPV_TEXT_ID_PREFIX = "mpv-text-"

/**
 * libmpv engine: mpv is driven directly with its own demuxer/decoder stack,
 * which plays containers and feeds Media3 rejects. Rendering and subtitles go
 * through mpv's gpu/libass output on the attached surface.
 */
@OptIn(UnstableApi::class)
internal class MpvEnginePlayer(
    context: Context,
    private val userAgent: String,
    private val headers: Map<String, String>,
    isLive: Boolean,
    cacheSeconds: Int,
    private val amlogicFix: Boolean
) : NativeEnginePlayer(context.applicationContext, isLive) {

    // The bundled FFmpeg has no Android CA store, so https origins fail at the
    // TLS handshake; every secure stream has to arrive through the loopback proxy.
    override val canPlayDirectHttps: Boolean get() = false

    private val mpv = requireNotNull(MPVLib.create(context.applicationContext)) {
        "libmpv could not create a player"
    }
    private var stopping = false
    private var loaded = false
    private val pendingSubtitles = mutableListOf<String>()

    private val observer = object : MPVLib.EventObserver {
        override fun event(ev: Int) {
            android.util.Log.d("StreamLine:engine", "mpv event $ev")
            when (ev) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                    loaded = false
                    engineBuffering(true)
                }
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    loaded = true
                    engineReady()
                    attachPendingSubtitles()
                    refreshTracks()
                    updateVideoSize()
                }
                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    engineReady()
                    refreshTracks()
                }
                MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> updateVideoSize()
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    when {
                        stopping -> {
                            stopping = false
                            engineStopped()
                        }
                        eofReached() -> engineEnded()
                        loaded -> engineFailed("MPV could not play this stream.")
                    }
                }
            }
        }

        override fun eventProperty(name: String) {
            if (name == "track-list") refreshTracks()
        }

        override fun eventProperty(name: String, value: Long) = Unit

        override fun eventProperty(name: String, value: Double) = Unit

        override fun eventProperty(name: String, value: Boolean) = Unit

        override fun eventProperty(name: String, value: String) = Unit
    }

    init {
        mpv.setOptionString("terminal", "no")
        mpv.setOptionString("idle", "yes")
        mpv.setOptionString("keep-open", "no")
        // The Amlogic fix pins MediaCodec's direct output instead of letting
        // auto-safe negotiate; that is the path the vendor firmware parses.
        mpv.setOptionString("hwdec", if (amlogicFix) "mediacodec" else "auto-safe")
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("cache", "yes")
        // Follows Settings → Video buffer size; before the tier existed mpv
        // always held 10 s, which is what BALANCED still maps to.
        mpv.setOptionString("cache-secs", cacheSeconds.toString())
        android.util.Log.i(
            "StreamLine:engine",
            "mpv cache-secs=$cacheSeconds amlogic=$amlogicFix hwdec=" +
                (if (amlogicFix) "mediacodec" else "auto-safe")
        )
        // cap the demuxer queues: mpv's defaults can hold hundreds of MB on a
        // long live stream, which is more than a low-RAM box has to spare
        mpv.setOptionString("demuxer-max-bytes", "32MiB")
        mpv.setOptionString("demuxer-max-back-bytes", "8MiB")
        // FFmpeg probes every variant of a master playlist before the first
        // frame. The 5 MB / 5 s defaults make that probe read large chunks of
        // each rendition through the loopback proxy (nearly a minute on slow
        // devices); 128 KB / 2 s is enough to detect HLS codecs.
        mpv.setOptionString("demuxer-lavf-probesize", "131072")
        mpv.setOptionString("demuxer-lavf-analyzeduration", "2")
        mpv.setOptionString("network-timeout", "15")
        mpv.setOptionString("user-agent", userAgent)
        headerFields(userAgent, headers)?.let { mpv.setOptionString("http-header-fields", it) }
        mpv.setOptionString("ytdl", "no")
        mpv.addObserver(observer)
        mpv.init()
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
    }

    private fun headerFields(userAgent: String, headers: Map<String, String>): String? {
        val fields = buildList {
            add("User-Agent: $userAgent")
            headers.forEach { (key, value) ->
                if (!key.equals("User-Agent", true)) add("$key: $value")
            }
        }
        return fields.joinToString(",").takeIf { it.isNotBlank() }
    }

    private fun eofReached(): Boolean =
        runCatching { mpv.getPropertyBoolean("eof-reached") }.getOrNull() == true

    private fun updateVideoSize() {
        val width = runCatching { mpv.getPropertyInt("width") }.getOrNull() ?: return
        val height = runCatching { mpv.getPropertyInt("height") }.getOrNull() ?: return
        engineVideoSize(width, height)
    }

    /** Container-declared rate, which is what display matching needs. */
    private fun sourceFrameRate(): Float =
        runCatching { mpv.getPropertyDouble("container-fps") }
            .getOrNull()
            ?.takeIf { it > 0.0 && !it.isNaN() }
            ?.toFloat()
            ?: 0f

    private fun attachPendingSubtitles() {
        if (pendingSubtitles.isEmpty()) return
        pendingSubtitles.forEachIndexed { index, url ->
            runCatching {
                mpv.command(arrayOf("sub-add", url, if (index == 0) "select" else "auto"))
            }
        }
        pendingSubtitles.clear()
    }

    private fun refreshTracks() {
        val groups = mutableListOf<Tracks.Group>()
        val width = runCatching { mpv.getPropertyInt("width") }.getOrNull() ?: 0
        val height = runCatching { mpv.getPropertyInt("height") }.getOrNull() ?: 0
        if (width > 0 && height > 0) {
            groups += videoTrackGroup(width, height, sourceFrameRate())
        }
        val count = runCatching { mpv.getPropertyInt("track-list/count") }.getOrNull() ?: 0
        for (index in 0 until count) {
            val type = runCatching { mpv.getPropertyString("track-list/$index/type") }
                .getOrNull() ?: continue
            val id = runCatching { mpv.getPropertyInt("track-list/$index/id") }
                .getOrNull() ?: continue
            if (id < 0) continue
            val title = runCatching { mpv.getPropertyString("track-list/$index/title") }
                .getOrNull()
            val language = runCatching { mpv.getPropertyString("track-list/$index/lang") }
                .getOrNull()
            val selected = runCatching { mpv.getPropertyBoolean("track-list/$index/selected") }
                .getOrNull() == true
            when (type) {
                "audio" -> groups += trackGroup(
                    id = "$MPV_AUDIO_ID_PREFIX$id",
                    label = title,
                    language = language,
                    mimeType = "audio/mpeg",
                    selected = selected
                )
                "sub" -> groups += trackGroup(
                    id = "$MPV_TEXT_ID_PREFIX$id",
                    label = title,
                    language = language,
                    mimeType = "text/vtt",
                    selected = selected
                )
            }
        }
        engineTracks(Tracks(groups))
    }

    override fun startEngine(item: MediaItem, play: Boolean) {
        val url = item.localConfiguration?.uri?.toString() ?: return
        android.util.Log.d("StreamLine:engine", "mpv loadfile $url play=$play")
        stopping = false
        loaded = false
        pendingSubtitles.clear()
        item.localConfiguration?.subtitleConfigurations?.forEach { subtitle ->
            pendingSubtitles += subtitle.uri.toString()
        }
        mpv.command(arrayOf("loadfile", url, "replace"))
        mpv.setPropertyBoolean("pause", !play)
    }

    override fun setEnginePlayWhenReady(play: Boolean) {
        runCatching { mpv.setPropertyBoolean("pause", !play) }
    }

    override fun seekEngineTo(positionMs: Long) {
        runCatching { mpv.setPropertyDouble("time-pos", positionMs / 1000.0) }
    }

    override fun setEngineSpeed(speed: Float) {
        runCatching { mpv.setPropertyDouble("speed", speed.toDouble()) }
    }

    override fun setEngineVolume(volume: Float) {
        runCatching { mpv.setPropertyDouble("volume", (volume * 100f).toDouble()) }
    }

    override fun stopEngine() {
        stopping = true
        runCatching { mpv.command(arrayOf("stop")) }
    }

    override fun releaseEngine() {
        runCatching { mpv.removeObserver(observer) }
        runCatching { mpv.detachSurface() }
        runCatching { mpv.destroy() }
    }

    override fun enginePositionMs(): Long {
        if (!loaded) return -1L
        val seconds = runCatching { mpv.getPropertyDouble("time-pos") }.getOrNull() ?: return -1L
        if (seconds.isNaN() || seconds < 0) return -1L
        return (seconds * 1000.0).roundToLong()
    }

    override fun engineDurationMs(): Long {
        if (!loaded) return -1L
        val seconds = runCatching { mpv.getPropertyDouble("duration") }.getOrNull() ?: return -1L
        if (seconds.isNaN() || seconds <= 0) return -1L
        return (seconds * 1000.0).roundToLong()
    }

    override fun onSurfaceAvailable(surface: Surface) {
        runCatching { mpv.attachSurface(surface) }
    }

    override fun onSurfaceResized(width: Int, height: Int) = Unit

    override fun onSurfaceLost() {
        runCatching { mpv.detachSurface() }
    }

    override fun applyTrackSelection(parameters: TrackSelectionParameters) {
        nativeTrackId(parameters, C.TRACK_TYPE_AUDIO, MPV_AUDIO_ID_PREFIX)?.let { id ->
            runCatching { mpv.setPropertyInt("aid", id) }
        }
        val textId = nativeTrackId(parameters, C.TRACK_TYPE_TEXT, MPV_TEXT_ID_PREFIX)
        when {
            textId != null -> runCatching { mpv.setPropertyInt("sid", textId) }
            parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ->
                runCatching { mpv.command(arrayOf("set", "sid", "no")) }
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

    companion object {
        /** libmpv's AAR targets API 26; below that the engine stays hidden. */
        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}
