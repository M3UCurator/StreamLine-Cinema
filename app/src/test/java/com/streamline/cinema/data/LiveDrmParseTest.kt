package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDrmParseTest {

    private val pageUrl = "https://page.example.com/watch/123"

    @Test
    fun watchPageUnescapesJsonUrlsAndResolvesRelativeLicence() {
        val html = """
            <script>var player = {
              "fileUrl":"https:\/\/cdn.example.com\/live\/main.mpd?token=1\u0026exp=9",
              "licenseUrl":"\/drm\/widevine"
            };</script>
        """.trimIndent()

        val resolved = StremioAddons.parseWatchPage(html, pageUrl)

        assertNotNull(resolved)
        assertEquals("https://cdn.example.com/live/main.mpd?token=1&exp=9", resolved!!.url)
        assertEquals(DrmScheme.WIDEVINE, resolved.drm?.scheme)
        assertEquals("https://page.example.com/drm/widevine", resolved.drm?.licenseUrl)
        assertEquals(pageUrl, resolved.headers["Referer"])
        assertEquals("https://page.example.com", resolved.headers["Origin"])
    }

    @Test
    fun watchPageClearKeyPairStaysLocal() {
        val html = """{ "streamUrl":"https://cdn.example.com/live.mpd",
            "kid":"00112233445566778899aabbccddeeff",
            "key":"ffeeddccbbaa99887766554433221100" }"""

        val resolved = StremioAddons.parseWatchPage(html, pageUrl)

        assertEquals(DrmScheme.CLEARKEY, resolved?.drm?.scheme)
        assertNull(resolved?.drm?.licenseUrl)
        assertTrue(resolved?.drm?.licenseJson.orEmpty().contains("\"kty\":\"oct\""))
    }

    @Test
    fun playReadyNamedLicenceWinsOverGenericDefault() {
        val html = """{"videoUrl":"https://cdn.example.com/a.mpd",
            "playreadyLicenseUrl":"https://lic.example.com/pr"}"""

        val resolved = StremioAddons.parseWatchPage(html, pageUrl)

        assertEquals(DrmScheme.PLAYREADY, resolved?.drm?.scheme)
        assertEquals("https://lic.example.com/pr", resolved?.drm?.licenseUrl)
    }

    @Test
    fun dashWidevineContentProtectionCarriesLaUrl() {
        val manifest = """
            <?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:ms="urn:microsoft">
              <Period><AdaptationSet>
                <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79b4-4a3f-85a1-a4dbf3b56b8a">
                  <ms:laurl licenseUrl="https://lic.example.com/wv"/>
                </ContentProtection>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()

        val parsed = StremioAddons.parseManifestDrm(manifest, "https://cdn.example.com/live/main.mpd")

        assertNotNull(parsed)
        assertTrue(parsed!!.protected)
        assertEquals(DrmScheme.WIDEVINE, parsed.drm?.scheme)
        assertEquals("https://lic.example.com/wv", parsed.drm?.licenseUrl)
        assertEquals("https://cdn.example.com", parsed.drm?.headers?.get("Origin"))
    }

    @Test
    fun dashPlayReadyContentProtectionIsDetected() {
        val manifest = """
            <MPD><Period><AdaptationSet>
              <ContentProtection schemeIdUri="urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95">
                <ms:laurl licenseUrl="https://lic.example.com/pr"/>
              </ContentProtection>
            </AdaptationSet></Period></MPD>
        """.trimIndent()

        val parsed = StremioAddons.parseManifestDrm(manifest, "https://cdn.example.com/live/main.mpd")

        assertEquals(DrmScheme.PLAYREADY, parsed?.drm?.scheme)
    }

    @Test
    fun dashProtectedWithoutLicenceReportsProtectionOnly() {
        val manifest = """
            <MPD><Period><AdaptationSet>
              <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79b4-4a3f-85a1-a4dbf3b56b8a" value="cenc">
                <cenc:pssh>AAAA</cenc:pssh>
              </ContentProtection>
            </AdaptationSet></Period></MPD>
        """.trimIndent()

        val parsed = StremioAddons.parseManifestDrm(manifest, "https://cdn.example.com/live/main.mpd")

        assertTrue(parsed!!.protected)
        assertNull(parsed.drm)
        assertEquals("WIDEVINE", parsed.detail)
    }

    @Test
    fun hlsSessionKeyWidevineCarriesLicence() {
        val manifest = """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="https://lic.example.com/wv",KEYFORMAT="urn:uuid:edef8ba9-79b4-4a3f-85a1-a4dbf3b56b8a"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000
            high.m3u8
        """.trimIndent()

        val parsed = StremioAddons.parseManifestDrm(manifest, "https://cdn.example.com/live/master.m3u8")

        assertEquals(DrmScheme.WIDEVINE, parsed?.drm?.scheme)
        assertEquals("https://lic.example.com/wv", parsed?.drm?.licenseUrl)
    }

    @Test
    fun hlsFairPlayIsProtectedButUnsupported() {
        val manifest = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://abc"
            #EXTINF:6,
            segment.ts
        """.trimIndent()

        val parsed = StremioAddons.parseManifestDrm(manifest, "https://cdn.example.com/live/play.m3u8")

        assertTrue(parsed!!.protected)
        assertNull(parsed.drm)
        assertEquals("FairPlay", parsed.detail)
    }

    @Test
    fun hlsAes128IsTransportEncryptionNotDrm() {
        val manifest = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:6,
            segment.ts
        """.trimIndent()

        assertNull(StremioAddons.parseManifestDrm(manifest, "https://cdn.example.com/live/play.m3u8"))
    }

    @Test
    fun clearManifestHasNoProtection() {
        val manifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            low.m3u8
        """.trimIndent()

        assertNull(StremioAddons.parseManifestDrm(manifest, "https://cdn.example.com/live/master.m3u8"))
        assertFalse(
            StremioAddons.parseManifestDrm("<MPD></MPD>", "https://cdn.example.com/a.mpd")?.protected == true
        )
    }

    @Test
    fun schemeIdsMapToMediaDrmUuids() {
        assertEquals(DrmScheme.WIDEVINE, DrmScheme.fromId("urn:uuid:edef8ba9-79b4-4a3f-85a1-a4dbf3b56b8a"))
        assertEquals(DrmScheme.PLAYREADY, DrmScheme.fromId("com.microsoft.playready"))
        assertEquals(DrmScheme.CLEARKEY, DrmScheme.fromId("org.w3.clearkey"))
        assertNull(DrmScheme.fromId("urn:mpeg:dash:mp4protection:2011"))
        assertEquals("edef8ba9-79b4-4a3f-85a1-a4dbf3b56b8a", DrmScheme.WIDEVINE.uuid)
    }
}
