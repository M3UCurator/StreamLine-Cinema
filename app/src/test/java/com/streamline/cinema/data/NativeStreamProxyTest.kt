package com.streamline.cinema.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStreamProxyTest {

    private val link: (String) -> String = { absolute ->
        "http://127.0.0.1:9999/tok/s/abc?u=${NativeStreamProxy.encodeBase64Url(absolute.toByteArray())}"
    }

    private fun unproxied(line: String): String {
        val encoded = line.substringAfter("?u=")
        return String(NativeStreamProxy.decodeBase64Url(encoded)!!)
    }

    @Test
    fun base64UrlRoundTripsTokensAndQuery() {
        val url = "https://cdn.example/live/seg-1.ts?token=abc+/=&&expires=1700000000&x=%2F"
        val encoded = NativeStreamProxy.encodeBase64Url(url.toByteArray())
        assertTrue(encoded.none { it == '+' || it == '/' || it == '=' })
        assertEquals(url, String(NativeStreamProxy.decodeBase64Url(encoded)!!))
    }

    @Test
    fun malformedBase64ReturnsNull() {
        assertNull(NativeStreamProxy.decodeBase64Url("not*base64"))
    }

    @Test
    fun masterPlaylistRewritesRelativeAndAbsoluteVariants() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1280x720
            https://cdn2.example/high/index.m3u8?token=abc
        """.trimIndent()

        val rewritten = NativeStreamProxy.rewritePlaylist(
            playlist,
            "https://cdn.example/live/master.m3u8?auth=1".toHttpUrl(),
            link
        )
        val lines = rewritten.trim().lines()

        assertEquals("#EXTM3U", lines[0])
        assertEquals("#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360", lines[1])
        assertEquals("https://cdn.example/live/low/index.m3u8", unproxied(lines[2]))
        assertEquals("https://cdn2.example/high/index.m3u8?token=abc", unproxied(lines[4]))
        assertTrue(lines[2].startsWith("http://127.0.0.1:9999/tok/s/abc?u="))
    }

    @Test
    fun keyMapAndMediaUrisAreRewritten() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXT-X-MAP:URI="../init.mp4"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",URI="audio/index.m3u8"
            #EXTINF:6.0,
            seg-1.ts
        """.trimIndent()

        val rewritten = NativeStreamProxy.rewritePlaylist(
            playlist,
            "https://cdn.example/a/b/index.m3u8".toHttpUrl(),
            link
        )
        val lines = rewritten.trim().lines()

        assertTrue(lines[1].contains("URI=\"http://127.0.0.1:9999/tok/s/abc?u="))
        assertTrue(lines[1].contains(NativeStreamProxy.encodeBase64Url(
            "https://cdn.example/a/b/key.bin".toByteArray()
        )))
        assertTrue(lines[2].contains(NativeStreamProxy.encodeBase64Url(
            "https://cdn.example/a/init.mp4".toByteArray()
        )))
        assertTrue(lines[3].contains(NativeStreamProxy.encodeBase64Url(
            "https://cdn.example/a/b/audio/index.m3u8".toByteArray()
        )))
        assertEquals("https://cdn.example/a/b/seg-1.ts", unproxied(lines[5]))
    }

    @Test
    fun contentTypeFollowsKnownStreamExtensions() {
        // Providers routinely omit or mislabel segment types; the extension is
        // the explicit declaration the app and native engines should rely on.
        assertEquals(
            "application/vnd.apple.mpegurl",
            NativeStreamProxy.contentTypeFor("https://cdn.example/live/index.m3u8?t=1", null)
        )
        assertEquals(
            "video/mp2t",
            NativeStreamProxy.contentTypeFor("https://cdn.example/live/seg-1.TS", "application/octet-stream")
        )
        assertEquals(
            "video/iso.segment",
            NativeStreamProxy.contentTypeFor("https://cdn.example/live/seg-1.m4s", "")
        )
        assertEquals(
            "text/vtt",
            NativeStreamProxy.contentTypeFor("https://cdn.example/subs/en.vtt", "text/plain")
        )
        assertEquals(
            "application/x-subrip",
            NativeStreamProxy.contentTypeFor("https://cdn.example/subs/en.srt", null)
        )
        assertEquals(
            "application/octet-stream",
            NativeStreamProxy.contentTypeFor("https://cdn.example/keys/k1.key", "text/plain")
        )
        assertEquals(
            "image/jpeg",
            NativeStreamProxy.contentTypeFor("https://cdn.example/thumbs/1.jpg", null)
        )
    }

    @Test
    fun contentTypeKeepsSpecificUpstreamAndRejectsGeneric() {
        assertEquals(
            "audio/mpeg",
            NativeStreamProxy.contentTypeFor("https://cdn.example/segment.php?id=7", "audio/mpeg; charset=binary")
        )
        assertEquals(
            "application/vnd.apple.mpegurl",
            NativeStreamProxy.contentTypeFor("https://cdn.example/play", "application/x-mpegURL")
        )
        assertEquals(
            "application/octet-stream",
            NativeStreamProxy.contentTypeFor("https://cdn.example/segment.php?id=7", "binary/octet-stream")
        )
        assertEquals(
            "application/octet-stream",
            NativeStreamProxy.contentTypeFor("https://cdn.example/segment.php?id=7", "   ")
        )
    }

    @Test
    fun streamIdIsStableAndHeaderSensitive() {
        val first = NativeStreamProxy.streamId(
            "https://cdn.example/a.m3u8",
            "UA",
            mapOf("Referer" to "https://site/")
        )
        val again = NativeStreamProxy.streamId(
            "https://cdn.example/a.m3u8",
            "UA",
            mapOf("Referer" to "https://site/")
        )
        val otherHeader = NativeStreamProxy.streamId(
            "https://cdn.example/a.m3u8",
            "UA",
            mapOf("Referer" to "https://other/")
        )
        assertEquals(first, again)
        assertNotEquals(first, otherHeader)
        assertEquals(16, first.length)
    }

    /** Providers bind tokens to the app's own UA, so the proxy must not swap in a browser UA. */
    @Test
    fun proxiedRequestsForwardTheRegisteredUserAgent() {
        val expected = "TiviMate/5.0.4 (Linux; Android 12) Android TV"
        val seen = java.util.concurrent.LinkedBlockingQueue<String>()
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10_000
        Thread {
            repeat(2) {
                runCatching {
                    server.accept().use { client ->
                        client.soTimeout = 10_000
                        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val requestLine = reader.readLine().orEmpty()
                        var userAgent = ""
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            val colon = header.indexOf(':')
                            if (colon > 0 && header.substring(0, colon).equals("User-Agent", true)) {
                                userAgent = header.substring(colon + 1).trim()
                            }
                        }
                        seen += userAgent
                        val playlist = requestLine.contains(".m3u8")
                        val body = (if (playlist) "#EXTM3U\n#EXTINF:4,\nseg.ts\n" else "segment")
                            .toByteArray(Charsets.UTF_8)
                        val type = if (playlist) "application/vnd.apple.mpegurl" else "video/mp2t"
                        client.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.US_ASCII)
                            )
                            write(body)
                            flush()
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            val handle = NativeStreamProxy.proxyHls(
                url = "http://localhost:${server.localPort}/live/index.m3u8",
                userAgent = expected
            ) ?: error("proxy did not start")
            fun fetch(url: String): String {
                val connection = java.net.URL(url).openConnection()
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                return connection.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val playlist = fetch(handle.url)
            fetch(playlist.lineSequence().first { it.startsWith("http") })
            assertEquals(expected, seen.poll(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(expected, seen.poll(10, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            server.close()
        }
    }

    @Test
    fun upstreamTripsOnceAfterThreeConsecutiveFailures() {
        val tripped = mutableListOf<String>()
        val listener = NativeStreamProxy.UpstreamFailureListener { tripped += it }
        NativeStreamProxy.addUpstreamFailureListener(listener)
        try {
            repeat(2) { NativeStreamProxy.noteUpstreamFailure("stream-a") }
            assertEquals(emptyList<String>(), tripped)

            NativeStreamProxy.noteUpstreamFailure("stream-a")
            assertEquals(listOf("stream-a"), tripped)

            // The counter is consumed on a trip, so the next failure starts
            // a fresh budget instead of tripping on its own.
            NativeStreamProxy.noteUpstreamFailure("stream-a")
            assertEquals(listOf("stream-a"), tripped)
        } finally {
            NativeStreamProxy.removeUpstreamFailureListener(listener)
        }
    }

    @Test
    fun upstreamSuccessBreaksTheFailureStreak() {
        val tripped = mutableListOf<String>()
        val listener = NativeStreamProxy.UpstreamFailureListener { tripped += it }
        NativeStreamProxy.addUpstreamFailureListener(listener)
        try {
            repeat(2) { NativeStreamProxy.noteUpstreamFailure("stream-b") }
            NativeStreamProxy.noteUpstreamSuccess("stream-b")
            repeat(2) { NativeStreamProxy.noteUpstreamFailure("stream-b") }
            assertEquals(emptyList<String>(), tripped)

            NativeStreamProxy.noteUpstreamFailure("stream-b")
            assertEquals(listOf("stream-b"), tripped)
        } finally {
            NativeStreamProxy.removeUpstreamFailureListener(listener)
        }
    }

    @Test
    fun loopbackHostAcceptsOnlyOwnLoopbackNames() {
        assertTrue(NativeStreamProxy.isLoopbackHost("127.0.0.1:41234", 41234))
        assertTrue(NativeStreamProxy.isLoopbackHost("localhost:41234", 41234))
        assertTrue(NativeStreamProxy.isLoopbackHost("LOCALHOST:41234", 41234))
        assertTrue(NativeStreamProxy.isLoopbackHost("  127.0.0.1:41234  ", 41234))
    }

    @Test
    fun loopbackHostRejectsRebindingAndForeignNames() {
        // A rebound page keeps its own domain in Host.
        assertTrue(!NativeStreamProxy.isLoopbackHost("evil.example:41234", 41234))
        assertTrue(!NativeStreamProxy.isLoopbackHost("127.0.0.1:5000", 41234))
        assertTrue(!NativeStreamProxy.isLoopbackHost("localhost:5000", 41234))
        assertTrue(!NativeStreamProxy.isLoopbackHost("127.0.0.1.nip.io:41234", 41234))
        assertTrue(!NativeStreamProxy.isLoopbackHost("127.0.0.1:abc", 41234))
    }

    @Test
    fun loopbackHostRejectsMissingOrMalformedHost() {
        assertTrue(!NativeStreamProxy.isLoopbackHost(null, 41234))
        assertTrue(!NativeStreamProxy.isLoopbackHost("", 41234))
        assertTrue(!NativeStreamProxy.isLoopbackHost("   ", 41234))
        // Bare host means port 80, which the proxy never binds.
        assertTrue(!NativeStreamProxy.isLoopbackHost("127.0.0.1", 41234))
        assertTrue(!NativeStreamProxy.isLoopbackHost("user@127.0.0.1:41234", 41234))
        assertTrue(!NativeStreamProxy.isLoopbackHost("127.0.0.1:41234/path", 41234))
        assertTrue(!NativeStreamProxy.isLoopbackHost("[::1]:41234", 41234))
    }
}
