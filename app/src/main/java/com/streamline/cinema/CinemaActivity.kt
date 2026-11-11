package com.streamline.cinema

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.activity.ComponentActivity

/** Shared by both activities so volume keys adjust media volume with the system slider. */
open class CinemaActivity : ComponentActivity() {

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Consuming both DOWN and UP keeps the player and any controller from
        // reacting to the same key.
        if (VolumeKeys.handle(audioManager, event.keyCode, event.action)) return true
        return super.dispatchKeyEvent(event)
    }
}
