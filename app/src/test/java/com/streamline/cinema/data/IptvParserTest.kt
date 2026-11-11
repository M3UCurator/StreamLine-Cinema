package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

class IptvParserTest {

    @Test
    fun extractsEpgUrlFromHeaderVariants() {
        assertEquals(
            "https://a.example/epg.xml.gz",
            IptvParser.extractEpgUrlFromM3u(
                "#EXTM3U url-tvg=\"https://a.example/epg.xml.gz\"\n#EXTINF:-1,One\nhttp://x/1.m3u8"
            )
        )
        assertEquals(
            "https://b.example/epg.xml",
            IptvParser.extractEpgUrlFromM3u(
                "#EXTM3U x-tvg-url=\"https://b.example/epg.xml\""
            )
        )
        assertEquals(
            "https://c.example/epg.xml",
            IptvParser.extractEpgUrlFromM3u(
                "#EXTM3U tvg-url=\"https://c.example/epg.xml\""
            )
        )
        assertEquals(
            "https://d.example/epg.xml",
            IptvParser.extractEpgUrlFromM3u(
                "#EXTM3U url-tvg=\"https://d.example/epg.xml,https://e.example/epg.xml\""
            )
        )
        assertNull(IptvParser.extractEpgUrlFromM3u("#EXTM3U\n#EXTINF:-1,One\nhttp://x/1.m3u8"))
        assertNull(IptvParser.extractEpgUrlFromM3u("not a playlist"))
    }

    @Test
    fun parsesExtendedM3uAttributesGroupsAndSurvivesDuplicates() {
        val m3u = """
            #EXTM3U url-tvg="https://epg.example/guide.xml.gz"
            #EXTINF:-1 tvg-id="bbc1" tvg-name="BBC One" tvg-logo="https://logo.example/bbc.png" group-title="News;UK" tvg-chno="101",BBC One HD
            http://stream.example/bbc1.m3u8
            #EXTINF:-1 tvg-id="bbc1" group-title="News",BBC One Backup
            http://stream.example/bbc1b.m3u8
            #EXTGRP:Sports
            #EXTINF:-1,Sky Sports
            http://stream.example/sports.m3u8
            #EXTINF:-1,No Attributes
            http://stream.example/plain.m3u8
        """.trimIndent()

        val playlist = IptvParser.parseM3u(m3u, sourceId = "src1")
        assertEquals("https://epg.example/guide.xml.gz", playlist.epgUrl)
        assertEquals(4, playlist.channels.size)

        val bbc = playlist.channels[0]
        assertEquals("BBC One HD", bbc.name)
        assertEquals("bbc1", bbc.epgId)
        assertEquals("https://logo.example/bbc.png", bbc.poster)
        assertEquals("News", bbc.category)
        assertEquals(101, bbc.channelNumber)
        assertEquals("src1", bbc.sourceId)
        assertEquals("http://stream.example/bbc1.m3u8", bbc.streamUrl)

        // Duplicate tvg-id keeps both channels, the second gets a suffix.
        assertEquals("bbc1#2", playlist.channels[1].id)

        // EXTGRP applies to following entries until overridden by group-title.
        assertEquals("Sports", playlist.channels[2].category)
        assertEquals("Sports", playlist.channels[3].category)
        assertEquals("No Attributes", playlist.channels[3].name)
        assertNull(playlist.channels[3].epgId)
    }

