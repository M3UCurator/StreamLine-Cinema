package com.streamline.cinema.ui.player

import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Rate handed to the display for [frameRate] content played at [speed].
 * Zero means "no rate known", which the platform reads as "keep the system
 * choice" instead of a request.
 */
internal fun seamlessRequestRate(frameRate: Float, speed: Float): Float =
    if (frameRate > 0f) frameRate * speed.coerceIn(0.25f, 4f) else 0f

/**
 * Hands the frame-rate request to [SeamlessFrameRate] while [enabled]. Media3's
 * own hint goes through the strategy-less Surface overload, so leaving it on
 * would add a second request the framework may take as CHANGE_FRAME_RATE_ALWAYS.
 */
@OptIn(UnstableApi::class)
internal fun ExoPlayer.ownFrameRateRequests(enabled: Boolean) {
    setVideoChangeFrameRateStrategy(
        if (enabled) {
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        } else {
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        }
    )
}

/**
 * HDMI 2.1a QMS refresh-rate matching through the platform frame-rate API.
 *
 * The three-argument Surface call (Android 12+) carries the switch strategy;
 * [Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS] makes the platform move the
 * panel to the content rate through Quick Media Switching, and decline the
 * switch when the TV or link cannot do it without blanking. A refused switch
 * therefore costs the viewer judder, never a black screen.
 */
internal object SeamlessFrameRate {
    private const val TAG = "StreamLine:qms"

    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Requests [frameRate] for [surface]; zero releases it back to the system. */
    fun apply(surface: Surface, frameRate: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !surface.isValid) return
        runCatching {
            surface.setFrameRate(
                frameRate.coerceAtLeast(0f),
                if (frameRate > 0f) {
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                } else {
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                },
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
            )
        }.onSuccess {
            Log.i(
                TAG,
                if (frameRate > 0f) {
                    "%.3f Hz · seamless only".format(frameRate)
                } else {
                    "Rate request released"
                }
            )
        }.onFailure { Log.w(TAG, "Rate request refused · ${it.message}") }
    }
}

/**
 * Follows the video surface of whichever engine is painting, so the QMS
 * request lands on the surface the viewer actually sees. A rebuilt view hands
 * over a new SurfaceView, so the controller is re-attached from the view
 * update, not only once at composition.
 */
internal class SeamlessFrameRateController {
    private var attached: SurfaceView? = null
    private var rate = 0f
    private var applied = false

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = push(holder)

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) = push(holder)

        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }

    fun attach(view: View?) {
        val surfaceView = view as? SurfaceView ?: return
        if (surfaceView === attached) return
        detachInternal()
        attached = surfaceView
        surfaceView.holder.addCallback(holderCallback)
        // The holder may already carry a surface by the time a rebuilt view
        // reaches us; the callback covers the late-created case.
        push(surfaceView.holder)
    }

    /** Detaches only the surface handed in, so a branch switch cannot undo it. */
    fun detach(view: View? = null) {
        val current = attached ?: return
        if (view != null && view !== current) return
        detachInternal()
    }

    fun request(frameRate: Float) {
        val next = frameRate.coerceAtLeast(0f)
        if (next == rate) return
        rate = next
        attached?.let { push(it.holder) }
    }

    private fun detachInternal() {
        val current = attached ?: return
        attached = null
        current.holder.removeCallback(holderCallback)
        if (applied) {
            current.holder.surface?.let { SeamlessFrameRate.apply(it, 0f) }
            applied = false
        }
    }

    private fun push(holder: SurfaceHolder) {
        val surface = holder.surface ?: return
        if (rate > 0f) {
            SeamlessFrameRate.apply(surface, rate)
            applied = true
        } else if (applied) {
            SeamlessFrameRate.apply(surface, 0f)
            applied = false
        }
    }
}
