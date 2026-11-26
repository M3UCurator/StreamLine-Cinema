package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MulticastTest {

    @Test
    fun udpAndRtpAreRawTransport() {
        assertTrue(Multicast.isTransport("udp://@239.255.15.36:1234"))
        assertTrue(Multicast.isTransport("udp://239.255.15.36:1234"))
        assertTrue(Multicast.isTransport("rtp://@239.255.15.36:1234"))
        assertTrue(Multicast.isTransport("UDP://127.0.0.1:5000"))
        // Pipe headers are stripped before the scheme is read.
        assertTrue(Multicast.isTransport("udp://@239.255.15.37:1234|User-Agent=VLC/3.0"))
        assertFalse(Multicast.isTransport("rtsp://camera.example/live"))
        assertFalse(Multicast.isTransport("https://cdn.example/live.m3u8"))
        assertFalse(Multicast.isTransport(""))
        assertFalse(Multicast.isTransport(null))
    }

    @Test
    fun groupAddressesAreDetected() {
        assertTrue(Multicast.isGroup("udp://@239.255.15.36:1234"))
        assertTrue(Multicast.isGroup("udp://239.255.15.36:1234"))
        assertTrue(Multicast.isGroup("rtp://@224.0.1.1:5000"))
        assertTrue(Multicast.isGroup("udp://@239.255.15.36"))
        assertTrue(Multicast.isGroup("udp://@[ff15::1]:1234"))
        assertFalse(Multicast.isGroup("udp://@127.0.0.1:5000"))
        assertFalse(Multicast.isGroup("udp://@192.168.1.10:5000"))
        assertFalse(Multicast.isGroup("udp://receiver.example:1234"))
        // A group literal on a non-transport scheme is just a host name.
        assertFalse(Multicast.isGroup("http://239.255.15.36:1234/live"))
    }

    @Test
    fun transportPlaysOnVlcUnlessExternal() {
        assertEquals(PlaybackEngine.VLC, Multicast.engineFor("udp://@239.255.15.36:1234", PlaybackEngine.AUTO))
        assertEquals(PlaybackEngine.VLC, Multicast.engineFor("udp://@239.255.15.36:1234", PlaybackEngine.BUILT_IN))
        assertEquals(PlaybackEngine.VLC, Multicast.engineFor("udp://@239.255.15.36:1234", PlaybackEngine.MPV))
        assertEquals(PlaybackEngine.VLC, Multicast.engineFor("rtp://@239.255.15.36:1234", PlaybackEngine.VLC))
        assertNull(Multicast.engineFor("udp://@239.255.15.36:1234", PlaybackEngine.EXTERNAL))
        assertNull(Multicast.engineFor("https://cdn.example/live.m3u8", PlaybackEngine.AUTO))
        assertNull(Multicast.engineFor("rtsp://camera.example/live", PlaybackEngine.AUTO))
    }

    @Test
    fun playsInAppFollowsTheEngineOverride() {
        assertTrue(Multicast.playsInApp("udp://@239.255.15.36:1234", PlaybackEngine.AUTO))
        assertFalse(Multicast.playsInApp("udp://@239.255.15.36:1234", PlaybackEngine.EXTERNAL))
        assertFalse(Multicast.playsInApp("rtmp://cdn.example/live", PlaybackEngine.AUTO))
    }
}
