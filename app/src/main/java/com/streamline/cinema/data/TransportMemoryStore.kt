package com.streamline.cinema.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** What an origin has proved about the transport path it answers. */
enum class TransportVerdict { DIRECT, PROXY }

/**
 * Remembers the transport verdict per origin, so AUTO does not have to learn
 * the same gated provider on every channel zap or app launch. A denied direct
 * request (or a browser-masked success) routes the origin through the proxy;
 * a direct session that played cleanly keeps starting direct. Verdicts expire
 * after [VERDICT_TTL_MS] so a provider that changes its gating is re-probed
 * instead of being pinned forever.
 */
class TransportMemoryStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "transport_memory.json")
    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    init {
        load().forEach { (origin, entry) -> entries[origin] = entry }
    }

    fun verdict(origin: String?): TransportVerdict? {
        if (origin.isNullOrBlank()) return null
        synchronized(lock) {
            val entry = entries[origin] ?: return null
            if (expired(entry, System.currentTimeMillis())) {
                entries.remove(origin)
                return null
            }
            return entry.verdict
        }
    }

    fun record(origin: String?, verdict: TransportVerdict) {
        if (origin.isNullOrBlank()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val current = entries[origin]
            if (current?.verdict == verdict) {
                // Refreshing the timestamp keeps the mark alive without a write.
                entries[origin] = current.copy(atMs = now)
                return
            }
            entries[origin] = Entry(verdict, now)
            if (entries.size > MAX_ORIGINS) {
                entries.entries
                    .sortedBy { it.value.atMs }
                    .take(entries.size - MAX_ORIGINS)
                    .forEach { entries.remove(it.key) }
            }
            persist()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            runCatching { file.delete() }
        }
    }

    /** Forgets one origin, e.g. when the viewer corrects a wrong verdict. */
    fun forget(origin: String?) {
        if (origin.isNullOrBlank()) return
        synchronized(lock) {
            if (entries.remove(origin) == null) return
            persist()
        }
    }

    private data class Entry(val verdict: TransportVerdict, val atMs: Long)

    private fun expired(entry: Entry, now: Long): Boolean = now - entry.atMs > VERDICT_TTL_MS

    private fun load(): Map<String, Entry> = runCatching {
        val text = AtomicStore.read(file) ?: return emptyMap()
        val root = JSONObject(text)
        val now = System.currentTimeMillis()
        val result = HashMap<String, Entry>()
        root.keys().forEach { origin ->
            val entry = root.optJSONObject(origin) ?: return@forEach
            val verdict = when (entry.optString("v")) {
                TransportVerdict.DIRECT.name -> TransportVerdict.DIRECT
                TransportVerdict.PROXY.name -> TransportVerdict.PROXY
                else -> return@forEach
            }
            val atMs = entry.optLong("at")
            if (expired(Entry(verdict, atMs), now)) return@forEach
            result[origin] = Entry(verdict, atMs)
        }
        result
    }.getOrDefault(emptyMap())

    private fun persist() {
        runCatching {
            if (entries.isEmpty()) {
                file.delete()
                return
            }
            val root = JSONObject()
            entries.forEach { (origin, entry) ->
                root.put(
                    origin,
                    JSONObject()
                        .put("v", entry.verdict.name)
                        .put("at", entry.atMs)
                )
            }
            AtomicStore.write(file, root.toString(), "transport memory")
        }
    }

    private companion object {
        const val MAX_ORIGINS = 128
        const val VERDICT_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
