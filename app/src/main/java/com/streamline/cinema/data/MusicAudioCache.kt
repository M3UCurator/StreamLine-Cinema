package com.streamline.cinema.data

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Musicbox's on-disk replay cache. It keys on the queued placeholder URI rather
 * than the addon's resolved link, so a track keeps playing from disk after the
 * signed source URL expires; the bytes are still disposable cache, never a
 * download the user owns.
 *
 * The evictor is fixed when a cache opens, so a budget change only takes effect
 * the next time a music session starts. Clearing goes through
 * [SimpleCache.removeResource] while the service holds the cache open, because
 * deleting the directory underneath it would corrupt the index.
 */
@OptIn(UnstableApi::class)
object MusicAudioCache {

    internal const val DIR_NAME = "music_audio"

    private var cache: SimpleCache? = null
    private var database: StandaloneDatabaseProvider? = null

    fun directory(context: Context): File = File(context.applicationContext.cacheDir, DIR_NAME)

    @Synchronized
    fun open(context: Context, maxBytes: Long): SimpleCache {
        cache?.let { return it }
        val app = context.applicationContext
        val db = StandaloneDatabaseProvider(app)
        val opened = SimpleCache(
            directory(app),
            LeastRecentlyUsedCacheEvictor(maxBytes),
            db
        )
        database = db
        cache = opened
        return opened
    }

    @Synchronized
    fun close() {
        cache?.release()
        cache = null
        database?.close()
        database = null
    }

    @Synchronized
    fun sizeBytes(context: Context): Long =
        cache?.cacheSpace ?: dirSize(directory(context))

    /** Drops every cached track and returns the bytes released. */
    @Synchronized
    fun clear(context: Context): Long {
        val live = cache
        if (live != null) {
            val before = live.cacheSpace
            // A span being read right now is locked; the playing track simply
            // stays until it is evicted or cleared from a later session.
            live.keys.toList().forEach { key -> runCatching { live.removeResource(key) } }
            return (before - live.cacheSpace).coerceAtLeast(0L)
        }
        val dir = directory(context)
        val before = dirSize(dir)
        dir.listFiles()?.forEach { child -> runCatching { child.deleteRecursively() } }
        return (before - dirSize(dir)).coerceAtLeast(0L)
    }

    private fun dirSize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
