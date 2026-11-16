package com.streamline.cinema

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app assembles its playback profile during startup (settings store, engine
 * selection, devices); a cold start that reaches RESUMED is the cheapest device
 * check that the wiring still holds together.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {

    @Test
    fun coldStartReachesResumedWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
