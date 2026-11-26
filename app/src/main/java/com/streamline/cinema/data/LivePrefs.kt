package com.streamline.cinema.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Live-only preferences: starred channels, the order of catalogs, groups and
 * channels, and which of those are hidden. These belong to the viewer, not to
 * the playlist, so a roadmap refresh never resets them. JSON keyed by stable
 * channel/catalog keys keeps reordering cheap and tolerant of channels
 * disappearing from a provider.
 */
class LivePrefsStore(context: Context) {

    data class Prefs(
        val favorites: Set<String> = emptySet(),
        val catalogOrder: List<String> = emptyList(),
        val channelOrder: Map<String, List<String>> = emptyMap(),
        /** `"$catalogKey|$extraName"` → ordered group labels. */
        val groupOrder: Map<String, List<String>> = emptyMap(),
        /** Catalog key → the label to show while it is hidden. */
        val hiddenCatalogs: Map<String, String> = emptyMap(),
        /** Channel key → the label to show while it is hidden. */
        val hiddenChannels: Map<String, String> = emptyMap(),
        /** `"$catalogKey|$extraName"` → hidden group labels. */
        val hiddenGroups: Map<String, List<String>> = emptyMap()
    )

    private val file = File(context.applicationContext.filesDir, "live_prefs.json")
    private val lock = Any()
    private val _prefs = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    init {
        load()
    }

    fun isFavorite(channelKey: String): Boolean = channelKey in _prefs.value.favorites

    /** Returns true when the channel was added, false when it was removed. */
    fun toggleFavorite(channelKey: String): Boolean {
        if (channelKey.isBlank()) return false
        val added = synchronized(lock) {
            val current = _prefs.value
            val next = if (channelKey in current.favorites) {
                current.favorites - channelKey
            } else {
                current.favorites + channelKey
            }
            _prefs.value = current.copy(favorites = next)
            persist()
            channelKey in next
        }
        return added
    }

    fun setCatalogOrder(keys: List<String>) {
        synchronized(lock) {
            val clean = keys.filter { it.isNotBlank() }.distinct()
            if (clean == _prefs.value.catalogOrder) return
            _prefs.value = _prefs.value.copy(catalogOrder = clean)
            persist()
        }
    }

    fun setChannelOrder(catalogKey: String, keys: List<String>) {
        if (catalogKey.isBlank()) return
        synchronized(lock) {
            val clean = keys.filter { it.isNotBlank() }.distinct()
            if (_prefs.value.channelOrder[catalogKey] == clean) return
            _prefs.value = _prefs.value.copy(channelOrder = _prefs.value.channelOrder + (catalogKey to clean))
            persist()
        }
    }

    fun setGroupOrder(catalogKey: String, extraName: String, groups: List<String>) {
        if (catalogKey.isBlank()) return
        synchronized(lock) {
            val id = groupId(catalogKey, extraName)
            val clean = groups.filter { it.isNotBlank() }.distinct()
            if (_prefs.value.groupOrder[id] == clean) return
            _prefs.value = _prefs.value.copy(groupOrder = _prefs.value.groupOrder + (id to clean))
            persist()
        }
    }

    fun applyCatalogOrder(catalogs: List<LiveCatalog>): List<LiveCatalog> =
        applyOrder(catalogs, _prefs.value.catalogOrder) { it.key }

    fun applyChannelOrder(catalogKey: String, channels: List<LiveChannel>): List<LiveChannel> =
        applyOrder(channels, _prefs.value.channelOrder[catalogKey].orEmpty()) { it.key }

    fun applyGroupOrder(catalog: LiveCatalog, extra: CatalogExtra): CatalogExtra {
        val order = _prefs.value.groupOrder[groupId(catalog.key, extra.name)].orEmpty()
        if (order.isEmpty()) return extra
        return extra.copy(options = applyOrder(extra.options, order) { it })
    }

    fun isCatalogHidden(catalogKey: String): Boolean = catalogKey in _prefs.value.hiddenCatalogs

    fun isChannelHidden(channelKey: String): Boolean = channelKey in _prefs.value.hiddenChannels

    fun isGroupHidden(catalogKey: String, extraName: String, group: String): Boolean =
        group in _prefs.value.hiddenGroups[groupId(catalogKey, extraName)].orEmpty()

    /** Returns true when the list is now hidden, false when it came back. */
    fun toggleCatalogHidden(catalogKey: String, label: String): Boolean {
        if (catalogKey.isBlank()) return false
        return synchronized(lock) {
            val current = _prefs.value
            val wasHidden = catalogKey in current.hiddenCatalogs
            val next = if (wasHidden) {
                current.hiddenCatalogs - catalogKey
            } else {
                current.hiddenCatalogs + (catalogKey to label.ifBlank { "Live list" })
            }
            _prefs.value = current.copy(hiddenCatalogs = next)
            persist()
            !wasHidden
        }
    }

    /** Returns true when the channel is now hidden, false when it came back. */
    fun toggleChannelHidden(channelKey: String, label: String): Boolean {
        if (channelKey.isBlank()) return false
        return synchronized(lock) {
            val current = _prefs.value
            val wasHidden = channelKey in current.hiddenChannels
            val next = if (wasHidden) {
                current.hiddenChannels - channelKey
            } else {
                current.hiddenChannels + (channelKey to label.ifBlank { "Channel" })
            }
            _prefs.value = current.copy(hiddenChannels = next)
            persist()
            !wasHidden
        }
    }

