package com.streamline.cinema.data

import android.content.Context
import java.io.File

/**
 * Persists a compact channel → programme index of the last successful guide
 * load. Cold start can paint NOW/NEXT immediately from this file while the
 * full 24 MB XMLTV document is fetched and parsed again in the background.
 */
class GuideIndexStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "epg_index.json")

    fun load(): EpgGuide? = EpgGuide.fromIndexJson(AtomicStore.read(file))

    fun save(guide: EpgGuide) {
        if (guide.isEmpty) return
        runCatching { AtomicStore.write(file, guide.indexJson(), "guide index") }
    }

    fun sizeBytes(): Long = runCatching { file.takeIf { it.exists() }?.length() ?: 0L }.getOrDefault(0L)

    fun clear() {
        runCatching { file.delete() }
    }
}
