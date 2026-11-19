package com.streamline.cinema.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileAvatarTest {

    @Test
    fun legacyColorIndexMapsOntoAvatar() {
        val legacy = JSONObject().apply {
            put("id", "p1")
            put("name", "Sam")
            put("maturity", "ADULT")
            put("colorIndex", 4)
        }
        assertEquals(4, CinemaProfile.fromJson(legacy).avatarIndex)
    }

    @Test
    fun avatarIndexSurvivesRoundTrip() {
        val profile = CinemaProfile(id = "p2", name = "Ada", avatarIndex = 12)
        assertEquals(12, CinemaProfile.fromJson(profile.toJson()).avatarIndex)
    }

    @Test
    fun storedAvatarOutOfRangeClampsToLastOption() {
        val stored = JSONObject().apply {
            put("id", "p3")
            put("avatarIndex", 99)
        }
        assertEquals(PROFILE_AVATAR_COUNT - 1, CinemaProfile.fromJson(stored).avatarIndex)
    }
}
