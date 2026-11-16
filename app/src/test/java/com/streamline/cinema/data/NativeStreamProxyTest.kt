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
    fun contentTypeKeepsSpecificUpstreamAndRejectsGeneric() {        assertEquals(
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
    fun hlsPrefixSniffingAcceptsTagAndBom() {
        assertTrue(NativeStreamProxy.isHlsPlaylistPrefix("#EXTM3U\n#EXTINF:4,".toByteArray()))
        assertTrue(
            NativeStreamProxy.isHlsPlaylistPrefix(
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "#EXTM3U".toByteArray()
            )
        )
        assertTrue(!NativeStreamProxy.isHlsPlaylistPrefix("<!DOCTYPE html>".toByteArray()))
        assertTrue(!NativeStreamProxy.isHlsPlaylistPrefix("PK".toByteArray()))
        assertTrue(!NativeStreamProxy.isHlsPlaylistPrefix(ByteArray(0)))
    }

    @Test
    fun extensionlessPlaylistWithGenericTypeStillRewritesSegments() {
        // Token endpoints serve the playlist from a path without an extension
        // and label it octet-stream; the body, not the header, has to decide.
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10_000
        Thread {
            repeat(2) {
                runCatching {
                    server.accept().use { client ->
                        client.soTimeout = 10_000
                        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val requestLine = reader.readLine().orEmpty()
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                        }
                        val segment = requestLine.contains("/media/v0/seg718.ts")
                        val body = if (segment) {
                            "TS-BYTES".toByteArray()
                        } else {
                            ("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\n" +
                                "/media/v0/seg718.ts\n#EXT-X-ENDLIST\n").toByteArray()
                        }
                        val type = if (segment) "video/mp2t" else "application/octet-stream"
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
                url = "http://localhost:${server.localPort}/media/token/raw",
                userAgent = "UA"
            ) ?: error("proxy did not start")
            fun fetch(url: String): String {
                val connection = java.net.URL(url).openConnection()
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                return connection.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val playlist = fetch(handle.url)
            val segmentUrl = playlist.lineSequence().first { it.startsWith("http") }
            assertTrue(segmentUrl.startsWith("http://127.0.0.1"))
            assertEquals("TS-BYTES", fetch(segmentUrl))
        } finally {
            server.close()
        }
    }

    @Test
    fun playlistTargetsAreNarrowerThanMediaSegments() {
        assertTrue(NativeStreamProxy.isPlaylistTarget("https://cdn.example/live/master.m3u8?token=1"))
        assertTrue(NativeStreamProxy.isPlaylistTarget("https://cdn.example/live/list.M3U"))
        assertTrue(NativeStreamProxy.isPlaylistTarget("http://cdn.example/live/index.m3u8#frag"))
        assertTrue(!NativeStreamProxy.isPlaylistTarget("https://cdn.example/live/seg-1.ts"))
        assertTrue(!NativeStreamProxy.isPlaylistTarget("https://cdn.example/live/playlist"))
        assertTrue(!NativeStreamProxy.isPlaylistTarget("https://cdn.example/live/key.m3u8.key"))
    }

    @Test
    fun playlistRetriesOnceWhenTheUpstreamResetsBeforeAnswering() {
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10_000
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts\n"
        Thread {
            var attempts = 0
            while (attempts < 2) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                attempts += 1
                if (attempts == 1) {
                    // A one-off reset: accept, then drop without an answer.
                    client.close()
                    continue
                }
                runCatching {
                    client.use {
                        it.getInputStream().bufferedReader(Charsets.ISO_8859_1).apply {
                            readLine()
                            while (readLine()?.isNotEmpty() == true) Unit
                        }
                        val body = playlist.toByteArray()
                        it.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
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
                userAgent = "UA"
            ) ?: error("proxy did not start")
            val connection = java.net.URL(handle.url).openConnection()
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val body = connection.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
            assertTrue(body.startsWith("#EXTM3U"))
            assertTrue(body.contains("/s/"))
        } finally {
            server.close()
        }
    }

    @Test
    fun fixedLengthPlaylistsReuseOneLoopbackSocket() {
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10_000
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts\n"
        Thread {
            repeat(2) {
                runCatching {
                    server.accept().use { client ->
                        client.soTimeout = 10_000
                        client.getInputStream().bufferedReader(Charsets.ISO_8859_1).apply {
                            readLine()
                            while (readLine()?.isNotEmpty() == true) Unit
                        }
                        val body = playlist.toByteArray()
                        // Connection: close keeps the pooled upstream client
                        // from reusing its side; only the loopback socket is
                        // under test here.
                        client.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
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
                userAgent = "UA"
            ) ?: error("proxy did not start")
            val url = handle.url.toHttpUrl()
            java.net.Socket("127.0.0.1", url.port).use { socket ->
                socket.soTimeout = 5_000
                val input = java.io.BufferedInputStream(socket.getInputStream())
                fun request(): String {
                    socket.getOutputStream().apply {
                        write(
                            ("GET ${url.encodedPath} HTTP/1.1\r\n" +
                                "Host: 127.0.0.1:${url.port}\r\nConnection: keep-alive\r\n\r\n")
                                .toByteArray(Charsets.US_ASCII)
                        )
                        flush()
                    }
                    val head = java.io.ByteArrayOutputStream()
                    var matched = 0
                    while (matched < 4) {
                        val byte = input.read()
                        if (byte < 0) error("socket closed before headers")
                        head.write(byte)
                        matched = when {
                            (matched == 0 || matched == 2) && byte == '\r'.code -> matched + 1
                            (matched == 1 || matched == 3) && byte == '\n'.code -> matched + 1
                            else -> 0
                        }
                    }
                    val headText = head.toString(Charsets.ISO_8859_1.name())
                    assertTrue(headText.contains("Connection: keep-alive", true))
                    val length = Regex("(?i)Content-Length: *(\\d+)")
                        .find(headText)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val chunk = input.read(body, read, length - read)
                        if (chunk < 0) error("socket closed before the body")
                        read += chunk
                    }
                    return String(body, Charsets.UTF_8)
                }
                assertTrue(request().startsWith("#EXTM3U"))
                assertTrue(request().startsWith("#EXTM3U"))
            }
        } finally {
            server.close()
        }
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
