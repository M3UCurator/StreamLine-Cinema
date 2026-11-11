package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

/**
 * Adversarial inputs for the playlist/guide parsers: malformed documents, entity
 * bombs, decompression bombs and oversized text nodes must fail cleanly instead
 * of exhausting the heap or hanging the parse.
 */
class IptvStabilityTest {

    private val now = IptvParser.parseXmlTvDate("20261020140000 +0000")!!

    @Test
    fun unclosedXmlTvFailsInsteadOfReturningPartialData() {
        val broken = "<tv><channel id=\"a\"><display-name>A</display-name>" +
            "<programme start=\"20261020130000 +0000\" stop=\"20261020140000 +0000\" channel=\"a\">"
        try {
            IptvParser.parseXmlTv(broken, nowMs = now)
            fail("An unclosed document must not parse")
        } catch (expected: Exception) {
            assertTrue(expected.message != null)
        }
    }

    @Test
    fun billionLaughsEntityDeclarationIsRejectedBeforeExpansion() {
        val bomb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv [
              <!ENTITY a "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa">
              <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
              <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
            ]>
            <tv>
              <channel id="c"><display-name>&c;</display-name></channel>
            </tv>
        """.trimIndent()
        try {
            IptvParser.parseXmlTv(bomb, nowMs = now)
            fail("A recursive entity declaration must be rejected")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("entity", ignoreCase = true))
        }
    }

    @Test
    fun plainEntityDeclarationsStillParse() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd" [
              <!ENTITY nbsp "&#160;">
            ]>
            <tv>
              <channel id="c"><display-name>C</display-name></channel>
              <programme start="20261020130000 +0000" stop="20261020150000 +0000" channel="c">
                <title>News&nbsp;Hour</title>
              </programme>
            </tv>
        """.trimIndent()
        val guide = IptvParser.parseXmlTv(xml, nowMs = now)
        assertEquals(1, guide.programCount)
        assertTrue(guide.nowNext("c", "C", now)?.now?.title!!.startsWith("News"))
    }

    @Test
    fun giantDescriptionIsTruncated() {
        val huge = "x".repeat(200_000)
        val xml = """
            <tv>
              <channel id="c"><display-name>C</display-name></channel>
              <programme start="20261020130000 +0000" stop="20261020150000 +0000" channel="c">
                <title>Show</title>
                <desc>$huge</desc>
              </programme>
            </tv>
        """.trimIndent()
        val guide = IptvParser.parseXmlTv(xml, nowMs = now)
        val description = guide.nowNext("c", "C", now)?.now?.description.orEmpty()
        assertTrue("description should be truncated", description.length in 1..4096)
    }

    @Test
    fun gzipBombIsStoppedByTheDecodedByteCap() {
        val bomb = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(ByteArray(4 * 1024 * 1024) { 'a'.code.toByte() }) }
            out.toByteArray()
        }
        try {
            IptvParser.maybeGunzip(ByteArrayInputStream(bomb)).use { gunzipped ->
                CappedInputStream(gunzipped, 64 * 1024).use { it.readBytes() }
            }
            fail("The decoded cap must trip")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("exceeds"))
        }
    }

    @Test
    fun truncatedGzipFailsCleanly() {
        val gz = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write("<tv/>".repeat(1000).toByteArray()) }
            out.toByteArray()
        }
        try {
            IptvParser.maybeGunzip(ByteArrayInputStream(gz.copyOf(gz.size / 2))).use { it.readBytes() }
            fail("A truncated gzip stream must fail")
        } catch (expected: IOException) {
            assertTrue(expected.message != null)
        }
    }

    @Test
    fun invalidDatesDropTheProgrammeInsteadOfThrowing() {
        val xml = """
            <tv>
              <channel id="c"><display-name>C</display-name></channel>
              <programme start="20261320140000 +0000" stop="not-a-date" channel="c">
                <title>Broken</title>
              </programme>
              <programme start="20261020130000 +0000" stop="20261020120000 +0000" channel="c">
                <title>Backwards</title>
              </programme>
              <programme start="20261020130000 +0000" stop="20261020150000 +0000" channel="c">
                <title>Good</title>
              </programme>
            </tv>
        """.trimIndent()
        val guide = IptvParser.parseXmlTv(xml, nowMs = now)
        assertEquals(1, guide.programCount)
        assertEquals("Good", guide.nowNext("c", "C", now)?.now?.title)
    }

    @Test
    fun m3uStopsBuildingChannelsAtTheStoreBound() {
        val text = buildString {
            appendLine("#EXTM3U")
            repeat(MAX_IPTV_CHANNELS + 250) { index ->
                appendLine("#EXTINF:-1 tvg-id=\"c$index\",Channel $index")
                appendLine("http://stream.example/$index.m3u8")
            }
        }
        val channels = IptvParser.parseM3u(text, "src").channels
        assertEquals(MAX_IPTV_CHANNELS, channels.size)
        assertEquals("Channel 0", channels.first().name)
    }

    @Test
    fun mergingASingleGuideDoesNotCopyIt() {
        val xml = """
            <tv>
              <channel id="c"><display-name>C</display-name></channel>
              <programme start="20261020130000 +0000" stop="20261020140000 +0000" channel="c">
                <title>Show</title>
              </programme>
            </tv>
        """.trimIndent()
        val guide = IptvParser.parseXmlTv(xml, nowMs = now)
        assertSame(guide, EpgGuide.merge(listOf(guide)))
    }

    @Test
    fun mergedGuideKeepsThePerChannelBound() {
        fun stamp(secondsFromMidnight: Int): String {
            val hours = secondsFromMidnight / 3600
            val minutes = (secondsFromMidnight % 3600) / 60
            val seconds = secondsFromMidnight % 60
            return "20261020%02d%02d%02d +0000".format(hours, minutes, seconds)
        }

        fun guide(prefix: String): EpgGuide {
            val xml = """
                <tv>
                  <channel id="c"><display-name>C</display-name></channel>
                  ${(0 until 400).joinToString("\n") { index ->
                    val start = 10 * 3600 + index * 120
                    "<programme start=\"${stamp(start)}\" stop=\"${stamp(start + 120)}\" channel=\"c\"><title>$prefix $index</title></programme>"
                  }}
                </tv>
            """.trimIndent()
            return IptvParser.parseXmlTv(xml, nowMs = now)
        }
        val first = guide("A")
        val second = guide("B")
        assertEquals(400, first.programCount)
        val merged = EpgGuide.merge(listOf(first, second))
        assertEquals(600, merged.programs("c").size)
    }

    @Test
    fun emptyAndGarbagePlaylistsReturnNoChannels() {
        assertEquals(0, IptvParser.parseM3u("", "src").channels.size)
        assertEquals(0, IptvParser.parseM3u("#EXTM3U\n#EXTINF:-1,", "src").channels.size)
        val malformed = IptvParser.parseM3u(
            "#EXTM3U\n#EXTINF:-1,Broken\nnot a url with spaces\n#EXTINF:-1,Good\nhttp://x/y.m3u8",
            "src"
        )
        assertEquals(1, malformed.channels.size)
        assertEquals("Good", malformed.channels.single().name)
    }
}
