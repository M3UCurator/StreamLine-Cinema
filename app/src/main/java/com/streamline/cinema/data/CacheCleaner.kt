package com.streamline.cinema.data

import android.content.Context
import coil.Coil
import com.streamline.cinema.StreamLineApp
import java.io.File

data class CacheBreakdown(
    val imagesBytes: Long = 0L,
    val scratchBytes: Long = 0L,
    val channelBytes: Long = 0L,
    val guideIndexBytes: Long = 0L
) {
    val totalBytes: Long get() = imagesBytes + scratchBytes + channelBytes + guideIndexBytes
}

/**
 * Clears the disposable caches used by the UI: Coil's memory/disk image cache
 * and everything under the app cache directories (HTTP scratch files, decoded
 * bitmaps written by the framework). Library data and settings live in
 * filesDir/SharedPreferences and are never touched.
 */
object CacheCleaner {

    fun sizeBytes(context: Context): Long = cacheDirs(context).sumOf(::dirSize)

    fun breakdown(context: Context): CacheBreakdown {
        val app = context.applicationContext as? StreamLineApp
        return CacheBreakdown(
            imagesBytes = runCatching { Coil.imageLoader(context).diskCache?.size ?: 0L }
                .getOrDefault(0L),
            scratchBytes = sizeBytes(context),
            channelBytes = runCatching { app?.container?.iptvStore?.channelCacheBytes() ?: 0L }
                .getOrDefault(0L),
            guideIndexBytes = runCatching { app?.container?.guideIndexStore?.sizeBytes() ?: 0L }
                .getOrDefault(0L)
        )
    }

    fun clear(context: Context): Long {
        val before = sizeBytes(context)
        clearImageFiles(context)
        cacheDirs(context).forEach { dir ->
            dir.listFiles()?.forEach { child -> runCatching { child.deleteRecursively() } }
        }
        return (before - sizeBytes(context)).coerceAtLeast(0)
    }

    fun clearImages(context: Context): Long {
        val before = runCatching { Coil.imageLoader(context).diskCache?.size ?: 0L }.getOrDefault(0L)
        clearImageFiles(context)
        val after = runCatching { Coil.imageLoader(context).diskCache?.size ?: 0L }.getOrDefault(0L)
        return (before - after).coerceAtLeast(0)
    }

    fun clearIptvChannelCaches(context: Context): Long {
        val app = context.applicationContext as? StreamLineApp ?: return 0L
        val store = app.container.iptvStore
        val before = runCatching { store.channelCacheBytes() }.getOrDefault(0L)
        runCatching { store.clearChannelCaches() }
        return (before - runCatching { store.channelCacheBytes() }.getOrDefault(0L)).coerceAtLeast(0)
    }

    fun clearGuideIndex(context: Context): Long {
        val app = context.applicationContext as? StreamLineApp ?: return 0L
        val before = runCatching { app.container.guideIndexStore.sizeBytes() }.getOrDefault(0L)
        runCatching { app.container.guideIndexStore.clear() }
        return before.coerceAtLeast(0)
    }

    fun clearIfEnabled(context: Context) {
        val app = context.applicationContext as? StreamLineApp ?: return
        if (!app.container.settingsStore.settings.value.clearCacheOnExit) return
        Thread { clear(context) }.start()
    }

    private fun clearImageFiles(context: Context) {
        runCatching {
            val loader = Coil.imageLoader(context)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
    }

    private fun cacheDirs(context: Context): List<File> =
        listOfNotNull(context.cacheDir, context.externalCacheDir).distinct()

    private fun dirSize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
