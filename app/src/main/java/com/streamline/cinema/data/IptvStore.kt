package com.streamline.cinema.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists IPTV source definitions and, per source, the last parsed channel
 * list so the Live tab still opens when the provider is offline.
 */
class IptvStore(context: Context) {

    private val appContext = context.applicationContext
    private val sourceFile = File(appContext.filesDir, "iptv_sources.json")
    private val lock = Any()
    private val channelCache = ConcurrentHashMap<String, List<LiveChannel>>()

    private val _sources = MutableStateFlow<List<IptvSource>>(emptyList())
    val sources: StateFlow<List<IptvSource>> = _sources.asStateFlow()

    init {
        loadSources()
    }

    fun source(id: String): IptvSource? = _sources.value.firstOrNull { it.id == id }

    /**
     * Replaces a source with the same id, otherwise appends it. Throws
     * [InsufficientStorageException] before the write when the device has no
     * room for the staged copy, so the caller can show the real reason.
     */
    fun put(source: IptvSource) {
        synchronized(lock) {
            val next = _sources.value.toMutableList()
            val index = next.indexOfFirst { it.id == source.id }
            if (index >= 0) next[index] = source else next += source
            _sources.value = next.sortedByDescending { it.addedAt }
            persistSources(_sources.value)
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            _sources.value = _sources.value.filterNot { it.id == id }
            persistSources(_sources.value)
        }
        channelCache.remove(id)
        runCatching { cacheFile(id).delete() }
    }

    fun cachedChannels(id: String): List<LiveChannel>? {
        channelCache[id]?.let { return it }
        val loaded = loadChannelCache(id) ?: return null
        channelCache[id] = loaded
        return loaded
    }

    fun saveChannels(id: String, channels: List<LiveChannel>) {
        channelCache[id] = channels
        val root = JSONObject().put("channels", channelsToJson(channels))
        AtomicStore.write(cacheFile(id), root.toString(), "channel list")
    }

    fun clearChannels(id: String) {
        channelCache.remove(id)
        runCatching { cacheFile(id).delete() }
    }

    /** On-disk size of the per-source channel caches, shown in Settings. */
    fun channelCacheBytes(): Long = appContext.filesDir
        .listFiles { file -> file.name.startsWith("iptv_cache_") && file.isFile }
        .orEmpty()
        .sumOf { it.length() }

    fun clearChannelCaches() {
        channelCache.clear()
        appContext.filesDir
            .listFiles { file -> file.name.startsWith("iptv_cache_") }
            .orEmpty()
            .forEach { runCatching { it.delete() } }
    }

    private fun cacheFile(id: String): File {
        val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(appContext.filesDir, "iptv_cache_$safe.json")
    }

    private fun loadChannelCache(id: String): List<LiveChannel>? = runCatching {
        val file = cacheFile(id)
        if (!file.exists()) return null
        val array = JSONObject(file.readText()).optJSONArray("channels") ?: return null
        (0 until array.length().coerceAtMost(MAX_IPTV_CHANNELS)).mapNotNull { index ->
            array.optJSONObject(index)?.let { LiveChannel.fromJson(it) }
        }
    }.getOrNull()

    private fun persistSources(sources: List<IptvSource>) {
        val root = JSONObject().put("sources", JSONArray(sources.map { it.toJson() }))
        AtomicStore.write(sourceFile, root.toString(), "IPTV sources")
    }

    private fun loadSources() {
        _sources.value = loadSourceFile(sourceFile).orEmpty()
    }

    companion object {
        /**
         * Reads the source list, falling back to the `.bak` copy when the
         * primary file is missing, zero-byte or truncated. A failed backup read
         * yields null rather than an empty list so the caller can distinguish
         * "nothing stored" from "unreadable" when diagnosing.
         */
        internal fun loadSourceFile(primary: File): List<IptvSource>? {
            val backup = File(primary.parentFile, "${primary.name}.bak")
            return parseSources(AtomicStore.read(primary)) ?: parseSources(AtomicStore.read(backup))
        }

        internal fun parseSources(text: String?): List<IptvSource>? = runCatching {
            if (text.isNullOrBlank()) return null
            val array = JSONObject(text).optJSONArray("sources") ?: return null
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { IptvSource.fromJson(it) }
            }.filter { it.id.isNotBlank() && it.location.isNotBlank() }
                .sortedByDescending { it.addedAt }
        }.getOrNull()
    }
}
