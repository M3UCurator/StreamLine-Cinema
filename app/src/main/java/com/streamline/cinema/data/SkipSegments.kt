package com.streamline.cinema.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One reported skip segment. [endMs] null means the segment runs to the end of
 * the file (TheIntroDB reports credits and anime previews that way).
 */
data class SkipSegment(val startMs: Long, val endMs: Long?)

enum class SkipKind(val label: String, val short: String) {
    RECAP("Skip recap", "RECAP"),
    INTRO("Skip intro", "INTRO"),
    CREDITS("Skip credits", "CREDITS")
}

/** What the contextual dock button should do at the current playhead. */
data class SkipOffer(val kind: SkipKind, val targetMs: Long)

/**
 * Skip data for one episode, as published by TheIntroDB.
 *
 * The database is community-fed, so the parser rejects zero-length entries,
 * reversed ranges and ranges outside any believable episode; [offerAt] and
 * [creditsStart] then resolve what is left against the file's real duration.
 */
/** A segment resolved against the file's real duration. */
private data class SegmentWindow(val startMs: Long, val endMs: Long)

data class SkipSegments(
    val recap: SkipSegment? = null,
    val intro: SkipSegment? = null,
    val credits: SkipSegment? = null
) {
    val isEmpty: Boolean get() = recap == null && intro == null && credits == null

    /** Segment the dock should offer at [positionMs], if any. */
    fun offerAt(positionMs: Long, durationMs: Long): SkipOffer? {
        if (durationMs <= 0) return null
        for ((kind, segment) in ordered()) {
            val window = segment?.window(durationMs) ?: continue
            // A credits segment from the first frame means the whole file is
            // credits, which no episode is.
            if (kind == SkipKind.CREDITS &&
                (window.startMs <= 0 || durationMs - window.startMs < CREDITS_MIN_TAIL_MS)
            ) {
                continue
            }
            if (positionMs < window.startMs - LEAD_MS) continue
            if (positionMs >= window.endMs - TAIL_GUARD_MS) continue
            if (window.endMs - positionMs < MIN_GAIN_MS) continue
            return SkipOffer(kind, window.endMs)
        }
        return null
    }

    /**
     * Where the end credits begin, which is when the next episode becomes
     * relevant. Null when the segment is unknown or starts too near the file
     * end to be worth an early offer.
     */
    fun creditsStart(durationMs: Long): Long? {
        val window = credits?.window(durationMs) ?: return null
        if (window.startMs <= 0) return null
        if (durationMs - window.startMs < CREDITS_MIN_TAIL_MS) return null
        return window.startMs
    }

    /** Drops segments that no real episode of [durationMs] could contain. */
    private fun SkipSegment.window(durationMs: Long): SegmentWindow? {
        val end = (endMs ?: durationMs).coerceAtMost(durationMs)
        if (startMs <= 0 && endMs == null) return null
        if (startMs >= durationMs - MIN_SEGMENT_MS) return null
        if (end - startMs < MIN_SEGMENT_MS) return null
        return SegmentWindow(startMs, end)
    }

    private fun ordered(): List<Pair<SkipKind, SkipSegment?>> = listOf(
        SkipKind.RECAP to recap,
        SkipKind.INTRO to intro,
        SkipKind.CREDITS to credits
    )

    /** Canonical form kept in the on-disk cache; [parse] reads it back. */
    fun toJson(): JSONObject {
        fun entry(segment: SkipSegment): JSONObject = JSONObject().apply {
            put("start_ms", segment.startMs)
            put("end_ms", if (segment.endMs == null) JSONObject.NULL else segment.endMs)
        }
        return JSONObject().apply {
            recap?.let { put("recap", JSONArray().put(entry(it))) }
            intro?.let { put("intro", JSONArray().put(entry(it))) }
            credits?.let { put("credits", JSONArray().put(entry(it))) }
        }
    }

    companion object {
        val EMPTY = SkipSegments()

        private const val MIN_SEGMENT_MS = 3_000L
        private const val MIN_GAIN_MS = 3_000L
        private const val TAIL_GUARD_MS = 2_000L
        private const val LEAD_MS = 1_000L
        private const val CREDITS_MIN_TAIL_MS = 30_000L
        private const val MAX_MS = 6 * 60 * 60_000L

        /**
         * Parses a `/v3/media` body. Returns null when the response carries no
         * usable segment, so callers can fall back to their own heuristic.
         * The API omits absent segment types entirely.
         */
        fun parse(json: String?): SkipSegments? {
            if (json.isNullOrBlank()) return null
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val recap = firstSegment(root.optJSONArray("recap"))
            val intro = firstSegment(root.optJSONArray("intro"))
            // Anime entries often carry only `preview` for the tail; it marks
            // the same end-of-episode boundary that credits do.
            val credits = firstSegment(root.optJSONArray("credits"))
                ?: firstSegment(root.optJSONArray("preview"))
            val parsed = SkipSegments(recap = recap, intro = intro, credits = credits)
            return parsed.takeUnless { it.isEmpty }
        }

        private fun firstSegment(array: JSONArray?): SkipSegment? {
            if (array == null) return null
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                // An absent start means "from 0" and an absent end means "to EOF".
                val start = if (entry.isNull("start_ms")) 0L else entry.optLong("start_ms", -1L)
                val end = if (entry.isNull("end_ms")) null else entry.optLong("end_ms", -1L)
                if (start < 0 || (end != null && end < 0)) continue
                // Zero-length, reversed and open-ended-at-zero entries are junk
                // seen in live responses (e.g. {"start_ms":0,"end_ms":0}).
                if (start <= 0 && end == null) continue
                if (end != null && end - start < MIN_SEGMENT_MS) continue
                if (start > MAX_MS || (end != null && end > MAX_MS)) continue
                return SkipSegment(start, end)
            }
            return null
        }
    }
}
