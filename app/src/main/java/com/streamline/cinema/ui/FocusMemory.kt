package com.streamline.cinema.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay

/**
 * Remembers which card was focused on each rail/grid surface, so returning to a
 * tab or closing a detail page lands on the same title instead of the first one.
 *
 * Restore requests are per surface: bumping one surface never makes a card on
 * another rail steal focus during a transition.
 */
@Stable
class FocusMemory {

    private val slots = mutableStateMapOf<String, String>()
    private val surfaceTicks = mutableStateMapOf<String, Int>()

    var lastSurface: String? by mutableStateOf(null)
        private set

    fun record(surface: String, key: String) {
        if (key.isBlank()) return
        slots[surface] = key
        lastSurface = surface
    }

    fun recall(surface: String): String? = slots[surface]

    fun restoreTick(surface: String?): Int =
        if (surface.isNullOrBlank()) 0 else surfaceTicks[surface] ?: 0

    /** Asks the cards on [surface] (last focused by default) to re-claim focus. */
    fun requestRestore(surface: String? = lastSurface) {
        if (surface.isNullOrBlank()) return
        surfaceTicks[surface] = (surfaceTicks[surface] ?: 0) + 1
    }
}

val LocalFocusMemory = staticCompositionLocalOf { FocusMemory() }

/**
 * Re-claims the remembered card when a screen re-enters composition after the
 * tab content was disposed. Screens call this once on entry.
 */
@Composable
fun RestoreSurfaceFocus(surface: String, delayMs: Long = 140) {
    val focusMemory = LocalFocusMemory.current
    LaunchedEffect(surface) {
        if (focusMemory.recall(surface) != null) {
            delay(delayMs)
            focusMemory.requestRestore(surface)
        }
    }
}
