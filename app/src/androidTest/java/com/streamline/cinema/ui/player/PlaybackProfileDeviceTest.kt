package com.streamline.cinema.ui.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamline.cinema.data.AppSettings
import com.streamline.cinema.data.DecoderType
import com.streamline.cinema.data.QualityLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The resolver is unit-tested on the JVM; this exercises what only a device can
 * prove — that Media3 carries the resolved ceiling into the selector it is given
 * and that narrowing the cap on a live player re-applies without a teardown.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackProfileDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun resolve(limit: QualityLimit = QualityLimit.OFF): PlaybackProfile =
        playbackProfile(
            settings = AppSettings(qualityLimit = limit),
            decoder = DecoderType.AUTO,
            adaptive = true,
            lowRam = false
        )

    private fun newSelector(profile: PlaybackProfile): DefaultTrackSelector =
        DefaultTrackSelector(
            context,
            AdaptiveTrackSelection.Factory(15_000, 10_000, 25_000, 0.7f)
        ).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(
                        profile.maxResolution.maxWidth,
                        profile.maxResolution.maxHeight
                    )
                    .build()
            )
        }

    @Test
    fun selectorIsBuiltWithTheResolvedCeiling() {
        val cap = resolve().maxResolution

        val selector = newSelector(resolve())

        assertEquals(cap.maxWidth, selector.parameters.maxVideoWidth)
        assertEquals(cap.maxHeight, selector.parameters.maxVideoHeight)
        assertEquals(7680, selector.parameters.maxVideoWidth)
        assertEquals(4320, selector.parameters.maxVideoHeight)
    }

    @Test
    fun narrowingTheCapAppliesToTheLivePlayer() {
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context)
                    .setTrackSelector(newSelector(resolve()))
                    .build()
            }
            val running = player!!

            instrumentation.runOnMainSync {
                assertEquals(7680, running.trackSelectionParameters.maxVideoWidth)
                assertEquals(4320, running.trackSelectionParameters.maxVideoHeight)

                val narrowed = resolve(QualityLimit.Q480).maxResolution
                running.trackSelectionParameters = running.trackSelectionParameters
                    .buildUpon()
                    .setMaxVideoSize(narrowed.maxWidth, narrowed.maxHeight)
                    .build()
            }

            instrumentation.runOnMainSync {
                assertEquals(854, running.trackSelectionParameters.maxVideoWidth)
                assertEquals(480, running.trackSelectionParameters.maxVideoHeight)
                assertEquals(Player.STATE_IDLE, running.playbackState)
                assertNull(running.playerError)
            }
        } finally {
            player?.let { instrumentation.runOnMainSync { it.release() } }
        }
    }
}
