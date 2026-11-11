package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.Random
import java.util.TimeZone

/**
 * Real-world playlist fixtures plus seeded fuzzing and persistence-recovery
 * cases. The goal is that no provider document, however odd, can take the app
 * down or leave an unreadable state file behind.
 */
class IptvCorpusRecoveryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val now = utcAt(2026, 10, 20, 14, 0)

    @Test
    fun parsesSingleQuotedAttributesAndIgnoresHlsDirectives() {
        val m3u = "\uFEFF#EXTM3U url-tvg='https://epg.example/guide.xml'\r\n" +
            "#EXTINF:-1 tvg-id='bbc1' tvg-name='BBC One' group-title='News' " +
            "tvg-logo='https://logo.example/b.png' http-user-agent='Box/2.0',BBC One HD\r\n" +
            "http://stream.example/bbc1.m3u8\r\n" +
            "#EXT-X-DISCONTINUITY\r\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example/k\"\r\n" +
            "#EXT-X-MEDIA-SEQUENCE:42\r\n" +
            "#EXTINF:-1 tvg-id=bbc2,Sports\r\n" +
            "http://stream.example/sports.m3u8\r\n"

        val playlist = IptvParser.parseM3u(m3u, "src")
        assertEquals("https://epg.example/guide.xml", playlist.epgUrl)
        assertEquals(2, playlist.channels.size)

        val bbc = playlist.channels[0]
        assertEquals("BBC One HD", bbc.name)
        assertEquals("bbc1", bbc.epgId)
        assertEquals("News", bbc.category)
        assertEquals("https://logo.example/b.png", bbc.poster)
        assertEquals("Box/2.0", StreamUrls.parse(bbc.streamUrl!!).headers["User-Agent"])

        assertEquals("bbc2", playlist.channels[1].epgId)
        assertEquals(0, playlist.report!!.malformed)
    }

    @Test
    fun realWorldCorpusCountsDroppedRows() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,No URL
            #EXTINF:-1,Good One
            http://stream.example/good1.m3u8
            #EXTINF:-1,Truncated
            #EXTINF:-1,Good Two
            not a url at all
            #EXTINF:-1,Good Three
            http://stream.example/good3.m3u8
        """.trimIndent()

        val playlist = IptvParser.parseM3u(m3u, "src")
        assertEquals(2, playlist.channels.size)
        val report = playlist.report!!
        assertEquals(2, report.total)
        // Both truncated rows and the invalid URL are counted, not silently dropped.
        assertEquals(3, report.malformed)
        assertEquals("2 channels · 3 malformed", report.summary)
    }

    @Test
    fun xmlTvReportCountsKeptSkippedAndMalformedProgrammes() {
        val xml = """
            <tv>
              <channel id="c1"><display-name>C1</display-name></channel>
              <programme start="20261020130000 +0000" stop="20261020150000 +0000" channel="c1">
                <title>Good</title>
              </programme>
              <programme start="20261020130000 +0000" stop="20261020150000 +0000">
                <title>No channel</title>
              </programme>
              <programme start="not-a-date" stop="20261020150000 +0000" channel="c1">
                <title>Bad date</title>
              </programme>
              <programme start="20200101000000 +0000" stop="20200101010000 +0000" channel="c1">
                <title>Ancient</title>
              </programme>
              <programme start="20261020130000 +0000" stop="20261020150000 +0000" channel="c1"/>
            </tv>
        """.trimIndent()

        val parse = IptvParser.parseXmlTvDetailed(xml.byteInputStream(), nowMs = now)
        assertEquals(1, parse.guide.programCount)
        assertEquals("Good", parse.guide.nowNext("c1", "C1", now)?.now?.title)
        assertEquals(1, parse.report.total)
        assertEquals(1, parse.report.skipped)
        assertEquals(3, parse.report.malformed)
    }

    @Test
    fun guideIndexSurvivesAColdStartRoundTrip() {
        val xml = """
            <tv>
              <channel id="c1"><display-name>C1</display-name><lcn>7</lcn></channel>
              <programme start="20261020130000 +0000" stop="20261020150000 +0000" channel="c1">
                <title>Now Show</title>
                <sub-title>Live</sub-title>
                <desc>Long description that the index does not keep.</desc>
              </programme>
              <programme start="20261020150000 +0000" stop="20261020160000 +0000" channel="c1">
                <title>Next Show</title>
              </programme>
            </tv>
        """.trimIndent()

        val guide = IptvParser.parseXmlTv(xml, nowMs = now)
        val restored = EpgGuide.fromIndexJson(guide.indexJson())!!
        assertEquals(1, restored.channelCount)
        assertEquals(7, restored.channels.getValue("c1").number)
        assertEquals(2, restored.programCount)
        val nowNext = restored.nowNext("c1", "C1", now)
        assertEquals("Now Show", nowNext?.now?.title)
        assertEquals("Live", nowNext?.now?.subTitle)
        assertNull(nowNext?.now?.description)
        assertEquals("Next Show", nowNext?.next?.title)

        // The persisted index is capped so a huge guide cannot bloat the file.
        assertEquals(1, EpgGuide.fromIndexJson(guide.indexJson(maxPrograms = 1))!!.programCount)

        assertNull(EpgGuide.fromIndexJson(null))
        assertNull(EpgGuide.fromIndexJson(""))
        assertNull(EpgGuide.fromIndexJson("{not json"))
        assertNull(EpgGuide.fromIndexJson("{}"))
    }

    @Test
    fun sourceStoreFallsBackToTheBackupCopy() {
        val dir = temp.newFolder()
        val primary = File(dir, "iptv_sources.json")
        val backup = File(dir, "iptv_sources.json.bak")
        val valid = """{"sources":[{"id":"a","name":"A","location":"https://x/list.m3u"}]}"""

        // Truncated primary (power loss mid-write) still recovers from .bak.
        primary.writeText(valid.take(18))
        backup.writeText(valid)
        assertEquals("a", IptvStore.loadSourceFile(primary)!!.single().id)

        // Zero-byte primary behaves the same as truncated.
        primary.writeText("")
        assertEquals("a", IptvStore.loadSourceFile(primary)!!.single().id)

        // A corrupt backup with no usable primary is reported as "unreadable".
        primary.writeText("{\"sources\":")
        backup.writeText("{\"sources\":[{")
        assertNull(IptvStore.loadSourceFile(primary))

        // Neither file present at all.
        assertNull(IptvStore.loadSourceFile(File(dir, "never.json")))
    }

    @Test
    fun atomicStoreKeepsThePreviousCopyAsBackup() {
        val dir = temp.newFolder()
        val target = File(dir, "state.json")
        AtomicStore.write(target, """{"v":1}""", "state")
        AtomicStore.write(target, """{"v":2}""", "state")

        assertEquals("""{"v":2}""", AtomicStore.read(target))
        assertEquals("""{"v":1}""", AtomicStore.read(File(dir, "state.json.bak")))
        // An empty primary (power loss after truncation) falls back to the backup.
        target.writeText("")
        assertEquals("""{"v":1}""", AtomicStore.readWithBackup(target))
    }

    @Test
    fun writeFailsClearlyWhenTheFolderIsNotUsable() {
        val blocker = temp.newFile("blocked")
        try {
            AtomicStore.write(File(blocker, "state.json"), "{}", "state")
            fail("Writing under a regular file must fail")
        } catch (expected: IOException) {
            assertNotNull(expected)
        }
    }

    @Test
    fun spacePreflightRejectsDocumentsThatWouldFillTheDisk() {
        val payload = 16L * 1024 * 1024
        assertFalse(AtomicStore.hasRoomFor(payload, free = payload))
        assertTrue(AtomicStore.hasRoomFor(payload, free = payload * 3))
        // An unmeasurable volume (0) must never block a write.
        assertTrue(AtomicStore.hasRoomFor(payload, free = 0))
        val error = InsufficientStorageException("channel list", 40L * 1024 * 1024, 1L * 1024 * 1024)
        assertTrue(error.message!!.contains("Not enough storage"))
    }

    @Test
    fun channelHealthMarksDeadOnlyAtTheThreshold() {
        assertFalse(ChannelHealth(failures = DEAD_CHANNEL_FAILURES - 1).dead)
        assertTrue(ChannelHealth(failures = DEAD_CHANNEL_FAILURES).dead)
        assertTrue(ChannelHealth(failures = DEAD_CHANNEL_FAILURES + 1).dead)
    }

    @Test
    fun lowRamProfilesLowerTheEpgCaps() {
        assertTrue(
            DeviceProfile.mergedEpgProgramCap(true) < DeviceProfile.mergedEpgProgramCap(false)
        )
        assertTrue(
            DeviceProfile.singleEpgProgramCap(true) < DeviceProfile.singleEpgProgramCap(false)
        )
        assertTrue(DeviceProfile.singleEpgProgramCap(false) <= DeviceProfile.mergedEpgProgramCap(false))
    }

    @Test
    fun m3uParserSurvivesSeededRandomInput() {
        val random = Random(20261107L)
        val alphabet = "#EXTM3U tvg-id=\"',:/\\\r\n\uFEFF0123456789abcXYZ".toByteArray()
        repeat(250) {
            val bytes = ByteArray(random.nextInt(4096))
            for (index in bytes.indices) bytes[index] = alphabet[random.nextInt(alphabet.size)]
            val playlist = IptvParser.parseM3u(String(bytes, Charsets.UTF_8), "fuzz")
            assertTrue(playlist.channels.size <= MAX_IPTV_CHANNELS)
            assertEquals(playlist.channels.size, playlist.report!!.total)
        }
    }

    @Test
    fun xmlTvParserSurvivesSeededRandomInput() {
        val random = Random(20261108L)
        repeat(120) {
            val bytes = ByteArray(random.nextInt(2048))
            random.nextBytes(bytes)
            val parse = runCatching {
                IptvParser.parseXmlTvDetailed(ByteArrayInputStream(bytes), nowMs = now)
            }.getOrNull()
            if (parse != null) {
                assertTrue(parse.guide.programCount <= 1_000)
                assertEquals(parse.guide.channelCount, parse.report.total)
            }
        }
    }

    private fun utcAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
}
