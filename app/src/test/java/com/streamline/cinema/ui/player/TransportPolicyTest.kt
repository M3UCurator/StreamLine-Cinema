package com.streamline.cinema.ui.player

import com.streamline.cinema.data.TransportVerdict
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTO is "direct unless the origin or the engine says otherwise": the learned
 * verdict and the capability flag can veto it, and the pins ignore both. These
 * cases are the whole route decision, so they are pinned down here instead of
 * only being reachable through the player.
 */
class TransportPolicyTest {

    @Test
    fun autoStartsDirectOnAnUnknownOrigin() {
        assertTrue(transportUsesDirect(TransportMode.AUTO, null, canDirect = true, autoDirectFailed = false))
        assertTrue(
            transportUsesDirect(
                TransportMode.AUTO,
                TransportVerdict.DIRECT,
                canDirect = true,
                autoDirectFailed = false
            )
        )
    }

    @Test
    fun autoHonoursALearnedProxyVerdict() {
        assertFalse(
            transportUsesDirect(
                TransportMode.AUTO,
                TransportVerdict.PROXY,
                canDirect = true,
                autoDirectFailed = false
            )
        )
    }

    @Test
    fun autoStaysOnTheProxyAfterTheDirectAttemptFailed() {
        assertFalse(
            transportUsesDirect(TransportMode.AUTO, null, canDirect = true, autoDirectFailed = true)
        )
    }

    @Test
    fun autoFallsBackWhenTheEngineCannotFetchHttpsItself() {
        assertFalse(
            transportUsesDirect(TransportMode.AUTO, null, canDirect = false, autoDirectFailed = false)
        )
    }

    @Test
    fun pinnedModesIgnoreTheVerdictAndTheFailedAttempt() {
        assertTrue(
            transportUsesDirect(
                TransportMode.DIRECT,
                TransportVerdict.PROXY,
                canDirect = true,
                autoDirectFailed = true
            )
        )
        assertFalse(
            transportUsesDirect(
                TransportMode.PROXY,
                TransportVerdict.DIRECT,
                canDirect = true,
                autoDirectFailed = false
            )
        )
    }

    @Test
    fun directPinStillCannotBypassAnEngineWithoutDirectHttps() {
        assertFalse(
            transportUsesDirect(
                TransportMode.DIRECT,
                null,
                canDirect = false,
                autoDirectFailed = false
            )
        )
    }
}
