package com.streamline.cinema.ui.player

import androidx.media3.common.Player
import com.streamline.cinema.data.NativeStreamProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Reproduces a live rebuffer end to end at the layer the app controls: a real
 * loopback proxy transfer whose upstream CDN deliberately dribbles a segment
 * for longer than the production stall window. The watchdog must stay quiet
 * while those bytes are still arriving, and must still fire once the refill
 * ends and playback freezes again.
 */
class ThrottledLiveRebufferTest {

    private class FakePlayer(
        override var playbackState: Int,
        override var currentPosition: Long = 0L,
        override var bufferedPosition: Long = 0L
    ) : WatchdogPlayer {
        override val hasError: Boolean = false
        override val playWhenReady: Boolean = true
        override val suppressionReason: Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    }

    @Test
    fun throttledRefillLongerThanTheStallWindowIsNotDeclaredStalled() {
        // ~1 KB/s: the 17 KB segment takes longer than the 15 s freeze window,
        // which is exactly the producer-slow rebuffer the 60 s grace covers.
        val chunkSize = 512
        val chunks = 34
        val chunkDelayMs = 500L
        val segmentSize = chunkSize * chunks
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10_000
        val playlist = "#EXTM3U\n" +
            "#EXT-X-VERSION:3\n" +
            "#EXT-X-TARGETDURATION:6\n" +
            "#EXT-X-MEDIA-SEQUENCE:42\n" +
            "#EXTINF:6.0,\n" +
            "seg-1.ts\n"
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
                        val out = client.getOutputStream()
                        if (!requestLine.contains("seg-1.ts")) {
                            val body = playlist.toByteArray()
                            out.write(
                                ("HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.US_ASCII)
                            )
                            out.write(body)
                            out.flush()
                        } else {
                            // Headers first, then the body one chunk at a time:
                            // a live segment that is being produced as it is read.
                            out.write(
                                ("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n" +
                                    "Content-Length: $segmentSize\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.US_ASCII)
                            )
                            out.flush()
                            repeat(chunks) { index ->
                                out.write(ByteArray(chunkSize) { index.toByte() })
                                out.flush()
                                Thread.sleep(chunkDelayMs)
                            }
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
            fun fetch(url: String): String {
                val connection = java.net.URL(url).openConnection()
                connection.connectTimeout = 5_000
                connection.readTimeout = 30_000
                return connection.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
            }
            val rewritten = fetch(handle.url)
            assertTrue(rewritten.startsWith("#EXTM3U"))
            val segmentUrl = rewritten.lineSequence().first { it.startsWith("http") }

            val watchdog = StallWatchdog()
            val player = FakePlayer(Player.STATE_READY, currentPosition = 60_000L)
            assertEquals(StallDecision.Progress, watchdog.tick(player, 0L, scrubbing = false))

            // From here the player is rebuffering: the playhead is parked and
            // only the loopback body is moving.
            player.playbackState = Player.STATE_BUFFERING
            val connection = java.net.URL(segmentUrl).openConnection()
            connection.connectTimeout = 5_000
            connection.readTimeout = 30_000
            val started = System.nanoTime()
            var received = 0
            var elapsedMs = 0L
            connection.getInputStream().use { input ->
                val buffer = ByteArray(1024)
                while (received < segmentSize) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        fail("proxy closed the loopback body after $received of $segmentSize bytes")
                    }
                    received += read
                    elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    // One poll per read, on the real timeline the app would see.
                    assertEquals(
                        "a refill must not be stalled at ${elapsedMs} ms while bytes are arriving",
                        StallDecision.Idle,
                        watchdog.tick(player, elapsedMs, scrubbing = false)
                    )
                }
            }
            assertEquals(segmentSize, received)
            assertTrue(
                "the throttled refill must outlast the ${PLAYBACK_STALL_WATCHDOG_MS} ms window, " +
                    "otherwise it does not reproduce the rebuffer (was ${elapsedMs} ms)",
                elapsedMs > PLAYBACK_STALL_WATCHDOG_MS
            )

            // The refill ends; a genuinely frozen READY player still trips the
            // normal 15 s window from here.
            player.playbackState = Player.STATE_READY
            assertEquals(
                StallDecision.Stalled,
                watchdog.tick(player, elapsedMs + PLAYBACK_STALL_WATCHDOG_MS, scrubbing = false)
            )
        } finally {
            server.close()
        }
    }
}
