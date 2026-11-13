package com.streamline.cinema.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Source resolution: addon streams rarely name their container in the URL, so
 * every path that hands a URL to Media3 or an external player has to agree on
 * the redirect target and the extractor type.
 */
class StreamSourceTest {

    private val servers = mutableListOf<HttpServer>()

    @After
    fun stopServers() {
        servers.forEach { it.stop(0) }
        servers.clear()
    }

    @Test
    fun kindComesFromTheExtensionBeforeAnythingElse() {
        assertEquals(StreamKind.HLS, StreamUrls.kindOf("https://cdn/a/master.m3u8"))
        assertEquals(StreamKind.HLS, StreamUrls.kindOf("https://cdn/a/live.M3U8?token=1"))
        assertEquals(StreamKind.DASH, StreamUrls.kindOf("https://cdn/a/manifest.mpd#t=10"))
        assertEquals(StreamKind.SMOOTH, StreamUrls.kindOf("https://cdn/a/Manifest"))
        // A path that names a container wins over a generic response type.
        assertEquals(StreamKind.HLS, StreamUrls.kindOf("https://cdn/a.m3u8", "application/octet-stream"))
    }

    @Test
    fun kindFallsBackToContentTypeThenManifestPrefix() {
        assertEquals(
            StreamKind.HLS,
            StreamUrls.kindOf("https://cdn/play/abc123", "application/vnd.apple.mpegurl; charset=utf-8")
        )
        assertEquals(StreamKind.DASH, StreamUrls.kindOf("https://cdn/play/abc123", "application/dash+xml"))
        assertNull(StreamUrls.kindOf("https://cdn/play/abc123", "application/octet-stream"))
        assertEquals(
            StreamKind.HLS,
            StreamUrls.kindOf("https://cdn/play/abc123", "application/octet-stream", "#EXTM3U\n#EXT-X-STREAM-INF")
        )
        assertEquals(
            StreamKind.DASH,
            StreamUrls.kindOf("https://cdn/play/abc123", null, "<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash\"")
        )
        assertNull(StreamUrls.kindOf("https://cdn/movie/12345", null, "ftypisom"))
    }

    @Test
    fun kindDiscoverySkipsKnownFiles() {
        assertTrue(StreamUrls.needsKindDiscovery("https://short.link/stvp-FR200016Y5"))
        assertTrue(StreamUrls.needsKindDiscovery("https://cdn/play.php?id=7"))
        assertFalse(StreamUrls.needsKindDiscovery("https://cdn/master.m3u8"))
        assertFalse(StreamUrls.needsKindDiscovery("https://cdn/film.mp4"))
        assertFalse(StreamUrls.needsKindDiscovery("https://cdn/film.mkv"))
        // Pipe headers are not part of the URL that gets classified.
        assertTrue(StreamUrls.needsKindDiscovery("https://short.link/abc|Referer=https%3A%2F%2Fsite%2F"))
    }

    @Test
    fun resolveSourceWalksTheShortLinkAndReportsHls() = runBlocking {
        val base = start(
            "/go" to { exchange -> redirect(exchange, "/direct/stream") },
            "/direct/stream" to { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                text(exchange, "#EXTM3U\n#EXTINF:6,\nseg.ts")
            }
        )
        val resolved = StremioAddons.resolveSource("$base/go", emptyMap())
        assertEquals("$base/direct/stream", resolved.url)
        assertEquals(StreamKind.HLS, resolved.kind)
        assertTrue(resolved.contentType.orEmpty().contains("mpegurl"))
    }

