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

class LibraryStore(context: Context) {

    private data class ProfileData(
        val progress: Map<String, WatchProgress> = emptyMap(),
        val favorites: List<CatalogItem> = emptyList(),
        val watchedEpisodes: Set<String> = emptySet()
    )

    private val stateFile = File(context.filesDir, "cinema_state.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var saveJob: Job? = null

    // Favorites, progress and watched marks are per profile; the catalog is shared.
    private val profileData = mutableMapOf<String, ProfileData>()
    private var activeProfileId = CinemaProfile.DEFAULT_ID

    private val _items = MutableStateFlow<List<CatalogItem>>(emptyList())
    private val _sources = MutableStateFlow<List<ImportSource>>(emptyList())
    private val _progress = MutableStateFlow<Map<String, WatchProgress>>(emptyMap())
    private val _favorites = MutableStateFlow<List<CatalogItem>>(emptyList())
    private val _watchedEpisodes = MutableStateFlow<Set<String>>(emptySet())

    val items: StateFlow<List<CatalogItem>> = _items.asStateFlow()
    val sources: StateFlow<List<ImportSource>> = _sources.asStateFlow()
    val progress: StateFlow<Map<String, WatchProgress>> = _progress.asStateFlow()
    val favorites: StateFlow<List<CatalogItem>> = _favorites.asStateFlow()
    val watchedEpisodes: StateFlow<Set<String>> = _watchedEpisodes.asStateFlow()

    init {
        // Parsing a large library (items, sources, progress, episodes) used to run
        // on the main thread from Application.onCreate. It now loads off-main; the
        // lock makes any mutation wait for the load, so nothing can overwrite state.
        scope.launch {
            synchronized(lock) { load() }
        }
    }

    /** Swaps the exposed favorites/progress/watched flows to [profileId]'s bucket. */
    fun setActiveProfile(profileId: String) {
        synchronized(lock) {
            if (profileId == activeProfileId && profileData.containsKey(profileId)) return
            snapshotActive()
            activeProfileId = profileId
            if (profileData[profileId] == null) profileData[profileId] = ProfileData()
            applyActive()
            scheduleSave()
        }
    }

    private fun snapshotActive() {
        profileData[activeProfileId] = ProfileData(
            progress = _progress.value,
            favorites = _favorites.value,
            watchedEpisodes = _watchedEpisodes.value
        )
    }

    private fun applyActive() {
        val data = profileData[activeProfileId] ?: ProfileData()
        _progress.value = data.progress
        _favorites.value = data.favorites
        _watchedEpisodes.value = data.watchedEpisodes
    }

    private fun scrubItemFromAllProfiles(itemKey: String) {
        val watchedPrefix = "$itemKey|"
        profileData.keys.toList().forEach { id ->
            val data = profileData[id] ?: return@forEach
            profileData[id] = data.copy(
                progress = data.progress.filterValues { it.itemKey != itemKey },
                favorites = data.favorites.filterNot { it.key == itemKey },
                watchedEpisodes = data.watchedEpisodes
                    .filterNot { it.startsWith(watchedPrefix) }
                    .toSet()
            )
        }
    }

    private fun scrubItemsFromAllProfiles(doomed: Set<String>) {
        profileData.keys.toList().forEach { id ->
            val data = profileData[id] ?: return@forEach
            profileData[id] = data.copy(
                progress = data.progress.filterValues { it.itemKey !in doomed },
                favorites = data.favorites.filterNot { it.key in doomed },
                watchedEpisodes = data.watchedEpisodes
                    .filterNot { key -> doomed.any { itemKey -> key.startsWith("$itemKey|") } }
                    .toSet()
            )
        }
    }

    fun addAll(incoming: List<CatalogItem>): Int {
        if (incoming.isEmpty()) return 0
        synchronized(lock) {
            val map = _items.value.associateBy { it.key }.toMutableMap()
            var added = 0
            incoming.forEach { fresh ->
                if (fresh.tmdbId <= 0) return@forEach
                val old = map[fresh.key]
                if (old == null) {
                    map[fresh.key] = fresh
                    added++
                } else {
                    map[fresh.key] = mergeItems(old, fresh)
                }
            }
            _items.value = map.values.sortedByDescending { it.addedAt }
            scheduleSave()
            return added
        }
    }

    fun replace(item: CatalogItem) {
        synchronized(lock) {
            val map = _items.value.associateBy { it.key }.toMutableMap()
            val old = map[item.key]
            val stored = if (old != null) item.copy(addedAt = old.addedAt) else item
            map[item.key] = stored
            _items.value = map.values.sortedByDescending { it.addedAt }
            syncFavorite(stored)
            snapshotActive()
            scheduleSave()
        }
    }

    fun updateSeasons(itemKey: String, seasons: List<Season>) {
        synchronized(lock) {
            val item = _items.value.firstOrNull { it.key == itemKey } ?: return
            replaceLocked(item.copy(seasons = mergeSeasons(item.seasons, seasons), tmdbLoaded = true))
        }
    }

    private fun replaceLocked(item: CatalogItem) {
        val map = _items.value.associateBy { it.key }.toMutableMap()
        map[item.key] = item
        _items.value = map.values.sortedByDescending { it.addedAt }
        syncFavorite(item)
        snapshotActive()
        scheduleSave()
    }

    private fun syncFavorite(item: CatalogItem) {
        if (_favorites.value.none { it.key == item.key }) return
        _favorites.value = _favorites.value.map { favorite ->
            if (favorite.key == item.key) item.copy(addedAt = favorite.addedAt) else favorite
        }
    }

    fun remove(itemKey: String) {
        synchronized(lock) {
            _items.value = _items.value.filterNot { it.key == itemKey }
            scrubItemFromAllProfiles(itemKey)
            applyActive()
            scheduleSave()
        }
    }

    fun toggleFavorite(item: CatalogItem): Boolean {
        synchronized(lock) {
            val existing = _favorites.value.firstOrNull { it.key == item.key }
            _favorites.value = if (existing == null) {
                listOf(item) + _favorites.value
            } else {
                _favorites.value.filterNot { it.key == item.key }
            }
            snapshotActive()
            scheduleSave()
            return existing == null
        }
    }

    fun addFavorites(incoming: List<CatalogItem>): Int {
        synchronized(lock) {
            val map = _favorites.value.associateBy { it.key }.toMutableMap()
            var added = 0
            incoming.forEach { item ->
                if (item.tmdbId <= 0) return@forEach
                if (map[item.key] == null) {
                    map[item.key] = item
                    added++
                }
            }
            if (added > 0) {
                _favorites.value = map.values.sortedByDescending { it.addedAt }
                snapshotActive()
                scheduleSave()
            }
            return added
        }
    }

    fun isFavorite(itemKey: String): Boolean = _favorites.value.any { it.key == itemKey }

    fun findByKey(key: String): CatalogItem? = _items.value.firstOrNull { it.key == key }

    fun findByTmdbId(tmdbId: Int, type: MediaType): CatalogItem? =
        _items.value.firstOrNull { it.tmdbId == tmdbId && it.type == type }

    fun upsertSource(source: ImportSource) {
        synchronized(lock) {
            val map = _sources.value.associateBy { it.id }.toMutableMap()
            map[source.id] = source
            _sources.value = map.values.sortedByDescending { it.lastSync }
            scheduleSave()
        }
    }

    fun removeSource(id: String, deleteItems: Boolean = false): Int {
        synchronized(lock) {
            val source = _sources.value.firstOrNull { it.id == id } ?: return 0
            _sources.value = _sources.value.filterNot { it.id == id }
            var removed = 0
            if (deleteItems) {
                val doomed = _items.value.filter { it.source == source.name }.map { it.key }.toSet()
                if (doomed.isNotEmpty()) {
                    _items.value = _items.value.filterNot { it.key in doomed }
                    scrubItemsFromAllProfiles(doomed)
                    applyActive()
                    removed = doomed.size
                }
            }
            scheduleSave()
            return removed
        }
    }

    fun saveProgress(progress: WatchProgress) {
        synchronized(lock) {
            val map = _progress.value.toMutableMap()
            map[progress.playbackKey] = progress
            val trimmed = map.values.sortedByDescending { it.updatedAt }.take(250).associateBy { it.playbackKey }
            _progress.value = trimmed
            snapshotActive()
            scheduleSave()
        }
    }

    fun progressFor(playbackKey: String): WatchProgress? = _progress.value[playbackKey]

    /**
     * Manual tracker marks. Watching an episode for real already counts via
     * [WatchProgress]; these keys cover seasons the user marked without playing.
     * Unmarking also drops playback progress for those episodes so a reset
     * really starts from zero.
     */
    fun setEpisodesWatched(item: CatalogItem, episodes: Set<Pair<Int, Int>>, watched: Boolean) {
        if (episodes.isEmpty()) return
        synchronized(lock) {
            val keys = episodes.mapTo(mutableSetOf()) { (season, episode) ->
                watchedEpisodeKey(item.key, season, episode)
            }
            _watchedEpisodes.value = if (watched) {
                _watchedEpisodes.value + keys.toSet()
            } else {
                _watchedEpisodes.value - keys.toSet()
            }
            if (!watched) {
                val playbackKeys = episodes.mapTo(mutableSetOf()) { (season, episode) ->
                    item.playbackKey(season, episode)
                }
                _progress.value = _progress.value.filterKeys { it !in playbackKeys }
            }
            snapshotActive()
            scheduleSave()
        }
    }

    fun removeProgressForItem(groupKey: String) {
        synchronized(lock) {
            _progress.value = _progress.value.filterValues { it.groupKey != groupKey }
            snapshotActive()
            scheduleSave()
        }
    }

    private fun mergeItems(old: CatalogItem, fresh: CatalogItem): CatalogItem {
        if (old.tmdbLoaded && !fresh.tmdbLoaded) {
            return old
        }
        val base = fresh.copy(addedAt = old.addedAt, source = old.source)
        val seasons = when {
            base.seasons.isEmpty() -> old.seasons
            else -> mergeSeasons(old.seasons, base.seasons)
        }
        return base.copy(
            seasons = seasons,
            streamUrl = fresh.streamUrl.ifBlank { old.streamUrl },
            backdropUrl = fresh.backdropUrl ?: old.backdropUrl,
            posterUrl = fresh.posterUrl ?: old.posterUrl
        )
    }

    private fun mergeSeasons(old: List<Season>, fresh: List<Season>): List<Season> {
        if (old.isEmpty()) return fresh
        val oldByNumber = old.associateBy { it.number }
        return fresh.map { season ->
            val previous = oldByNumber[season.number] ?: return@map season
            if (season.episodes.isEmpty() && previous.episodes.isNotEmpty()) {
                season.copy(episodes = previous.episodes)
            } else if (season.episodes.isNotEmpty() && previous.episodes.isNotEmpty()) {
                val previousEpisodes = previous.episodes.associateBy { it.number }
                season.copy(episodes = season.episodes.map { ep ->
                    val oldEp = previousEpisodes[ep.number]
                    if (oldEp != null && ep.streamUrl.isBlank()) ep.copy(streamUrl = oldEp.streamUrl) else ep
                })
            } else {
                season
            }
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(350)
            writeState()
        }
    }

    private fun writeState() {
        synchronized(lock) {
            runCatching {
                snapshotActive()
                val profiles = JSONObject().apply {
                    profileData.forEach { (id, data) ->
                        put(
                            id,
                            JSONObject().apply {
                                put("progress", JSONArray(data.progress.values.map { it.toJson() }))
                                put("favorites", JSONArray(data.favorites.map { it.toJson() }))
                                put("watchedEpisodes", JSONArray(data.watchedEpisodes.toList()))
                            }
                        )
                    }
                }
                val root = JSONObject().apply {
                    put("version", 2)
                    put("activeProfile", activeProfileId)
                    put("items", JSONArray(_items.value.map { it.toJson() }))
                    put("sources", JSONArray(_sources.value.map { it.toJson() }))
                    put("profiles", profiles)
                }
                val tmp = File(stateFile.parentFile, "${stateFile.name}.tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(stateFile)) {
                    stateFile.writeText(root.toString())
                    tmp.delete()
                }
            }
        }
    }

    private fun load() {
        runCatching {
            if (!stateFile.exists()) {
                profileData[CinemaProfile.DEFAULT_ID] = ProfileData()
                return
            }
            val root = JSONObject(stateFile.readText())
            val items = root.optJSONArray("items")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { CatalogItem.fromJson(it) } }
            }.orEmpty()
            val sources = root.optJSONArray("sources")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { ImportSource.fromJson(it) } }
            }.orEmpty()
            _items.value = items
            _sources.value = sources

            val profilesJson = root.optJSONObject("profiles")
            if (profilesJson != null) {
                profilesJson.keys().forEach { id ->
                    val entry = profilesJson.optJSONObject(id) ?: return@forEach
                    profileData[id] = ProfileData(
                        progress = entry.optJSONArray("progress")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optJSONObject(i)?.let { WatchProgress.fromJson(it) }
                            }
                        }.orEmpty().associateBy { it.playbackKey },
                        favorites = entry.optJSONArray("favorites")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optJSONObject(i)?.let { CatalogItem.fromJson(it) }
                            }
                        }.orEmpty(),
                        watchedEpisodes = entry.optJSONArray("watchedEpisodes")?.let { arr ->
                            (0 until arr.length())
                                .mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                        }.orEmpty().toSet()
                    )
                }
                activeProfileId = root.optString("activeProfile")
                    .takeIf { profileData.containsKey(it) }
                    ?: profileData.keys.firstOrNull()
                    ?: CinemaProfile.DEFAULT_ID
            } else {
                // Version 1 stores a single shared bucket; keep it as the default profile.
                profileData[CinemaProfile.DEFAULT_ID] = ProfileData(
                    progress = root.optJSONArray("progress")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.let { WatchProgress.fromJson(it) }
                        }
                    }.orEmpty().associateBy { it.playbackKey },
                    favorites = root.optJSONArray("favorites")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.let { CatalogItem.fromJson(it) }
                        }
                    }.orEmpty(),
                    watchedEpisodes = root.optJSONArray("watchedEpisodes")?.let { arr ->
                        (0 until arr.length())
                            .mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                    }.orEmpty().toSet()
                )
                activeProfileId = CinemaProfile.DEFAULT_ID
            }
            if (profileData.isEmpty()) profileData[CinemaProfile.DEFAULT_ID] = ProfileData()
            if (profileData[activeProfileId] == null) {
                activeProfileId = profileData.keys.first()
            }
            applyActive()
        }
    }
}