    @Test
    fun injectsPipeHeadersFromExtinfAndVlcOptions() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 http-user-agent="Player/1.0" http-referrer="https://ref.example/page",With Attrs
            http://stream.example/a.m3u8
            #EXTINF:-1,VLC
            #EXTVLCOPT:http-user-agent=VLC/3.0
            #EXTVLCOPT:http-referrer="https://ref.example/vlc"
            #EXTVLCOPT:http-cookie=session=abc
            http://stream.example/b.m3u8
            #EXTINF:-1,Already Piped
            http://stream.example/c.m3u8|User-Agent=Custom&Referer=https://keep.example/
        """.trimIndent()

        val channels = IptvParser.parseM3u(m3u, "src").channels
        val attrs = StreamUrls.parse(channels[0].streamUrl!!)
        assertEquals("http://stream.example/a.m3u8", attrs.url)
        assertEquals("Player/1.0", attrs.headers["User-Agent"])
        assertEquals("https://ref.example/page", attrs.headers["Referer"])

        val vlc = StreamUrls.parse(channels[1].streamUrl!!)
        assertEquals("VLC/3.0", vlc.headers["User-Agent"])
        assertEquals("https://ref.example/vlc", vlc.headers["Referer"])
        assertEquals("session=abc", vlc.headers["Cookie"])

        // Pipe syntax already present is preserved verbatim.
        assertEquals(
            "http://stream.example/c.m3u8|User-Agent=Custom&Referer=https://keep.example/",
            channels[2].streamUrl
        )
    }

    @Test
    fun resolvesRelativeStreamUrlsAgainstThePlaylistUrl() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Relative
            sub/channel.m3u8
            #EXTINF:-1,Absolute
            https://other.example/abs.m3u8
        """.trimIndent()
        val channels = IptvParser.parseM3u(m3u, "src", baseUrl = "https://host.example/path/list.m3u").channels
        assertEquals("https://host.example/path/sub/channel.m3u8", channels[0].streamUrl)
        assertEquals("https://other.example/abs.m3u8", channels[1].streamUrl)
    }

    @Test
    fun parsesXspfTracksAndEpgLink() {
        val xspf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <playlist version="1" xmlns="http://xspf.org/ns/0/"
                      xmlns:vlc="http://www.videolan.org/vlc/playlist/ns/0/">
              <title>My IPTV</title>
              <link rel="http://xmltv.example/epg" href="https://epg.example/guide.xml"/>
              <trackList>
                <track>
                  <location>http://stream.example/news.m3u8</location>
                  <title>News 24</title>
                  <annotation>Rolling coverage</annotation>
                  <image>http://logo.example/news.png</image>
                  <extension application="http://www.videolan.org/vlc/playlist/0">
                    <vlc:id>3</vlc:id>
                    <vlc:group>News</vlc:group>
                  </extension>
                </track>
              </trackList>
            </playlist>
        """.trimIndent()

        val playlist = IptvParser.parseXspf(xspf, "xsrc")
        assertEquals("My IPTV", playlist.title)
        assertEquals("https://epg.example/guide.xml", playlist.epgUrl)
        assertEquals(1, playlist.channels.size)
        val channel = playlist.channels.single()
        assertEquals("News 24", channel.name)
        assertEquals("http://stream.example/news.m3u8", channel.streamUrl)
        assertEquals("Rolling coverage", channel.description)
        assertEquals("http://logo.example/news.png", channel.poster)
        assertEquals("News", channel.category)
        assertEquals("xsrc", channel.sourceId)
    }

    @Test
    fun parsesXmlTvChannelsProgrammesAndNowNext() {
        val now = IptvParser.parseXmlTvDate("20261020140000 +0000")!!
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv>
              <channel id="bbc1">
                <display-name>BBC One HD</display-name>
                <icon src="https://logo.example/bbc1.png"/>
                <lcn>1</lcn>
              </channel>
              <programme start="20261020120000 +0000" stop="20261020140000 +0000" channel="bbc1">
                <title>Early Show</title>
              </programme>
              <programme start="20261020140000 +0000" stop="20261020150000 +0000" channel="bbc1">
                <title>News at Two</title>
                <sub-title>Midday bulletin</sub-title>
                <desc>Headlines and weather.</desc>
              </programme>
              <programme start="20261020150000 +0000" stop="20261020160000 +0000" channel="bbc1">
                <title>Afternoon Show</title>
              </programme>
              <programme start="20200101000000 +0000" stop="20200101010000 +0000" channel="bbc1">
                <title>Ancient</title>
              </programme>
            </tv>
        """.trimIndent()

        val guide = IptvParser.parseXmlTv(xml, nowMs = now)
        assertEquals(1, guide.channelCount)
        assertEquals(3, guide.programCount)

        val info = guide.channels.getValue("bbc1")
        assertEquals("BBC One HD", info.name)
        assertEquals(1, info.number)
        assertEquals("https://logo.example/bbc1.png", info.icon)

        val byId = guide.nowNext("bbc1", "Whatever", now)
        assertNotNull(byId)
        assertEquals("News at Two", byId!!.now?.title)
        assertEquals("Midday bulletin", byId.now?.subTitle)
        assertEquals("Headlines and weather.", byId.now?.description)
        assertEquals("Afternoon Show", byId.next?.title)

        // tvg-id-less entries fall back to a normalised display-name match.
        val byName = guide.nowNext(null, "BBC One [HD]", now)
        assertEquals("News at Two", byName?.now?.title)

        // Nothing at all before the first programme of that channel? It is still
        // inside the retention window, so early times return the first programme.
        val early = guide.nowNext("bbc1", "BBC One", now - 3 * 60 * 60 * 1000L)
        assertNull(early?.now)
        assertEquals("Early Show", early?.next?.title)
    }

    @Test
    fun searchProgramsReturnsAiringThenUpcomingMatches() {
        val t0 = 1_000_000L
        fun programme(title: String, start: Long, stop: Long) = EpgProgram(
            channelId = "c1",
            title = title,
            startMs = start,
            stopMs = stop
        )
        val guide = EpgGuide(
            programsByChannel = mapOf(
                "c1" to listOf(
                    programme("Evening News", t0, t0 + 600_000),
                    programme("News at Nine", t0 + 3_600_000, t0 + 7_200_000)
                ),
                "c2" to listOf(
                    programme("Old News", t0 - 7_200_000, t0 - 3_600_000),
                    programme("News Today", t0 + 900_000, t0 + 1_800_000)
                )
            ),
            channels = mapOf(
                "c1" to EpgChannelInfo("c1", "One"),
                "c2" to EpgChannelInfo("c2", "Two")
            )
        )

        val hits = guide.searchPrograms("news", atMs = t0 + 60_000, limit = 4)
        assertEquals(listOf("Evening News", "News Today", "News at Nine"), hits.map { it.program.title })
        assertEquals("Two", hits[1].channelName)
        // Only programmes still airing or upcoming are tuneable.
        assertTrue(guide.searchPrograms("old", atMs = t0 + 60_000).isEmpty())
    }

    @Test
    fun parseXmlTvDateConvertsOffsetsAndPartialDates() {
        fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(year, month - 1, day, hour, minute, 0)
            }.timeInMillis

        assertEquals(utc(2026, 10, 20, 12, 0), IptvParser.parseXmlTvDate("20261020140000 +0200"))
        assertEquals(utc(2026, 10, 20, 12, 0), IptvParser.parseXmlTvDate("20261020140000+0200"))
        assertEquals(utc(2026, 10, 20, 8, 30), IptvParser.parseXmlTvDate("20261020140000 +05:30"))
        assertEquals(utc(2026, 10, 20, 14, 0), IptvParser.parseXmlTvDate("20261020140000Z"))
        assertEquals(utc(2026, 10, 20, 14, 0), IptvParser.parseXmlTvDate("2026-10-20T14:00:00Z"))
        assertEquals(utc(2026, 10, 20, 12, 0), IptvParser.parseXmlTvDate("2026-10-20 14:00:00 +0200"))
        assertEquals(utc(2026, 10, 20, 12, 0), IptvParser.parseXmlTvDate("2026-10-20T14:00:00.000+02:00"))
        assertEquals(utc(2026, 10, 20, 14, 0), IptvParser.parseXmlTvDate("2026-10-20T14:00:00.000Z"))
        assertEquals(utc(2026, 10, 21, 4, 30), IptvParser.parseXmlTvDate("20261021013000 -0300"))
        assertEquals(utc(2026, 10, 20, 0, 0), IptvParser.parseXmlTvDate("20261020"))
        assertEquals(utc(2026, 10, 20, 14, 0), IptvParser.parseXmlTvDate("20261020140000"))
        assertEquals(utc(2026, 10, 20, 14, 0), IptvParser.parseXmlTvDate("2026-10-20 14:00:00"))
        assertNull(IptvParser.parseXmlTvDate(""))
        assertNull(IptvParser.parseXmlTvDate("garbage"))
        assertNull(IptvParser.parseXmlTvDate("20261320140000"))
    }

    @Test
    fun parseXmlTvDateTreatsZonelessStampsAsUtc() {
        val utcTime = utcAt(2026, 10, 20, 14, 0)

        assertEquals(utcTime, IptvParser.parseXmlTvDate("20261020140000"))
        // Sub-second fractions are accepted and dropped; the zone after them still counts.
        assertEquals(utcTime, IptvParser.parseXmlTvDate("2026-10-20T14:00:00.250Z"))
        assertEquals(utcAt(2026, 10, 20, 12, 0), IptvParser.parseXmlTvDate("2026-10-20T14:00:00.250+02:00"))

        // A zoneless guide is on the UTC clock, so now/next lands on the stamp.
        val xml = """
            <tv>
              <channel id="c"><display-name>C</display-name></channel>
              <programme start="20261020140000" stop="20261020150000" channel="c">
                <title>Matinale</title>
              </programme>
            </tv>
        """.trimIndent()
        val guide = IptvParser.parseXmlTv(xml, nowMs = utcAt(2026, 10, 20, 14, 15))
        assertEquals("Matinale", guide.nowNext("c", "C", utcAt(2026, 10, 20, 14, 15))?.now?.title)
        assertNull(guide.nowNext("c", "C", utcAt(2026, 10, 20, 12, 15))?.now)
    }

    @Test
    fun shiftedCorrectsGuidesWithAMislabelledOffset() {
        // epgshare's ZA feed stamps UTC+8 wall clocks as "+0700", so every
        // programme lands an hour late until the source carries a -60 min shift.
        val xml = """
            <tv>
              <channel id="SABC.1.HD.za"><display-name>SABC 1</display-name></channel>
              <programme start="20261102190000 +0700" stop="20261102193000 +0700" channel="SABC.1.HD.za">
                <title>Xhosa/Zulu News</title>
              </programme>
              <programme start="20261102193000 +0700" stop="20261102200200 +0700" channel="SABC.1.HD.za">
                <title>Skeem Saam</title>
              </programme>
            </tv>
        """.trimIndent()
        val parsed = IptvParser.parseXmlTv(xml, nowMs = utcAt(2026, 11, 2, 12, 0))
        assertEquals(utcAt(2026, 11, 2, 12, 0), parsed.programs("SABC.1.HD.za").first().startMs)

        val fixed = parsed.shifted(-60)
        assertEquals(utcAt(2026, 11, 2, 11, 0), fixed.programs("SABC.1.HD.za").first().startMs)
        assertEquals(
            "Xhosa/Zulu News",
            fixed.nowNext("SABC.1.HD.za", "SABC 1", utcAt(2026, 11, 2, 11, 10))?.now?.title
        )
        assertEquals(
            "Skeem Saam",
            fixed.nowNext("SABC.1.HD.za", "SABC 1", utcAt(2026, 11, 2, 11, 40))?.now?.title
        )
    }

    @Test
    fun autoShiftDetectsTheSouthAfricanMislabelledGuide() {
        val xml = """
            <tv>
              <channel id="SABC.1.HD.za"><display-name>SABC 1</display-name></channel>
              <channel id="SABC.2.HD.za"><display-name>SABC 2</display-name></channel>
              <programme start="20261102190000 +0700" stop="20261102193000 +0700" channel="SABC.1.HD.za">
                <title>Xhosa/Zulu News</title>
              </programme>
              <programme start="20261102193000 +0700" stop="20261102200200 +0700" channel="SABC.1.HD.za">
                <title>Skeem Saam</title>
              </programme>
            </tv>
        """.trimIndent()
        val parsed = IptvParser.parseXmlTv(xml, nowMs = utcAt(2026, 11, 2, 12, 0))

        assertEquals(7 * 60, parsed.declaredOffsetMinutes)
        assertEquals(-60, parsed.autoShiftMinutes())

        val fixed = parsed.shifted(parsed.autoShiftMinutes())
        assertEquals(
            "Xhosa/Zulu News",
            fixed.nowNext("SABC.1.HD.za", "SABC 1", utcAt(2026, 11, 2, 11, 10))?.now?.title
        )
        assertEquals(
            "Skeem Saam",
            fixed.nowNext("SABC.1.HD.za", "SABC 1", utcAt(2026, 11, 2, 11, 40))?.now?.title
        )
    }

    @Test
    fun autoShiftLeavesSaneOrFixedGuidesAlone() {
        fun guide(channelId: String, stamp: String): EpgGuide {
            val xml = """
                <tv>
                  <channel id="$channelId"><display-name>Channel</display-name></channel>
                  <programme start="$stamp" stop="${stamp.take(8)}220000 +0000" channel="$channelId">
                    <title>Show</title>
                  </programme>
                </tv>
            """.trimIndent()
            return IptvParser.parseXmlTv(xml, nowMs = utcAt(2026, 11, 2, 12, 0))
        }

        // A UK feed with a plausible +0000 label needs no correction even
        // though its ids are all one country.
        assertEquals(0, guide("TNT.Sports.1.uk", "20261102190000 +0000").autoShiftMinutes())
        // The same ZA feed corrected by its provider stops matching the bug.
        assertEquals(0, guide("SABC.1.HD.za", "20261102190000 +0200").autoShiftMinutes())
        // A mixed guide is never shifted.
        val mixed = """
            <tv>
              <channel id="SABC.1.HD.za"><display-name>SABC 1</display-name></channel>
              <channel id="BBC.1.uk"><display-name>BBC 1</display-name></channel>
              <programme start="20261102190000 +0700" stop="20261102200000 +0700" channel="SABC.1.HD.za">
                <title>News</title>
              </programme>
            </tv>
        """.trimIndent()
        val mixedGuide = IptvParser.parseXmlTv(mixed, nowMs = utcAt(2026, 11, 2, 12, 0))
        assertEquals(0, mixedGuide.autoShiftMinutes())
    }

    private fun utcAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun legacySourcesKeepManualShiftsAndDefaultTheRestToAuto() {
        val untouched = IptvSource.fromJson(
            org.json.JSONObject("""{"id":"a","name":"A","location":"x","epgShiftMinutes":0}""")
        )
        assertTrue(untouched.epgShiftAuto)
        val corrected = IptvSource.fromJson(
            org.json.JSONObject("""{"id":"b","name":"B","location":"x","epgShiftMinutes":-60}""")
        )
        assertTrue(!corrected.epgShiftAuto)
        assertEquals(-60, corrected.epgShiftMinutes)
    }

    @Test
    fun gunzipHandlesCompressedGuides() {
        val xml = "<tv><channel id=\"a\"><display-name>A</display-name></channel></tv>".toByteArray()
        val gz = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(xml) }
            out.toByteArray()
        }
        assertEquals(String(xml), String(IptvParser.ungzip(gz)))
        assertEquals(String(xml), IptvParser.maybeGunzip(ByteArrayInputStream(gz)).readBytes().toString(Charsets.UTF_8))
        assertEquals(String(xml), IptvParser.maybeGunzip(ByteArrayInputStream(xml)).readBytes().toString(Charsets.UTF_8))
        assertTrue(IptvParser.parseXmlTv(IptvParser.maybeGunzip(ByteArrayInputStream(gz))).isEmpty)
    }

    @Test
    fun sourceJsonRoundTripKeepsIptvFields() {
        val source = IptvSource(
            id = "id1",
            name = "Provider",
            location = "https://example.com/list.m3u",
            kind = IptvKind.XSPF,
            epgUrl = "https://example.com/epg1.xml",
            extractedEpgUrl = "https://example.com/epg2.xml",
            epgShiftMinutes = -60,
            epgShiftAuto = false,
            channelCount = 12,
            lastLoadedAt = 1234L
        )
        val restored = IptvSource.fromJson(source.toJson())
        assertEquals(source, restored)
        assertEquals("https://example.com/epg1.xml", restored.effectiveEpgUrl)
        assertTrue(!restored.isLocal)
    }
}
