package com.streamline.cinema.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * An edited source must keep its identity, playlist state and guide correction
 * when the amended fields are written back to `iptv_sources.json`.
 */
class IptvSourceEditTest {

    @Test
    fun editedFieldsSurviveDiskRoundTrip() {
        val dir = Files.createTempDirectory("iptv-edit").toFile()
        try {
            val file = File(dir, "iptv_sources.json")
            val edited = IptvSource(
                id = "edit-1",
                name = "Provider · renamed",
                location = "https://provider.example/list.m3u|User-Agent=VLC/3.0",
                kind = IptvKind.M3U,
                epgUrl = "https://provider.example/guide.xml.gz",
                extractedEpgUrl = "https://old.example/guide.xml",
                epgShiftMinutes = -30,
                epgShiftAuto = false,
                addedAt = 1_760_000_000_000L,
                channelCount = 812,
                lastLoadedAt = 1_760_000_500_000L,
                lastReport = "812 channels · 1 skipped"
            )
            file.writeText(JSONObject().put("sources", JSONArray(listOf(edited.toJson()))).toString())

            val loaded = IptvStore.loadSourceFile(file).orEmpty()

            assertEquals(1, loaded.size)
            val restored = loaded.single()
            assertEquals("edit-1", restored.id)
            assertEquals("Provider · renamed", restored.name)
            assertEquals(edited.location, restored.location)
            assertEquals("https://provider.example/guide.xml.gz", restored.epgUrl)
            assertEquals(812, restored.channelCount)
            assertEquals(-30, restored.epgShiftMinutes)
            assertEquals(false, restored.epgShiftAuto)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun editedGuideOverrideWinsOverPlaylistAdvertisedUrl() {
        val source = IptvSource(
            id = "edit-2",
            name = "Provider",
            location = "https://provider.example/list.m3u",
            extractedEpgUrl = "https://old.example/guide.xml"
        )

        val edited = source.copy(epgUrl = "https://provider.example/new-guide.xml")

        assertEquals("https://provider.example/new-guide.xml", edited.effectiveEpgUrl)
        assertTrue(edited.location.startsWith("https://provider.example/"))
    }
}
