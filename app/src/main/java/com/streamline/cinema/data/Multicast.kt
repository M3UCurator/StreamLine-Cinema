package com.streamline.cinema.data

/**
 * Raw IP transport (`udp://`, `rtp://`) used by IPTV multicast lineups.
 *
 * Media3 has no data source for either scheme: its UdpDataSource sends from a
 * plain DatagramSocket and never joins a group, so a 239.x feed would receive
 * nothing. libVLC's access module joins the group itself, which is why these
 * sources are pinned to the VLC engine regardless of the configured engine.
 * External stays a hand-off: it is an explicit request for another app.
 */
object Multicast {

    private val TRANSPORT_SCHEMES = setOf("udp", "rtp")

    fun isTransport(raw: String?): Boolean = schemeOf(raw) in TRANSPORT_SCHEMES

    /**
     * Whether [raw] addresses a multicast group rather than one host: IPv4
     * class D (224.0.0.0/4, which includes the 239.x site range) or an IPv6
     * `ff00::/8` literal. The VLC group marker (`udp://@239.…`) is not part of
     * the host, so it is stripped before the first octet is read.
     */
    fun isGroup(raw: String?): Boolean {
        val host = hostOf(raw) ?: return false
        if (host.contains(':')) return host.startsWith("ff")
        val first = host.substringBefore('.').toIntOrNull() ?: return false
        return first in 224..239
    }

    /**
     * Engine substitution for a source the configured engine cannot open at
     * all, or null to leave the route alone (a non-transport URL, or External).
     */
    fun engineFor(raw: String?, configured: PlaybackEngine): PlaybackEngine? {
        if (!isTransport(raw)) return null
        return if (configured == PlaybackEngine.EXTERNAL) null else PlaybackEngine.VLC
    }

    /** True when this source plays in the app instead of being handed off. */
    fun playsInApp(raw: String?, configured: PlaybackEngine): Boolean =
        engineFor(raw, configured) != null

    private fun schemeOf(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val clean = StreamUrls.clean(raw)
        if (!clean.contains("://")) return null
        return clean.substringBefore("://").lowercase().takeIf { it.isNotEmpty() }
    }

    private fun hostOf(raw: String?): String? {
        if (raw == null || !isTransport(raw)) return null
        val rest = StreamUrls.clean(raw)
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trimStart('@')
        val host = if (rest.startsWith('[')) {
            rest.substringAfter('[').substringBefore(']')
        } else {
            rest.substringBeforeLast(':', rest)
        }
        return host.lowercase().takeIf { it.isNotBlank() }
    }
}
