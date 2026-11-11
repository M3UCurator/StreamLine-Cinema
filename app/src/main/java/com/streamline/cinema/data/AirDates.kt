package com.streamline.cinema.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** `2024-09-12` → `Aired Sep 12, 2024`, or `Airs …` when that day is still ahead of [now]. */
fun formatEpisodeAirDate(raw: String?, now: Date = Date()): String? {
    val value = raw?.trim().orEmpty()
    if (value.length < 10) return null
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(value)
    }.getOrNull() ?: return null
    val formatted = SimpleDateFormat("MMM d, yyyy", Locale.US).format(parsed)
    val startOfToday = Calendar.getInstance().apply {
        time = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time
    return if (parsed.after(startOfToday)) "Airs $formatted" else "Aired $formatted"
}
