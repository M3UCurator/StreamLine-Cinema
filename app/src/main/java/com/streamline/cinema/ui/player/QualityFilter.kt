package com.streamline.cinema.ui.player

import com.streamline.cinema.data.PlaybackOption
import com.streamline.cinema.data.QualityLimit

private val QUALITY_PATTERN = Regex(
    "(?i)\\b(8k|4k|4320p|2160p|1440p|1080p|720p|576p|540p|480p|360p|240p)\\b"
)

/** Quality tier named in a source's label, or null when it carries no tag. */
internal fun qualityOf(option: PlaybackOption): String? {
    // The parsed stream metadata already scanned name and title, so it also
    // catches tiers the display label dropped when it was shortened.
    option.meta.quality.takeIf { it.isNotBlank() }?.let {
        return when (it) {
            "8K" -> "8K"
            "4K" -> "4K"
            "SD" -> "480p"
            else -> it
        }
    }
    val raw = QUALITY_PATTERN.find(option.name)?.value?.lowercase() ?: return null
    return when (raw) {
        "8k", "4320p" -> "8K"
        "4k", "2160p" -> "4K"
        else -> raw
    }
}

internal fun qualityHeight(label: String): Int = when (label) {
    "8K" -> 4320
    "4K" -> 2160
    else -> label.removeSuffix("p").toIntOrNull() ?: 0
}

/**
 * Drops sources at or above the configured tier. Sources without a quality tag
 * are kept because there is nothing to compare. When every source is at or
 * above the tier the full list comes back, so a title never becomes unplayable.
 */
internal fun excludeHighQualitySources(
    options: List<PlaybackOption>,
    limit: QualityLimit
): List<PlaybackOption> {
    if (limit.cutoffHeight == 0) return options
    val kept = options.filter { option ->
        val height = qualityOf(option)?.let(::qualityHeight) ?: 0
        height <= 0 || !limit.excludes(height)
    }
    return kept.ifEmpty { options }
}
