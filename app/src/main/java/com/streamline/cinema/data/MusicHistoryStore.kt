package com.streamline.cinema.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Where the queue left off for one profile. [positionMs] is checked against the
 * track's own duration by [isResumable], because a queue that ended is not a
 * place to restart playback from.
 */
data class MusicResume(
    val queue: List<MusicTrack>,
    val index: Int,
    val positionMs: Long,
    val updatedAt: Long = 0L
) {
    val current: MusicTrack? get() = queue.getOrNull(index)

    val isResumable: Boolean
        get() {
            val track = current ?: return false
            if (positionMs < MIN_RESUME_MS) return false
            return track.durationMs <= 0L || positionMs < track.durationMs - MIN_TAIL_MS
        }

    private companion object {
        const val MIN_RESUME_MS = 5_000L
        const val MIN_TAIL_MS = 5_000L
    }
}

/** What the Musicbox tab shows for the active profile. */
data class MusicHistory(
    val recent: List<MusicTrack> = emptyList(),
    val resume: MusicResume? = null
)

/**
 * Per-profile music state: recently played tracks, the last queue and its
 * playhead. One crash-safe JSON document beside the library store, debounced
 * like [LibraryStore]; cache clearing only touches cacheDir, so this survives
 * a CLEAR CACHE the same way favorites and watch progress do.
 */
class MusicHistoryStore(context: Context) {

    private data class ProfileEntry(
        val recent: List<MusicTrack> = emptyList(),
        val resume: MusicResume? = null
    )

    private val file = File(context.applicationContext.filesDir, "music_history.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var saveJob: Job? = null
    private var dirty = false
    private var loaded = false
    private val profiles = mutableMapOf<String, ProfileEntry>()

    private var activeProfileId = CinemaProfile.DEFAULT_ID

    private val _history = MutableStateFlow(MusicHistory())
    val history: StateFlow<MusicHistory> = _history.asStateFlow()

    fun setActiveProfile(profileId: String) {
        synchronized(lock) {
            if (profileId == activeProfileId && loaded) return
            activeProfileId = profileId
            loadIfNeeded()
            publish()
        }
    }

    fun session(profileId: String = activeProfileId): MusicResume? = synchronized(lock) {
        loadIfNeeded()
        profiles[profileId]?.resume?.takeIf { it.queue.isNotEmpty() }
    }

    /** Newest first, one row per track; a replay moves the track back to the top. */
    fun recordPlayed(profileId: String, track: MusicTrack) {
        synchronized(lock) {
            loadIfNeeded()
            val current = profiles[profileId] ?: ProfileEntry()
            val recent = (listOf(track) + current.recent.filterNot { it.key == track.key })
                .take(MAX_RECENT)
            profiles[profileId] = current.copy(recent = recent)
            publishIfActive(profileId)
            scheduleSave()
        }
    }

    fun saveSession(
        profileId: String,
        queue: List<MusicTrack>,
        index: Int,
        positionMs: Long,
        now: Long = System.currentTimeMillis()
    ) {
        if (queue.isEmpty()) return
        synchronized(lock) {
            loadIfNeeded()
            val current = profiles[profileId] ?: ProfileEntry()
            val previous = current.resume
            val window = trimMusicQueue(queue, index)
            val position = positionMs.coerceAtLeast(0L)
            val unchanged = previous != null &&
                previous.queue.map { it.key } == window.first.map { it.key } &&
                previous.index == window.second &&
                abs(previous.positionMs - position) < POSITION_EPSILON_MS
            if (unchanged) return
            profiles[profileId] = current.copy(
                resume = MusicResume(
                    queue = window.first,
                    index = window.second,
                    positionMs = position,
                    updatedAt = now
                )
            )
            publishIfActive(profileId)
            scheduleSave()
        }
    }

    fun clearProfile(profileId: String) {
        synchronized(lock) {
            loadIfNeeded()
            if (profiles.remove(profileId) == null) return
            publishIfActive(profileId)
            scheduleSave()
        }
    }

    /** Writes immediately; the playback service calls this while shutting down. */
    fun flush() {
        synchronized(lock) {
            saveJob?.cancel()
            saveJob = null
            writeLocked()
        }
    }

    private fun publishIfActive(profileId: String) {
        if (profileId == activeProfileId) publish()
    }

    private fun publish() {
        val entry = profiles[activeProfileId]
        _history.value = MusicHistory(
            recent = entry?.recent.orEmpty(),
            resume = entry?.resume?.takeIf { it.queue.isNotEmpty() }
        )
    }

    private fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            synchronized(lock) { writeLocked() }
        }
    }

    private fun writeLocked() {
        if (!dirty) return
        runCatching {
            val root = JSONObject()
                .put("version", 1)
                .put(
                    "profiles",
                    JSONObject().apply {
                        profiles.forEach { (id, entry) ->
                            put(
                                id,
                                JSONObject().apply {
                                    put("recent", JSONArray(entry.recent.map { it.toJson() }))
                                    entry.resume?.let { resume ->
                                        put("queue", JSONArray(resume.queue.map { it.toJson() }))
                                        put("index", resume.index)
                                        put("positionMs", resume.positionMs)
                                        put("updatedAt", resume.updatedAt)
                                    }
                                }
                            )
                        }
                    }
                )
            AtomicStore.write(file, root.toString(), "music history")
            dirty = false
        }
    }

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        runCatching {
            val text = AtomicStore.readWithBackup(file) ?: return@runCatching
            val entries = JSONObject(text).optJSONObject("profiles") ?: return@runCatching
            entries.keys().forEach { id ->
                val entry = entries.optJSONObject(id) ?: return@forEach
                profiles[id] = ProfileEntry(
                    recent = tracks(entry.optJSONArray("recent"), MAX_RECENT),
                    resume = entry.optJSONArray("queue")
                        ?.let { tracks(it) }
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { queue ->
                            MusicResume(
                                queue = queue,
                                index = entry.optInt("index", 0).coerceIn(0, queue.lastIndex),
                                positionMs = entry.optLong("positionMs", 0L).coerceAtLeast(0L),
                                updatedAt = entry.optLong("updatedAt", 0L)
                            )
                        }
                )
            }
        }
        publish()
    }

    private fun tracks(array: JSONArray?, limit: Int = MAX_QUEUE): List<MusicTrack> {
        if (array == null) return emptyList()
        val count = array.length().coerceAtMost(limit)
        return (0 until count).mapNotNull { index ->
            array.optJSONObject(index)?.let { MusicTrack.fromJson(it) }
        }
    }

    private companion object {
        const val MAX_RECENT = 50
        const val MAX_QUEUE = 200
        const val SAVE_DEBOUNCE_MS = 350L
        const val POSITION_EPSILON_MS = 5_000L
    }
}

/**
 * Keeps a queue of any length to [maxQueue] rows around the playing index, so a
 * restored queue still starts where the listener left off. Returns the trimmed
 * list and the index's new position.
 */
internal fun trimMusicQueue(
    queue: List<MusicTrack>,
    index: Int,
    maxQueue: Int = 200
): Pair<List<MusicTrack>, Int> {
    val safeIndex = index.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
    if (queue.size <= maxQueue) return queue to safeIndex
    val start = (safeIndex - maxQueue / 2).coerceIn(0, queue.size - maxQueue)
    return queue.subList(start, start + maxQueue).toList() to (safeIndex - start)
}
