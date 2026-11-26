package com.streamline.cinema.ui.player

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import kotlin.math.abs

/** Pure mode-selection input so the matching rules stay unit-testable. */
internal data class DisplayModeOption(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float
)

/**
 * Picks the display mode whose refresh rate matches the source. Only modes at
 * the current resolution are considered, so a 4K panel never drops to 1080p
 * just to find a 24 Hz mode; a panel that offers 23.976, 24, 50 and 60 Hz is
 * matched exactly. Null means "leave the display alone".
 */
internal fun chooseDisplayMode(
    frameRate: Float,
    current: DisplayModeOption,
    modes: List<DisplayModeOption>
): DisplayModeOption? {
    if (frameRate <= 0f) return null
    val best = modes
        .asSequence()
        .filter { it.refreshRate > 0f && it.width == current.width && it.height == current.height }
        .minByOrNull { abs(it.refreshRate - frameRate) }
        ?: return null
    // 23.976 vs 24 and 59.94 vs 60 are the same display rate in practice.
    if (abs(best.refreshRate - frameRate) > 0.75f) return null
    return best.takeIf { it.modeId != current.modeId }
}

/**
 * Legacy app-level frame rate matching for platforms without the HDMI 2.1a
 * seamless path (Android 11 and older). Pinning the display mode forces a
 * full HDMI mode change, which blanks the screen; on Android 12+ the player
 * instead asks the surface for [SeamlessFrameRate] so the switch goes through
 * QMS or is declined. The preferred mode is set on the player window and
 * restored when the player screen is disposed.
 */
internal object FrameRateMatching {
    private const val TAG = "StreamLine:afr"
    private var originalModeId = 0
    private var appliedModeId = 0

    fun apply(activity: Activity?, frameRate: Float) {
        val activity = activity ?: return
        if (frameRate <= 0f) return
        val display = displayOf(activity) ?: return
        val current = runCatching { display.mode }.getOrNull() ?: return
        val modes = runCatching { display.supportedModes?.map { it.toOption() } }
            .getOrNull()
            .orEmpty()
        val target = chooseDisplayMode(frameRate, current.toOption(), modes)
        if (target == null) {
            // Single-mode panels and rate mismatches stay where they are.
            Log.d(
                TAG,
                "%.2f Hz source · %.2f Hz display kept (%d modes)".format(
                    frameRate,
                    current.refreshRate,
                    modes.size
                )
            )
            return
        }
        if (target.modeId == appliedModeId) return
        if (originalModeId == 0) originalModeId = current.modeId
        runCatching { setPreferredModeId(activity, target.modeId) }
            .onSuccess {
                appliedModeId = target.modeId
                Log.i(
                    TAG,
                    "%.2f Hz source → %.2f Hz mode %d".format(frameRate, target.refreshRate, target.modeId)
                )
            }
            .onFailure { Log.w(TAG, "Display rejected mode ${target.modeId}", it) }
    }

    fun restore(activity: Activity?) {
        val activity = activity ?: return
        val restoreId = originalModeId
        originalModeId = 0
        appliedModeId = 0
        if (restoreId == 0) return
        runCatching { setPreferredModeId(activity, restoreId) }
            .onSuccess { Log.i(TAG, "Restored display mode $restoreId") }
            .onFailure { Log.w(TAG, "Could not restore display mode $restoreId", it) }
    }

    private fun setPreferredModeId(activity: Activity, modeId: Int) {
        val attributes = activity.window.attributes
        if (attributes.preferredDisplayModeId == modeId) return
        attributes.preferredDisplayModeId = modeId
        activity.window.attributes = attributes
    }

    private fun displayOf(activity: Activity): Display? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }
    }.getOrNull()

    private fun Display.Mode.toOption() =
        DisplayModeOption(modeId, physicalWidth, physicalHeight, refreshRate)
}
