package com.streamline.cinema.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCacheBudgetTest {

    @Test
    fun offKeepsNothingAndTheRestGrow() {
        assertEquals(0L, AudioCacheBudget.OFF.maxBytes)
        val sized = AudioCacheBudget.entries.filter { it.maxBytes > 0 }
        assertTrue(sized.zipWithNext().all { (a, b) -> a.maxBytes < b.maxBytes })
    }

    @Test
    fun unknownOrMissingValueKeepsTheDefault() {
        assertEquals(AudioCacheBudget.MB512, AudioCacheBudget.from(null))
        assertEquals(AudioCacheBudget.MB512, AudioCacheBudget.from("2TB"))
    }

    @Test
    fun budgetSurvivesSettingsJson() {
        val restored = AppSettings.fromJson(
            AppSettings(audioCacheBudget = AudioCacheBudget.GB1).toJson()
        )
        assertEquals(AudioCacheBudget.GB1, restored.audioCacheBudget)
    }

    @Test
    fun settingsWrittenBeforeTheSettingDefaultTo512() {
        val restored = AppSettings.fromJson(JSONObject())
        assertEquals(AudioCacheBudget.MB512, restored.audioCacheBudget)
    }
}
