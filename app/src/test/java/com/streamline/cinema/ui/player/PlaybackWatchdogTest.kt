package com.streamline.cinema.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePlayer(
    override var playbackState: Int = Player.STATE_BUFFERING,
    override var hasError: Boolean = false,
    override var playWhenReady: Boolean = true,
    override var suppressionReason: Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
    override var currentPosition: Long = 0L,
    override var bufferedPosition: Long = 0L
) : WatchdogPlayer

class PlaybackWatchdogTest {

    // --- tune deadline ---------------------------------------------------

    @Test
    fun tuneKeepsWaitingBeforeTheDeadline() {
        val watchdog = TuneWatchdog(startedAtMs = 1_000L, deadlineMs = 20_000L)
        val decision = watchdog.tick(
            FakePlayer(playbackState = Player.STATE_BUFFERING),
            nowMs = 20_999L,
            nativeError = false,
            proxyBypassed = false,
            isHls = true
        )
        assertEquals(TuneDecision.Continue, decision)
    }

    @Test
    fun tuneStopsAtTheDeadlineEvenAtTheBoundary() {
        val watchdog = TuneWatchdog(startedAtMs = 1_000L, deadlineMs = 20_000L)
        val decision = watchdog.tick(
            FakePlayer(playbackState = Player.STATE_BUFFERING),
            nowMs = 21_000L,
            nativeError = false,
            proxyBypassed = false,
            isHls = true
        )
        assertEquals(TuneDecision.BypassProxy, decision)
    }

    @Test
    fun tuneReadyWinsOverAnExpiredDeadline() {
        val watchdog = TuneWatchdog(startedAtMs = 0L, deadlineMs = 20_000L)
        assertEquals(
            TuneDecision.Ready,
            watchdog.tick(FakePlayer(playbackState = Player.STATE_READY), 90_000L, false, false, true)
        )
    }

    @Test
    fun tuneAbortsWhenAnErrorAlreadyOwnsTheScreen() {
        val watchdog = TuneWatchdog(startedAtMs = 0L)
        assertEquals(
            TuneDecision.Aborted,
            watchdog.tick(FakePlayer(hasError = true), 1_000L, false, false, true)
        )
        assertEquals(
            TuneDecision.Aborted,
            watchdog.tick(FakePlayer(), 1_000L, nativeError = true, proxyBypassed = false, isHls = true)
        )
    }

    @Test
    fun tuneFailsWhenTheProxyWasAlreadyBypassed() {
        val watchdog = TuneWatchdog(startedAtMs = 0L, deadlineMs = 20_000L)
        assertEquals(
            TuneDecision.Failed,
            watchdog.tick(FakePlayer(), 25_000L, false, proxyBypassed = true, isHls = true)
        )
    }

    @Test
    fun vodDeadlineIsMoreGenerousThanLiveButStillBounded() {
        val watchdog = TuneWatchdog(startedAtMs = 0L, deadlineMs = VOD_TUNE_WATCHDOG_MS)
        assertEquals(
            TuneDecision.Continue,
            watchdog.tick(FakePlayer(), VOD_TUNE_WATCHDOG_MS - 1L, false, false, isHls = true)
        )
        assertEquals(
            TuneDecision.BypassProxy,
            watchdog.tick(FakePlayer(), VOD_TUNE_WATCHDOG_MS, false, false, isHls = true)
        )
        assertEquals(
            TuneDecision.Failed,
            watchdog.tick(FakePlayer(), VOD_TUNE_WATCHDOG_MS, false, true, isHls = true)
        )
        assertTrue(VOD_TUNE_WATCHDOG_MS > LIVE_TUNE_WATCHDOG_MS)
    }

    @Test
    fun tuneOnlyBypassesTheProxyForHls() {
        val watchdog = TuneWatchdog(startedAtMs = 0L, deadlineMs = 20_000L)
        assertEquals(
            TuneDecision.Failed,
            watchdog.tick(FakePlayer(), 25_000L, false, proxyBypassed = false, isHls = false)
        )
    }

    // --- mid-stream stall ------------------------------------------------

    @Test
    fun stallIsNotArmedUntilTheStreamReachedReady() {
        val watchdog = StallWatchdog(stallMs = 15_000L)
        val player = FakePlayer(playbackState = Player.STATE_BUFFERING)
        assertEquals(StallDecision.Idle, watchdog.tick(player, 0L, scrubbing = false))
        assertEquals(StallDecision.Idle, watchdog.tick(player, 60_000L, scrubbing = false))
    }

    @Test
    fun movingPlayheadNeverStalls() {
        val watchdog = StallWatchdog(stallMs = 15_000L, minProgressMs = 250L)
        val player = FakePlayer(playbackState = Player.STATE_READY, currentPosition = 0L)
        var now = 0L
        repeat(60) {
            now += 1_000L
            player.currentPosition += 1_000L
            assertEquals(StallDecision.Progress, watchdog.tick(player, now, scrubbing = false))
        }
    }

    @Test
    fun frozenPlayheadStallsAfterTheWindow() {
        val watchdog = StallWatchdog(stallMs = 15_000L, minProgressMs = 250L)
        val player = FakePlayer(playbackState = Player.STATE_READY, currentPosition = 5_000L)
        // First tick samples the playhead and starts the window.
        assertEquals(StallDecision.Progress, watchdog.tick(player, 0L, scrubbing = false))
        assertEquals(StallDecision.Idle, watchdog.tick(player, 14_999L, scrubbing = false))
        assertEquals(StallDecision.Stalled, watchdog.tick(player, 15_000L, scrubbing = false))
    }

