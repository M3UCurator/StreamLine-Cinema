package com.streamline.cinema

import android.media.AudioManager
import android.view.KeyEvent

/**
 * Adjusts media volume and asks the platform to render its volume panel, and
 * reports whether the key was a volume key so callers can consume it.
 */
object VolumeKeys {

    fun handle(audioManager: AudioManager?, keyCode: Int, action: Int): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            else -> return false
        }
        if (action == KeyEvent.ACTION_DOWN) {
            runCatching {
                audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    direction,
                    AudioManager.FLAG_SHOW_UI
                )
            }
        }
        return true
    }
}
