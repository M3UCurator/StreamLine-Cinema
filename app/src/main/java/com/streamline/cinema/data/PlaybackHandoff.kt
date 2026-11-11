package com.streamline.cinema.data

/**
 * Large live playlists cannot travel through an Intent: a 1000+ channel list
 * with EPG exceeds the Binder transaction limit and the player launch dies with
 * TransactionTooLargeException. The list stays in process memory and the Intent
 * only carries a token. If the process was recreated the token is unknown and
 * the player falls back to the single channel it was launched with.
 */
object PlaybackHandoff {
    // Only the launch being prepared and the one it replaces need to survive.
    // Each entry can be a thousand-channel guide, so four copies of it sat in
    // memory while the player held the same list for zapping.
    private const val MAX_ENTRIES = 2

    private val entries = object : LinkedHashMap<String, List<LiveChannel>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<LiveChannel>>?
        ): Boolean = size > MAX_ENTRIES
    }

    fun store(channels: List<LiveChannel>): String {
        val token = System.nanoTime().toString(16) + "-" + System.identityHashCode(channels).toString(16)
        synchronized(entries) { entries[token] = channels }
        return token
    }

    fun lookup(token: String?): List<LiveChannel> {
        if (token.isNullOrBlank()) return emptyList()
        return synchronized(entries) { entries[token] } ?: emptyList()
    }
}