    /** Returns true when the group is now hidden, false when it came back. */
    fun toggleGroupHidden(catalogKey: String, extraName: String, group: String): Boolean {
        if (catalogKey.isBlank() || group.isBlank()) return false
        return synchronized(lock) {
            val id = groupId(catalogKey, extraName)
            val current = _prefs.value.hiddenGroups
            val labels = current[id].orEmpty()
            val next = if (group in labels) labels - group else labels + group
            val groups = current.toMutableMap().apply {
                if (next.isEmpty()) remove(id) else put(id, next)
            }
            _prefs.value = _prefs.value.copy(hiddenGroups = groups)
            persist()
            group in next
        }
    }

    fun unhideCatalog(catalogKey: String) {
        synchronized(lock) {
            if (catalogKey !in _prefs.value.hiddenCatalogs) return
            _prefs.value = _prefs.value.copy(
                hiddenCatalogs = _prefs.value.hiddenCatalogs - catalogKey
            )
            persist()
        }
    }

    fun unhideChannel(channelKey: String) {
        synchronized(lock) {
            if (channelKey !in _prefs.value.hiddenChannels) return
            _prefs.value = _prefs.value.copy(
                hiddenChannels = _prefs.value.hiddenChannels - channelKey
            )
            persist()
        }
    }

    fun unhideGroup(catalogKey: String, extraName: String, group: String) {
        synchronized(lock) {
            val id = groupId(catalogKey, extraName)
            val labels = _prefs.value.hiddenGroups[id].orEmpty()
            if (group !in labels) return
            val next = labels - group
            val groups = _prefs.value.hiddenGroups.toMutableMap().apply {
                if (next.isEmpty()) remove(id) else put(id, next)
            }
            _prefs.value = _prefs.value.copy(hiddenGroups = groups)
            persist()
        }
    }

    private fun groupId(catalogKey: String, extraName: String) = liveGroupId(catalogKey, extraName)

    private fun <T> applyOrder(items: List<T>, order: List<String>, key: (T) -> String): List<T> {
        if (order.isEmpty() || items.isEmpty()) return items
        val rank = order.withIndex().associate { (index, value) -> value to index }
        return items.withIndex()
            .sortedWith(compareBy({ rank[key(it.value)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    private fun persist() {
        runCatching {
            val prefs = _prefs.value
            val root = JSONObject().apply {
                put("version", 1)
                put("favorites", JSONArray(prefs.favorites.toList()))
                put("catalogOrder", JSONArray(prefs.catalogOrder))
                put("channelOrder", JSONObject().apply {
                    prefs.channelOrder.forEach { (catalog, keys) -> put(catalog, JSONArray(keys)) }
                })
                put("groupOrder", JSONObject().apply {
                    prefs.groupOrder.forEach { (id, groups) -> put(id, JSONArray(groups)) }
                })
                put("hiddenCatalogs", JSONObject().apply {
                    prefs.hiddenCatalogs.forEach { (key, label) -> put(key, label) }
                })
                put("hiddenChannels", JSONObject().apply {
                    prefs.hiddenChannels.forEach { (key, label) -> put(key, label) }
                })
                put("hiddenGroups", JSONObject().apply {
                    prefs.hiddenGroups.forEach { (id, groups) -> put(id, JSONArray(groups)) }
                })
            }
            AtomicStore.write(file, root.toString(), "live preferences")
        }
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            fun stringList(value: Any?): List<String> {
                val array = value as? JSONArray ?: return emptyList()
                return (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            }
            fun orderMap(value: Any?): Map<String, List<String>> {
                val objectValue = value as? JSONObject ?: return emptyMap()
                return objectValue.keys().asSequence().associateWith { key ->
                    stringList(objectValue.optJSONArray(key))
                }.filterValues { it.isNotEmpty() }
            }
            fun labelMap(value: Any?): Map<String, String> {
                val objectValue = value as? JSONObject ?: return emptyMap()
                return objectValue.keys().asSequence().mapNotNull { key ->
                    objectValue.optString(key).takeIf { it.isNotBlank() }?.let { key to it }
                }.toMap()
            }
            _prefs.value = Prefs(
                favorites = stringList(root.optJSONArray("favorites")).toSet(),
                catalogOrder = stringList(root.optJSONArray("catalogOrder")),
                channelOrder = orderMap(root.optJSONObject("channelOrder")),
                groupOrder = orderMap(root.optJSONObject("groupOrder")),
                hiddenCatalogs = labelMap(root.optJSONObject("hiddenCatalogs")),
                hiddenChannels = labelMap(root.optJSONObject("hiddenChannels")),
                hiddenGroups = orderMap(root.optJSONObject("hiddenGroups"))
            )
        }
    }
}

/** Stable id for one extra group of one catalog, shared by ordering and hiding. */
internal fun liveGroupId(catalogKey: String, extraName: String) = "$catalogKey|$extraName"
