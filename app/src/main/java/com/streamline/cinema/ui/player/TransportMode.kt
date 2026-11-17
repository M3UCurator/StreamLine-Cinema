package com.streamline.cinema.ui.player

import com.streamline.cinema.data.TransportVerdict

/** Which route the current tune may use. AUTO is the default and learns. */
internal enum class TransportMode(val label: String) {
    AUTO("AUTO"),
    DIRECT("DIRECT"),
    PROXY("PROXY")
}

/**
 * The path a tune should attempt.
 *
 * AUTO starts direct: it only yields to the loopback proxy when the engine
 * cannot reach https on its own (MPV), the origin has denied a direct request
 * before, or a direct attempt already failed during this tune. DIRECT and
 * PROXY pin the viewer's choice; an engine without direct TLS still falls back
 * to the proxy because the alternative is no playback at all.
 */
internal fun transportUsesDirect(
    mode: TransportMode,
    verdict: TransportVerdict?,
    canDirect: Boolean,
    autoDirectFailed: Boolean
): Boolean = when (mode) {
    TransportMode.DIRECT -> canDirect
    TransportMode.PROXY -> false
    TransportMode.AUTO -> canDirect && !autoDirectFailed && verdict != TransportVerdict.PROXY
}
