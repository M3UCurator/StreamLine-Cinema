package com.streamline.cinema.ui.player

import androidx.media3.common.Player
import kotlin.math.abs

// Providers that accept the tune and then stall the manifest never emit a
// player error (the retry policy keeps backing off), so a live tune needs its
// own deadline before the UI is allowed to keep spinning.
internal const val LIVE_TUNE_WATCHDOG_MS = 20_000L
// A film has a bounded manifest/segment chain, but a slow CDN can still answer
// the first request and then dribble nothing. The deadline is generous enough
// for a cold TLS + manifest chain and short enough to surface the failure panel
// before the user gives up on the spinner.
internal const val VOD_TUNE_WATCHDOG_MS = 35_000L
// VLC/mpv discover every rendition of a master playlist through the loopback
// proxy before the first frame, which takes far longer than ExoPlayer's tune.
internal const val NATIVE_TUNE_WATCHDOG_MS = 60_000L
// The same applies mid-stream: a cut connection keeps the loader retrying well
// past a minute, so watch the playhead instead of waiting for Media3 to give up.
internal const val PLAYBACK_STALL_WATCHDOG_MS = 15_000L
internal const val PLAYBACK_STALL_POLL_MS = 1_000L
internal const val PLAYBACK_MIN_PROGRESS_MS = 250L
// A stall is first retried in place, because most are the provider recycling a
// session rather than a dead source. Live reconnects are cheap (no resume
// point), a film spends fewer attempts because each one costs another window.
internal const val LIVE_STALL_RETRY_LIMIT = 3
internal const val VOD_STALL_RETRY_LIMIT = 2
// Error-path reconnects share the budget: live retries outlast a film's
// because there is no playhead to lose when a channel is re-tuned.
internal const val LIVE_ERROR_RETRY_LIMIT = 3
internal const val VOD_ERROR_RETRY_LIMIT = 2

/**
 * The slice of [Player] the watchdogs observe. Keeping it this narrow lets
 * unit tests drive both state machines with a fake instead of a real player.
 */
internal interface WatchdogPlayer {
    val playbackState: Int
    val hasError: Boolean
    val playWhenReady: Boolean
    val suppressionReason: Int
    val currentPosition: Long
    val bufferedPosition: Long
}

internal enum class TuneDecision {
    /** Keep polling; the stream is still within its deadline. */
    Continue,

    /** The tune succeeded. */
    Ready,

    /** A player/native failure already owns the screen. */
    Aborted,

    /** Deadline hit and the proxy has not been bypassed yet: retry direct. */
    BypassProxy,

    /** Deadline hit with nothing left to try: surface the stall panel. */
    Failed
}

/**
 * Hard deadline for a live tune. A provider that accepts the connection and
 * then sends no manifest produces no error code, so Media3 would buffer
 * indefinitely; after [deadlineMs] the player is stopped and either retried
 * without the loopback proxy once, or reported as stalled.
 */
internal class TuneWatchdog(
    private val startedAtMs: Long,
    private val deadlineMs: Long = LIVE_TUNE_WATCHDOG_MS
) {
    fun tick(
        player: WatchdogPlayer,
        nowMs: Long,
        nativeError: Boolean,
        proxyBypassed: Boolean,
        isHls: Boolean
    ): TuneDecision = when {
        player.playbackState == Player.STATE_READY -> TuneDecision.Ready
        player.hasError || nativeError -> TuneDecision.Aborted
        nowMs - startedAtMs < deadlineMs -> TuneDecision.Continue
        !proxyBypassed && isHls -> TuneDecision.BypassProxy
        else -> TuneDecision.Failed
    }
}

internal enum class StallDecision {
    /** Not armed yet, paused, scrubbing, or audio suppressed. */
    Idle,

    /** The playhead moved (or the first sample was taken). */
    Progress,

    /** Armed and playing, but the playhead has not moved for the stall window. */
    Stalled,

    /** Playback finished. */
    Ended
}

/**
 * Mid-stream liveness check: once the stream has reached READY, watch the
 * playhead rather than the buffering state. A frozen loader keeps Media3
 * retrying for over a minute; [stallMs] catches it while still ignoring user
 * pauses, scrubs and audio-focus suppression, which reset the window.
 *
 * A seek parks the playhead while the player refills from the new position, so
 * buffer growth counts as progress too. Progress is accumulated against
 * anchors for both values, not a per-poll delta: a provider that trickles in
 * less than [minProgressMs] per poll still moves the anchor over time, so a
 * slow-but-alive stream never trips the panel while a fully frozen one never
 * accumulates the window. A jump in either direction larger than
 * [minProgressMs] is a seek and re-baselines the anchors.
 */
internal class StallWatchdog(
    private val stallMs: Long = PLAYBACK_STALL_WATCHDOG_MS,
    private val minProgressMs: Long = PLAYBACK_MIN_PROGRESS_MS
) {
    private var armed = false
    private var anchorPosition = Long.MIN_VALUE
    private var anchorBuffered = Long.MIN_VALUE
    private var lastProgressAt = Long.MIN_VALUE

    fun tick(player: WatchdogPlayer, nowMs: Long, scrubbing: Boolean): StallDecision {
        when (player.playbackState) {
            Player.STATE_ENDED -> return StallDecision.Ended
            Player.STATE_READY -> armed = true
        }
        if (!armed) return StallDecision.Idle
        if (lastProgressAt == Long.MIN_VALUE) lastProgressAt = nowMs
        val tracking = player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            !scrubbing
        if (!tracking) {
            reset(nowMs)
            return StallDecision.Idle
        }
        val position = player.currentPosition
        val buffered = player.bufferedPosition
        val moved = anchorPosition == Long.MIN_VALUE ||
            abs(position - anchorPosition) >= minProgressMs ||
            abs(buffered - anchorBuffered) >= minProgressMs
        if (moved) {
            anchorPosition = position
            anchorBuffered = buffered
            lastProgressAt = nowMs
            return StallDecision.Progress
        }
        return if (nowMs - lastProgressAt >= stallMs) StallDecision.Stalled else StallDecision.Idle
    }

    private fun reset(nowMs: Long) {
        anchorPosition = Long.MIN_VALUE
        anchorBuffered = Long.MIN_VALUE
        lastProgressAt = nowMs
    }
}

/**
 * Budget for retrying the same source before the error panel (and its source
 * failover) owns the stream. Live reconnects are cheap because a re-tune has
 * no resume point; a film spends fewer attempts because each one costs a full
 * stall window.
 */
internal fun errorRetryLimit(isLive: Boolean): Int =
    if (isLive) LIVE_ERROR_RETRY_LIMIT else VOD_ERROR_RETRY_LIMIT

internal fun stallRetryLimit(isLive: Boolean): Int =
    if (isLive) LIVE_STALL_RETRY_LIMIT else VOD_STALL_RETRY_LIMIT

// A stalled player is stopped first, so its retry only has to serialize a new
// request; transient errors keep a short backoff to let the provider recover.
internal fun stallRetryDelayMs(attempt: Int): Long = minOf(2_000L * attempt, 8_000L)

internal fun transientReconnectDelayMs(attempt: Int, isLive: Boolean): Long =
    if (isLive) minOf(5_000L * attempt, 15_000L) else minOf(3_000L * attempt, 10_000L)