    @Test
    fun subThresholdJitterIsNotProgress() {
        val watchdog = StallWatchdog(stallMs = 15_000L, minProgressMs = 250L)
        val player = FakePlayer(playbackState = Player.STATE_READY, currentPosition = 10_000L)
        assertEquals(StallDecision.Progress, watchdog.tick(player, 0L, scrubbing = false))
        var now = 0L
        repeat(10) {
            now += 1_000L
            player.currentPosition = 10_000L + if (it % 2 == 0) 100L else 0L
            watchdog.tick(player, now, scrubbing = false)
        }
        player.currentPosition = 10_000L
        assertEquals(StallDecision.Stalled, watchdog.tick(player, 15_000L, scrubbing = false))
    }

    @Test
    fun pauseResetsTheStallWindow() {
        val watchdog = StallWatchdog(stallMs = 15_000L, minProgressMs = 250L)
        val player = FakePlayer(playbackState = Player.STATE_READY, currentPosition = 1_000L)
        assertEquals(StallDecision.Progress, watchdog.tick(player, 0L, scrubbing = false))
        assertEquals(StallDecision.Idle, watchdog.tick(player, 10_000L, scrubbing = false))

        player.playWhenReady = false
        assertEquals(StallDecision.Idle, watchdog.tick(player, 12_000L, scrubbing = false))
        player.playWhenReady = true
        // The window restarts at the resume, so 14 s later it is still alive.
        assertEquals(StallDecision.Progress, watchdog.tick(player, 26_000L, scrubbing = false))
        assertEquals(StallDecision.Idle, watchdog.tick(player, 40_000L, scrubbing = false))
        assertEquals(StallDecision.Stalled, watchdog.tick(player, 41_000L, scrubbing = false))
    }

    @Test
    fun scrubbingAndAudioSuppressionResetTheWindow() {
        val watchdog = StallWatchdog(stallMs = 15_000L, minProgressMs = 250L)
        val player = FakePlayer(playbackState = Player.STATE_READY, currentPosition = 1_000L)
        assertEquals(StallDecision.Progress, watchdog.tick(player, 0L, scrubbing = false))

        assertEquals(StallDecision.Idle, watchdog.tick(player, 10_000L, scrubbing = true))
        player.suppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        assertEquals(StallDecision.Idle, watchdog.tick(player, 20_000L, scrubbing = false))
        player.suppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE
        assertEquals(StallDecision.Progress, watchdog.tick(player, 34_000L, scrubbing = false))
        assertEquals(StallDecision.Stalled, watchdog.tick(player, 49_000L, scrubbing = false))
    }

    @Test
    fun endedAndIdleStopTracking() {
        val watchdog = StallWatchdog(stallMs = 15_000L)
        val player = FakePlayer(playbackState = Player.STATE_READY)
        assertEquals(StallDecision.Progress, watchdog.tick(player, 0L, scrubbing = false))
        player.playbackState = Player.STATE_ENDED
        assertEquals(StallDecision.Ended, watchdog.tick(player, 15_000L, scrubbing = false))

        val second = StallWatchdog(stallMs = 15_000L, minProgressMs = 250L)
        val idle = FakePlayer(playbackState = Player.STATE_READY, currentPosition = 2_000L)
        assertEquals(StallDecision.Progress, second.tick(idle, 0L, scrubbing = false))
        idle.playbackState = Player.STATE_IDLE
        assertEquals(StallDecision.Idle, second.tick(idle, 5_000L, scrubbing = false))
    }

    @Test
    fun seekRebufferingWithAGrowingBufferIsNotAStall() {
        val watchdog = StallWatchdog(stallMs = 15_000L, minProgressMs = 250L)
        val player = FakePlayer(
            playbackState = Player.STATE_READY,
            currentPosition = 60_000L,
            bufferedPosition = 75_000L
        )
        assertEquals(StallDecision.Progress, watchdog.tick(player, 0L, scrubbing = false))
        // The user seeks: the playhead parks on the new spot while it downloads.
        player.currentPosition = 120_000L
        player.playbackState = Player.STATE_BUFFERING
        player.bufferedPosition = 121_000L
        assertEquals(StallDecision.Progress, watchdog.tick(player, 1_000L, scrubbing = false))
        var now = 1_000L
        repeat(30) {
            now += 1_000L
            player.bufferedPosition += 1_000L
            assertEquals(StallDecision.Progress, watchdog.tick(player, now, scrubbing = false))
        }
    }

    @Test
    fun bufferGrowthThatStopsStillStalls() {
        val watchdog = StallWatchdog(stallMs = 15_000L, minProgressMs = 250L)
        val player = FakePlayer(
            playbackState = Player.STATE_READY,
            currentPosition = 10_000L,
            bufferedPosition = 30_000L
        )
        assertEquals(StallDecision.Progress, watchdog.tick(player, 0L, scrubbing = false))
        player.playbackState = Player.STATE_BUFFERING
        player.bufferedPosition = 31_000L
        assertEquals(StallDecision.Progress, watchdog.tick(player, 1_000L, scrubbing = false))
        assertEquals(StallDecision.Idle, watchdog.tick(player, 15_999L, scrubbing = false))
        assertEquals(StallDecision.Stalled, watchdog.tick(player, 16_000L, scrubbing = false))
    }
}
