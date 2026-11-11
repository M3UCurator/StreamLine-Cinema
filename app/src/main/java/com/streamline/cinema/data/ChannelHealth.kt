package com.streamline.cinema.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** Consecutive failed tunes/playbacks before a channel is marked dead. */
const val DEAD_CHANNEL_FAILURES = 3

private const val FORGET_AFTER_MS = 7L * 24 * 60 * 60 * 1000

data class ChannelHealth(
    val failures: Int = 0,
    val lastFailureAt: Long = 0L,
    val lastSuccessAt: Long = 0L
) {
    val dead: Boolean get() = failures >= DEAD_CHANNEL_FAILURES
}

/**
 * Remembers which live channels keep failing. Providers leave dead entries in
 * their playlists for weeks, and without this every zap walks into the same
 * timeout. A single successful tune clears the mark, so a provider that fixes
 * an entry is picked up automatically; stale marks decay after a week.
 */
class ChannelHealthStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "channel_health.json")
    private val lock = Any()
    private val _health = MutableStateFlow(load())
    val health: StateFlow<Map<String, ChannelHealth>> = _health.asStateFlow()

    fun isDead(key: String): Boolean = _health.value[key]?.dead == true

    /** Records a failure; returns true when this failure marked the channel dead. */
    fun recordFailure(key: String, atMs: Long = System.currentTimeMillis()): Boolean {
        if (key.isBlank()) return false
        var becameDead = false
        synchronized(lock) {
            val current = _health.value[key]
            val failures = (current?.failures ?: 0) + 1
            becameDead = failures == DEAD_CHANNEL_FAILURES
            _health.value = _health.value + (key to ChannelHealth(
                failures = failures.coerceAtMost(DEAD_CHANNEL_FAILURES),
                lastFailureAt = atMs,
                lastSuccessAt = current?.lastSuccessAt ?: 0L
            ))
            persist(_health.value)
        }
        return becameDead
    }

    fun recordSuccess(key: String, atMs: Long = System.currentTimeMillis()) {
        if (key.isBlank()) return
        synchronized(lock) {
            val current = _health.value[key] ?: return
            // Only a channel with a failure record needs clearing. Writing a
            // success for a healthy channel grew the map and rewrote the file
            // on every single tune; the mark is never persisted anyway.
            if (current.failures == 0) return
            val next = _health.value.toMutableMap()
            next.remove(key)
            _health.value = next
            persist(next)
        }
    }

    fun clear() {
        synchronized(lock) {
            _health.value = emptyMap()
            runCatching { file.delete() }
        }
    }

    /** Forgets the given channels so the next tune tries them fresh. */
    fun forget(keys: Collection<String>) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            val next = _health.value - keys.toSet()
            if (next.size == _health.value.size) return
            _health.value = next
            persist(next)
        }
    }

    private fun load(): Map<String, ChannelHealth> = runCatching {
        val text = AtomicStore.read(file) ?: return emptyMap()
        val root = JSONObject(text)
        val now = System.currentTimeMillis()
        val result = HashMap<String, ChannelHealth>()
        root.keys().forEach { key ->
            val entry = root.optJSONObject(key) ?: return@forEach
            val failures = entry.optInt("f").coerceIn(0, DEAD_CHANNEL_FAILURES)
            val lastFailure = entry.optLong("lf")
            if (failures == 0) return@forEach
            if (lastFailure > 0 && now - lastFailure > FORGET_AFTER_MS) return@forEach
            result[key] = ChannelHealth(
                failures = failures,
                lastFailureAt = lastFailure,
                lastSuccessAt = entry.optLong("ls")
            )
        }
        result
    }.getOrDefault(emptyMap())

    private fun persist(entries: Map<String, ChannelHealth>) {
        runCatching {
            val root = JSONObject()
            entries.forEach { (key, value) ->
                if (value.failures == 0) return@forEach
                root.put(key, JSONObject()
                    .put("f", value.failures)
                    .put("lf", value.lastFailureAt)
                    .put("ls", value.lastSuccessAt))
            }
            if (entries.isEmpty()) {
                file.delete()
            } else {
                AtomicStore.write(file, root.toString(), "channel health")
            }
        }
    }
}