    @Test
    fun resolveSourceSniffsAnOctetStreamManifest() = runBlocking {
        val base = start(
            "/tok" to { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                text(exchange, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8")
            }
        )
        val resolved = StremioAddons.resolveSource("$base/tok", emptyMap())
        assertEquals("$base/tok", resolved.url)
        assertEquals(StreamKind.HLS, resolved.kind)
    }

    @Test
    fun resolveSourceKeepsTheOriginalUrlWhenNothingAnswers() = runBlocking {
        val unresolved = "http://127.0.0.1:1/nowhere"
        val resolved = StremioAddons.resolveSource(unresolved, emptyMap())
        assertEquals(unresolved, resolved.url)
        assertNull(resolved.kind)
    }

    @Test
    fun addonStreamCarriesMergedHeadersAndKind() {
        val stream = StreamItem(
            name = "1080p",
            title = null,
            url = "https://cdn/live/master.m3u8|User-Agent=Box/2.0&Referer=https%3A%2F%2Fsite%2F",
            externalUrl = null,
            behaviorHints = mapOf(
                "proxyHeaders" to mapOf(
                    "request" to mapOf("Referer" to "https://site/page", "Origin" to "https://site")
                )
            )
        )
        val option = stream.toPlaybackOption("Addon")!!
        assertEquals("https://cdn/live/master.m3u8", option.url)
        assertEquals("https://cdn/live/master.m3u8", StreamUrls.clean(stream.url!!))
        assertEquals(StreamKind.HLS, option.streamKind)
        // Pipe values win over behaviour hints.
        assertEquals("Box/2.0", option.headers["User-Agent"])
        assertEquals("https://site/", option.headers["Referer"])
        assertEquals("https://site", option.headers["Origin"])
    }

    @Test
    fun headerValuesStayUniqueAcrossCasings() {
        val headers = mapOf(
            "user-agent" to "App/1.0",
            "User-Agent" to "Addon/2.0",
            "referer" to "https://addon/",
            "X-Token" to "abc"
        )
        val pairs = ExternalIntents.headerPairs("https://page/", headers, "Fallback/3.0")
        val names = pairs.filterIndexed { index, _ -> index % 2 == 0 }
        assertEquals(names.size, names.map { it.lowercase() }.distinct().size)
        // The explicit Referer wins, and the last UA per casing survives.
        assertEquals("https://page/", valueOf(pairs, "Referer"))
        assertEquals("Addon/2.0", valueOf(pairs, "User-Agent"))
        assertEquals("abc", valueOf(pairs, "X-Token"))
    }

    @Test
    fun externalTypeUsesTheKindWhenTheUrlHasNone() {
        assertEquals(
            "application/x-mpegURL",
            ExternalIntents.mimeFor("https://cdn/play/abc123", StreamKind.HLS)
        )
        assertEquals(
            "application/dash+xml",
            ExternalIntents.mimeFor("https://cdn/play/abc123", StreamKind.DASH)
        )
        assertEquals("video/mp4", ExternalIntents.mimeFor("https://cdn/film.mp4", null))
        assertEquals("video/*", ExternalIntents.mimeFor("https://cdn/play/abc123", null))
    }

    @Test
    fun pipeHeadersAcceptPipeAndAmpersandSeparators() {
        val parsed = StreamUrls.parse(
            "https://cdn/live.m3u8|User-Agent=VLC/3.0|Referer=https%3A%2F%2Fsite%2F&Origin=https://site"
        )
        assertEquals("https://cdn/live.m3u8", parsed.url)
        assertEquals("VLC/3.0", parsed.headers["User-Agent"])
        assertEquals("https://site/", parsed.headers["referer"])
        assertEquals("https://site", parsed.headers["Origin"])
    }

    @Test
    fun pipeHeaderValuesKeepPlusAndDecodePercentRuns() {
        val parsed = StreamUrls.parse(
            "https://cdn/a.mkv|X-Token=abc+def==&Cookie=sid%3D1%26v%3D2"
        )
        assertEquals("abc+def==", parsed.headers["X-Token"])
        assertEquals("sid=1&v=2", parsed.headers["Cookie"])
    }

    @Test
    fun pipeHeadersIgnoreJunkReservedAndInvalidNames() {
        val parsed = StreamUrls.parse(
            "https://cdn/a.m3u8|junk&Host=evil.example&Bad Name=x&Referer=\"https://site/\""
        )
        assertEquals("https://cdn/a.m3u8", parsed.url)
        assertEquals(1, parsed.headers.size)
        assertNull(parsed.headers["Host"])
        assertEquals("https://site/", parsed.headers["Referer"])
    }

    @Test
    fun pipeHeaderNamesAreCaseInsensitive() {
        val parsed = StreamUrls.parse("https://cdn/a.m3u8|user-agent=One&User-Agent=Two")
        assertEquals(1, parsed.headers.size)
        assertEquals("Two", parsed.headers["USER-AGENT"])
    }

    @Test
    fun pipeSuffixWithoutHeadersLeavesTheUrlIntact() {
        val literalPipe = StreamUrls.parse("https://cdn/a|b")
        assertEquals("https://cdn/a|b", literalPipe.url)
        assertTrue(literalPipe.headers.isEmpty())
        assertEquals("https://cdn/a", StreamUrls.parse("https://cdn/a|").url)
        assertEquals("https://cdn/a", StreamUrls.parse("  https://cdn/a  ").url)
    }

    @Test
    fun mergedHeadersDropWhatTheDataSourceCannotSend() {
        val merged = StreamUrls.mergeHeaders(
            mapOf("User-Agent" to "Default"),
            mapOf("user-agent" to "Addon", "Connection" to "keep-alive", "Bad Name" to "x")
        )
        assertEquals(1, merged.size)
        assertEquals("Addon", merged["user-agent"])
    }

    @Test
    fun reassembledPipeHeadersRoundTrip() {
        val raw = StreamUrls.withHeaders(
            "https://cdn/a.m3u8",
            mapOf("Referer" to "https://site/with space", "X-Token" to "a+b==")
        )
        val parsed = StreamUrls.parse(raw)
        assertEquals("https://cdn/a.m3u8", parsed.url)
        assertEquals("https://site/with space", parsed.headers["Referer"])
        assertEquals("a+b==", parsed.headers["X-Token"])
    }

    private fun valueOf(pairs: Array<String>, name: String): String? =
        pairs.toList().chunked(2).firstOrNull { it[0].equals(name, true) }?.get(1)

    private fun url(server: HttpServer): String =
        "http://${InetAddress.getLoopbackAddress().hostAddress}:${server.address.port}"

    private fun start(vararg routes: Pair<String, (HttpExchange) -> Unit>): String {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        routes.forEach { (path, handler) ->
            server.createContext(path) { exchange -> handler(exchange) }
        }
        server.start()
        servers += server
        return url(server)
    }

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun text(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
