package com.streamline.cinema

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamline.cinema.data.CacheCleaner
import com.streamline.cinema.ui.AppRoot
import com.streamline.cinema.ui.CinemaTheme
import com.streamline.cinema.ui.PinKeyCapture

class MainActivity : CinemaActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()
        setContent {
            CinemaTheme {
                AppRoot(vm = viewModel())
            }
        }
    }

    /**
     * PIN surfaces that are not dialogs register a sink here so typed digits land
     * even when focus sits on the top bar rather than the PIN pane. Dialogs own a
     * separate window and capture their own keys.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val code = event.keyCode
            val digit = when {
                code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                    '0' + (code - KeyEvent.KEYCODE_0)
                code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
                    '0' + (code - KeyEvent.KEYCODE_NUMPAD_0)
                else -> null
            }
            if (digit != null && PinKeyCapture.consumeDigit(digit)) return true
            if ((code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL) &&
                PinKeyCapture.consumeBackspace()
            ) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onDestroy() {
        if (isFinishing) CacheCleaner.clearIfEnabled(this)
        super.onDestroy()
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
