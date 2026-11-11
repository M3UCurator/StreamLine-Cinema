package com.streamline.cinema.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPresentationTest {

    @Test
    fun splitsAioStreamsLayoutIntoCardFields() {
        val name = "AIOStreams\n4K (web)\n(Web-dl)"
        val title = "Avatar Fire And Ash (2025)\nHEVC ◆ HDR\n39.2 GB · 26.4 Mbps\n" +
            "Frenchio · SUPPLY\nFR · MULTI"
        val meta = StreamPresentation.parse("AIOStreams", name, title, "https://cdn/logo.png")

        assertEquals("AIOStreams", meta.provider)
        assertEquals("https://cdn/logo.png", meta.providerLogo)
        assertEquals("4K (web)", meta.headline)
        assertEquals("(Web-dl)", meta.subhead)
        assertEquals("Avatar Fire And Ash (2025)", meta.releaseTitle)
        assertEquals("39.2 GB", meta.size)
        assertEquals("26.4 Mbps", meta.bitrate)
        assertEquals("FR · MULTI", meta.languages)
        assertEquals("4K", meta.quality)
        assertTrue(meta.haystack.contains("Web-dl"))
    }

    @Test
    fun keepsCodecAndHdrOnOneFormatRow() {
        val meta = StreamPresentation.parse(
            "Torrentio",
            "Torrentio\n1080p",
            "<b>1080p</b><br>BluRay<br>HEVC HDR10+<br>12.4 GB<br>SPARKS"
        )
        assertEquals("1080p", meta.quality)
        assertEquals("12.4 GB", meta.size)
        assertTrue(meta.format.contains("HEVC"))
        assertEquals("BluRay", meta.subhead)
        assertEquals("SPARKS", meta.releaseTitle)
    }

    @Test
    fun derivesHeadlineFromTitleWhenNameHasNoTags() {
        val meta = StreamPresentation.parse("Hyper", null, "WEBDL 2160p DDP5.1 Atmos")
        assertEquals("4K (WEB-DL)", meta.headline)
        assertEquals("4K", meta.quality)
    }

    @Test
    fun ignoresProviderEchoLines() {
        val meta = StreamPresentation.parse(
            "AIOStreams",
            "AIOStreams",
            "AIOStreams\n1080p · WEBRip\n1.2 GB\nhttps://example.test/stream.m3u8"
        )
        assertEquals("1080p · WEBRip", meta.headline)
        assertTrue(meta.releaseTitle.isBlank())
    }

    @Test
    fun badgeCatalogParsesGroupsAndMatchesText() {
        val filters = JSONArray()
            .put(
                JSONObject()
                    .put("id", "webdl")
                    .put("groupId", "source")
                    .put("name", "WEB-DL")
                    .put("pattern", "(?i)\\bweb[\\s._-]?dl\\b")
                    .put("imageURL", "https://cdn/webl.png")
                    .put("isEnabled", true)
                    .put("type", "filter")
            )
            .put(
                JSONObject()
                    .put("id", "hdr")
                    .put("groupId", "video-tech")
                    .put("name", "HDR")
                    .put("pattern", "(?i)\\bhdr\\b")
                    .put("imageURL", "https://cdn/hdr.png")
                    .put("isEnabled", true)
            )
            .put(
                JSONObject()
                    .put("id", "off")
                    .put("groupId", "source")
                    .put("name", "Disabled")
                    .put("pattern", ".*")
                    .put("imageURL", "https://cdn/off.png")
                    .put("isEnabled", false)
            )
        val groups = JSONArray()
            .put(JSONObject().put("id", "source").put("name", "Source"))
            .put(JSONObject().put("id", "video-tech").put("name", "Video Tech"))

        val catalog = StreamBadgeCatalog.parse(filters, groups, "https://example.test/b.json", 42L)

        assertEquals(2, catalog.badges.size)
        assertEquals(2, catalog.sections().size)
        assertEquals("Source", catalog.sections().first().first.name)
        assertEquals(listOf("WEB-DL", "HDR"), catalog.matches("WEBDL HEVC HDR").map { it.name })
        assertEquals(emptyList<StreamBadge>(), catalog.matches("nothing here"))
    }

    @Test
    fun badgeCatalogSkipsBrokenPatternsButKeepsTheRest() {
        val filters = JSONArray()
            .put(
                JSONObject()
                    .put("id", "broken")
                    .put("groupId", "source")
                    .put("name", "Broken")
                    .put("pattern", "([unclosed")
                    .put("imageURL", "https://cdn/broken.png")
            )
            .put(
                JSONObject()
                    .put("id", "remux")
                    .put("groupId", "source")
                    .put("name", "REMUX")
                    .put("pattern", "(?i)\\bremux\\b")
                    .put("imageURL", "https://cdn/remux.png")
            )
        val catalog = StreamBadgeCatalog.parse(filters, null, "", 0L)

        assertEquals(2, catalog.badges.size)
        assertNull(catalog.badges.first().regex)
        assertEquals(listOf("REMUX"), catalog.matches("BluRay REMUX").map { it.name })
    }

    @Test
    fun badgeCapKeepsDocumentOrder() {
        val filters = JSONArray()
        repeat(8) { index ->
            filters.put(
                JSONObject()
                    .put("id", "badge-$index")
                    .put("groupId", "source")
                    .put("name", "B$index")
                    .put("pattern", "hdr")
                    .put("imageURL", "https://cdn/$index.png")
            )
        }
        val catalog = StreamBadgeCatalog.parse(filters, null, "", 0L)
        assertEquals(
            listOf("B0", "B1", "B2", "B3", "B4"),
            catalog.matches("hdr hdr hdr").map { it.name }
        )
    }

    @Test
    fun badgeSourceAcceptsOnlyHttpUrls() {
        assertEquals("", normalizeBadgeSource("  "))
        assertEquals(
            "https://example.test/badges.json",
            normalizeBadgeSource(" https://example.test/badges.json ")
        )
        assertNull(normalizeBadgeSource("example.test/badges.json"))
        assertNull(normalizeBadgeSource("ftp://example.test/badges.json"))
    }
}
