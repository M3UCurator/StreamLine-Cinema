package com.streamline.cinema.data

/**
 * Desktop-Chrome request profile for media URLs. Trace's ByteDance edge answers
 * 403 with a zero-length body to every non-browser agent, which the player can
 * only surface as a malformed manifest. The mask rides under the caller's own
 * headers, so a pipe User-Agent or Referer still wins for providers that bind
 * to their app's agent.
 */
object BrowserMask {
    private val base: Map<String, String> = mapOf(
        "User-Agent" to Http.BROWSER_USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    /** Browser defaults under [explicit]: explicit values win case-insensitively. */
    fun withDefaults(explicit: Map<String, String>): Map<String, String> =
        StreamUrls.mergeHeaders(base, explicit)
}
