package com.streamline.cinema.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * Counters that move while a film runs: the playhead, the buffer edge and the
 * live wall clock. The screen's 1 Hz ticker writes them, but only the OSD
 * leaves read them, so a position update recomposes the dock instead of the
 * composable that owns the video surface.
 */
internal class PlaybackTelemetry {
    var positionMs by mutableLongStateOf(0L)
    var bufferedMs by mutableLongStateOf(0L)
    var liveNowMs by mutableLongStateOf(System.currentTimeMillis())
}

/** Next-up countdown, isolated so only the card invalidates once a second. */
internal class NextUpCountdown {
    var seconds by mutableIntStateOf(1)
    var total by mutableIntStateOf(1)
}
